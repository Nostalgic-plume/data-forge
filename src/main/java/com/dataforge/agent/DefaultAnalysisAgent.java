package com.dataforge.agent;

import com.dataforge.analysis.ColumnToolDecider;
import com.dataforge.analysis.RuleFilter;
import com.dataforge.analysis.RuleSetters;
import com.dataforge.cache.GenerationCache;
import com.dataforge.db.SchemaReader;
import com.dataforge.db.SourceScanner;
import com.dataforge.expansion.PlanBuilder;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.GenerationPlan;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 分析 agent 默认实现（P6 核心）：读库结构 → 子任务并行（规则提炼 LLM + 行数推荐确定性）
 * → project_rules 母版 + 置信度标注 + 缓存指纹。
 *
 * <p>信任模型红线：LLM 只提炼规则（一次性 + 人工确认），规则执行由确定性代码编译。
 * 提炼的规则中引擎可执行的（AGG_EQUAL）进 correctionRules；其余（ENUM_WEIGHT/
 * STATUS_FLOW/VALUE_RANGE）进 generationRules + 人工检查点确认。</p>
 */
@Service
public class DefaultAnalysisAgent implements AnalysisAgent {

    private static final Logger log = LoggerFactory.getLogger(DefaultAnalysisAgent.class);

    private final SchemaReader schemaReader;
    private final DataLlm llm;

    public DefaultAnalysisAgent(SchemaReader schemaReader, DataLlm llm) {
        this.schemaReader = schemaReader;
        this.llm = llm;
    }

    @Override
    public AnalysisResult analyze(AnalysisRequest req) {
        // 1. 确定性读库结构（JDBC information_schema；替代 MCP fallback）
        List<TableMeta> tables = schemaReader.readTables(req.schemaName());
        if (tables.isEmpty()) {
            throw new IllegalStateException("库 `" + req.schemaName() + "` 未读到任何表");
        }
        // 2. 确定性源码语义（决策 A，修 D2）：扫描 projectPath 的 Entity/枚举 → 列语义字典
        SourceScanner.SourceSemantics semantics = SourceScanner.scan(req.projectPath());
        return analyzeCore(tables, req.schemaName(), semantics);
    }

    /** 从已解析表结构分析（新域验证：不连库，直接分析 DDL 解析出的表结构；无源码语义） */
    public AnalysisResult analyzeTables(List<TableMeta> tables) {
        return analyzeCore(tables, "DDL", new SourceScanner.SourceSemantics());
    }

