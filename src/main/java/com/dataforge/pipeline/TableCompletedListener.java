package com.dataforge.pipeline;

import com.dataforge.model.GeneratedData;

/**
 * 表完成事件回调（P4 流水线修正）：每张表生成成功即触发，供修正流水线
 * "边生成边落库边修正"（小表修正不阻塞大表生成）。
 */
@FunctionalInterface
public interface TableCompletedListener {

    /** 表生成完成（成功），data 为该表最终产物 */
    void onTableCompleted(String tableName, GeneratedData data);
}
