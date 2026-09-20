package com.dataforge.orchestration;

import com.dataforge.agent.AnalysisAgent;
import com.dataforge.agent.CorrectionAgent;
import com.dataforge.agent.DefaultAnalysisAgent;
import com.dataforge.agent.DefaultGenerationAgent;
import com.dataforge.agent.GenerationAgent;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.correction.CorrectionEngine;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.IncrementalConfig;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.model.ValidationReport;
import com.dataforge.report.RunReport;
import com.dataforge.service.DbSession;
import com.dataforge.service.GenerationSupport;
import com.dataforge.service.IncrementalSupport;
import com.dataforge.service.RulesService;
import org.bsc.langgraph4j.RunnableConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排节点集：把原先内联在 DataForgeApiController 里的各阶段逻辑搬成图节点。
 *
 * <p><b>搬迁原则</b>：只搬"编排"（谁在什么时候调用谁），<b>不动"算法"</b>——
 * 节点内部一律调用现有组件（Agent / Validator / CorrectionEngine），不复制业务逻辑。</p>
 *
 * <p><b>数据不经过状态</b>：生成结果（十万行级）直接落 staging 库，状态里只留摘要；
 * 大数据只作为局部变量在节点内流转。</p>
 */
@Component
public class ForgeNodes {

    public static final String ANALYZE = "analyze";
    public static final String GENERATE = "generateStage";
    public static final String VALIDATE = "validate";
    public static final String CORRECT = "correct";
    public static final String REPORT = "report";

    public static final String EDGE_RETRY = "retry";
    public static final String EDGE_DONE = "done";

    private static final Logger log = LoggerFactory.getLogger(ForgeNodes.class);

    private final DefaultAnalysisAgent analysisAgent;
    private final DefaultGenerationAgent generationAgent;
    private final DataLlm llm;
    private final GeneratorProperties props;
    private final RulesService rulesService;

    public ForgeNodes(DefaultAnalysisAgent analysisAgent, DefaultGenerationAgent generationAgent,
                      DataLlm llm, GeneratorProperties props, RulesService rulesService) {
        this.analysisAgent = analysisAgent;
        this.generationAgent = generationAgent;
        this.llm = llm;
        this.props = props;
        this.rulesService = rulesService;
    }

    // ---------- ① 分析 ----------

    /** 读表结构 → 算引用闭包 → 分析规则与生成计划 */
    public Map<String, Object> analyze(ForgeState s, RunnableConfig cfg) {
        DbSession db = session(cfg);
        List<TableMeta> allTables = db.schemaReader.readTables(s.schema());
        GenerationSupport.Closure closure = GenerationSupport.referenceClosure(
                allTables, s.rows(), props.minParentRows());
        List<TableMeta> genTables = allTables.stream()
                .filter(t -> closure.tables().contains(t.name())).toList();

        AnalysisAgent.AnalysisResult analyzed = analysisAgent.analyzeTables(genTables);
        generationAgent.setPlan(analyzed.plan());
        reportOf(cfg).analysis(analyzed);

        int ruleCount = analyzed.rules() == null ? 0
                : analyzed.rules().generationRules().size() + analyzed.rules().correctionRules().size();
        log.info("[编排] 分析完成：{} 张表 / {} 条规则", genTables.size(), ruleCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.TABLES, genTables);
        // 增量模式：只按用户显式指定的行数生成，父表 rowCount=0 由 generateIncremental 内部按"引用现有"处理
        out.put(ForgeState.ROW_COUNTS, s.incremental() ? new LinkedHashMap<>(s.rows()) : closure.rowCounts());
        out.put(ForgeState.RULE_COUNT, ruleCount);
        out.put(ForgeState.LOG, "分析：" + genTables.size() + " 张表 / " + ruleCount + " 条规则");
        return out;
    }

    // ---------- ② 生成 + 落库（合成一个节点：数据必须落库才算一个可恢复的完成点）----------

