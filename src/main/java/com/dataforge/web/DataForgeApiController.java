package com.dataforge.web;

import com.dataforge.agent.*;
import com.dataforge.config.GeneratorProperties;
import com.dataforge.correction.CorrectionEngine;
import com.dataforge.db.DbConnections;
import com.dataforge.db.MysqlJdbcDataSource;
import com.dataforge.db.MysqlStagingManager;
import com.dataforge.db.Publisher;
import com.dataforge.db.SchemaReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.*;
import com.dataforge.service.GenerationSupport;
import com.dataforge.service.HistoryService;
import com.dataforge.service.IncrementalSupport;
import com.dataforge.service.RealTableReader;
import com.dataforge.service.RulesService;
import com.dataforge.validation.MetaRuleCompiler;
import com.dataforge.validation.Scorer;
import com.dataforge.validation.SqlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.*;
import java.util.stream.Collectors;

/**
 * P7 Web REST API（Web 界面重做版）：五步向导——数据库连接配置（动态）→ 分析（读库表清单）
 * → 生成（逐表行数）→ 修正 → 评分。前端静态页 src/main/resources/static/index.html。
 */
@RestController
@RequestMapping("/api")
public class DataForgeApiController {

    private static final Logger log = LoggerFactory.getLogger(DataForgeApiController.class);

    private final DefaultAnalysisAgent analysisAgent;
    private final DefaultGenerationAgent generationAgent;
    private final DataLlm llm;
    private final Scorer scorer;
    private final GeneratorProperties props;
    private final HistoryService history;
    private final Publisher publisher;
    private final RulesService rulesService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataForgeApiController(DefaultAnalysisAgent analysisAgent, DefaultGenerationAgent generationAgent,
                                  DataLlm llm, Scorer scorer,
                                  GeneratorProperties props, HistoryService history, Publisher publisher,
                                  RulesService rulesService) {
        this.analysisAgent = analysisAgent;
        this.generationAgent = generationAgent;
        this.llm = llm;
        this.scorer = scorer;
        this.props = props;
        this.history = history;
        this.publisher = publisher;
        this.rulesService = rulesService;
    }

    /** 每请求动态连接会话（Web 前端配置数据库连接） */
    private static final class Session {
        final DataSource ds;
        final MysqlJdbcDataSource jdbc;
        final MysqlStagingManager staging;
        final SchemaReader schemaReader;
        final SqlValidator validator;

        Session(Map<String, String> db) {
            this.ds = DbConnections.dataSource(db);
            this.jdbc = DbConnections.jdbc(ds);
            this.staging = new MysqlStagingManager(ds);
            this.schemaReader = new SchemaReader(jdbc);
            this.validator = new SqlValidator(jdbc, new MetaRuleCompiler(), staging);
        }
    }

    // ---- ① 配置 ----

    /** 返回默认配置：连接 + 项目路径 + skill + 缓存状态（前端预填/徽标） */
    @GetMapping("/config")
    public Map<String, Object> config() {
        boolean rulesExist = java.nio.file.Files.exists(java.nio.file.Path.of("project_rules.json"));
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("dbHost", System.getProperty("DB_HOST", "localhost"));
        cfg.put("dbPort", System.getProperty("DB_PORT", "3306"));
        cfg.put("dbUser", System.getProperty("DB_USER", "root"));
        cfg.put("dbPassword", System.getProperty("DB_PASSWORD", ""));
        cfg.put("dbSchema", System.getProperty("DB_SCHEMA", "shop"));
        cfg.put("projectPath", System.getProperty("PROJECT_PATH", ""));
        cfg.put("skills", List.of("通用", "电商"));   // skill 选择（PLAN 决策 #11，垂直清单扩展点）
        cfg.put("cacheStatus", rulesExist ? "RULES_EXISTS" : "NONE");  // 缓存状态徽标
        return cfg;
    }

