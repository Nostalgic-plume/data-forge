package com.dataforge.agent;

import com.dataforge.model.ProjectRules;

import java.util.List;
import java.util.Map;

/**
 * 分析 agent（API_CONTRACT §1.1，P6 核心）：项目 → project_rules 母版 + 行数推荐 + 行构建器。
 *
 * <p>职责边界（PLAN 决策 #2"LLM 发现、代码执行"）：分析 agent 用 LLM 提炼规则
 * （一次性 + 人工确认兜底），规则执行由确定性代码编译（校验 SQL / 修正 SQL）。</p>
 *
 * <p>子任务并行（虚拟线程）：表结构（确定性读库）/ 枚举状态 / 关联关系 / 列语义 / 业务规则
 * → 合并 + 置信度标注 → project_rules 母版（前置约束节 + 后置修正规则节）。</p>
 */
public interface AnalysisAgent {

    /** 项目路径 + 库名 → project_rules + 行数推荐 + 行构建器 */
    AnalysisResult analyze(AnalysisRequest req);

    /** 分析请求（skill 可选增强；未勾选走通用清单） */
    record AnalysisRequest(
            String projectPath,          // 源码目录（可 null，只读库结构时）
            String schemaName,           // 目标库名（information_schema 读表结构）
            List<String> skills          // 可选：垂直 skill（当前占位）
    ) {
    }

    /** 分析结果 */
    record AnalysisResult(
            ProjectRules rules,              // project_rules 母版（generation + correction + builders）
            Map<String, Integer> rowRecommend,  // 推荐行数（按关联推导，LLM 参考值）
            List<ProjectRules.BuilderDef> builders,  // 行构建器（P1 展开器执行）
            com.dataforge.model.GenerationPlan plan,  // 生成计划（通用化：分析 agent 启发式产出）
            CacheFingerprint fingerprint     // 分析时指纹快照（缓存判定用）
    ) {
    }

    /** 分析缓存指纹：schema 结构变化 = 客观失效；rules 修改 = 用户偏好信号 */
    record CacheFingerprint(
            String schemaFingerprint,        // information_schema 结构 hash
            String rulesFileHash             // project_rules 内容 hash
    ) {
    }
}
