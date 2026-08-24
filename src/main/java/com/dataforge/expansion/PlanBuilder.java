package com.dataforge.expansion;

import com.dataforge.db.SourceScanner;
import com.dataforge.model.*;

import java.util.*;

/**
 * 生成计划启发式构建（通用化核心）：从表结构推断"怎么生成每张表"——
 * 表分类（source/derived/fixed）+ 列生成模式（fk/seq/enum/weighted/date/faker...）。
 *
 * <p>分析 agent 产出初稿 → 人工确认（置信度标注），换任何业务域零 Java 改动。
 * 启发式规则：
 * <ul>
 *   <li>有 FK 引用 → derived（父表派生）；被引用 + 语义列 → source（LLM 样本）；否则 → fixed</li>
 *   <li>列模式：PK/FK 自动；显式枚举 → enum；数值枚举（status/deleted）→ weighted；
 *       唯一键 → seq；日期 → date；语义格式列（name/phone/email/address/remark）→ faker</li>
 * </ul>
 */
public final class PlanBuilder {

    private PlanBuilder() {}

    /** 从表结构构建完整生成计划（分析 agent 产出，低置信度由人工确认） */
    public static GenerationPlan build(List<TableMeta> tables) {
        return build(tables, new SourceScanner.SourceSemantics());
    }

    /** 源码语义增强版（决策 A，修 D2）：源码枚举/注释优化列模式，替代纯启发式猜列名 */
    public static GenerationPlan build(List<TableMeta> tables, SourceScanner.SourceSemantics semantics) {
        Set<String> referenced = referencedTables(tables);
        List<GenerationPlan.TablePlan> plans = new ArrayList<>();
        for (TableMeta t : tables) {
            plans.add(buildTablePlan(t, tables, referenced, semantics));
        }
        return new GenerationPlan(plans);
    }

    private static GenerationPlan.TablePlan buildTablePlan(TableMeta t, List<TableMeta> all,
                                                           Set<String> referenced,
                                                           SourceScanner.SourceSemantics semantics) {
        String builder = classify(t, referenced);
        String parent = t.foreignKeys().isEmpty() ? null
                : t.foreignKeys().get(0).refTable();
        List<GenerationPlan.ColumnPlan> cols = new ArrayList<>();
        // derived / fixed 表配列模式；source 表列走 LLM（不配）
        if (!"source".equals(builder)) {
            for (ColumnDef c : t.columns()) {
                if (c.primaryKey()) continue;                       // PK 自动
                if (isFkColumn(t, c)) {                             // FK 列 → fk 模式（引用父表池，zipf 幂律长尾）
                    for (ForeignKeyDef fk : t.foreignKeys()) {
                        if (fk.column().equals(c.name())) {
                            cols.add(GenerationPlan.ColumnPlan.of(c.name(), "fk",
                                    Map.of("ref_table", fk.refTable(), "ref_column", fk.refColumn(),
                                            "zipf", "true")));
                            break;
                        }
                    }
                    continue;
                }
                // 价格锚点（锚点派生，修 D4）：derived 表 price 列，父表有 price → 基准价 × 系数
                GenerationPlan.ColumnPlan anchor = anchorPricePlan(t, c, all, parent);
                if (anchor != null) {
                    cols.add(anchor);
                    continue;
                }
                GenerationPlan.ColumnPlan cp = columnPlan(t, c, semantics);
                if (cp != null) cols.add(cp);
            }
        }
        // A1：源码 @TableId PK 策略提示（无则 null → PkGen 用全局默认）
        String pkStrategy = semantics == null ? null : semantics.pkStrategyFor(t.name());
        return new GenerationPlan.TablePlan(t.name(), builder, parent, "2-5", cols, pkStrategy);
    }

    /** 表分类：derived（有 FK）/ source（被引用 + 语义列）/ fixed（其余） */
    private static String classify(TableMeta t, Set<String> referenced) {
        if (!t.foreignKeys().isEmpty()) return "derived";
        if (referenced.contains(t.name()) && hasSemanticColumn(t)) return "source";
        return "fixed";
    }

