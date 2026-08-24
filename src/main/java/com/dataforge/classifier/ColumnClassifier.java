package com.dataforge.classifier;

import com.dataforge.model.ColumnDef;

import java.util.ArrayList;
import java.util.List;

/**
 * 列分类器（P1 默认启发式；P6 分析 agent 上线后产出列生成指令覆盖此默认）。
 *
 * <p>分类规则：
 * <ol>
 *   <li>技术类型 RULE：PK / FK / ENUM / 日期 / 数值枚举开关列（status/deleted/level，规则层用 COMMENT 加权）</li>
 *   <li>名称模式 RULE：phone / email / url / avatar / ip / qq 等格式固定字符串</li>
 *   <li>其余业务语义列 → LLM（由 COMMENT 决定具体语义）</li>
 * </ol>
 * 信任模型红线：RULE 列用确定性代码生成，LLM 只做语义决策。</p>
 */
public final class ColumnClassifier {

    public enum Category { RULE, LLM }

    /** 分类结果（含来源标记：TYPE / PATTERN / LLM） */
    public record ColumnClassification(ColumnDef column, Category category, String source) {
    }

    public static final String SOURCE_TYPE = "TYPE";
    public static final String SOURCE_PATTERN = "PATTERN";
    public static final String SOURCE_LLM = "LLM";

    private ColumnClassifier() {}

    /**
     * 整表三层混合分类。
     *
     * @param columns    全列
     * @param fkColumns  本表外键列名集合（来自 TableMeta.foreignKeys）
     */
    public static List<ColumnClassification> hybridClassify(List<ColumnDef> columns, List<String> fkColumns) {
        List<ColumnClassification> result = new ArrayList<>();
        for (ColumnDef col : columns) {
            Category typeCat = classifyByType(col, fkColumns);
            if (typeCat != null) {
                result.add(new ColumnClassification(col, typeCat, SOURCE_TYPE));
                continue;
            }
            if (isPatternString(col)) {
                result.add(new ColumnClassification(col, Category.RULE, SOURCE_PATTERN));
                continue;
            }
            result.add(new ColumnClassification(col, Category.LLM, SOURCE_LLM));
        }
        return result;
    }

    /** 技术类型分类：PK / FK / ENUM / 日期 / 数值枚举 → RULE；其余返回 null 交下层判断 */
    public static Category classifyByType(ColumnDef col, List<String> fkColumns) {
        String type = col.normalizedType();
        if (col.primaryKey()) return Category.RULE;
        if (fkColumns != null && fkColumns.contains(col.name())) return Category.RULE;
        if ("ENUM".equals(type)) return Category.RULE;
        if (type.startsWith("DATE") || type.startsWith("TIME") || type.startsWith("YEAR")) return Category.RULE;
        // 数值枚举/开关列 → RULE（确定性规则生成，非 LLM）：
        // status/deleted/admin_level 等"绝大多数为 X"的字段，规则层用 COMMENT 提取 + 加权生成
        if (isNumericEnumColumn(col)) return Category.RULE;
        return null;
    }

    /** 数值枚举/开关列判定：TINYINT/INT 等 且（列名语义 或 COMMENT 含数字枚举模式） */
    static boolean isNumericEnumColumn(ColumnDef col) {
        String type = col.normalizedType();
        boolean numeric = type.startsWith("TINYINT") || type.startsWith("SMALLINT")
                || type.startsWith("MEDIUMINT") || type.startsWith("INT") || type.startsWith("BIGINT");
        if (!numeric) return false;
        String name = col.name().toLowerCase();
        // 列名语义：状态/开关/级别/标志
        if (name.contains("status") || name.contains("state") || name.contains("deleted")
                || name.contains("enable") || name.contains("disabled") || name.contains("level")
                || name.contains("flag") || name.contains("is_") || name.contains("has_")
                || name.equals("type") || name.equals("sort") || name.equals("quantity")) {
            return true;
        }
        // COMMENT 含数字枚举模式（"0正常 1停用 2注销中"）
        String comment = col.comment() != null ? col.comment() : "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+\\s*[\\u4e00-\\u9fa5A-Za-z]").matcher(comment);
        int count = 0;
        while (m.find() && count < 3) count++;
        return count >= 2;
    }

    /** 格式固定字符串列（VARCHAR/TEXT）→ RULE：手机号/邮箱/身份证/URL/图片/IP/QQ */
    public static boolean isPatternString(ColumnDef col) {
        String type = col.normalizedType();
        if (!type.equals("VARCHAR") && !type.equals("CHAR") && !type.equals("TEXT")) {
            return false;
        }
        String name = col.name().toLowerCase();
        // 手机号
        if (name.equals("phone") || name.equals("mobile") || name.equals("tel")
                || name.equals("telephone") || name.equals("cellphone")
                || name.startsWith("phone_") || name.startsWith("mobile_")
                || name.equals("contact_phone") || name.equals("contact_tel")
                || name.contains("_phone") || name.contains("_mobile")) {
            return true;
        }
        // 邮箱
        if (name.equals("email") || name.equals("mail")
                || name.endsWith("_email") || name.endsWith("_mail")) {
            return true;
        }
        // 身份证
        if (name.contains("id_card") || name.contains("idcard")
                || name.equals("identity_card") || name.equals("id_no")
                || name.contains("identity_number")) {
            return true;
        }
        // URL / 图片 / 图标 / LOGO
        if (name.equals("url") || name.equals("website") || name.equals("link")
                || name.equals("avatar") || name.equals("photo")
                || name.equals("image") || name.equals("images")
                || name.equals("icon") || name.equals("icons")
                || name.equals("logo") || name.equals("shop_logo")
                || name.endsWith("_url") || name.endsWith("_link")
                || name.endsWith("_image") || name.endsWith("_img")
                || name.endsWith("_pic") || name.endsWith("_logo")
                || name.endsWith("_icon")) {
            return true;
        }
        // IP 地址
        if (name.equals("ip") || name.equals("ip_address")) {
            return true;
        }
        // QQ / 微信
        return name.equals("qq") || name.equals("wechat") || name.equals("weixin");
    }
}