    public Map<String, Object> generateAndStage(ForgeState s, RunnableConfig cfg) {
        DbSession db = session(cfg);
        List<TableMeta> genTables = s.tables();
        ProjectRules rules = rulesService.currentRules();
        GenerationAgent.GenerationRequest req = new GenerationAgent.GenerationRequest(
                genTables, rules, s.rowCounts(), IncrementalConfig.full(),
                props.seed(), props.batchSize(), props.maxConcurrency());

        GenerationAgent.GenerationResult result;
        Long scopeMinId = null;
        if (s.incremental()) {
            Map<String, GeneratedData> baseline = db.staging.read(s.schema(), genTables);
            result = generationAgent.generateIncremental(req, baseline, s.cacheEnabled());
            scopeMinId = IncrementalSupport.scopeMinIdOf(baseline);
        } else {
            result = generationAgent.generate(req, false);
        }

        db.staging.drop(s.schema());
        db.staging.create(s.schema(), genTables);
        db.staging.load(s.schema(), result.data());

        reportOf(cfg).generation(result);

        int totalRows = result.actualCounts().values().stream().mapToInt(Integer::intValue).sum();
        log.info("[编排] 生成并落库：{} 行，失败表 {} 个", totalRows, result.failedTables().size());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.ACTUAL_COUNTS, result.actualCounts());
        out.put(ForgeState.FAILED_TABLES, result.failedTables());
        if (scopeMinId != null) {
            out.put(ForgeState.SCOPE_MIN_ID, scopeMinId);
        }
        out.put(ForgeState.LOG, "生成并落库：" + totalRows + " 行");
        return out;
    }

    // ---------- ③ 校验 ----------

    public Map<String, Object> validate(ForgeState s, RunnableConfig cfg) {
        DbSession db = session(cfg);
        ValidationReport report = db.validator.validate(
                s.tables(), rulesService.currentRules(), s.schema());
        reportOf(cfg).validation(report);

        log.info("[编排] 校验：第 {} 轮，错误 {} 个", s.iteration(), report.totalErrors());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.ERROR_COUNT, report.totalErrors());
        if (s.iteration() == 0) {
            out.put(ForgeState.INITIAL_ERRORS, report.totalErrors());
        }
        out.put(ForgeState.LOG, "校验第 " + s.iteration() + " 轮：" + report.totalErrors() + " 个错误");
        return out;
    }

    // ---------- ④ 修正（一轮；内部仍复用现有 CorrectionEngine）----------

    public Map<String, Object> correct(ForgeState s, RunnableConfig cfg) {
        DbSession db = session(cfg);
        List<TableMeta> genTables = s.tables();
        ProjectRules rules = rulesService.currentRules();
        Map<String, GeneratedData> data = db.staging.read(s.schema(), genTables);
        ValidationReport initial = db.validator.validate(genTables, rules, s.schema());

        CorrectionEngine engine = new CorrectionEngine(db.jdbc, db.staging, db.validator, llm);
        CorrectionAgent.CorrectionResult fix = engine.run(
                new CorrectionAgent.CorrectionRequest(
                        genTables, data, initial, rules, s.schema(), s.scopeMinId()),
                props.maxIterations());
        reportOf(cfg).correction(fix);

        int round = s.iteration() + 1;
        log.info("[编排] 修正第 {} 轮：{} 条，残留错误 {} 个",
                round, fix.appliedFixes().size(), fix.finalReport().totalErrors());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.ITERATION, round);
        out.put(ForgeState.ERROR_COUNT, fix.finalReport().totalErrors());
        out.put(ForgeState.CONVERGED, fix.converged());
        out.put(ForgeState.ROLLED_BACK, fix.rolledBack());
        out.put(ForgeState.FIX_COUNT, s.fixCount() + fix.appliedFixes().size());
        out.put(ForgeState.LOG, "修正第 " + round + " 轮：" + fix.appliedFixes().size() + " 条");
        return out;
    }

    // ---------- ⑤ 报告 ----------

    public Map<String, Object> report(ForgeState s, RunnableConfig cfg) {
        RunReport runReport = reportOf(cfg);
        Map<String, Object> summary = runReport.toMap();
        runReport.save();
        log.info("[编排] 运行报告已落盘 run-reports/");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.REPORT, summary);
        out.put(ForgeState.LOG, "运行报告已落盘");
        return out;
    }

    // ---------- 条件边：校验后是否进入修正 ----------

    /** 全量模式不修正（与现网 /api/generate 行为一致）；增量模式按收敛情况决定 */
    public String afterValidate(ForgeState s) {
        if (!s.incremental()) {
            return EDGE_DONE;
        }
        if (s.errorCount() == 0) {
            return EDGE_DONE;
        }
        if (s.iteration() >= props.maxIterations()) {
            log.warn("[编排] 达到修正轮数上限 {}，带着 {} 个残留错误收工",
                    props.maxIterations(), s.errorCount());
            return EDGE_DONE;
        }
        return EDGE_RETRY;
    }

    // ---------- 运行时对象获取（不进状态）----------

    private static DbSession session(RunnableConfig cfg) {
        return (DbSession) cfg.metadata(ForgeState.META_SESSION).orElseThrow(
                () -> new IllegalStateException("缺少数据库会话：RunnableConfig.metadata[" + ForgeState.META_SESSION + "]"));
    }

    private static RunReport reportOf(RunnableConfig cfg) {
        return (RunReport) cfg.metadata(ForgeState.META_REPORT).orElseThrow(
                () -> new IllegalStateException("缺少运行报告：RunnableConfig.metadata[" + ForgeState.META_REPORT + "]"));
    }
}