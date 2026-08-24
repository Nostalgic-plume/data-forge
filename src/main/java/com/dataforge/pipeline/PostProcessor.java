package com.dataforge.pipeline;

import com.dataforge.model.ColumnDef;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 行级后处理（移植 real-world-data PostProcessor，只管"行内合理"）。
 *
 * <ul>
 *   <li>时间顺序：create_time &lt; update_time</li>
 *   <li>价格顺序：original_price ≥ price</li>
 *   <li>价格比率：original_price / price ≤ 5</li>
 *   <li>状态-时间联动：非支付/完成状态清空支付时间</li>
 * </ul>
 *
 * <p>确定性代码执行（非 LLM），跨表一致交给修正 agent（P3）。</p>
 */
public final class PostProcessor {

    private PostProcessor() {}

    /** 对行值列表做行内后处理（入参 rows 会被原地修改）。 */
    public static void process(List<Map<String, Object>> rows, List<ColumnDef> columns) {
        if (rows == null || rows.isEmpty() || columns == null || columns.isEmpty()) return;

        String ctKey = null, utKey = null;
        String opKey = null, pKey = null;
        String statusKey = null, payTimeKey = null;

        for (ColumnDef col : columns) {
            String category = resolveCategory(col.name());
            if (category == null) continue;
            switch (category) {
                case "create_time" -> ctKey = col.name();
                case "update_time" -> utKey = col.name();
                case "original_price" -> opKey = col.name();
                case "price" -> pKey = col.name();
                case "status" -> statusKey = col.name();
                case "pay_time" -> payTimeKey = col.name();
            }
        }

        boolean hasTimePair = ctKey != null && utKey != null;
        boolean hasPricePair = opKey != null && pKey != null;
        boolean hasStatusTime = statusKey != null && payTimeKey != null;

        for (Map<String, Object> row : rows) {
            if (hasTimePair) fixTimeOrder(row, ctKey, utKey);
            if (hasPricePair) {
                fixPriceOrder(row, opKey, pKey);
                fixPriceRatio(row, opKey, pKey);
            }
            if (hasStatusTime) fixStatusTime(row, statusKey, payTimeKey);
        }
    }

    static String resolveCategory(String columnName) {
        String n = columnName.toLowerCase();
        if (isCreateTimeName(n)) return "create_time";
        if (isUpdateTimeName(n)) return "update_time";
        if (isOriginalPriceName(n)) return "original_price";
        if (isPriceName(n)) return "price";
        if (isStatusName(n)) return "status";
        if (isPayTimeName(n)) return "pay_time";
        return null;
    }

    // =============================================================
    //  时间顺序修正
    // =============================================================

    private static void fixTimeOrder(Map<String, Object> row, String ctKey, String utKey) {
        Object ct = row.get(ctKey);
        Object ut = row.get(utKey);
        if (ct instanceof String ctStr && ut instanceof String utStr) {
            if (utStr.compareTo(ctStr) < 0) {
                row.put(utKey, advanceDateTime(ctStr));
            }
        }
    }

    private static String advanceDateTime(String dateTimeStr) {
        try {
            SimpleDateFormat sdf = dateTimeStr.length() <= 10
                    ? new SimpleDateFormat("yyyy-MM-dd")
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date dt = sdf.parse(dateTimeStr);
            long offset = 3600000L + (long) (new Random().nextDouble() * 2592000000L); // 1h ~ 30d
            return sdf.format(new Date(dt.getTime() + offset));
        } catch (Exception e) {
            return dateTimeStr;
        }
    }

    // =============================================================
    //  价格顺序/比率修正
    // =============================================================

    private static void fixPriceOrder(Map<String, Object> row, String opKey, String pKey) {
        Object op = row.get(opKey);
        Object p = row.get(pKey);
        if (op instanceof Number opNum && p instanceof Number pNum) {
            if (opNum.doubleValue() < pNum.doubleValue()) {
                row.put(opKey, p);
                row.put(pKey, op);
            }
        }
    }

    /** 价格比率：original_price / price ≤ 5，超出则按 3~5 倍重设原价 */
    private static void fixPriceRatio(Map<String, Object> row, String opKey, String pKey) {
        Object op = row.get(opKey);
        Object p = row.get(pKey);
        if (op instanceof Number opNum && p instanceof Number pNum) {
            double opVal = opNum.doubleValue();
            double pVal = pNum.doubleValue();
            if (opVal > 0 && pVal > 0 && opVal / pVal > 5.0) {
                double ratio = 3.0 + new Random().nextDouble() * 2.0;
                row.put(opKey, Math.round(pVal * ratio * 100.0) / 100.0);
            }
        }
    }

    // =============================================================
    //  状态-时间联动
    // =============================================================

    private static void fixStatusTime(Map<String, Object> row, String statusKey, String payTimeKey) {
        Object status = row.get(statusKey);
        Object payTime = row.get(payTimeKey);
        boolean hasPayTime = payTime instanceof String s && !s.isBlank();
        String statusStr = status != null ? status.toString().toLowerCase() : "";
        if (hasPayTime && !statusStr.contains("paid") && !statusStr.contains("pay")
                && !statusStr.contains("已付") && !statusStr.contains("已完成")
                && !statusStr.matches(".*[1234].*")) {
            row.put(payTimeKey, null);
        }
    }

    // =============================================================
    //  列名匹配器
    // =============================================================

    static boolean isCreateTimeName(String name) {
        return name.equals("create_time") || name.equals("created_at")
                || name.equals("created_time") || name.equals("create_date")
                || name.equals("gmt_create");
    }

    static boolean isUpdateTimeName(String name) {
        return name.equals("update_time") || name.equals("updated_at")
                || name.equals("updated_time") || name.equals("update_date")
                || name.equals("gmt_modified") || name.equals("gmt_update");
    }

    static boolean isOriginalPriceName(String name) {
        return name.equals("original_price") || name.equals("market_price")
                || name.equals("list_price") || name.equals("cost_price")
                || name.contains("originalprice");
    }

    static boolean isPriceName(String name) {
        return name.equals("price") || name.equals("sale_price")
                || name.equals("selling_price") || name.equals("retail_price")
                || name.equals("final_price");
    }

    static boolean isStatusName(String name) {
        return name.equals("status") || name.equals("order_status")
                || name.equals("pay_status") || name.equals("state");
    }

    static boolean isPayTimeName(String name) {
        return name.equals("pay_time") || name.equals("paid_time")
                || name.equals("payment_time") || name.equals("pay_at");
    }
}
