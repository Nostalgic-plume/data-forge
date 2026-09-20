package com.dataforge.pipeline;

import com.dataforge.model.ForeignKeyDef;
import com.dataforge.model.TableMeta;
import com.dataforge.model.PipelineContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Phase B：FK 预分配（移植 real-world-data FkAllocPhase）。
 *
 * <p>为子表每个 FK 列均匀轮询分配父表 PK 引用值，再 Fisher-Yates 打散，
 * 避免"前 N 行引用 PK 1，后 N 行引用 PK 2"的区块偏差。</p>
 *
 * <p>父表不在生成集 / 无 PK 时跳过并警告（失败表隔离的连带语义：
 * 父表生成失败不影响其余表，FK 悬挂由校验阶段报告）。</p>
 *
 * <p>幂律长尾权重（少数用户多订单）属 P1 增强，接口预留。</p>
 */
public final class FkAlloc {

    private static final Logger log = LoggerFactory.getLogger(FkAlloc.class);

    private FkAlloc() {}

    public static void execute(PipelineContext context) {
        Map<String, Map<String, List<Object>>> pkSkeletons = context.pkSkeletons();

        for (TableMeta table : context.tables()) {
            String tableName = table.name();
            int childCount = context.rowCount(tableName);

            for (ForeignKeyDef fk : table.foreignKeys()) {
                // 父表 PK 值列表 = 现有池（P5 增量，目标库现有行）∪ 新增骨架
                List<Object> parentPkValues = new ArrayList<>();
                List<Object> existing = context.existingPools().get(fk.refTable());
                if (existing != null) parentPkValues.addAll(existing);
                Map<String, List<Object>> parentPkMap = pkSkeletons.get(fk.refTable());
                if (parentPkMap != null && parentPkMap.containsKey(fk.refColumn())) {
                    parentPkValues.addAll(parentPkMap.get(fk.refColumn()));
                }
                if (parentPkValues.isEmpty()) {
                    log.warn("FK 分配跳过: `{}`.`{}` 引用 `{}`.`{}` — 父表 PK 未找到（父表未生成或失败）",
                            tableName, fk.column(), fk.refTable(), fk.refColumn());
                    continue;
                }
                List<Object> assigned = allocate(childCount, parentPkValues, context.newRandom());

                context.fkAllocations()
                        .computeIfAbsent(tableName, k -> new LinkedHashMap<>())
                        .put(fk.column(), assigned);

                log.info("FK 分配: `{}`.`{}` → {} 行引用 `{}` 的 {} 个 PK 值（含现有 {} 个）",
                        tableName, fk.column(), childCount, fk.refTable(),
                        parentPkValues.size(), existing == null ? 0 : existing.size());
            }
        }
    }

    /** 均匀轮询分配 + Fisher-Yates 打散，长度 = childCount，每值指向一个父 PK。 */
    static List<Object> allocate(int childCount, List<Object> parentPkValues, Random rng) {
        if (parentPkValues == null || parentPkValues.isEmpty() || childCount <= 0) return List.of();

        List<Object> result = new ArrayList<>(childCount);
        int parentSize = parentPkValues.size();
        for (int i = 0; i < childCount; i++) {
            result.add(parentPkValues.get(i % parentSize));
        }
        // Fisher-Yates 打散（用确定性 rng，可复现）
        for (int i = childCount - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            Object tmp = result.get(i);
            result.set(i, result.get(j));
            result.set(j, tmp);
        }
        return result;
    }
}
