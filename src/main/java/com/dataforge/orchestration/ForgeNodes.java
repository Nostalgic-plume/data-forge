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
import com.dataforge.model.GenerationPlan;
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
 * 大数据只作为局部变量在节点内流转。表结构也不进状态（只存表名，节点按需重读）。</p>
 *
 * <p><b>修正循环在图内还是节点内</b>：循环留在 {@link CorrectionEngine} 内部（ReAct 收敛四要素
 * 依赖跨轮"问题清单闭环"，拆到图上回边会丢掉跨轮回归基准）。图上只编排"要不要修正"这一步，
 * 不再对同一批修正做二次循环——否则图回边 × 引擎内循环 = 轮数平方放大。</p>
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
        // 空请求守卫：绝不能带着空闭包往下走（下游 drop staging 会把用户已有数据清空）
        if (s.rows().isEmpty()) {
            throw new IllegalArgumentException("请先在表清单中设置行数");
        }
        DbSession db = session(cfg);
        int minParentRows = s.minParentRows() != null ? s.minParentRows() : props.minParentRows();
        List<TableMeta> allTables = db.schemaReader.readTables(s.schema());
        GenerationSupport.Closure closure = GenerationSupport.referenceClosure(
                allTables, s.rows(), minParentRows);
        List<TableMeta> genTables = allTables.stream()
                .filter(t -> closure.tables().contains(t.name())).toList();

        // 增量 replan=false：跳过 LLM 重规划，退回内置模板 fallback（省一次 LLM 调用）
        GenerationPlan plan = GenerationPlan.empty();
        int ruleCount = 0;
        if (!s.incremental() || s.replan()) {
            AnalysisAgent.AnalysisResult analyzed = analysisAgent.analyzeTables(genTables);
            plan = analyzed.plan();
            reportOf(cfg).analysis(analyzed);
            ruleCount = analyzed.rules() == null ? 0
                    : analyzed.rules().generationRules().size() + analyzed.rules().correctionRules().size();
        } else {
            log.warn("[编排] 增量 replan=false：跳过分析规划，退回内置模板 fallback");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.TABLE_NAMES, genTables.stream().map(TableMeta::name).toList());
        out.put(ForgeState.PLAN, plan);
        // 增量模式：只按用户显式指定的行数生成，父表 rowCount=0 由 generateIncremental 内部按"引用现有"处理
        out.put(ForgeState.ROW_COUNTS, s.incremental() ? new LinkedHashMap<>(s.rows()) : closure.rowCounts());
        out.put(ForgeState.RULE_COUNT, ruleCount);
        // 新一次运行（analyze 只在全新运行执行；断点恢复从被中断节点续跑）→ 运行态计数器复位
        out.put(ForgeState.ITERATION, 0);
        out.put(ForgeState.ERROR_COUNT, 0);
        out.put(ForgeState.FIX_COUNT, 0);
        out.put(ForgeState.CONVERGED, false);
        out.put(ForgeState.ROLLED_BACK, false);
        out.put(ForgeState.LOG, "分析：" + genTables.size() + " 张表 / " + ruleCount + " 条规则");
        log.info("[编排] 分析完成：{} 张表 / {} 条规则", genTables.size(), ruleCount);
        return out;
    }

    // ---------- ② 生成 + 落库（合成一个节点：数据必须落库才算一个可恢复的完成点）----------

    public Map<String, Object> generateAndStage(ForgeState s, RunnableConfig cfg) {
        DbSession db = session(cfg);
        List<TableMeta> genTables = tablesOf(s, cfg);
        // 二次守卫：状态可来自检查点，落库前再挡一次空集（防 drop 掉用户已有 staging）
        if (genTables.isEmpty()) {
            throw new IllegalStateException("生成表集为空，已中止（不触碰 staging，避免清空已有数据）");
        }
        ProjectRules rules = rulesService.currentRules();
        boolean cacheEnabled = s.cacheEnabled() != null ? s.cacheEnabled() : props.cacheDir() != null;
        GenerationAgent.GenerationRequest req = new GenerationAgent.GenerationRequest(
                genTables, rules, s.rowCounts(), IncrementalConfig.full(),
                props.seed(), props.batchSize(), props.maxConcurrency(), s.plan());

        GenerationAgent.GenerationResult result;
        Long scopeMinId = null;
        boolean baselineEmpty = false;
        if (s.incremental()) {
            Map<String, GeneratedData> baseline = db.staging.read(s.schema(), genTables);
            baselineEmpty = baseline.values().stream().allMatch(g -> g.rowCount() == 0);
            result = generationAgent.generateIncremental(req, baseline, cacheEnabled);
            scopeMinId = IncrementalSupport.scopeMinIdOf(baseline);
        } else {
            result = generationAgent.generate(req, cacheEnabled);
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
        if (s.incremental()) {
            out.put(ForgeState.BASELINE_EMPTY, baselineEmpty);
        }
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
                tablesOf(s, cfg), rulesService.currentRules(), s.schema());
        reportOf(cfg).validation(report);

        log.info("[编排] 校验：错误 {} 个", report.totalErrors());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.ERROR_COUNT, report.totalErrors());
        out.put(ForgeState.INITIAL_ERRORS, report.totalErrors());
        // 收敛 = 校验后无残留错误（全量模式同样适用，避免"未校验就宣称已收敛"）
        out.put(ForgeState.CONVERGED, report.totalErrors() == 0);
        out.put(ForgeState.LOG, "校验：" + report.totalErrors() + " 个错误");
        return out;
    }

    // ---------- ④ 修正（一轮编排；收敛循环仍在 CorrectionEngine 内）----------

    public Map<String, Object> correct(ForgeState s, RunnableConfig cfg) {
        DbSession db = session(cfg);
        List<TableMeta> genTables = tablesOf(s, cfg);
        ProjectRules rules = rulesService.currentRules();
        Map<String, GeneratedData> data = db.staging.read(s.schema(), genTables);
        ValidationReport initial = db.validator.validate(genTables, rules, s.schema());

        CorrectionEngine engine = new CorrectionEngine(db.jdbc, db.staging, db.validator, llm);
        long start = System.currentTimeMillis();
        CorrectionAgent.CorrectionResult fix = engine.run(
                new CorrectionAgent.CorrectionRequest(
                        genTables, data, initial, rules, s.schema(), s.scopeMinId()),
                props.maxIterations());
        long elapsedMs = System.currentTimeMillis() - start;
        reportOf(cfg).correction(fix, elapsedMs);   // 累加语义：多轮修正不覆盖前几轮数据

        int rounds = s.iteration() + fix.iterations();
        log.info("[编排] 修正完成：引擎 {} 轮 / {} 条，残留错误 {} 个",
                fix.iterations(), fix.appliedFixes().size(), fix.finalReport().totalErrors());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.ITERATION, rounds);
        out.put(ForgeState.ERROR_COUNT, fix.finalReport().totalErrors());
        out.put(ForgeState.CONVERGED, fix.converged());
        out.put(ForgeState.ROLLED_BACK, fix.rolledBack());
        out.put(ForgeState.FIX_COUNT, s.fixCount() + fix.appliedFixes().size());
        out.put(ForgeState.LOG, "修正：" + fix.appliedFixes().size() + " 条 / " + fix.iterations() + " 轮");
        return out;
    }

    // ---------- ⑤ 报告 ----------

    public Map<String, Object> report(ForgeState s, RunnableConfig cfg) {
        RunReport runReport = reportOf(cfg).mode(s.mode());
        Map<String, Object> summary = runReport.toMap();
        runReport.save();
        log.info("[编排] 运行报告已落盘 run-reports/");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ForgeState.REPORT, summary);
        out.put(ForgeState.LOG, "运行报告已落盘");
        return out;
    }

    // ---------- 条件边：校验后是否进入修正 ----------

    /**
     * 全量模式不修正（与现网 /api/generate 行为一致）；增量模式有错才进入修正。
     *
     * <p>修正轮数上限由 {@link CorrectionEngine#run} 内部判定（那里才有跨轮问题清单基准）；
     * correct 之后直接进 report，不再回边 validate——避免"图回边 × 引擎内循环"把轮数放大成平方。
     * 因此本方法只需两个闸门：模式 + 是否有错。</p>
     */
    public String afterValidate(ForgeState s) {
        if (!s.incremental()) {
            return EDGE_DONE;
        }
        if (s.errorCount() == 0) {
            return EDGE_DONE;
        }
        return EDGE_RETRY;
    }

    // ---------- 运行时对象获取（不进状态）----------

    /** 按状态里的表名从 schema 重读表结构（状态不放 TableMeta 对象：体积 + 检查点可序列化） */
    private static List<TableMeta> tablesOf(ForgeState s, RunnableConfig cfg) {
        List<String> names = s.tableNames();
        return session(cfg).schemaReader.readTables(s.schema()).stream()
                .filter(t -> names.contains(t.name())).toList();
    }

    private static DbSession session(RunnableConfig cfg) {
        return (DbSession) cfg.metadata(ForgeState.META_SESSION).orElseThrow(
                () -> new IllegalStateException("缺少数据库会话：RunnableConfig.metadata[" + ForgeState.META_SESSION + "]"));
    }

    private static RunReport reportOf(RunnableConfig cfg) {
        return (RunReport) cfg.metadata(ForgeState.META_REPORT).orElseThrow(
                () -> new IllegalStateException("缺少运行报告：RunnableConfig.metadata[" + ForgeState.META_REPORT + "]"));
    }
}
