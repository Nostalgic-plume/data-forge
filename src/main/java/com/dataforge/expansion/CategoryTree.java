package com.dataforge.expansion;

import com.dataforge.model.ColumnDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 商品分类树（固定种子表 {@code category} 的唯一数据源）：读 {@code category-tree.json}，
 * 按数组顺序整表打表生成——<b>不随机、不重名、父子关系由文件写死</b>。
 *
 * <p>为什么要整表生成：分类原先在 plan 里被拆成"每列各自随机"（name 走 pool、
 * parent_id 走 valueRange），于是同时踩了三个坑——pool 是独立抽样所以 30 个名池生成 30 行必然重名、
 * parent_id 在 0~6 随机所以"水果"会挂在"家用电器"下、status 写死 1 而 DDL 里 1 是"隐藏"。
 * 三者都不是引擎 bug，是"把一棵静态树拆成独立随机列"这个建模方式本身就错了。</p>
 *
 * <p>与 {@link SpecTemplates} 的关键差异在失败姿态：规格模板缺失 → 返回空表，因为规格列允许为空；
 * 而本表是 {@code product} 的父表，缺了它下游引用全部悬挂，<b>静默兜底会造出一库孤儿数据</b>，
 * 故一律抛异常走失败表隔离。</p>
 */
final class CategoryTree {

    private static final Logger log = LoggerFactory.getLogger(CategoryTree.class);
    private static final String TREE_FILE = "category-tree.json";

    /** 解析结果：与配置同序的节点表（下标 + 1 即 id） */
    private record Node(String name, long parentId) {}

    private static volatile List<Node> cached;

    private CategoryTree() {}

    /** 是否为固定分类表（{@code PkGen} / {@code ContentGen} 借 {@code FixedSamples} 分派到这里） */
    static boolean isCategory(String tableName) {
        return "category".equals(tableName);
    }

    /** 生成整棵分类树的行（列按列名匹配，未覆盖的列落 null） */
    static List<Map<String, Object>> rows(List<ColumnDef> columns, Random rng) {
        List<Node> nodes = load();
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            long id = i + 1L;
            String createTime = ValuePools.randomDateTime(365, rng);
            Map<String, Object> row = new LinkedHashMap<>();
            for (ColumnDef col : columns) {
                row.put(col.name(), switch (col.name()) {
                    case "id" -> id;
                    case "name" -> node.name();
                    case "parent_id" -> node.parentId();
                    case "sort" -> (int) id * 10;          // 同层之间有间隔，便于前端插位
                    case "icon" -> "/static/icons/category/" + id + ".png";
                    case "status" -> 0;                    // DDL：0 正常 / 1 隐藏——写 1 会让整库类目看不见
                    case "create_time" -> createTime;
                    case "update_time" -> ValuePools.laterDateTime(createTime, rng);
                    case "deleted" -> 0;
                    default -> null;
                });
            }
            rows.add(row);
        }
        log.info("固定分类树生成 {} 行（来源 {}）", rows.size(), TREE_FILE);
        return rows;
    }

    /** 读文件并解析成节点表；父子按名字二级回查（故要求父节点在前） */
    private static List<Node> load() {
        List<Node> local = cached;
        if (local != null) return local;
        synchronized (CategoryTree.class) {
            if (cached != null) return cached;
            cached = read();
            return cached;
        }
    }

    private static List<Node> read() {
        File file = new File(TREE_FILE);
        if (!file.isFile()) {
            throw new IllegalStateException("固定分类表 `category` 缺少数据源 " + TREE_FILE
                    + "（路径：" + file.getAbsolutePath() + "）；该文件不入库，需本地创建。"
                    + "分类是 product 的父表，缺数据源会造出满库孤儿引用，故此处不兜底。");
        }
        try {
            JsonNode nodes = new ObjectMapper().readTree(file).path("nodes");
            if (!nodes.isArray() || nodes.isEmpty()) {
                throw new IllegalStateException(TREE_FILE + " 的 nodes 必须是非空数组");
            }
            Map<String, Long> idByName = new LinkedHashMap<>();
            List<Node> out = new ArrayList<>(nodes.size());
            for (int i = 0; i < nodes.size(); i++) {
                JsonNode item = nodes.get(i);
                if (!item.isArray() || item.size() < 1 || !item.get(0).isTextual()) {
                    throw new IllegalStateException(TREE_FILE + " 第 " + (i + 1)
                            + " 项格式应为 [分类名, 父分类名或 null]");
                }
                String name = item.get(0).asText().trim();
                if (name.isEmpty()) {
                    throw new IllegalStateException(TREE_FILE + " 第 " + (i + 1) + " 项分类名为空");
                }
                if (idByName.putIfAbsent(name, i + 1L) != null) {
                    throw new IllegalStateException(TREE_FILE + " 分类名重复：" + name);
                }

                JsonNode parentNode = item.size() > 1 ? item.get(1) : null;
                long parentId = 0L;                        // DDL 约定：0 = 一级类目
                if (parentNode != null && parentNode.isTextual()) {
                    String parentName = parentNode.asText().trim();
                    if (!parentName.isEmpty()) {
                        Long pid = idByName.get(parentName);
                        if (pid == null) {
                            throw new IllegalStateException(TREE_FILE + " 第 " + (i + 1) + " 项「" + name
                                    + "」的父分类「" + parentName + "」未在前面出现——父节点必须先于子节点声明");
                        }
                        parentId = pid;
                    }
                }
                out.add(new Node(name, parentId));
            }
            return List.copyOf(out);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析 " + TREE_FILE + " 失败：" + e.getMessage(), e);
        }
    }
}
