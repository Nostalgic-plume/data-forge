package com.dataforge.agent;

import com.dataforge.model.FixCommand;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;

import java.util.List;
import java.util.Map;

/**
 * 修正 agent（API_CONTRACT §1.3，P3 核心）：数据 + 校验报告 → 修正后数据。
 *
 * <p>内部手写 ReAct 收敛循环：报告压缩（已有分桶）→ 桶决策（规则通道优先 + LLM 兜底）
 * → id 锚定最小干预应用 → 再校验。收敛控制：问题清单闭环（新错误=回归=回滚）
 * + 通过率上升判定 + maxIterations。</p>
 *
 * <p>修正 SQL 化硬前提：修正全程在 staging 库（数据必须落库，非内存）——连库定位，
 * staging 是修正的 source of truth，最终数据从库读回。</p>
 */
public interface CorrectionAgent {

    /** 数据 + 校验报告 → 修正后数据 */
    CorrectionResult correct(CorrectionRequest req);

    /** 修正请求 */
    record CorrectionRequest(
            List<TableMeta> tables,           // 表结构（修正 SQL / 再校验）
            Map<String, GeneratedData> data,  // 待修正数据（已落 staging 库）
            ValidationReport initialReport,   // 首轮校验报告（循环起点）
            ProjectRules rules,               // 后置修正规则节
            String schemaName,                // 目标 schema（staging = schemaName_staging）
            Long scopeMinId                   // 增量作用域下界（全量 null → 不限）
    ) {
    }

    /** 修正结果 */
    record CorrectionResult(
            Map<String, GeneratedData> data,  // 修正后数据（从 staging 读回，含 CORRECTED 血缘）
            ValidationReport finalReport,     // 收敛后报告
            boolean converged,                // 是否在 maxIterations 内收敛（硬错误 = 0）
            boolean rolledBack,               // 是否发生回归回滚（新错误 → 重建工作区）
            int iterations,                   // 实际循环轮数
            List<FixCommand> appliedFixes     // 全部修正记录（审计/面试）
    ) {
    }
}
