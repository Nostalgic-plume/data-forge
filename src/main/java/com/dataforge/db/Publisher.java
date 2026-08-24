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
 * 发布层（PLAN 决策 #13）：staging 工作库按保留策略（REPLACE 清空重建 / APPEND 追加 /
 * KEEP 不动）落到目标库。目标库变更永远是显式的最后一步——发布前 preflight 二次确认。
 */
@Component
public class Publisher {

    private static final Logger log = LoggerFactory.getLogger(Publisher.class);

    /** 发布结果：成功表 / 跳过表（KEEP）/ 失败表 */
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
    public Result publish(MysqlJdbcDataSource jdbc, String targetSchema, List<TableMeta> tables,
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
                if ("KEEP".equals(policy)) {
                    skipped.add(t.name());
                    continue;
                }
                // 安全红线：目标库绝不 DROP/清空——只追加（APPEND），主键冲突由 INSERT 报错提示
                insertRows(jdbc, targetSchema, t, gd);
                published++;
            } catch (Exception e) {
                errors.add(t.name() + ": " + e.getMessage());
            }
        }
        log.info("发布完成：{} 张表（REPLACE/APPEND），跳过 {}（KEEP），失败 {}",
                published, skipped.size(), errors.size());
        return new Result(published, skipped, errors);
    }

    private void insertRows(MysqlJdbcDataSource jdbc, String schema, TableMeta t, GeneratedData gd) {
        String cols = t.columns().stream().map(ColumnDef::name)
                .map(MysqlJdbcDataSource::quote).collect(Collectors.joining(", "));
        String placeholders = String.join(",", Collections.nCopies(t.columns().size(), "?"));
        String sql = "INSERT INTO " + MysqlJdbcDataSource.quote(schema) + "."
                + MysqlJdbcDataSource.quote(t.name()) + " (" + cols + ") VALUES (" + placeholders + ")";
        for (DataRow row : gd.rows()) {
            Object[] vals = t.columns().stream()
                    .map(c -> row.values().get(c.name())).toArray();
            jdbc.execute(sql, vals);
        }
    }
}
