package com.dataforge.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码语义扫描器（决策 A，修 D2）：确定性扫描项目源码 → 列语义字典。
 *
 * <p>分析 agent 的确定性子任务，与 LLM 规则提炼并行。识别：
 * <ul>
 *   <li><b>表名</b>：@TableName("user") 注解；无注解时类名去 DO/Entity 后缀转 snake_case</li>
 *   <li><b>列注释</b>：@Schema(description="...")（比 DB COMMENT 更丰富）</li>
 *   <li><b>PK 策略</b>：@TableId(type=ASSIGN_ID) → SNOWFLAKE；AUTO → SEQUENTIAL</li>
 *   <li><b>枚举常量</b>：enum 类的常量值（字段类型匹配枚举类 → 列枚举）</li>
 * </ul>
 * 轻量文本扫描（不引 AST 库）：逐行状态机，字段与其上方注解关联。</p>
 *
 * <p>信任模型红线：确定性扫描（非 LLM），产出供 PlanBuilder 优化列模式。</p>
 */
public final class SourceScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceScanner.class);

    private SourceScanner() {}

    /** 单列语义（表 → 列 → 语义） */
    public record ColumnSemantics(
            String comment,            // @Schema 注释（可 null）
            List<String> enumValues,   // 关联枚举常量（可空）
            String pkStrategy          // null / "SEQUENTIAL" / "SNOWFLAKE"（@TableId 判定）
    ) {
    }

    /** 扫描结果：表名(snake) → 列名(snake) → 语义 */
    public static final class SourceSemantics {
        private final Map<String, Map<String, ColumnSemantics>> columns = new LinkedHashMap<>();

        public boolean isEmpty() {
            return columns.isEmpty();
        }

        public Map<String, ColumnSemantics> tableColumns(String table) {
            return columns.get(table);
        }

        public Map<String, Map<String, ColumnSemantics>> all() {
            return columns;
        }

        /** 某表 PK 策略提示（@TableId 判定；无 → null）——A1：PkGen 消费 */
        public String pkStrategyFor(String table) {
            Map<String, ColumnSemantics> cols = columns.get(table);
            if (cols == null) return null;
            for (ColumnSemantics sem : cols.values()) {
                if (sem.pkStrategy() != null) return sem.pkStrategy();
            }
            return null;
        }

        void put(String table, String column, ColumnSemantics sem) {
            columns.computeIfAbsent(table, k -> new LinkedHashMap<>()).put(column, sem);
        }
    }

    // ---- 正则（逐行匹配） ----

    private static final Pattern TABLE_NAME = Pattern.compile(
            "@TableName\\s*\\(\\s*\"?([A-Za-z0-9_]+)\"?\\s*\\)");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?:public\\s+|abstract\\s+|final\\s+)*class\\s+(\\w+)");
    private static final Pattern ENUM_DECL = Pattern.compile(
            "(?:public\\s+|final\\s+)?enum\\s+(\\w+)");
    private static final Pattern FIELD_DECL = Pattern.compile(
            "^\\s*private\\s+(\\S+?)\\s+(\\w+)\\s*;");
    private static final Pattern SCHEMA_COMMENT = Pattern.compile(
            "@Schema\\s*\\(\\s*description\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern TABLE_ID = Pattern.compile(
            "@TableId\\s*\\(\\s*type\\s*=\\s*IdType\\.(\\w+)");

    /**
     * 扫描 projectPath 下所有 .java 源码（跳过 target/ 等构建目录）。
     *
     * @param projectPath 源码根目录（null/空/不存在 → 空结果，不抛异常）
     */
    public static SourceSemantics scan(String projectPath) {
        SourceSemantics result = new SourceSemantics();
        if (projectPath == null || projectPath.isBlank()) return result;
        Path root = Path.of(projectPath);
        if (!Files.exists(root)) {
            log.warn("源码路径不存在，跳过源码语义扫描: {}", projectPath);
            return result;
        }
        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream.filter(p -> {
                String s = p.toString().replace('\\', '/');
                return s.endsWith(".java") && !s.contains("/target/") && !s.contains("/node_modules/");
            }).toList();
        } catch (IOException e) {
            log.warn("源码扫描失败（跳过）: {}", e.getMessage());
            return result;
        }
        if (files.isEmpty()) return result;

        // 1. 先收集枚举类常量（字段类型引用枚举）
        Map<String, List<String>> enums = new HashMap<>();
        for (Path f : files) {
            try {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                Matcher em = ENUM_DECL.matcher(text);
                while (em.find()) {
                    String name = em.group(1);
                    enums.put(name, extractEnumConstants(extractBraced(text, em.end())));
                }
            } catch (IOException ignored) {
                // 单个文件读取失败跳过
            }
        }

        // 2. 解析 Entity 类 → 列语义字典
        for (Path f : files) {
            try {
                parseEntityFile(Files.readString(f, StandardCharsets.UTF_8), result, enums);
            } catch (IOException ignored) {
                // 单个文件读取失败跳过
            }
        }
        log.info("源码语义扫描完成：{} 个 .java，{} 张表（含列语义）", files.size(), result.columns.size());
        return result;
    }

    /** 逐行状态机：@TableName → 表名；@Schema → 列注释；@TableId → PK 策略；private 字段 → 产出语义 */
    private static void parseEntityFile(String text, SourceSemantics result, Map<String, List<String>> enums) {
        String currentTable = null;
        String pendingClass = null;
        String pendingComment = null;
        String pendingPk = null;

        for (String line : text.split("\r?\n")) {
            String t = line.trim();
            Matcher tm = TABLE_NAME.matcher(t);
            if (tm.find()) {
                currentTable = tm.group(1);
                continue;
            }
            Matcher cm = CLASS_DECL.matcher(t);
            if (cm.find() && t.contains("class") && !t.contains("import")) {
                pendingClass = cm.group(1);
                continue;
            }
            Matcher im = TABLE_ID.matcher(t);
            if (im.find()) {
                pendingPk = im.group(1);
                continue;
            }
            Matcher sm = SCHEMA_COMMENT.matcher(t);
            if (sm.find()) {
                pendingComment = sm.group(1);
                continue;
            }
            Matcher fm = FIELD_DECL.matcher(t);
            if (fm.find()) {
                String type = fm.group(1);
                String field = fm.group(2);
                String table = currentTable;
                if (table == null && pendingClass != null) {
                    table = camelToSnake(stripEntitySuffix(pendingClass));
                }
                if (table != null) {
                    String column = camelToSnake(field);
                    String pkStrategy = "ASSIGN_ID".equals(pendingPk) ? "SNOWFLAKE"
                            : ("AUTO".equals(pendingPk) ? "SEQUENTIAL" : null);
                    List<String> enumVals = enums.get(type);
                    result.put(table, column, new ColumnSemantics(
                            pendingComment,
                            enumVals == null ? List.of() : enumVals,
                            pkStrategy));
                }
                pendingComment = null;
                pendingPk = null;
            }
        }
    }

    // ---- 枚举常量提取 ----

    /** 提取 { } 匹配块（从 from 位置找 {） */
    private static String extractBraced(String text, int from) {
        int start = text.indexOf('{', from);
        if (start < 0) return "";
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start + 1, i);
            }
        }
        return "";
    }

    /** 枚举体顶层逗号分隔的常量（大写标识符） */
    private static List<String> extractEnumConstants(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[A-Z][A-Z0-9_]*").matcher(body);
        while (m.find()) out.add(m.group());
        return out;
    }

    // ---- 命名转换 ----

    /** 类名 → 表名：去 DO/Entity 后缀再 snake_case（UserDO → user，UserRoleDO → user_role） */
    private static String stripEntitySuffix(String className) {
        String name = className;
        for (String suffix : new String[]{"DO", "Entity", "DOImpl"}) {
            if (name.endsWith(suffix) && name.length() > suffix.length()) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return name;
    }

    /** 驼峰 → snake_case：createTime → create_time；username → username */
    static String camelToSnake(String camel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
