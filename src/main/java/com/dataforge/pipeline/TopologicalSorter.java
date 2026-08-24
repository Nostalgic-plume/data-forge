package com.dataforge.pipeline;

import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.TableMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 拓扑排序器（移植 real-world-data TopologicalSorter，适配 TableMeta）。
 *
 * <p>两张核心能力：
 * <ul>
 *   <li>{@link #sort}：Kahn BFS 分层，同层无依赖可并行，层间串行；含环检测（环内表追加最后）</li>
 *   <li>{@link #dependencyGraph}：tableName → 直接父表集合，ContentGen 做 DAG 数据流调度</li>
 * </ul>
 */
public final class TopologicalSorter {

    private TopologicalSorter() {}

    /** Kahn BFS 分层排序，返回 层(表列表) 序列；环内表追加到最后层。 */
    public static List<List<TableMeta>> sort(List<TableMeta> tables) {
        Map<String, TableMeta> tableMap = new LinkedHashMap<>();
        for (TableMeta t : tables) tableMap.put(t.name(), t);

        Map<String, Integer> inDegree = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            inDegree.put(t.name(), 0);
            dependents.put(t.name(), new LinkedHashSet<>());
        }
        for (TableMeta t : tables) {
            for (ForeignKeyDef fk : t.foreignKeys()) {
                if (tableMap.containsKey(fk.refTable())) {
                    dependents.computeIfAbsent(fk.refTable(), k -> new LinkedHashSet<>()).add(t.name());
                    inDegree.merge(t.name(), 1, Integer::sum);
                }
            }
        }

        List<List<TableMeta>> levels = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.offer(entry.getKey());
        }

        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            List<String> currentNames = new ArrayList<>(levelSize);
            for (int i = 0; i < levelSize; i++) {
                String name = queue.poll();
                currentNames.add(name);
                visited.add(name);
            }
            for (String name : currentNames) {
                for (String dep : dependents.getOrDefault(name, Set.of())) {
                    int deg = inDegree.get(dep) - 1;
                    inDegree.put(dep, deg);
                    if (deg == 0 && !visited.contains(dep)) {
                        queue.offer(dep);
                    }
                }
            }
            List<TableMeta> levelTables = currentNames.stream()
                    .map(tableMap::get).filter(Objects::nonNull).toList();
            if (!levelTables.isEmpty()) levels.add(levelTables);
        }

        // 环内表（未被访问的）追加到最后层
        List<TableMeta> orphaned = tables.stream()
                .filter(t -> !visited.contains(t.name())).toList();
        if (!orphaned.isEmpty()) levels.add(orphaned);

        return levels;
    }

    /** tableName → 其直接依赖的所有父表集合（仅生成集内的表） */
    public static Map<String, Set<String>> dependencyGraph(List<TableMeta> tables) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        Map<String, TableMeta> tableMap = tables.stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        for (TableMeta t : tables) {
            Set<String> parents = new LinkedHashSet<>();
            for (ForeignKeyDef fk : t.foreignKeys()) {
                if (tableMap.containsKey(fk.refTable())) {
                    parents.add(fk.refTable());
                }
            }
            deps.put(t.name(), parents);
        }
        return deps;
    }
}
