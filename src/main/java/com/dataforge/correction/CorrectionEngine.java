package com.dataforge.correction;

import com.dataforge.agent.CorrectionAgent;
import com.dataforge.db.JdbcDataSource;
import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.db.StagingManager;
import com.dataforge.generator.RuleDataGenerator;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.*;
import com.dataforge.validation.SqlGate;
import com.dataforge.validation.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 修正引擎（P3 核心）：手写 ReAct 收敛循环。
 *
 * <p>循环：校验报告（压缩分桶）→ 桶决策（规则通道优先 + LLM 兜底）
 * → id 锚定最小干预应用 → 再校验。收敛控制四要素（PLAN 决策 #5/#22/#23）：</p>
 * <ul>
 *   <li><b>问题清单闭环</b>：首轮问题签名集合为基准，新错误 = 回归 = 回滚（重建工作区）</li>
 *   <li><b>通过率上升判定</b>：错误未减少即提前终止（防死循环）</li>
 *   <li><b>maxIterations</b>：循环上限，收敛目标硬错误 = 0，残余写入最终报告</li>
 *   <li><b>桶冲突串行</b>：决策串行生成动作，应用阶段按桶顺序串行执行——
 *       命中同一行的桶按确定性顺序应用，互不覆盖（P4 再并行决策）</li>
 * </ul>
 *
 * <p>桶处理分流（API_CONTRACT §1.3）：规则通道（90%+，零 LLM，受控 SQL 批量秒级）；
 * LLM 通道（修正值需语义理解）→ 决策值 → 转 UPDATE（保留 id 锚定）。</p>
 */
@Component
public class CorrectionEngine {

    private static final Logger log = LoggerFactory.getLogger(CorrectionEngine.class);

    private final JdbcDataSource jdbc;
    private final StagingManager stagingManager;
    private final SqlValidator validator;
    private final DataLlm llm;

    public CorrectionEngine(JdbcDataSource jdbc, StagingManager stagingManager,
                            SqlValidator validator, DataLlm llm) {
        this.jdbc = jdbc;
        this.stagingManager = stagingManager;
        this.validator = validator;
        this.llm = llm;
    }

    /** 修正动作：桶决策产物——受控批量 SQL / CUSTOM_SQL 修复 SQL / 逐行修正指令（id 锚定） */
    record FixAction(
            String table,
            String errorType,
            String reason,
            String applySql,         // 规则通道批量 SQL（可为 null → 走 fixes/fixSql）
            List<FixCommand> fixes,  // 逐行修正（FK 重分配 / LLM 通道 / CUSTOM_SQL 血缘标记）
            String fixSql,           // CUSTOM_SQL 修复 SQL（参数化，可为 null）
            List<Long> fixIds,       // CUSTOM_SQL 问题行 id 列表（逐行绑定）
            int fixParamCount        // fixSql 中 {id} 出现次数（参数填充次数）
    ) {
    }

    /**
     * 收敛循环入口。
     *
     * @param maxIterations 循环上限
     */
    public CorrectionAgent.CorrectionResult run(CorrectionAgent.CorrectionRequest req, int maxIterations) {
        String staging = stagingManager.stagingSchema(req.schemaName());
        ValidationReport report = req.initialReport();
        List<FixCommand> applied = new ArrayList<>();
        // 问题清单闭环基准：首轮问题签名集合（新签名 = 回归）
        Set<String> baseline = issueKeys(report.buckets());
        // 根因分桶汇总（同根因问题行数，审计/关联可见；A7）
        Map<String, Integer> rootCauseCounts = new LinkedHashMap<>();
        for (ValidationReport.Bucket b : report.buckets()) {
            rootCauseCounts.merge(b.rootCauseKey(), b.count(), Integer::sum);
        }
        if (!rootCauseCounts.isEmpty()) {
            log.info("根因分桶: {}（同根因问题可归因批量处理）", rootCauseCounts);
        }
        int iterations = 0;

        while (report.totalErrors() > 0 && iterations < maxIterations) {
            iterations++;
            // ① 桶决策（规则通道优先 + LLM 兜底）
            List<FixAction> actions = plan(report.buckets(), req.tables(), req.rules(), staging, req.scopeMinId());
            if (actions.isEmpty()) {
                log.warn("收敛中止：{} 个问题桶无法修正（规则未覆盖且 LLM 无值），上报残留",
                        report.buckets().size());
                break;
            }
            // ② 应用修正（串行：命中同一行的桶按确定性顺序应用，互不覆盖）
            List<FixCommand> roundFixes = new ArrayList<>();
            for (FixAction action : actions) {
                roundFixes.addAll(execute(action, staging));
            }
            applied.addAll(roundFixes);
            log.info("第 {} 轮：应用 {} 条修正（{} 个动作）", iterations, roundFixes.size(), actions.size());
            // ③ 再校验
            ValidationReport next = validator.validate(req.tables(), req.rules(), req.schemaName());
            // ④ 收敛判定
            if (!issueKeys(next.buckets()).stream().allMatch(baseline::contains)) {
                log.warn("回归检测：出现首轮清单之外的新错误 → 回滚（重建 staging 工作区）");
                rollback(req);
                return new CorrectionAgent.CorrectionResult(
                        req.data(), req.initialReport(), false, true, iterations, applied);
            }
            if (next.totalErrors() >= report.totalErrors()) {
                log.warn("通过率未上升（{} → {}），提前终止防死循环",
                        report.totalErrors(), next.totalErrors());
                return new CorrectionAgent.CorrectionResult(
                        readBack(req.tables(), staging, req.data(), applied), next, false, false, iterations, applied);
            }
            report = next;
        }

        boolean converged = report.totalErrors() == 0;
        log.info("修正循环结束：{} 轮 / 收敛={} / 修正 {} 条 / 残余错误 {}",
                iterations, converged, applied.size(), report.totalErrors());
        return new CorrectionAgent.CorrectionResult(
                readBack(req.tables(), staging, req.data(), applied),
                report, converged, false, iterations, applied);
    }