    /** 列模式推断（源码语义增强：枚举常量优先于启发式） */
    private static GenerationPlan.ColumnPlan columnPlan(TableMeta t, ColumnDef c,
                                                        SourceScanner.SourceSemantics semantics) {
        String name = c.name().toLowerCase();
        String type = c.normalizedType();

        // 源码枚举常量 → enum（显式值列表，优先于 DB 枚举/启发式）
        Map<String, SourceScanner.ColumnSemantics> sem =
                semantics == null ? null : semantics.tableColumns(t.name());
        SourceScanner.ColumnSemantics cs = sem == null ? null : sem.get(c.name());
        if (cs != null && !cs.enumValues().isEmpty()) {
            return GenerationPlan.ColumnPlan.of(c.name(), "enum",
                    Map.of("values", String.join(",", cs.enumValues())));
        }

        // 显式枚举 → enum
        if (!c.enumValues().isEmpty()) {
            return GenerationPlan.ColumnPlan.of(c.name(), "enum");
        }
        // 唯一键 → seq（追加不冲突）
        if (t.uniqueKeys().stream().anyMatch(uk -> uk.split(",")[0].trim().equals(c.name()))) {
            return GenerationPlan.ColumnPlan.of(c.name(), "seq",
                    Map.of("prefix", defaultPrefix(t, c), "width", "6"));
        }
        // 数值枚举（status/deleted 等，COMMENT 含枚举语义）→ weighted，dominant 权重（"绝大多数为 X" → 0.85）
        if (isNumericEnum(c)) {
            List<String> enums = extractEnumNumbers(c.comment());
            if (enums.isEmpty()) return null;
            String dominant = extractDominant(c.comment(), enums);
            String weights;
            if (dominant != null && enums.size() > 1) {
                double rest = Math.round(0.15 / (enums.size() - 1) * 1000) / 1000.0;
                weights = enums.stream().map(v -> v + ":"
                        + (v.equals(dominant) ? "0.85" : String.valueOf(rest)))
                        .collect(java.util.stream.Collectors.joining(","));
            } else {
                weights = enums.stream().map(v -> v + ":1").collect(java.util.stream.Collectors.joining(","));
            }
            return GenerationPlan.ColumnPlan.of(c.name(), "weighted", Map.of("weights", weights));
        }
        // 日期列 → date
        if (type.startsWith("DATE") || type.startsWith("TIME") || "TIMESTAMP".equals(type)
                || type.startsWith("DATETIME")) {
            return GenerationPlan.ColumnPlan.of(c.name(), "date", Map.of("range", "-90d,0d"));
        }
        // 语义格式列 → faker（按列名探测，仅明确人名列）
        String fakerType = fakerType(name);
        if (fakerType != null) {
            return GenerationPlan.ColumnPlan.of(c.name(), "faker", Map.of("type", fakerType));
        }
        // 数值/其他 → const 占位（对账回填或后续增强）
        if (isNumeric(type) || "deleted".equals(name)) {
            return GenerationPlan.ColumnPlan.of(c.name(), "const", Map.of("value", "0"));
        }
        // 其他字符串语义列（商品名/标题/规格/描述）→ LLM 生成真实语义（配置驱动通用）
        if (type.startsWith("VARCHAR") || type.startsWith("CHAR") || type.startsWith("TEXT")) {
            return GenerationPlan.ColumnPlan.of(c.name(), "llm");
        }
        return null;  // 其余留空（由后续/人工确认）
    }

    /**
     * 价格锚点派生（修 D4/D6）：derived 表的 numeric price 列，若父表存在 price 列，
     * 则配 anchorPrice 模式——生成时从父表锚点价 × 随机系数（系数表如 0.8/1.0/1.2）。
     * 满足"锚点派生"语义（product 锚点 → sku 价格变体），参数化通用（不写死电商表名）。
     */
    private static GenerationPlan.ColumnPlan anchorPricePlan(TableMeta t, ColumnDef c,
                                                             List<TableMeta> all, String parent) {
        if (parent == null) return null;
        if (!c.name().toLowerCase().contains("price")) return null;
        if (!isNumeric(c.normalizedType())) return null;
        TableMeta parentMeta = all.stream().filter(x -> x.name().equals(parent)).findFirst().orElse(null);
        if (parentMeta == null) return null;
        String parentPriceCol = parentMeta.columns().stream()
                .filter(pc -> pc.name().toLowerCase().contains("price"))
                .map(ColumnDef::name).findFirst().orElse(null);
        if (parentPriceCol == null) return null;
        return GenerationPlan.ColumnPlan.of(c.name(), "anchorPrice",
                Map.of("ref_table", parent, "ref_column", parentPriceCol, "coefficients", "0.8,1.0,1.2"));
    }

