package com.dataforge.generator;

import com.dataforge.model.ColumnDef;
import net.datafaker.Faker;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 规则列生成器（移植 real-world-data RuleDataGenerator，瘦身：PK/FK 列不在此生成，
 * 由 PkGen 骨架 / FkAlloc 预分配注入——职责单一）。
 *
 * <p>支持：TINYINT/INT/BIGINT（COMMENT 枚举范围 + 语义加权）、DECIMAL（价格语义）、
 * ENUM（语义加权）、DATE/DATETIME（列名+COMMENT 语义时间范围）、格式字符串（Faker）。</p>
 *
 * <p>随机源由调用方传入（管线统一 Random(seed)，保证可复现）。</p>
 */
public final class RuleDataGenerator {

    private static final Faker FAKER = new Faker(Locale.CHINA);

    /** picsum 占位图 seed 上界（随机种子，降低 URL 重复） */
    private static final int PICSUM_SEED_BOUND = 10000;

    private RuleDataGenerator() {}

    /**
     * picsum 占位图 URL（400×400）。
     *
     * <p>原先在"images 多图"与"单图 logo/avatar"两个分支各拼接一次同一模板，
     * 调整尺寸或换占位服务时容易只改一处。</p>
     */
    private static String picsumUrl(Random rng) {
        return "https://picsum.photos/seed/" + rng.nextInt(PICSUM_SEED_BOUND) + "/400/400";
    }