    // ---- 桶决策（plan）----

    /** 每个桶 → 修正动作；规则模板未覆盖的桶走 LLM 通道；均失败则跳过该桶 */
    private List<FixAction> plan(List<ValidationReport.Bucket> buckets, List<TableMeta> tables,
                                 ProjectRules rules, String staging, Long scopeMinId) {
        List<FixAction> actions = new ArrayList<>();
        for (ValidationReport.Bucket bucket : buckets) {
            FixAction action = planOne(bucket, tables, rules, staging, scopeMinId);
            if (action != null) actions.add(action);
        }
        return actions;
    }

    private FixAction planOne(ValidationReport.Bucket bucket, List<TableMeta> tables,
                              ProjectRules rules, String staging, Long scopeMinId) {
        return switch (bucket.errorType()) {
            case "AGG_EQUAL" -> planAggEqual(bucket, rules, staging, scopeMinId);
            case "UNIQUE_CONFLICT" -> planUnique(bucket, staging, scopeMinId);
            case "FK_ORPHAN" -> planFkOrphan(bucket, tables, staging, scopeMinId);
            case "VALUE_RANGE" -> planValueRange(bucket, rules, staging, scopeMinId);
            case "NON_NULL" -> planNonNull(bucket, rules, tables, staging, scopeMinId);
            case "FORMAT" -> planFormat(bucket, rules, tables, staging, scopeMinId);
            case "STATUS_FLOW" -> planStatusFlow(bucket, rules, staging, scopeMinId);
            case "CUSTOM_SQL" -> planCustomSql(bucket, rules, tables, staging, scopeMinId);
            default -> planLlm(bucket, tables);
        };
    }

    /**
     * 规则通道：AGG_EQUAL（目标列 = 引用表按 join 键求和的差异），零 LLM。
     * 表名/列名全部来自 correctionRules 的 params（P4 通用化，引擎不写死业务表）。
     */
    private FixAction planAggEqual(ValidationReport.Bucket bucket, ProjectRules rules,
                                   String staging, Long scopeMinId) {
        ProjectRules.Rule rule = rules.correctionRules().stream()
                .filter(r -> r.type().equals("AGG_EQUAL")
                        && r.table().equals(bucket.table()) && r.column().equals(bucket.column()))
                .findFirst().orElse(null);
        if (rule == null) return null;
        String refTable = rule.params().get("ref_table");
        String sumCol = rule.params().get("sum_col");
        String joinCol = rule.params().get("join_col");
        if (refTable == null || sumCol == null || joinCol == null) return null;
        // check：受影响行 + 修正目标值（expected）
        List<Map<String, Object>> affected = jdbc.query(CorrectionSqls.aggEqualCheckSql(
                staging, rule.table(), rule.column(), refTable, sumCol, joinCol, scopeMinId));
        if (affected.isEmpty()) return null;
        List<FixCommand> fixes = affected.stream()
                .map(r -> new FixCommand(rule.table(), idOf(r),
                        Map.of(rule.column(), r.get("expected")), rule.expression()))
                .toList();
        return new FixAction(rule.table(), "AGG_EQUAL", rule.expression(),
                CorrectionSqls.aggEqualApplySql(staging, rule.table(), rule.column(),
                        refTable, sumCol, joinCol, scopeMinId), fixes, null, List.of(), 0);
    }

