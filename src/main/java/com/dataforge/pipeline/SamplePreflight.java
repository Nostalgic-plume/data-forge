package com.dataforge.pipeline;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;

import java.util.*;

/**
 * 样本预检（P1 早期止损）：LLM 语义源表样本在<b>展开前</b>做确定性校验。
 *
 * <p>预检规则（确定性，非 LLM）：
 * <ol>
 *   <li>行数达标（样本不足 → 展开会缺量）</li>
 *   <li>每行 NOT NULL 列齐全（缺列 = 列完整性失败）</li>
 *   <li>唯一键无冲突（uk_username 等）</li>
 *   <li>FK 引用存在（引用父表已生成样本的值）</li>
 * </ol>
 * 失败 → 调用方重生成（≤2 次），仍失败标记失败表（失败隔离）。</p>
 */
public final class SamplePreflight {

    private SamplePreflight() {}

    /** 预检一张 LLM 样本表，返回错误列表（空 = 通过） */
    public static List<String> preflight(TableMeta table, GeneratedData data, int expectedRows,
                                         Map<String, Map<String, List<Object>>> completedData) {
        List<String> errors = new ArrayList<>();
        List<DataRow> rows = data.rows();
        if (rows == null || rows.isEmpty()) {
            errors.add("表 `" + table.name() + "` 样本为空");
            return errors;
        }
        // 1. 行数达标
        if (rows.size() < expectedRows) {
            errors.add("表 `" + table.name() + "` 样本行数不足：期望 " + expectedRows + "，实际 " + rows.size());
        }
        // 2. NOT NULL 列齐全 + 3. 唯一键无冲突
        List<String> notNullCols = table.columns().stream()
                .filter(c -> !c.nullable()).map(ColumnDef::name).toList();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> values = rows.get(i).values();
            for (String col : notNullCols) {
                Object v = values.get(col);
                if (v == null || (v instanceof String s && s.isBlank())) {
                    errors.add("表 `" + table.name() + "` 第 " + (i + 1) + " 行 NOT NULL 列缺失: `" + col + "`");
                    break;
                }
            }
            for (String ukGroup : table.uniqueKeys()) {
                StringBuilder key = new StringBuilder();
                for (String col : ukGroup.split(",")) {
                    Object v = values.get(col.trim());
                    key.append(v != null ? v : "null").append("|");
                }
                if (!seen.add(key.toString())) {
                    errors.add("表 `" + table.name() + "` 唯一键冲突: (" + ukGroup + ") = " + key);
                }
            }
        }
        // 4. FK 引用存在（父表已生成的样本值）
        for (var fk : table.foreignKeys()) {
            List<Object> parentValues = completedData.get(fk.refTable()) != null
                    ? completedData.get(fk.refTable()).get(fk.refColumn()) : null;
            if (parentValues == null) continue; // 父表未生成，展开阶段负责
            Set<String> parentSet = new HashSet<>();
            for (Object v : parentValues) if (v != null) parentSet.add(v.toString());
            for (int i = 0; i < rows.size(); i++) {
                Object v = rows.get(i).values().get(fk.column());
                if (v != null && !parentSet.contains(v.toString())) {
                    errors.add("表 `" + table.name() + "` 第 " + (i + 1) + " 行 FK 悬挂: `"
                            + fk.column() + "` = " + v + "（不在 `" + fk.refTable() + "` 中）");
                    break;
                }
            }
        }
        return errors;
    }
}
