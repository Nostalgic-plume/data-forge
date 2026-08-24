package com.dataforge.agent;

import com.dataforge.model.GeneratedData;
import com.dataforge.model.IncrementalConfig;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;

import java.util.List;
import java.util.Map;

/**
 * 生成 agent（API_CONTRACT §1.2，P0 核心对外入口）。
 *
 * <p>输入：表结构 + 规则 + 行数 + 增量配置 + 确定性参数 → 输出：各表数据 + 失败表清单。</p>
 * <p>全量是增量的特例（mode=full，P5 实现增量语义）。</p>
 *
 * <pre>
 * 一次运行：缓存/断点恢复 → PK 骨架 → FK 分配 → DAG 并发生成（含 5 优化）→ 跨表对账
 * </pre>
 */
public interface GenerationAgent {

    GenerationResult generate(GenerationRequest req);

    /** 请求 */
    record GenerationRequest(
            List<TableMeta> tables,          // DDL 解析出的生成集（引用闭包后的表）
            ProjectRules rules,              // 分析产物（P0 传空规则）
            Map<String, Integer> rowCounts,  // 每表目标行数
            IncrementalConfig incremental,   // 全量模式传 mode=full
            long seed,                       // 确定性种子
            int batchSize,                   // 初始批次（batch 自适应动态调整）
            int maxConcurrency               // 表内批次并行度
    ) {
    }

    /** 结果 */
    record GenerationResult(
            Map<String, GeneratedData> data,  // 表名 → 数据（修正前）
            List<String> failedTables,        // 失败表（失败隔离，不 abort 全局）
            long elapsedMs,
            Map<String, Integer> actualCounts, // 每表实际行数
            Map<String, TableStat> tableStats  // 每表生成统计（CLI 汇总/调试）
    ) {
    }

    /** 单表生成统计（调试/汇总） */
    record TableStat(
            int target,          // 目标行数
            int actual,          // 实际行数
            long elapsedMs,      // 表级耗时
            int llmBatches,      // LLM 调用批次数
            int downshifts,      // batch 自适应降批次数
            int gapFills,        // 补差次数
            boolean cacheHit     // 缓存/断点命中
    ) {
    }
}