    /** 明确人名列 → faker 人名；其他语义列（商品名/规格/标题）→ LLM（不在本方法） */
    private static String fakerType(String columnName) {
        if (columnName.contains("phone") || columnName.contains("mobile")) return "phone";
        if (columnName.contains("email")) return "email";
        if (columnName.contains("address")) return "address";
        if (columnName.contains("remark") || columnName.contains("note")
                || columnName.contains("comment")) return "remark";
        if (columnName.contains("logistics")) return "logistics";
        // 仅明确人名列用中文名池（收货人/联系人/昵称/会员名）
        if (columnName.startsWith("receiver") || columnName.startsWith("contact")
                || columnName.contains("nickname") || columnName.contains("member_name")
                || columnName.contains("user_name") || columnName.contains("username")) return "name";
        return null;
    }

    /** 从 COMMENT 提炼枚举数字（"状态 1借出 2已还" → [1,2]） */
    private static List<String> extractEnumNumbers(String comment) {
        if (comment == null || comment.isBlank()) return List.of();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(comment);
        List<String> nums = new ArrayList<>();
        while (m.find()) {
            nums.add(m.group());
        }
        return nums;
    }

    /** 从 COMMENT 提取 dominant 枚举值："（绝大多数为 4 已完成）" 或 "4已完成" → "4"；无则 null */
    private static String extractDominant(String comment, List<String> enums) {
        if (comment == null || comment.isBlank()) return null;
        // 1) 显式 dominant："绝大多数为 4" / "默认 1"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(绝大多数|大部分|全部|默认)\\s*(?:为|是)?\\s*(\\d+)").matcher(comment);
        while (m.find()) {
            if (enums.contains(m.group(2))) return m.group(2);
        }
        // 2) 语义终结态："4已完成" / "1正常" / "1上架"（MySQL comment 常被截断"绝大多数"描述，靠语义兜底）
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(\\d+)\\s*([\\u4e00-\\u9fa5A-Za-z]+)").matcher(comment);
        while (m2.find()) {
            if (enums.contains(m2.group(1)) && isDominantWord(m2.group(2))) return m2.group(1);
        }
        return null;
    }

    /** 强终结态关键词（绝大多数数据的落点状态；弱中间态如"已收货/已支付"不算） */
    private static boolean isDominantWord(String word) {
        return word.contains("完成") || word.contains("正常") || word.contains("成功")
                || word.contains("上架") || word.contains("生效") || word.contains("有效");
    }

    private static String defaultPrefix(TableMeta t, ColumnDef c) {
        String table = t.name().toLowerCase();
        String col = c.name().toLowerCase();
        if (col.equals("order_no")) return "ORD-";
        if (table.length() >= 3) return table.substring(0, 3).toUpperCase() + "-";
        return table.toUpperCase() + "-";
    }

    private static boolean isNumericEnum(ColumnDef c) {
        String name = c.name().toLowerCase();
        String type = c.normalizedType();
        boolean numeric = type.startsWith("TINYINT") || type.startsWith("SMALLINT")
                || type.startsWith("INT");
        boolean enumLike = name.contains("status") || name.contains("state")
                || name.contains("type") || name.contains("level") || name.contains("deleted")
                || name.contains("gender") || name.contains("category");
        return numeric && enumLike;
    }

    private static boolean isNumeric(String type) {
        return type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("SMALLINT")
                || type.startsWith("TINYINT") || type.startsWith("DECIMAL") || type.startsWith("NUMERIC")
                || type.startsWith("FLOAT") || type.startsWith("DOUBLE");
    }

    private static boolean isFkColumn(TableMeta t, ColumnDef c) {
        return t.foreignKeys().stream().anyMatch(fk -> fk.column().equals(c.name()));
    }

    private static boolean hasSemanticColumn(TableMeta t) {
        return t.columns().stream().anyMatch(c -> {
            String name = c.name().toLowerCase();
            return name.contains("name") || name.contains("title") || name.contains("nickname")
                    || name.contains("desc") || name.contains("remark") || name.contains("comment");
        });
    }

    private static Set<String> referencedTables(List<TableMeta> tables) {
        Set<String> referenced = new HashSet<>();
        for (TableMeta t : tables) {
            for (ForeignKeyDef fk : t.foreignKeys()) {
                referenced.add(fk.refTable());
            }
        }
        return referenced;
    }
}