    /** 解析表结构（界面二选表）：纯确定性读库，不调分析 agent */
    @PostMapping("/parse")
    public Map<String, Object> parse(@RequestBody Map<String, String> req) {
        Session s = new Session(req);
        String schema = orDefault(req.get("dbSchema"), "shop");
        List<TableMeta> tables = s.schemaReader.readTables(schema);
        List<Map<String, Object>> list = tables.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("columns", t.columns().size());
            m.put("fks", t.foreignKeys().size());
            m.put("refCount", 0);   // 被引用数（简单统计）
            return m;
        }).collect(java.util.stream.Collectors.toList());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("tables", list);
        resp.put("elapsedMs", 0L);
        return resp;
    }

    /** 解析阶段推荐行数：轻量调 LLM（不提炼规则），解析/分析职责分离 */
    @PostMapping("/recommend-rows")
    public Map<String, Object> recommendRows(@RequestBody Map<String, String> req) {
        Session s = new Session(req);
        String schema = orDefault(req.get("dbSchema"), "shop");
        List<TableMeta> tables = s.schemaReader.readTables(schema);
        Map<String, Integer> rec = llm.recommendRows(DefaultAnalysisAgent.buildSchemaSummary(tables));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("recommend", rec);
        return resp;
    }

    /** 加载已保存规则（规则复用：上次分析落盘的 project_rules.json → 三段规则 + 构建器） */
    @GetMapping("/load-rules")
    public Map<String, Object> loadRules() {
        try {
            java.nio.file.Path pf = java.nio.file.Path.of("project_rules.json");
            if (!java.nio.file.Files.exists(pf)) return Map.of("exists", false);
            ProjectRules rules = objectMapper.readValue(pf.toFile(), ProjectRules.class);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("exists", true);
            resp.put("generationRules", rules.generationRules());
            resp.put("correctionRules", rules.correctionRules());
            resp.put("builders", rules.builders());
            return resp;
        } catch (Exception e) {
            log.warn("已保存规则读取失败: {}", e.getMessage());
            return Map.of("exists", false, "error", e.getMessage());
        }
    }

    /** 分析规则修订（人工检查点 + HITL 对话式询问）：用户指令 → LLM 修订规则 */
    @PostMapping("/refine-rules")
    public Map<String, Object> refineRules(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) req.getOrDefault("rules", List.of());
        String instruction = String.valueOf(req.getOrDefault("instruction", ""));
        List<ProjectRules.Rule> current = rawRules.stream()
                .map(m -> objectMapper.convertValue(m, ProjectRules.Rule.class)).toList();
        List<ProjectRules.Rule> refined = llm.refineRules(current, instruction);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("rules", refined);
        history.record("refine-rules", Map.of("instruction", instruction, "rules", refined.size()));
        // 落盘闭环（修 D1 断链）：修订后写回 project_rules.json，与 analyze 分流一致
        //   （AGG_EQUAL/STATUS_FLOW → correctionRules；其余 → generationRules；builders/version 沿用）
        try {
            ProjectRules existing = rulesService.currentRules();
            List<ProjectRules.Rule> correction = refined.stream()
                    .filter(r -> "AGG_EQUAL".equals(r.type()) || "STATUS_FLOW".equals(r.type())
                            || "CUSTOM_SQL".equals(r.type())).toList();
            List<ProjectRules.Rule> generation = refined.stream()
                    .filter(r -> !"AGG_EQUAL".equals(r.type()) && !"STATUS_FLOW".equals(r.type())
                            && !"CUSTOM_SQL".equals(r.type())).toList();
            ProjectRules updated = new ProjectRules(generation, correction,
                    existing.builders(), existing.version() + 1);
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(java.nio.file.Path.of("project_rules.json").toFile(), updated);
            resp.put("saved", true);
        } catch (Exception e) {
            resp.put("saved", false);
            log.warn("refine-rules 落盘失败: {}", e.getMessage());
        }
        return resp;
    }

    /** 数据预览：从 staging 读回每表前 50 行（界面四展示 + 血缘） */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody Map<String, String> req) {
        Session s = new Session(req);
        String schema = orDefault(req.get("dbSchema"), "shop");
        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        Map<String, GeneratedData> data = s.staging.read(schema, allTables);
        Map<String, Object> preview = new LinkedHashMap<>();
        for (var e : data.entrySet()) {
            preview.put(e.getKey(), Map.of(
                    "rows", e.getValue().rows().stream().limit(50)
                            .map(r -> Map.of("values", r.values(), "lineage", r.lineage())).toList(),
                    "total", e.getValue().rowCount()));
        }
        return preview;
    }

    /** 发布：staging 按保留策略（REPLACE/APPEND/KEEP）落目标库（二次确认） */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody Map<String, Object> req) throws Exception {
        Session s = new Session(toStringMap(req));
        String schema = orDefault(toStringMap(req).get("dbSchema"), "shop");
        boolean confirmed = Boolean.TRUE.equals(req.get("confirmed"));
        @SuppressWarnings("unchecked")
        Map<String, String> policies = (Map<String, String>) req.getOrDefault("policies", Map.of());
        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        Map<String, GeneratedData> data = s.staging.read(schema, allTables);
        List<TableMeta> tables = allTables.stream().filter(t -> data.containsKey(t.name())).toList();
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        tables.forEach(t -> rowCounts.put(t.name(), data.get(t.name()).rowCount()));
        if (!confirmed) {
            Map<String, Object> pre = new LinkedHashMap<>();
            pre.put("confirmed", false);
            pre.put("tables", rowCounts);
            pre.put("message", "请确认保留策略后再次发布（REPLACE 会清空重建目标表）");
            return pre;
        }
        Publisher.Result r = publisher.publish(s.jdbc, schema, tables, data, policies, true);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("confirmed", true);
        resp.put("published", r.published());
        resp.put("skipped", r.skipped());
        resp.put("errors", r.errors());
        history.record("publish", Map.of("schema", schema, "published", r.published()));
        return resp;
    }

    /** 测试连接 */
    @PostMapping("/test-connection")
    public Map<String, Object> testConnection(@RequestBody Map<String, String> db) {
        try (Connection c = DbConnections.dataSource(db).getConnection()) {
            return Map.of("ok", true, "message", "连接成功: " + c.getMetaData().getURL());
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    /** 列出该连接下的数据库（测试连接成功后前端填充库名下拉框） */
    @PostMapping("/list-databases")
    public Map<String, Object> listDatabases(@RequestBody Map<String, String> db) {
        // 不带库名连接（信息库枚举，库名由前端下拉选择）
        Map<String, String> conn = new HashMap<>(db);
        conn.put("dbSchema", "");
        try (Connection c = DbConnections.dataSource(conn).getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery(
                     "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA " +
                     "WHERE SCHEMA_NAME NOT IN ('information_schema','mysql','performance_schema','sys') " +
                     "ORDER BY SCHEMA_NAME")) {
            List<String> databases = new ArrayList<>();
            while (rs.next()) databases.add(rs.getString(1));
            return Map.of("ok", true, "databases", databases);
        } catch (Exception e) {
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    // ---- ③ 分析（分析 agent 只分析用户勾选的表 → 三段式 project_rules） ----

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody Map<String, Object> req) {
        long start = System.currentTimeMillis();
        Session s = new Session(toStringMap(req));
        String schema = orDefault(toStringMap(req).get("dbSchema"), "shop");
        @SuppressWarnings("unchecked")
        List<String> selected = (List<String>) req.getOrDefault("selected", List.of());
        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        // 分析 agent 只分析用户勾选的表（未勾选 → 全部分析）
        List<TableMeta> tables = selected.isEmpty() ? allTables
                : allTables.stream().filter(t -> selected.contains(t.name())).toList();
        AnalysisAgent.AnalysisResult result = analysisAgent.analyzeTables(tables);

        // 三段式 project_rules：前置约束（生成）/ 后置修正（校验修正）/ 行构建器
        List<Map<String, Object>> tableList = new ArrayList<>();
        for (TableMeta t : tables) {
            GenerationPlan.TablePlan tp = result.plan().tablePlan(t.name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("builder", tp == null ? "?" : tp.builder());
            m.put("recommend", result.rowRecommend().getOrDefault(t.name(), 10));
            // T5.1：返回每表列模式（生成计划，前端第 3 步展示——用户看到"怎么生成"而非只见规则）
            m.put("columns", tp == null ? List.of()
                    : tp.columns().stream().map(cp -> Map.of(
                            "column", cp.column(),
                            "mode", cp.mode(),
                            "params", cp.params())).toList());
            tableList.add(m);
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("analyzedTables", tableList);
        resp.put("generationRules", result.rules().generationRules());   // 前置约束
        resp.put("correctionRules", result.rules().correctionRules());   // 后置修正
        resp.put("builders", result.rules().builders());                 // 行构建器
        resp.put("rowRecommend", result.rowRecommend());
        resp.put("elapsedMs", System.currentTimeMillis() - start);
        history.record("analyze", Map.of("schema", schema, "tables", tableList.size()));
        // 落盘 project_rules.json + generation-plan.json（唯一参数源：后续生成/校验自动加载，刷新不丢失）
        try {
            java.nio.file.Path pf = java.nio.file.Path.of("project_rules.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(pf.toFile(), result.rules());
            // 生成计划必须一并落盘：analyze 响应体只含 name/builder/recommend/columns，
            // 不含 parent/rowsPerParent，调用方无法自行拼出计划文件；不落盘则后续生成会加载
            // 上一版（可能来自不同 schema）的陈旧计划，本次分析结果白白丢失。
            java.nio.file.Path planFile = java.nio.file.Path.of("generation-plan.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(planFile.toFile(), result.plan());
            resp.put("saved", true);
        } catch (Exception e) {
            resp.put("saved", false);
            log.warn("分析产物落盘失败: {}", e.getMessage());
        }
        return resp;
    }

    /**
     * 解析本次生成用的计划：优先加载人工确认过的落盘计划（generation-plan.json），
     * 缺失 / 解析失败 / 未覆盖本次全部表 → 回退现场分析。
     *
     * <p>落盘计划是唯一的人工干预入口——分析 agent 的 PlanBuilder 是确定性启发式，
     * 在无物理外键的库上会判错父子关系（进而把外键列退化成常量），必须支持人工修正后生效；
     * 回退现场分析保证计划文件缺失时流程不中断。</p>
     */
    private GenerationPlan resolvePlan(List<TableMeta> genTables) {
        java.nio.file.Path pf = java.nio.file.Path.of("generation-plan.json");
        if (java.nio.file.Files.exists(pf)) {
            try {
                GenerationPlan loaded = objectMapper.readValue(pf.toFile(), GenerationPlan.class);
                if (loaded != null && !loaded.tables().isEmpty()) {
                    Set<String> names = genTables.stream().map(TableMeta::name)
                            .collect(Collectors.toSet());
                    // 判定"计划适用于本次生成"：只要求与本次表集有交集。
                    // 计划里多出的表无害（GenerationAgent 只按 genTables 逐表查 tablePlan），
                    // 计划里缺少的表同样合法（走内置展开器 / FixedSamples fallback）。
                    // 两者都不能作为回退条件——否则"只生成部分表"就会静默绕过人工确认的计划。
                    long overlap = loaded.tables().stream()
                            .map(GenerationPlan.TablePlan::table)
                            .filter(names::contains).count();
                    if (overlap > 0) {
                        log.info("加载落盘生成计划 generation-plan.json（共 {} 表，本次命中 {} 表），跳过现场分析",
                                loaded.tables().size(), overlap);
                        return loaded;
                    }
                    log.warn("落盘计划与本次生成范围 {} 无交集，回退现场分析", names);
                }
            } catch (Exception e) {
                log.warn("generation-plan.json 解析失败（回退现场分析）: {}", e.getMessage());
            }
        }
        return analysisAgent.analyzeTables(genTables).plan();
    }

    // ---- ③ 生成（逐表行数 → 配置驱动生成 → 落库） ----

    @PostMapping("/generate")
    public Map<String, Object> generate(@RequestBody Map<String, Object> req) throws Exception {
        long start = System.currentTimeMillis();
        Session s = new Session(toStringMap(req));
        String schema = orDefault(toStringMap(req).get("dbSchema"), "shop");
        @SuppressWarnings("unchecked")
        Map<String, Integer> rows = (Map<String, Integer>) req.getOrDefault("rows", Map.of());
        if (rows.isEmpty()) {
            return Map.of("error", "请先在表清单中设置行数");
        }
        List<TableMeta> tables = s.schemaReader.readTables(schema);

        // 闭包：指定表 + FK 父表（未指定用 minParentRows 缺省）
        GenerationSupport.Closure closure = GenerationSupport.referenceClosure(tables, rows, props.minParentRows());
        Map<String, Integer> rowCounts = closure.rowCounts();
        List<TableMeta> genTables = tables.stream().filter(t -> closure.tables().contains(t.name())).toList();

        // 配置驱动：优先加载人工确认过的落盘计划（generation-plan.json），缺失/不匹配才现场分析
        GenerationPlan plan = resolvePlan(genTables);

        GenerationAgent.GenerationResult result = generationAgent.generate(
                new GenerationAgent.GenerationRequest(genTables, rulesService.currentRules(), rowCounts,
                        IncrementalConfig.full(), props.seed(), props.batchSize(), props.maxConcurrency(),
                        plan),
                false);
        // 动态落库 + 校验
        s.staging.drop(schema);
        s.staging.create(schema, genTables);
        s.staging.load(schema, result.data());
        ValidationReport report = s.validator.validate(genTables,
                rulesService.currentRules(), schema);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("actualCounts", result.actualCounts());
        resp.put("elapsedMs", System.currentTimeMillis() - start);
        resp.put("failedTables", result.failedTables());
        resp.put("validation", report.totalErrors());
        history.record("generate", Map.of("schema", schema, "rows",
                result.actualCounts().values().stream().mapToInt(Integer::intValue).sum()));
        return resp;
    }

    // ---- ③.5 增量生成（追加不冲突不误伤：起始 ID=MAX+1 + 现有池引用 + 作用域修正） ----

    @PostMapping("/incremental")
    public Map<String, Object> incremental(@RequestBody Map<String, Object> req) throws Exception {
        long start = System.currentTimeMillis();
        Session s = new Session(toStringMap(req));
        String schema = orDefault(toStringMap(req).get("dbSchema"), "shop");
        @SuppressWarnings("unchecked")
        Map<String, Integer> rows = (Map<String, Integer>) req.getOrDefault("rows", Map.of());
        if (rows.isEmpty()) {
            return Map.of("error", "请先在表清单中设置行数");
        }
        int minParentRows = req.get("minParentRows") instanceof Number n
                ? n.intValue() : props.minParentRows();
        boolean cacheEnabled = Boolean.TRUE.equals(req.get("cacheEnabled"));
        boolean replan = !Boolean.FALSE.equals(req.get("replan"));

        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        // 闭包：增量只生成用户显式指定的表（父表 rowCount=0 → 引用现有 staging）
        GenerationSupport.Closure closure = GenerationSupport.referenceClosure(allTables, rows, minParentRows);
        List<TableMeta> genTables = allTables.stream()
                .filter(t -> closure.tables().contains(t.name())).toList();

        // baseline：现有 staging 数据（FK 池 / 展开器引用；空则退化为全量）
        Map<String, GeneratedData> baseline = s.staging.read(schema, genTables);
        boolean baselineEmpty = baseline.values().stream().allMatch(g -> g.rowCount() == 0);

        // 生成计划（配置驱动，复用 /generate 逻辑）；replan=false 时跳过规划，退回内置模板 fallback
        GenerationPlan plan = replan ? resolvePlan(genTables) : GenerationPlan.empty();
        if (!replan) {
            log.warn("replan=false：跳过规划，本次生成退回内置模板 fallback（计划不随请求传入）");
        }

        ProjectRules rules = rulesService.currentRules();
        // 增量生成：只生成用户显式表行数，父表 rowCount=0 → 引用现有（generateIncremental 内部处理）
        GenerationAgent.GenerationRequest incReq = new GenerationAgent.GenerationRequest(
                genTables, rules, new LinkedHashMap<>(rows), IncrementalConfig.full(),
                props.seed(), props.batchSize(), props.maxConcurrency(), plan);
        GenerationAgent.GenerationResult result =
                generationAgent.generateIncremental(incReq, baseline, cacheEnabled);

        // 重建 staging 完整快照（result.data() 已含现有+新增，直接 drop→create→load）
        s.staging.drop(schema);
        s.staging.create(schema, genTables);
        s.staging.load(schema, result.data());

        // 跨现有校验 + 作用域修正（scopeMinId = 各表现有 MAX(id)+1 的最小值，只动新增区间）
        ValidationReport initial = s.validator.validate(genTables, rules, schema);
        Long scopeMinId = IncrementalSupport.scopeMinIdOf(baseline);
        boolean converged = true;
        int iterations = 0;
        int fixCount = 0;
        int finalErrors = initial.totalErrors();
        boolean rolledBack = false;
        if (initial.totalErrors() > 0) {
            CorrectionEngine engine = new CorrectionEngine(s.jdbc, s.staging, s.validator, llm);
            CorrectionAgent.CorrectionResult fix = engine.run(new CorrectionAgent.CorrectionRequest(
                    genTables, result.data(), initial, rules, schema, scopeMinId),
                    props.maxIterations());
            converged = fix.converged();
            iterations = fix.iterations();
            fixCount = fix.appliedFixes().size();
            finalErrors = fix.finalReport().totalErrors();
            rolledBack = fix.rolledBack();
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("baselineEmpty", baselineEmpty);
        resp.put("scopeMinId", scopeMinId);
        resp.put("actualCounts", result.actualCounts());
        resp.put("failedTables", result.failedTables());
        resp.put("validationInitialErrors", initial.totalErrors());
        resp.put("converged", converged);
        resp.put("iterations", iterations);
        resp.put("fixCount", fixCount);
        resp.put("finalErrors", finalErrors);
        resp.put("rolledBack", rolledBack);
        resp.put("elapsedMs", System.currentTimeMillis() - start);
        history.record("incremental", Map.of("schema", schema,
                "rows", result.actualCounts().values().stream().mapToInt(Integer::intValue).sum(),
                "baselineEmpty", baselineEmpty));
        return resp;
    }

    // ---- ④ 修正 ----

    @PostMapping("/correct")
    public Map<String, Object> correct(@RequestBody Map<String, String> req) throws Exception {
        long start = System.currentTimeMillis();
        Session s = new Session(req);
        String schema = orDefault(req.get("dbSchema"), "shop");
        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        Map<String, GeneratedData> data = s.staging.read(schema, allTables);
        List<TableMeta> tables = allTables.stream().filter(t -> data.containsKey(t.name())).toList();
        ValidationReport initial = s.validator.validate(tables,
                rulesService.currentRules(), schema);
        CorrectionEngine engine = new CorrectionEngine(s.jdbc, s.staging, s.validator, llm);
        CorrectionAgent.CorrectionResult fix = engine.run(new CorrectionAgent.CorrectionRequest(
                tables, data, initial, rulesService.currentRules(), schema, null),
                props.maxIterations());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("converged", fix.converged());
        resp.put("iterations", fix.iterations());
        resp.put("fixCount", fix.appliedFixes().size());
        resp.put("finalErrors", fix.finalReport().totalErrors());
        resp.put("elapsedMs", System.currentTimeMillis() - start);
        history.record("correct", Map.of("schema", schema, "converged", fix.converged(),
                "fixCount", fix.appliedFixes().size()));
        return resp;
    }

    // ---- ⑤ 评分 ----

    @PostMapping("/score")
    public Map<String, Object> score(@RequestBody Map<String, String> req) {
        long start = System.currentTimeMillis();
        Session s = new Session(req);
        String schema = orDefault(req.get("dbSchema"), "shop");
        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        Map<String, GeneratedData> generated = s.staging.read(schema, allTables);
        List<TableMeta> tables = allTables.stream().filter(t -> generated.containsKey(t.name())).toList();
        Scorer.ScoreReport report = scorer.score(generated, RealTableReader.read(s.jdbc, schema, tables));
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("schema", schema);
        resp.put("overall", report.overall());
        Map<String, Object> tablesScore = new LinkedHashMap<>();
        report.tables().forEach((t, ts) -> tablesScore.put(t, Map.of("score", ts.score(),
                "columns", ts.columns().stream().map(c -> Map.of("column", c.column(), "method", c.method(),
                        "distance", c.distance(), "score", c.score())).toList())));
        resp.put("tables", tablesScore);
        resp.put("elapsedMs", System.currentTimeMillis() - start);
        history.record("score", Map.of("schema", schema, "overall", report.overall()));
        return resp;
    }

    /** 试编译（三层防护①）：规则保存前校验能否编译成检查 SQL（语法拒绝） */
    @PostMapping("/compile-rules")
    public Map<String, Object> compileRules(@RequestBody Map<String, Object> req) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRules = (List<Map<String, Object>>) req.getOrDefault("rules", List.of());
        List<ProjectRules.Rule> rules = rawRules.stream()
                .map(m -> objectMapper.convertValue(m, ProjectRules.Rule.class)).toList();
        List<String> errors = new ArrayList<>();
        for (ProjectRules.Rule r : rules) {
            try {
                new MetaRuleCompiler().compileProjectRule(r, null, "schema");
            } catch (Exception e) {
                errors.add(r.table() + "." + r.column() + ": " + e.getMessage());
            }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", errors.isEmpty());
        resp.put("errors", errors);
        return resp;
    }

    /** 导出 SQL 文件：staging 数据 → INSERT 语句（用户可直接执行） */
    @PostMapping("/export-sql")
    public Map<String, Object> exportSql(@RequestBody Map<String, String> req) {
        Session s = new Session(req);
        String schema = orDefault(req.get("dbSchema"), "shop");
        List<TableMeta> allTables = s.schemaReader.readTables(schema);
        Map<String, GeneratedData> data = s.staging.read(schema, allTables);
        StringBuilder sb = new StringBuilder();
        sb.append("-- DataForge 导出：").append(schema).append("\n\n");
        int inserts = 0;
        for (Map.Entry<String, GeneratedData> e : data.entrySet()) {
            TableMeta t = allTables.stream().filter(x -> x.name().equals(e.getKey())).findFirst().orElse(null);
            if (t == null || e.getValue().rows().isEmpty()) continue;
            String cols = t.columns().stream().map(ColumnDef::name)
                    .map(MysqlJdbcDataSource::quote).collect(java.util.stream.Collectors.joining(", "));
            sb.append("INSERT INTO ").append(MysqlJdbcDataSource.quote(schema)).append('.')
                    .append(MysqlJdbcDataSource.quote(t.name())).append(" (").append(cols).append(") VALUES\n");
            List<String> rowSql = new ArrayList<>();
            for (DataRow row : e.getValue().rows()) {
                List<String> vals = t.columns().stream()
                        .map(c -> sqlValue(row.values().get(c.name()))).toList();
                rowSql.add("  (" + String.join(", ", vals) + ")");
            }
            sb.append(String.join(",\n", rowSql)).append(";\n\n");
            inserts += e.getValue().rowCount();
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("sql", sb.toString());
        resp.put("inserts", inserts);
        return resp;
    }

    private static String sqlValue(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Number) return String.valueOf(v);
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    // ---- 历史 + SSE ----

    @GetMapping("/history")
    public List<Map<String, Object>> history() {
        return history.list();
    }

    @GetMapping(value = "/events", produces = "text/event-stream")
    public SseEmitter events() {
        SseEmitter emitter = new SseEmitter(60_000L);
        try {
            emitter.send(SseEmitter.event().name("status").data(Map.of("message", "DataForge Web 已连接")));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    // ---- 工具 ----

    @SuppressWarnings("unchecked")
    private static Map<String, String> toStringMap(Map<String, Object> req) {
        return (Map<String, String>) (Map<?, ?>) req;
    }

    private static String orDefault(String v, String dflt) {
        return v == null || v.isBlank() ? dflt : v;
    }

    @ExceptionHandler(Exception.class)
    public Map<String, Object> handleError(Exception e) {
        log.error("API 错误", e);
        return Map.of("error", e.getMessage() == null ? e.toString() : e.getMessage());
    }
}