    private AnalysisResult analyzeCore(List<TableMeta> tables, String schemaName,
                                       SourceScanner.SourceSemantics sourceSemantics) {
        String schemaSummary = buildSchemaSummary(tables);

        // 2. 子任务并行（虚拟线程）：LLM 规则提炼 + 确定性行数推荐
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<List<ProjectRules.Rule>> rulesFuture =
                    pool.submit(() -> llm.extractRules(schemaName, schemaSummary));
            Future<Map<String, Integer>> rowsFuture = pool.submit(() -> recommendRowCounts(tables));
            List<ProjectRules.Rule> rules = rulesFuture.get();
            Map<String, Integer> rowRecommend = rowsFuture.get();

            // 3. 生成计划 + 组装 project_rules 母版（builders 覆盖所有派生表）；
            //    源码语义增强列模式（enum 枚举/数值枚举权重，决策 A）
            GenerationPlan plan = PlanBuilder.build(tables, sourceSemantics);
            //    垃圾规则过滤（代码层根治）：宽范围/数值枚举列的 VALUE_RANGE 剔除，不落盘、不注入 prompt
            List<ProjectRules.Rule> filtered = RuleFilter.filterGeneration(rules, tables);
            //    RuleSetters：LLM 规则参数 → plan 列模式（决策 B 方式 1，修 D3：
            //    ENUM_WEIGHT → weighted / VALUE_RANGE → valueRange）
            plan = RuleSetters.apply(plan, filtered);
            //    工具感知列分类（核心改造）：LLM 感知工具清单，为候选语义列决策"工具/llm"；
            //    铁律层（PK/FK/ENUM/日期）确定性绑定不交 LLM，结构校验 + 兜底由 ColumnToolDecider 负责
            plan = ColumnToolDecider.apply(plan, tables, llm);
            ProjectRules projectRules = buildProjectRules(filtered, plan);
            // 4. 缓存指纹：schema 结构（客观失效）+ rules 内容（用户偏好）
            String schemaFp = GenerationCache.schemaFingerprint(tables);
            String rulesHash = sha256(rules.toString()).substring(0, 12);
            log.info("分析完成：`{}` {} 张表，提炼 {} 条规则（correction {} / builders {}）",
                    schemaName, tables.size(), rules.size(),
                    projectRules.correctionRules().size(), projectRules.builders().size());
            return new AnalysisResult(projectRules, rowRecommend,
                    projectRules.builders(), plan, new CacheFingerprint(schemaFp, rulesHash));
        } catch (Exception e) {
            throw new IllegalStateException("分析 agent 失败: " + e.getMessage(), e);
        } finally {
            pool.shutdown();
        }
    }

    // ---- project_rules 母版组装 ----

    private ProjectRules buildProjectRules(List<ProjectRules.Rule> rules, GenerationPlan plan) {
        // 后置修正规则（校验/修正类）：金额对账 + 状态联动 + 自定义 SQL（LLM 产 check/fix SQL）
        List<ProjectRules.Rule> correction = rules.stream()
                .filter(r -> "AGG_EQUAL".equals(r.type()) || "STATUS_FLOW".equals(r.type())
                        || "CUSTOM_SQL".equals(r.type()))
                .toList();
        // 前置约束（生成约束）：权重/范围等
        List<ProjectRules.Rule> generation = rules.stream()
                .filter(r -> !"AGG_EQUAL".equals(r.type()) && !"STATUS_FLOW".equals(r.type())
                        && !"CUSTOM_SQL".equals(r.type()))
                .toList();
        // 行构建器覆盖所有派生表（从 plan：derived 表 → BuilderDef，锚点 = 父表）
        List<ProjectRules.BuilderDef> builders = plan.tables().stream()
                .filter(GenerationPlan.TablePlan::isDerived)
                .map(tp -> new ProjectRules.BuilderDef(tp.table(), "default", tp.parent()))
                .toList();
        return new ProjectRules(generation, correction, builders, 1);
    }

    /** 确定性行数推荐：按 FK 关联放大（入度 0 表 10 行，子表 ≈ 父表 × 1.5） */
    static Map<String, Integer> recommendRowCounts(List<TableMeta> tables) {
        Map<String, Integer> rec = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            int base = 10;
            for (var fk : t.foreignKeys()) {
                Integer parent = rec.get(fk.refTable());
                if (parent != null) {
                    base = Math.max(base, (int) Math.ceil(parent * 1.5));
                }
            }
            rec.put(t.name(), base);
        }
        return rec;
    }

    /** 表结构紧凑文本（喂 LLM 提炼规则 / 推荐行数的输入） */
    public static String buildSchemaSummary(List<TableMeta> tables) {
        StringBuilder sb = new StringBuilder();
        for (TableMeta t : tables) {
            sb.append("表 ").append(t.name()).append(":\n");
            for (ColumnDef c : t.columns()) {
                sb.append("  ").append(c.name()).append("(").append(c.type()).append(")");
                if (c.primaryKey()) sb.append(" [PK]");
                if (c.autoIncrement()) sb.append(" [自增]");
                if (!c.enumValues().isEmpty()) sb.append(" 枚举=").append(c.enumValues());
                if (c.comment() != null && !c.comment().isEmpty()) sb.append(" — ").append(c.comment());
                sb.append("\n");
            }
            for (var fk : t.foreignKeys()) {
                sb.append("  FK ").append(fk.column()).append(" → ")
                        .append(fk.refTable()).append(".").append(fk.refColumn()).append("\n");
            }
        }
        return sb.toString();
    }

    private static String sha256(String raw) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
