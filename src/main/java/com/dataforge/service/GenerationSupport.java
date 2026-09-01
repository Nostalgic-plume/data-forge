package com.dataforge.service;

import com.dataforge.model.TableMeta;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成支撑：引用闭包计算（R1，CLI/Web 重复逻辑统一）。
 *
 * <p>闭包 = 用户显式指定表 ∪ 沿 FK 递归扩展的必需父表；未指定行数的父表用缺省值。
 * 统一规则：请求表不存在时抛异常（Web 旧实现静默跳过，统一抛错更安全）。</p>
 */
public final class GenerationSupport {

    private GenerationSupport() {}

    /** 闭包结果：生成表集合 + 每表行数（未指定行数的父表用缺省值） */
    public record Closure(Set<String> tables, Map<String, Integer> rowCounts) {
    }

    /**
     * 引用闭包：指定表 ∪ 沿 FK 的必需父表。
     *
     * @param allTables         全量表结构（按表名索引）
     * @param requested         用户显式指定表 → 行数
     * @param parentDefaultRows 闭包父表缺省行数（Web 旧实现硬编码 10，统一参数化，传 props.minParentRows()）
     * @throws IllegalArgumentException 请求表不存在于表结构
     */
    public static Closure referenceClosure(List<TableMeta> allTables, Map<String, Integer> requested,
                                           int parentDefaultRows) {
        Map<String, TableMeta> byName = new HashMap<>();
        for (TableMeta t : allTables) byName.put(t.name(), t);
        for (String table : requested.keySet()) {
            if (!byName.containsKey(table)) {
                throw new IllegalArgumentException("DDL 中不存在表: " + table);
            }
        }
        Set<String> closure = new LinkedHashSet<>(requested.keySet());
        Deque<String> queue = new ArrayDeque<>(requested.keySet());
        while (!queue.isEmpty()) {
            TableMeta meta = byName.get(queue.poll());
            if (meta == null) continue;
            for (var fk : meta.foreignKeys()) {
                if (byName.containsKey(fk.refTable()) && closure.add(fk.refTable())) {
                    queue.add(fk.refTable());
                }
            }
        }
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        for (String table : closure) {
            rowCounts.put(table, requested.getOrDefault(table, parentDefaultRows));
        }
        return new Closure(closure, rowCounts);
    }
}