    /** 规则通道：唯一后缀去重（重复组保留 MIN(id)，其余追加 _<id>），零 LLM */
    private FixAction planUnique(ValidationReport.Bucket bucket, String staging, Long scopeMinId) {
        if (bucket.column().contains(",")) {
            log.warn("复合唯一键 {} 暂不支持自动去重，上报残留", bucket.column());
            return null;
        }
        List<Map<String, Object>> affected = jdbc.query(
                CorrectionSqls.uniqueCheckSql(staging, bucket.table(), bucket.column(), scopeMinId));
        if (affected.isEmpty()) return null;
        List<FixCommand> fixes = affected.stream()
                .map(r -> new FixCommand(bucket.table(), idOf(r),
                        Map.of(bucket.column(), String.valueOf(r.get("value")) + "_" + idOf(r)),
                        "唯一后缀去重"))
                .toList();
        return new FixAction(bucket.table(), "UNIQUE_CONFLICT", "唯一去重",
                CorrectionSqls.uniqueApplySql(staging, bucket.table(), bucket.column(), scopeMinId),
                fixes, null, List.of(), 0);
    }

    /** 规则通道：FK 重分配（悬挂的 FK 指向一个存在的父 PK，保留子行，id 锚定最小干预） */
    private FixAction planFkOrphan(ValidationReport.Bucket bucket, List<TableMeta> tables,
                                   String staging, Long scopeMinId) {
        // 定位 FK 定义（bucket.column = fk_col）
        ForeignKeyDef fk = tables.stream()
                .filter(t -> t.name().equals(bucket.table()))
                .flatMap(t -> t.foreignKeys().stream())
                .filter(f -> f.column().equals(bucket.column()))
                .findFirst().orElse(null);
        if (fk == null) return null;
        // 父表 PK 池（存在值）
        List<Object> pool = jdbc.query(CorrectionSqls.fkPoolSql(staging, fk.refTable(), fk.refColumn()))
                .stream().map(r -> r.get("pk")).filter(Objects::nonNull).toList();
        if (pool.isEmpty()) return null;
        List<Map<String, Object>> orphans = jdbc.query(
                CorrectionSqls.fkOrphanSql(staging, bucket.table(), fk.column(),
                        fk.refTable(), fk.refColumn(), scopeMinId));
        if (orphans.isEmpty()) return null;
        List<FixCommand> fixes = orphans.stream()
                .map(r -> new FixCommand(bucket.table(), idOf(r),
                        Map.of(fk.column(), pool.get((int) (idOf(r) % pool.size()))),
                        "FK 重分配: 指向存在的父行"))
                .toList();
        return new FixAction(bucket.table(), "FK_ORPHAN", "FK 重分配", null, fixes,
                null, List.of(), 0);
    }

    /** LLM 通道：修正值需语义理解 → LLM 判断值 → 逐行 UPDATE（id 锚定） */
    private FixAction planLlm(ValidationReport.Bucket bucket, List<TableMeta> tables) {
        ColumnDef col = tables.stream()
                .filter(t -> t.name().equals(bucket.table()))
                .flatMap(t -> t.columns().stream())
                .filter(c -> c.name().equals(bucket.column()))
                .findFirst().orElse(null);
        if (col == null) return null;
        List<Map<String, Object>> llmFixes = llm.correctValues(
                bucket.table(), List.of(col), bucket.samples());
        if (llmFixes.isEmpty()) {
            log.warn("桶 {}:{} 走 LLM 通道但无修正值，上报残留", bucket.table(), bucket.column());
            return null;
        }
        List<FixCommand> fixes = llmFixes.stream()
                .filter(f -> f.get("id") != null && f.get(bucket.column()) != null)
                .map(f -> new FixCommand(bucket.table(), idOf(f),
                        Map.of(bucket.column(), f.get(bucket.column())), "LLM 语义修正"))
                .toList();
        return fixes.isEmpty() ? null
                : new FixAction(bucket.table(), bucket.errorType(), "LLM 语义修正", null, fixes,
                null, List.of(), 0);
    }

