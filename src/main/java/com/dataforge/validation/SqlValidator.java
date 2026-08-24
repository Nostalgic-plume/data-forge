package com.dataforge.validation;

import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL 校验器（P2：校验 SQL 化）：对 staging 库跑 RuleCompiler 生成的检查 SQL。
 *
 * <p>Detect（全量 SQL 扫描）→ Aggregate（根因分桶 + 样本 + 根因假设），
 * 输出 {@link ValidationReport}（喂修正 agent 的压缩视图）。确定性、全量、可并发。</p>
 */
@Component
public class SqlValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);
    private static final int SAMPLE_LIMIT = 3;

    private final JdbcDataSource jdbc;
    private final RuleCompiler ruleCompiler;
    private final StagingManager stagingManager;

    public SqlValidator(JdbcDataSource jdbc, RuleCompiler ruleCompiler, StagingManager stagingManager) {
        this.jdbc = jdbc;
        this.ruleCompiler = ruleCompiler;
        this.stagingManager = stagingManager;
    }

    /** 对 staging 库做全量 SQL 校验，返回分桶报告 */
    public ValidationReport validate(List<TableMeta> tables, ProjectRules rules, String schemaName) {
        long start = System.currentTimeMillis();
        String staging = stagingManager.stagingSchema(schemaName);
        List<ValidationReport.Bucket> buckets = new ArrayList<>();

        // 通用规则（DDL 推导）：FK 悬挂 + 唯一键冲突
        for (TableMeta table : tables) {
            detectFkOrphan(table, staging, buckets);
            detectUniqueConflict(table, staging, buckets);
        }
        // 项目规则：遍历 correctionRules 逐条编译执行（P4 通用化：引擎不持有业务知识）
        detectProjectRules(tables, rules, staging, buckets);

        int totalErrors = buckets.stream().mapToInt(ValidationReport.Bucket::count).sum();
        long elapsed = System.currentTimeMillis() - start;
        log.info("SQL 校验完成: {} 个问题分桶 / {} 行问题 / {}ms", buckets.size(), totalErrors, elapsed);
        return new ValidationReport(buckets, totalErrors, elapsed);
    }

    /** Detect: FK 悬挂（子表 FK 引用不在父表）→ 分桶 */
    private void detectFkOrphan(TableMeta table, String staging, List<ValidationReport.Bucket> buckets) {
        String sql = ruleCompiler.compileMetaCheck(table, RuleCompiler.MetaCheckType.FK_ORPHAN, staging);
        List<Map<String, Object>> rows = jdbc.query(sql);
        if (rows.isEmpty()) return;
        // 按 fk_col 分组
        Map<String, List<Map<String, Object>>> byColumn = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(r -> String.valueOf(r.get("fk_col"))));
        long totalRows = tableRowCount(staging, table.name());
        for (Map.Entry<String, List<Map<String, Object>>> e : byColumn.entrySet()) {
            List<Map<String, Object>> group = e.getValue();
            // 根因 key：FK_PARENT_FAIL:{父表}（同父表失败的悬挂归一根因）
            String refTable = table.foreignKeys().stream()
                    .filter(fk -> fk.column().equals(e.getKey()))
                    .map(fk -> fk.refTable())
                    .findFirst().orElse("?");
            buckets.add(new ValidationReport.Bucket(
                    table.name(), e.getKey(), "FK_ORPHAN",
                    group.size(), ratio(group.size(), totalRows),
                    group.subList(0, Math.min(SAMPLE_LIMIT, group.size())),
                    "父表引用缺失（FK 悬挂）",
                    "FK_PARENT_FAIL:" + refTable));
        }
    }

    /** Detect: 唯一键冲突 → 分桶 */
    private void detectUniqueConflict(TableMeta table, String staging, List<ValidationReport.Bucket> buckets) {
        String sql = ruleCompiler.compileMetaCheck(table, RuleCompiler.MetaCheckType.UNIQUE_CONFLICT, staging);
        List<Map<String, Object>> rows = jdbc.query(sql);
        if (rows.isEmpty()) return;
        long totalRows = tableRowCount(staging, table.name());
        int conflictRows = rows.stream().mapToInt(r -> ((Number) r.get("cnt")).intValue() - 1).sum();
        buckets.add(new ValidationReport.Bucket(
                table.name(), String.join(",", table.uniqueKeys()), "UNIQUE_CONFLICT",
                conflictRows, ratio(conflictRows, totalRows),
                rows.subList(0, Math.min(SAMPLE_LIMIT, rows.size())),
                "唯一键重复",
                "UNIQUE_DUP"));
    }

    /**
     * Detect: 项目规则（P4 通用化）——遍历 correctionRules 逐条编译执行。
     * errorType = rule.type()（如 AGG_EQUAL），rootCause = rule.expression()（喂修正 agent）。
     */
    private void detectProjectRules(List<TableMeta> tables, ProjectRules rules, String staging,
                                    List<ValidationReport.Bucket> buckets) {
        if (rules == null || rules.correctionRules().isEmpty()) return;
        for (ProjectRules.Rule rule : rules.correctionRules()) {
            TableMeta table = tables.stream()
                    .filter(t -> t.name().equals(rule.table())).findFirst().orElse(null);
            if (table == null) continue;   // 该表不在生成集内 → 跳过（不误报）
            try {
                String sql = ruleCompiler.compileProjectRule(rule, table, staging);
                List<Map<String, Object>> rows = jdbc.query(sql);
                if (rows.isEmpty()) continue;
                long totalRows = tableRowCount(staging, rule.table());
                buckets.add(new ValidationReport.Bucket(
                        rule.table(), rule.column(), rule.type(),
                        rows.size(), ratio(rows.size(), totalRows),
                        rows.subList(0, Math.min(SAMPLE_LIMIT, rows.size())),
                        rule.expression(),
                        "AGG_EQUAL".equals(rule.type()) ? "AMOUNT_MISMATCH" : rule.type()));
            } catch (Exception e) {
                log.warn("规则校验跳过（表缺失或编译失败）: {}", e.getMessage());
            }
        }
    }

    private long tableRowCount(String staging, String table) {
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT COUNT(*) AS c FROM `" + staging + "`.`" + table + "`");
        return rows.isEmpty() || rows.get(0).get("c") == null
                ? 0 : ((Number) rows.get(0).get("c")).longValue();
    }

    private double ratio(int part, long total) {
        return total == 0 ? 0 : (double) part / total;
    }
}
