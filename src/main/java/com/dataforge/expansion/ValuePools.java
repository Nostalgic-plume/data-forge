package com.dataforge.expansion;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 中文语义值池（展开器共享）：姓名 / 省市区 / 地址 / 电话 / 时间 / 备注。
 * 确定性（Random 由 seed 派生），P1 内置池，P6 分析 agent 可替换为业务专属池。
 */
public final class ValuePools {

    private ValuePools() {}

    private static final List<String> SURNAMES = List.of(
            "王", "李", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴", "徐", "孙", "马", "朱", "胡", "郭", "何", "林");

    private static final List<String> GIVEN_NAMES = List.of(
            "伟", "芳", "娜", "敏", "静", "磊", "军", "洋", "勇", "艳", "杰", "涛", "明", "超", "秀英", "霞",
            "平", "刚", "桂英", "文", "辉", "强", "涛", "鑫", "晨", "宇", "欣怡", "子涵", "雨欣", "浩然");

    /** 省市区三级（各一例代表性区域） */
    private static final String[][] REGIONS = {
            {"广东省", "广州市", "天河区"}, {"广东省", "深圳市", "南山区"},
            {"广东省", "东莞市", "南城街道"}, {"广东省", "佛山市", "禅城区"},
            {"北京市", "北京市", "朝阳区"}, {"上海市", "上海市", "浦东新区"},
            {"浙江省", "杭州市", "西湖区"}, {"浙江省", "宁波市", "鄞州区"},
            {"江苏省", "南京市", "鼓楼区"}, {"江苏省", "苏州市", "工业园区"},
            {"四川省", "成都市", "武侯区"}, {"湖北省", "武汉市", "洪山区"},
            {"湖南省", "长沙市", "岳麓区"}, {"福建省", "厦门市", "思明区"},
            {"山东省", "青岛市", "市南区"}, {"陕西省", "西安市", "雁塔区"},
    };

    private static final List<String> STREETS = List.of(
            "科技园", "软件路", "中山大道", "人民路", "建设街", "文化路", "创新大道", "湖滨路",
            "学府路", "阳光花园", "金地小区", "万达广场", "中心城", "数码港", "创意园");

    private static final List<String> REMARKS = List.of(
            "尽快发货", "周末不收货", "放快递柜即可", "包装好一点", "", "", "");

    /** 中文姓名（2-3 字） */
    public static String randomChineseName(Random rng) {
        String surname = SURNAMES.get(rng.nextInt(SURNAMES.size()));
        int given = 1 + rng.nextInt(2);
        StringBuilder sb = new StringBuilder(surname);
        for (int i = 0; i < given; i++) {
            sb.append(GIVEN_NAMES.get(rng.nextInt(GIVEN_NAMES.size())));
        }
        return sb.toString();
    }

    /** 省市区 + 详细地址（20-100 字符） */
    public static String randomAddress(Random rng) {
        String[] region = REGIONS[rng.nextInt(REGIONS.length)];
        String street = STREETS.get(rng.nextInt(STREETS.size()));
        int no = 1 + rng.nextInt(200);
        String building = 1 + rng.nextInt(30) + "栋" + (1 + rng.nextInt(30)) + "0" + rng.nextInt(10) + "室";
        return region[0] + region[1] + region[2] + street + no + "号" + building;
    }

    /** 省 / 市 / 区 三级（address 表分列） */
    public static String[] randomRegion(Random rng) {
        return REGIONS[rng.nextInt(REGIONS.length)];
    }

    /** 11 位手机号（确定性） */
    public static String randomPhone(Random rng) {
        return "1" + (3 + rng.nextInt(7)) + String.format("%09d", rng.nextInt(1_000_000_000));
    }

    /** 近 pastDays 天内随机时间 */
    public static String randomDateTime(int pastDays, Random rng) {
        long now = System.currentTimeMillis();
        long span = (long) pastDays * 24 * 3600_000L;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(now - (long) (rng.nextDouble() * span)));
    }

    /**
     * 有序时间：同一父行内按序号 {@code idx} 递增（idx 越大越晚），整组仍落在
     * {@code [now - pastDays, now]} 区间内。
     *
     * <p>用于"首尾相接的链"类列——如 user_status_log 按时间排出的状态链：只用
     * {@link #randomDateTime} 的话每条时间独立随机，链在时间维度上就是散的。</p>
     *
     * <p>实现：把区间按序号切段，段内再取随机点。{@code idx >= pastDays} 时钳在末段
     * （不再保证严格递增，但仍在有效区间内）。</p>
     */
    public static String orderedDateTime(int pastDays, int idx, Random rng) {
        long now = System.currentTimeMillis();
        int span = Math.max(1, pastDays);
        double slot = (double) Math.min(Math.max(idx, 0), span - 1) / span;
        double jitter = rng.nextDouble() / span;          // 段内抖动，不跨段
        long offset = (long) ((slot + jitter) * span * 24 * 3600_000L);
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(now - (long) span * 24 * 3600_000L + offset));
    }

    /** create 之后 1h~30d 的晚时间 */
    public static String laterDateTime(String createTime, Random rng) {
        try {
            SimpleDateFormat sdf = createTime.length() <= 10
                    ? new SimpleDateFormat("yyyy-MM-dd")
                    : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            Date base = sdf.parse(createTime);
            long offset = 3600000L + (long) (rng.nextDouble() * 2592000000L);
            return sdf.format(new Date(base.getTime() + offset));
        } catch (Exception e) {
            return createTime;
        }
    }

    public static String randomRemark(Random rng) {
        return REMARKS.get(rng.nextInt(REMARKS.size()));
    }

    /** 订单物流单号：SF + 11 位数字 */
    public static String randomLogisticsNo(Random rng) {
        return "SF" + String.format("%011d", 10000000000L + rng.nextLong(89999999999L));
    }
}