    /**
     * 为规则列生成行值（每行一个 Map：列名 → 值）。
     *
     * @param ruleCols  需本地生成的规则列（已排除 PK/FK）
     * @param rowCount  行数
     * @param rng       确定性随机源（管线注入 Random(seed)）
     */
    public static List<Map<String, Object>> generateRuleValues(List<ColumnDef> ruleCols, int rowCount, Random rng) {
        if (ruleCols == null || ruleCols.isEmpty()) {
            // 必须返回可变空 Map：调用方（injectPkAndFk）随后 put PK/FK 值，
            // Collections.emptyMap() 不可变会抛 UnsupportedOperationException
            List<Map<String, Object>> emptyRows = new ArrayList<>(rowCount);
            for (int i = 0; i < rowCount; i++) emptyRows.add(new LinkedHashMap<>());
            return emptyRows;
        }
        List<Map<String, Object>> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (ColumnDef col : ruleCols) {
                row.put(col.name(), generateRuleValue(col, rng));
            }
            rows.add(row);
        }
        return rows;
    }

    private static Object generateRuleValue(ColumnDef col, Random rng) {
        String type = col.normalizedType();

        // ENUM → 语义加权取值
        if ("ENUM".equals(type)) {
            List<String> values = col.enumValues();
            if (values == null || values.isEmpty()) return "unknown";
            return weightedEnumValue(values, col, rng);
        }

        // BOOLEAN / BOOL
        if ("BOOLEAN".equals(type) || "BOOL".equals(type)) {
            return rng.nextBoolean();
        }

        // TINYINT（状态/开关/枚举位）
        if ("TINYINT".equals(type)) {
            return generateTinyIntValue(col, rng);
        }

        // 整数类型（INT/SMALLINT/MEDIUMINT/BIGINT）
        if (type.startsWith("INT") || type.startsWith("SMALLINT")
                || type.startsWith("MEDIUMINT") || type.startsWith("BIGINT")) {
            return generateIntegerValue(col, rng, type.startsWith("BIGINT"));
        }

        // 精确小数（价格语义）
        if (type.startsWith("DECIMAL") || type.startsWith("NUMERIC")) {
            String name = col.name().toLowerCase();
            double maxVal = 100000.0;
            if (name.contains("price") && (name.contains("original") || name.contains("market")
                    || name.contains("list") || name.contains("cost"))) {
                maxVal = 100000.0;
            } else if (name.contains("price")) {
                maxVal = 10000.0;
            }
            return Math.round(rng.nextDouble() * maxVal * 100.0) / 100.0;
        }
        // 浮点数
        if (type.startsWith("FLOAT") || type.startsWith("DOUBLE")) {
            return Math.round(rng.nextDouble() * 100000.0 * 100.0) / 100.0;
        }

        // 日期/时间（列名 + COMMENT 语义时间范围）
        if (type.startsWith("DATE") || type.startsWith("TIME")
                || type.startsWith("YEAR") || "TIMESTAMP".equals(type)) {
            return generateDateTimeValue(type, col, rng);
        }

        // 格式字符串（VARCHAR/CHAR/TEXT）——仅 pattern 列到达此处
        if (type.equals("VARCHAR") || type.equals("CHAR") || type.equals("TEXT")) {
            return generatePatternStringValue(col, rng);
        }

        // 兜底：未覆盖类型返回 null（LLM 层不覆盖的罕见情况）
        return null;
    }

    // =============================================================
    //  数值列
    // =============================================================

    private static Object generateTinyIntValue(ColumnDef col, Random rng) {
        // 1) COMMENT 提取枚举范围（"0正常 1停用 2注销中" → 0~2），"绝大多数为 X"加权
        if (col.comment() != null && !col.comment().isEmpty()) {
            int maxEnum = extractMaxEnumValue(col.comment());
            if (maxEnum >= 0) {
                int dominant = extractDominantValue(col.comment(), maxEnum);
                if (dominant >= 0) return rng.nextDouble() < 0.7 ? dominant : rng.nextInt(maxEnum + 1);
                return rng.nextInt(maxEnum + 1);
            }
        }
        // 2) 列名语义 → 加权比率分布（deleted/valid/enable/is_/has_）
        int semanticVal = weightedSemanticValue(col.name().toLowerCase(), rng);
        if (semanticVal >= 0) return semanticVal;
        // 3) status/state → 状态权重分布（绝大多数为正常）
        String name = col.name().toLowerCase();
        if (name.contains("status") || name.contains("state")) {
            return weightedStatusValue(rng);
        }
        // 4) TINYINT(1) → 布尔（加权比率）
        if (col.type() != null && col.type().matches("(?i).*\\(1\\)")) {
            return rng.nextDouble() < 0.7 ? 1 : 0;
        }
        // 5) 普通 TINYINT → 小范围整数
        return 1 + rng.nextInt(100);
    }

    private static Object generateIntegerValue(ColumnDef col, Random rng, boolean isBigInt) {
        String comment = col.comment();
        if (comment != null && !comment.isEmpty()) {
            int maxEnum = extractMaxEnumValue(comment);
            if (maxEnum >= 0) {
                int dominant = extractDominantValue(comment, maxEnum);
                if (dominant >= 0) return rng.nextDouble() < 0.7 ? dominant : rng.nextInt(maxEnum + 1);
                return rng.nextInt(maxEnum + 1);
            }
        }
        String name = col.name().toLowerCase();
        int semanticVal = weightedSemanticValue(name, rng);
        if (semanticVal >= 0) return semanticVal;
        if (name.contains("status") || name.contains("state")) {
            return weightedStatusValue(rng);
        }
        if (name.contains("stock") || name.contains("quantity")
                || name.contains("inventory") || name.contains("qty")) {
            return rng.nextInt(1000);
        }
        if (name.contains("sort") || name.contains("seq") || name.contains("order_index")) {
            return rng.nextInt(100);
        }
        if (isBigInt) {
            return (long) (1 + rng.nextInt(Integer.MAX_VALUE));
        }
        return 1 + rng.nextInt(99999);
    }

    /** 从 COMMENT 提取枚举最大值（"0正常 1停用" → 1），范围 0-127 才有效 */
    private static int extractMaxEnumValue(String comment) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(comment);
        int max = -1;
        while (m.find()) {
            int val = Integer.parseInt(m.group());
            if (val > max) max = val;
        }
        return (max >= 0 && max <= 127) ? max : -1;
    }

    /** 从 COMMENT 提取"绝大多数为 X / 全部为 X / 默认 X"的 X（范围内返回，否则 -1） */
    private static int extractDominantValue(String comment, int max) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(绝大多数|大部分|全部|默认)\\s*(?:为|是)?\\s*(\\d+)").matcher(comment);
        if (m.find()) {
            int val = Integer.parseInt(m.group(2));
            return (val >= 0 && val <= max) ? val : -1;
        }
        return -1;
    }

    /** 布尔类字段加权比率（deleted/valid/enable/is_/has_） */
    private static int weightedSemanticValue(String nameLower, Random rng) {
        if (nameLower.contains("deleted")) {
            return rng.nextDouble() < 0.1 ? 1 : 0;      // deleted=1 仅 10%
        }
        if (nameLower.contains("valid") || nameLower.contains("enable")) {
            return rng.nextDouble() < 0.95 ? 1 : 0;     // 正常态 95%
        }
        if (nameLower.contains("is_") || nameLower.contains("has_") || nameLower.contains("flag")) {
            return rng.nextDouble() < 0.7 ? 1 : 0;
        }
        return -1;
    }

    /** status/state 权重分布：0→5% 1→70% 2→20% 3→5%（绝大多数为正常态） */
    private static int weightedStatusValue(Random rng) {
        double[] weights = {0.05, 0.70, 0.20, 0.05};
        double r = rng.nextDouble();
        double cum = 0;
        for (int i = 0; i < weights.length; i++) {
            cum += weights[i];
            if (r < cum) return i;
        }
        return weights.length - 1;
    }

    // =============================================================
    //  枚举列
    // =============================================================

    /** ENUM 语义加权：COMMENT"默认X" → 80%；status 首个枚举 → 70%；其余均匀 */
    static String weightedEnumValue(List<String> values, ColumnDef col, Random rng) {
        String comment = col.comment();
        String name = col.name().toLowerCase();

        if (comment != null && !comment.isEmpty()) {
            int defIdx = indexOfDefaultValue(comment, values);
            if (defIdx >= 0) {
                return rng.nextDouble() < 0.8 ? values.get(defIdx) : values.get(rng.nextInt(values.size()));
            }
        }
        if (name.contains("status") || name.contains("state")) {
            return rng.nextDouble() < 0.7 ? values.get(0) : values.get(rng.nextInt(values.size()));
        }
        return values.get(rng.nextInt(values.size()));
    }

    /** 从 COMMENT 匹配 "默认X" 并返回 X 在枚举中的下标 */
    private static int indexOfDefaultValue(String comment, List<String> values) {
        int idx = comment.indexOf("默认");
        while (idx >= 0 && idx + 2 < comment.length()) {
            String candidate = comment.substring(idx + 2).trim();
            for (int i = 0; i < values.size(); i++) {
                if (candidate.startsWith(values.get(i))) return i;
            }
            idx = comment.indexOf("默认", idx + 2);
        }
        return -1;
    }

    // =============================================================
    //  日期列
    // =============================================================

    /** 结合列名 + COMMENT 双重语义确定时间范围（生日/过期/上架/支付/发货/创建/更新） */
    private static String generateDateTimeValue(String type, ColumnDef col, Random rng) {
        long[] range = computeDateRange(col);
        long randomMs = range[0] + (long) (rng.nextDouble() * (range[1] - range[0]));

        if ("DATE".equals(type)) {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(new Date(randomMs));
        }
        if ("YEAR".equals(type)) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(randomMs);
            return String.valueOf(cal.get(Calendar.YEAR));
        }
        if (type.startsWith("TIME") && !type.startsWith("TIMESTAMP")) {
            return String.format("%02d:%02d:%02d", rng.nextInt(24), rng.nextInt(60), rng.nextInt(60));
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date(randomMs));
    }

    private static long[] computeDateRange(ColumnDef col) {
        long now = System.currentTimeMillis();
        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        String semantic = (col.name() + " " + (col.comment() != null ? col.comment() : "")).toLowerCase();

        if (semantic.contains("birth") || semantic.contains("shengri")
                || semantic.contains("chusheng") || semantic.contains("年龄")) {
            end.add(Calendar.YEAR, -18);
            start.add(Calendar.YEAR, -60);
        } else if (semantic.contains("expire") || semantic.contains("guoqi")
                || semantic.contains("daoqi") || semantic.contains("过期")
                || semantic.contains("到期") || semantic.contains("截止")) {
            end.add(Calendar.YEAR, 1);
        } else if (semantic.contains("publish") || semantic.contains("shangjia")
                || semantic.contains("fabu") || semantic.contains("上架")
                || semantic.contains("发布") || semantic.contains("开始")) {
            start.add(Calendar.MONTH, -3);
        } else if (semantic.contains("pay") || semantic.contains("zhifu")
                || semantic.contains("xiadan") || semantic.contains("支付")
                || semantic.contains("下单")) {
            start.add(Calendar.MONTH, -1);
        } else if (semantic.contains("ship") || semantic.contains("fahuo")
                || semantic.contains("qianshou") || semantic.contains("发货")
                || semantic.contains("签收") || semantic.contains("收货")) {
            start.add(Calendar.MONTH, -1);
        } else if (semantic.contains("create") || semantic.contains("register")
                || semantic.contains("chuangjian") || semantic.contains("zhuce")
                || semantic.contains("创建") || semantic.contains("注册")) {
            start.add(Calendar.MONTH, -3);
        } else if (semantic.contains("update") || semantic.contains("modify")
                || semantic.contains("gengxin") || semantic.contains("xiugai")
                || semantic.contains("更新") || semantic.contains("修改")) {
            start.add(Calendar.MONTH, -1);
        } else {
            // 兜底：2020-01-01 ~ 现在
            start.setTimeInMillis(1577836800000L);
            return new long[]{start.getTimeInMillis(), now};
        }
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    // =============================================================
    //  格式字符串列（Faker）
    // =============================================================

    private static String generatePatternStringValue(ColumnDef col, Random rng) {
        String name = col.name().toLowerCase();

        // 手机号 — 11 位纯数字
        if (name.equals("phone") || name.equals("mobile") || name.equals("tel")
                || name.equals("telephone") || name.equals("cellphone")
                || name.startsWith("phone_") || name.startsWith("mobile_")
                || name.equals("contact_phone") || name.equals("contact_tel")
                || name.contains("_phone") || name.contains("_mobile")) {
            return "1" + (3 + rng.nextInt(7)) + String.format("%09d", rng.nextInt(1_000_000_000));
        }
        // 邮箱
        if (name.equals("email") || name.equals("mail")
                || name.endsWith("_email") || name.endsWith("_mail")) {
            return FAKER.internet().emailAddress();
        }
        // 身份证号
        if (name.contains("id_card") || name.contains("idcard")
                || name.equals("identity_card") || name.equals("id_no")
                || name.contains("identity_number")) {
            return String.format("%06d", 110000 + rng.nextInt(100000))
                    + String.format("%04d%02d%02d", 1980 + rng.nextInt(26),
                    1 + rng.nextInt(12), 1 + rng.nextInt(28))
                    + String.format("%03d", rng.nextInt(1000))
                    + "0123456789X".charAt(rng.nextInt(11));
        }
        // URL / 图片 / 头像 / LOGO
        if (name.equals("url") || name.equals("website") || name.equals("link")
                || name.endsWith("_url") || name.endsWith("_link")) {
            return FAKER.internet().url();
        }
        if (name.equals("images") || name.endsWith("_images")) {
            int count = 3 + rng.nextInt(4);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(", ");
                sb.append('"').append(picsumUrl(rng)).append('"');
            }
            return sb.append(']').toString();
        }
        // 商品规格 JSON（name 的结构化重述）：交给 LLM 生成内嵌 JSON 会因转义不可靠而撑破外层结构，
        //   实测会导致整表失败——这类"结构固定"的列由本地确定性构造，只是值随机。
        if (name.equals("specs") || name.endsWith("_specs")) {
            String[] colors = {"黑色", "白色", "红色", "蓝色", "银色"};
            String[] sizes = {"S", "M", "L", "XL", "均码"};
            return "{\"color\":\"" + colors[rng.nextInt(colors.length)]
                    + "\",\"size\":\"" + sizes[rng.nextInt(sizes.length)] + "\"}";
        }
        // 详情富文本：同理，含引号的 HTML 标签会让 LLM 的 JSON 输出失效
        if (name.equals("detail") || name.endsWith("_detail")) {
            return "<p>正品保障，全国联保，支持 7 天无理由退换。</p>"
                    + "<p>发货时效：" + (1 + rng.nextInt(3)) + " 个工作日内发出，偏远地区顺延。</p>";
        }
        if (name.equals("avatar") || name.equals("photo") || name.equals("image")
                || name.equals("logo") || name.equals("shop_logo")
                || name.endsWith("_image") || name.endsWith("_img")
                || name.endsWith("_pic") || name.endsWith("_logo")) {
            return picsumUrl(rng);
        }
        // IP
        if (name.equals("ip") || name.equals("ip_address")) {
            return FAKER.internet().ipV4Address();
        }
        // QQ / 微信
        if (name.equals("qq") || name.equals("wechat") || name.equals("weixin")) {
            return String.valueOf(10000 + rng.nextInt(899999));
        }
        // 兜底：随机小写字母+数字（不应到达此处，防御性）
        int len = 5 + rng.nextInt(11);
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
