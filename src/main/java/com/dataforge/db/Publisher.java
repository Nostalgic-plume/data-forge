package com.dataforge.db;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 发布层（PLAN 决策 #13）：staging 工作库按保留策略落到目标库。
 * 目标库变更永远是显式的最后一步——发布前 preflight 二次确认。
 *
 * <p><b>策略（三重防线，默认最保守）</b>：
 * <ol>
 *   <li>{@code KEEP}（默认）——不动目标表</li>
 *   <li>{@code APPEND}——只追加，主键冲突由 INSERT 报错暴露</li>
 *   <li>{@code REPLACE}——先清空后全量导入；<b>只有显式指定才生效</b></li>
 * </ol>
 * <b>未知策略一律按 KEEP 跳过</b>（策略名拼错不会误清库），加上调用方的 {@code confirmed}
 * 二次确认，构成"清空目标表"这件事的三道闸。</p>
 */
@Component
public class Publisher {

    private static final Logger log = LoggerFactory.getLogger(Publisher.class);

    /** 分批多值 INSERT 的行数（22 万行逐行 execute 要 22 万次往返，慢到不可用） */
    private static final int INSERT_BATCH = 500;

    /** 发布结果：成功表 / 跳过表（KEEP 或未知策略）/ 失败表 */
    public record Result(int published, List<String> skipped, List<String> errors) {
    }

    /**
     * 发布 staging 数据到目标库（需 confirmed=true 二次确认）。
     *
     * @param jdbc         目标库确定性 JDBC 通道（动态连接）
     * @param targetSchema 目标库名（用户连接的库）
     * @param data         staging 读回的数据
     * @param policies     每表保留策略（缺省 KEEP）
     */
    public Result publish(JdbcDataSource jdbc, String targetSchema, List<TableMeta> tables,
                          Map<String, GeneratedData> data, Map<String, String> policies,
                          boolean confirmed) {
        if (!confirmed) {
            return new Result(0, List.of(), List.of("未二次确认，发布已拒绝"));
        }
        int published = 0;
        List<String> skipped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (TableMeta t : tables) {
            String policy = policies.getOrDefault(t.name(), "KEEP");
            GeneratedData gd = data.get(t.name());
            if (gd == null || gd.rows().isEmpty()) continue;
            try {
                switch (policy) {
                    case "REPLACE" -> {
                        truncate(jdbc, targetSchema, t.name());
                        insertRows(jdbc, targetSchema, t, gd);
                        published++;
                    }
                    case "APPEND" -> {
                        insertRows(jdbc, targetSchema, t, gd);
                        published++;
                    }
                    default -> {
                        // KEEP 及任何无法识别的策略：不动目标库（安全兜底）
                        if (!"KEEP".equals(policy)) {
                            log.warn("表 `{}` 的策略 `{}` 无法识别，按 KEEP 跳过", t.name(), policy);
                        }
                        skipped.add(t.name());
                    }
                }
            } catch (Exception e) {
                errors.add(t.name() + ": " + e.getMessage());
            }
        }
        log.info("发布完成：{} 张表，跳过 {}（KEEP/未知策略），失败 {}",
                published, skipped.size(), errors.size());
        return new Result(published, skipped, errors);
    }

    /**
     * 清空目标表（REPLACE 专用）。用 TRUNCATE（DDL，不可回滚）而非 DELETE：
     * 大表下 DELETE 慢一个量级，而"不可回滚"由调用方的二次确认 + 发布前快照承担。
     */
    private void truncate(JdbcDataSource jdbc, String schema, String table) {
        jdbc.execute("TRUNCATE TABLE " + MysqlJdbcDataSource.quote(schema)
                + "." + MysqlJdbcDataSource.quote(table));
    }

    private void insertRows(JdbcDataSource jdbc, String schema, TableMeta t, GeneratedData gd) {
        String cols = t.columns().stream().map(ColumnDef::name)
                .map(MysqlJdbcDataSource::quote).collect(Collectors.joining(", "));
        String oneRow = "(" + String.join(",",
                Collections.nCopies(t.columns().size(), "?")) + ")";
        String prefix = "INSERT INTO " + MysqlJdbcDataSource.quote(schema) + "."
                + MysqlJdbcDataSource.quote(t.name()) + " (" + cols + ") VALUES ";
        List<DataRow> rows = gd.rows();
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            int to = Math.min(from + INSERT_BATCH, rows.size());
            List<DataRow> slice = rows.subList(from, to);
            String sql = prefix + String.join(",", Collections.nCopies(slice.size(), oneRow));
            List<Object> params = new ArrayList<>(slice.size() * t.columns().size());
            for (DataRow row : slice) {
                for (ColumnDef c : t.columns()) {
                    params.add(row.values().get(c.name()));
                }
            }
            jdbc.execute(sql, params.toArray());
        }
    }
}