    /** 规则通道：VALUE_RANGE 范围钳制（批量 SQL 秒级，零 LLM） */
    private FixAction planValueRange(ValidationReport.Bucket bucket, ProjectRules rules,
                                     String staging, Long scopeMinId) {
        ProjectRules.Rule rule = findCorrectionRule(bucket, rules, "VALUE_RANGE");
        if (rule == null) return null;
        String min = rule.params().get("min");
        String max = rule.params().get("max");
        List<Map<String, Object>> affected = jdbc.query(CorrectionSqls.valueRangeCheckSql(
                staging, rule.table(), rule.column(), min, max, scopeMinId));
        if (affected.isEmpty()) return null;
        List<FixCommand> fixes = affected.stream()
                .map(r -> new FixCommand(rule.table(), idOf(r),
                        Map.of(rule.column(), r.get("value")), rule.expression()))
                .toList();
        return new FixAction(rule.table(), "VALUE_RANGE", rule.expression(),
                CorrectionSqls.valueRangeApplySql(staging, rule.table(), rule.column(), min, max, scopeMinId),
                fixes, null, List.of(), 0);
    }

    /** 规则通道：NON_NULL 补默认值（批量 SQL；default_value 缺省按列类型推导） */
    private FixAction planNonNull(ValidationReport.Bucket bucket, ProjectRules rules,
                                  List<TableMeta> tables, String staging, Long scopeMinId) {
        ProjectRules.Rule rule = findCorrectionRule(bucket, rules, "NON_NULL");
        if (rule == null) return null;
        String configuredDefault = rule.params().get("default_value");
        String defaultVal = (configuredDefault == null || configuredDefault.isBlank())
                ? typeDefault(bucket.table(), bucket.column(), tables) : configuredDefault;
        List<Map<String, Object>> affected = jdbc.query(CorrectionSqls.nonNullCheckSql(
                staging, rule.table(), rule.column(), scopeMinId));
        if (affected.isEmpty()) return null;
        List<FixCommand> fixes = affected.stream()
                .map(r -> new FixCommand(rule.table(), idOf(r),
                        Map.of(rule.column(), defaultVal), rule.expression()))
                .toList();
        return new FixAction(rule.table(), "NON_NULL", rule.expression(),
                CorrectionSqls.nonNullApplySql(staging, rule.table(), rule.column(), defaultVal, scopeMinId),
                fixes, null, List.of(), 0);
    }

    /** FORMAT：重生成合法格式值（RuleDataGenerator 按列类型确定性生成，逐行 id 锚定 UPDATE） */
    private FixAction planFormat(ValidationReport.Bucket bucket, ProjectRules rules,
                                 List<TableMeta> tables, String staging, Long scopeMinId) {
        ProjectRules.Rule rule = findCorrectionRule(bucket, rules, "FORMAT");
        if (rule == null) return null;
        ColumnDef col = findColumn(bucket.table(), bucket.column(), tables);
        if (col == null) return null;
        List<Map<String, Object>> affected = jdbc.query(CorrectionSqls.formatCheckSql(
                staging, rule.table(), rule.column(), rule.params().get("regex"), scopeMinId));
        if (affected.isEmpty()) return null;
        List<Map<String, Object>> genRows = RuleDataGenerator.generateRuleValues(
                List.of(col), affected.size(), new java.util.Random(42L));   // 固定种子，确定性可复现
        List<FixCommand> fixes = new ArrayList<>();
        for (int i = 0; i < affected.size() && i < genRows.size(); i++) {
            Object newVal = genRows.get(i).get(col.name());
            if (newVal != null) {
                fixes.add(new FixCommand(rule.table(), idOf(affected.get(i)),
                        Map.of(rule.column(), newVal), rule.expression()));
            }
        }
        return fixes.isEmpty() ? null
                : new FixAction(rule.table(), "FORMAT", rule.expression(), null, fixes,
                null, List.of(), 0);
    }

    /** STATUS_FLOW：时间线补正需业务假设（增量未知），保守上报残留（防引入新错） */
    private FixAction planStatusFlow(ValidationReport.Bucket bucket, ProjectRules rules,
                                     String staging, Long scopeMinId) {
        ProjectRules.Rule rule = findCorrectionRule(bucket, rules, "STATUS_FLOW");
        if (rule == null) return null;
        log.warn("桶 {}:{} STATUS_FLOW 无法确定性补正（时间增量需业务假设），上报残留",
                bucket.table(), bucket.column());
        return null;
    }

