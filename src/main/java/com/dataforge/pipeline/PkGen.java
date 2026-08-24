package com.dataforge.pipeline;

import com.dataforge.model.ColumnDef;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase A：全表并发生成 PK 骨架（移植 real-world-data PkGenPhase，瘦身）。
 *
 * <p>策略（API_CONTRACT §1.2 PkStrategy）：
 * <ul>
 *   <li>SEQUENTIAL：顺序数字 1..N（自增语义，P0 默认——便于验证，P6 分析 agent 做代码级判定）</li>
 *   <li>SNOWFLAKE：雪花算法（非自增 bigint 默认），含查重兜底</li>
 * </ul>
 * 字符串 PK → UUID 16 位。无 PK 的表返回空映射。</p>
 */
public final class PkGen {

    private static final Logger log = LoggerFactory.getLogger(PkGen.class);

    public enum Strategy { SEQUENTIAL, SNOWFLAKE }

    private PkGen() {}

    /** 为所有表生成 PK 骨架（确定性：SEQUENTIAL 严格可复现）。固定表（role/admin）跳过——行自带固定 id。 */
    public static void execute(PipelineContext context, Strategy strategy) {
        for (TableMeta table : context.tables()) {
            String name = table.name();
            if (com.dataforge.expansion.FixedSamples.isFixed(name)) {
                log.info("PK 骨架: `{}` 跳过（固定样本表，行自带固定 id）", name);
                continue;
            }
            int count = context.rowCount(name);
            // P5 增量：SEQUENTIAL 起始 ID = 现有 MAX(id)+1（全量默认 1）
            long startId = context.startId(name);
            // A1：逐表 PK 策略 hint（SourceScanner @TableId 判定）优先，fallback 全局
            Strategy effective = strategy;
            String hint = context.pkStrategyHints().get(name);
            if ("SNOWFLAKE".equals(hint)) effective = Strategy.SNOWFLAKE;
            else if ("SEQUENTIAL".equals(hint)) effective = Strategy.SEQUENTIAL;
            Map<String, List<Object>> pkMap = generatePkSkeleton(
                    table, count, effective, context.newRandom(), startId);
            synchronized (context.pkSkeletons()) {
                context.pkSkeletons().put(name, pkMap);
            }
            log.info("PK 骨架: `{}` → {} 行, 策略={}, 起始ID={}, PK列={}",
                    name, count, strategy, startId, pkMap.keySet());
        }
    }

    /** 为一张表生成 PK 骨架（仅 PK 列）。无 PK 的表返回空映射。 */
    static Map<String, List<Object>> generatePkSkeleton(TableMeta table, int rowCount,
                                                        Strategy strategy, Random rng) {
        return generatePkSkeleton(table, rowCount, strategy, rng, 1L);
    }

    /** P5 增量版：SEQUENTIAL 从 startId 开始（增量 = 现有 MAX(id)+1，全量默认 1） */
    static Map<String, List<Object>> generatePkSkeleton(TableMeta table, int rowCount,
                                                        Strategy strategy, Random rng, long startId) {
        List<ColumnDef> pkCols = table.columns().stream()
                .filter(ColumnDef::primaryKey)
                .toList();
        if (pkCols.isEmpty()) return Map.of();

        Map<String, List<Object>> result = new LinkedHashMap<>();
        for (ColumnDef pk : pkCols) {
            String pkType = pk.normalizedType();
            boolean isNumericPk = pkType.startsWith("INT") || pkType.startsWith("BIGINT")
                    || pkType.startsWith("SMALLINT") || pkType.startsWith("TINYINT")
                    || pkType.startsWith("DECIMAL") || pkType.startsWith("NUMERIC")
                    || pkType.startsWith("FLOAT") || pkType.startsWith("DOUBLE");
            if (isNumericPk) {
                List<Object> values = new ArrayList<>(rowCount);
                if (strategy == Strategy.SNOWFLAKE) {
                    Snowflake generator = new Snowflake(rng.nextLong(1024));
                    Set<Long> seen = new HashSet<>();
                    for (int i = 0; i < rowCount; i++) {
                        long id;
                        do {
                            id = generator.nextId();
                        } while (!seen.add(id));   // 查重兜底
                        values.add(id);
                    }
                } else {
                    for (long i = startId; i < startId + rowCount; i++) values.add(i);
                }
                result.put(pk.name(), values);
            } else {
                // 字符串 PK → UUID 16 位
                List<Object> values = new ArrayList<>(rowCount);
                for (int i = 0; i < rowCount; i++) {
                    values.add(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                }
                result.put(pk.name(), values);
            }
        }
        return result;
    }

    /** 简化雪花算法（时间戳 + workerId + 自增序列），同库跨表不冲突 */
    static final class Snowflake {
        private static final long EPOCH = 1704067200000L; // 2024-01-01
        private final long workerId;
        private long sequence = 0;
        private long lastTimestamp = -1L;

        Snowflake(long workerId) {
            this.workerId = workerId & 0x3FF; // 10 bit
        }

        synchronized long nextId() {
            long timestamp = System.currentTimeMillis();
            if (timestamp < lastTimestamp) {
                timestamp = lastTimestamp; // 时钟回拨：退化为上一毫秒 + 序列
            }
            if (timestamp == lastTimestamp) {
                sequence = (sequence + 1) & 0xFFF; // 12 bit 序列
                if (sequence == 0) {
                    timestamp = nextMillis();
                }
            } else {
                sequence = 0;
            }
            lastTimestamp = timestamp;
            return ((timestamp - EPOCH) << 22) | (workerId << 12) | sequence;
        }

        private long nextMillis() {
            long t = System.currentTimeMillis();
            while (t <= lastTimestamp) {
                t = System.currentTimeMillis();
            }
            return t;
        }
    }
}