    /**
     * CUSTOM_SQL 修正：LLM 产出的 fix_sql 修复 UPDATE（id 锚定，逐行绑定问题行 id）。
     * fix_sql 缺失 / 闸门不过 → 上报残留（保守，防全表 UPDATE/写坏数据）。
     * check_sql 重编译取<b>全部</b>受影响行（bucket.samples 只有前 3 条，不能只修 3 条）。
     */
    private FixAction planCustomSql(ValidationReport.Bucket bucket, ProjectRules rules,
                                    List<TableMeta> tables, String staging, Long scopeMinId) {
        ProjectRules.Rule rule = findCorrectionRule(bucket, rules, "CUSTOM_SQL");
        if (rule == null) return null;
        String fixSql = rule.params().get("fix_sql");
        if (fixSql == null || fixSql.isBlank()) {
            log.warn("桶 {}:{} CUSTOM_SQL 无 fix_sql，只检查不修正（上报残留）",
                    bucket.table(), bucket.column());
            return null;
        }
        TableMeta table = tables.stream()
                .filter(t -> t.name().equals(bucket.table())).findFirst().orElse(null);
        if (table == null) return null;
        // 防御纵深：运行时重查 fix_sql 闸门（防手改 project_rules.json 注入坏 SQL）
        if (!SqlGate.checkFixSql(fixSql, staging, table).isEmpty()) {
            log.warn("桶 {}:{} CUSTOM_SQL fix_sql 未过闸门，上报残留", bucket.table(), bucket.column());
            return null;
        }
        // 重编译 check_sql 取全部受影响行（含增量作用域过滤）
        String checkSql = rule.params().get("check_sql");
        List<Map<String, Object>> affected =
                jdbc.query(SqlGate.compileCheckSql(checkSql, staging, table, tables));
        if (affected.isEmpty()) return null;
        List<Long> ids = affected.stream()
                .map(CorrectionEngine::idOf)
                .filter(id -> scopeMinId == null || id > scopeMinId)
                .toList();
        if (ids.isEmpty()) return null;
        SqlGate.RenderedFix rendered = SqlGate.compileFixSql(fixSql, staging, table);
        // 血缘标记（readBack 按 fix_column 标 CORRECTED；fixes 只作审计/血缘，实际修走 fixSql）
        String fixCol = rule.params().getOrDefault("fix_column", rule.column());
        List<FixCommand> lineage = ids.stream()
                .map(id -> new FixCommand(rule.table(), id,
                        Map.of(fixCol, "FIXED-BY-CUSTOM-SQL"), rule.expression()))
                .toList();
        return new FixAction(rule.table(), "CUSTOM_SQL", rule.expression(),
                null, lineage, rendered.sql(), ids, rendered.paramCount());
    }

    // ---- 应用修正（execute）----

    /** 应用单个动作：CUSTOM_SQL 修复 SQL / 规则通道批量 SQL / 逐行 id 锚定 UPDATE；返回本轮 FixCommand 审计记录 */
    private List<FixCommand> execute(FixAction action, String staging) {
        List<FixCommand> commands = new ArrayList<>();
        if (action.fixSql() != null) {
            // CUSTOM_SQL：参数化 fix_sql 逐行绑定问题行 id（{id} → ?，参数填充防注入）
            for (Long id : action.fixIds()) {
                Object[] params = new Object[action.fixParamCount()];
                java.util.Arrays.fill(params, id);
                jdbc.execute(action.fixSql(), params);
            }
            commands.addAll(action.fixes());
            return commands;
        }
        if (action.applySql() != null) {
            jdbc.execute(action.applySql());
            commands.addAll(action.fixes());
        } else {
            for (FixCommand fix : action.fixes()) {
                for (Map.Entry<String, Object> e : fix.fixes().entrySet()) {
                    jdbc.execute("UPDATE " + MysqlJdbcDataSource.quote(staging) + "."
                                    + MysqlJdbcDataSource.quote(fix.table())
                                    + " SET " + MysqlJdbcDataSource.quote(e.getKey())
                                    + " = ? WHERE `id` = ?",
                            e.getValue(), fix.id());
                }
                commands.add(fix);
            }
        }
        return commands;
    }

    // ---- 回归回滚（rollback）----

    /**
     * 回归回滚：只恢复请求涉及的表到修正前快照（DELETE + 重载），不 drop 整个库。
     * 全量模式 req.tables() = 全部表 → 效果等同重建工作区；流水线模式单表修正不误伤其他已落库表。
     */
    private void rollback(CorrectionAgent.CorrectionRequest req) {
        String staging = stagingManager.stagingSchema(req.schemaName());
        for (TableMeta t : req.tables()) {
            String where = req.scopeMinId() == null
                    ? "" : " WHERE `id` > " + req.scopeMinId();
            jdbc.execute("DELETE FROM " + MysqlJdbcDataSource.quote(staging) + "."
                    + MysqlJdbcDataSource.quote(t.name()) + where);
        }
        stagingManager.load(req.schemaName(), req.data());
    }

    // ---- 读回（readBack）----

    /**
     * 从 staging 库读回修正后数据（staging 是修正的 source of truth）。
     * 血缘：被修正列标 CORRECTED，其余继承原数据血缘。
     */
    private Map<String, GeneratedData> readBack(List<TableMeta> tables, String staging,
                                                Map<String, GeneratedData> original,
                                                List<FixCommand> applied) {
        Set<String> correctedCols = new HashSet<>();
        for (FixCommand f : applied) {
            f.fixes().keySet().forEach(col -> correctedCols.add(f.table() + ":" + col));
        }
        Map<String, GeneratedData> result = new LinkedHashMap<>();
        for (TableMeta table : tables) {
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT * FROM " + MysqlJdbcDataSource.quote(staging) + "."
                            + MysqlJdbcDataSource.quote(table.name()));
            GeneratedData orig = original.get(table.name());
            Map<Long, DataRow> origById = orig == null ? Map.of() : orig.rows().stream()
                    .filter(r -> r.values().containsKey("id") && r.values().get("id") != null)
                    .collect(java.util.stream.Collectors.toMap(
                            r -> ((Number) r.values().get("id")).longValue(), r -> r, (a, b) -> a));
            List<DataRow> dataRows = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                long id = row.get("id") == null ? 0 : ((Number) row.get("id")).longValue();
                DataRow origRow = origById.get(id);
                Map<String, Object> values = new LinkedHashMap<>(row);
                Map<String, SourceTag> lineage = new LinkedHashMap<>();
                if (origRow != null) lineage.putAll(origRow.lineage());
                for (ColumnDef col : table.columns()) {
                    String key = table.name() + ":" + col.name();
                    if (correctedCols.contains(key)) {
                        lineage.put(col.name(), SourceTag.CORRECTED);
                    } else if (!lineage.containsKey(col.name())) {
                        lineage.put(col.name(), SourceTag.SEED);
                    }
                }
                dataRows.add(new DataRow(values, lineage));
            }
            result.put(table.name(), new GeneratedData(table.name(), table.columns(), dataRows));
        }
        return result;
    }

    // ---- 工具 ----

    /** 问题签名：table:column:errorType（问题清单闭环的判定键） */
    private static Set<String> issueKeys(List<ValidationReport.Bucket> buckets) {
        Set<String> keys = new HashSet<>();
        for (ValidationReport.Bucket b : buckets) {
            keys.add(b.table() + ":" + b.column() + ":" + b.errorType());
        }
        return keys;
    }

    private static long idOf(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    /** 从 correctionRules 找匹配该桶的规则（同表同列同类型） */
    private static ProjectRules.Rule findCorrectionRule(ValidationReport.Bucket bucket,
                                                        ProjectRules rules, String type) {
        if (rules == null || rules.correctionRules() == null) return null;
        return rules.correctionRules().stream()
                .filter(r -> type.equals(r.type())
                        && r.table().equals(bucket.table()) && r.column().equals(bucket.column()))
                .findFirst().orElse(null);
    }

    /** 从表结构找列（FORMAT/NON_NULL 类型默认值推导用） */
    private static ColumnDef findColumn(String tableName, String column, List<TableMeta> tables) {
        if (tables == null) return null;
        for (TableMeta t : tables) {
            if (!t.name().equals(tableName)) continue;
            for (ColumnDef c : t.columns()) {
                if (c.name().equals(column)) return c;
            }
        }
        return null;
    }

    /** 类型默认值 SQL 字面量：数值列 0，其余 'N/A' */
    private static String typeDefault(String tableName, String column, List<TableMeta> tables) {
        ColumnDef col = findColumn(tableName, column, tables);
        if (col != null) {
            String type = col.normalizedType();
            if (type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("SMALLINT")
                    || type.startsWith("TINYINT") || type.startsWith("MEDIUMINT")
                    || type.startsWith("DECIMAL") || type.startsWith("NUMERIC")
                    || type.startsWith("FLOAT") || type.startsWith("DOUBLE")) {
                return "0";
            }
        }
        return "'N/A'";
    }
}
