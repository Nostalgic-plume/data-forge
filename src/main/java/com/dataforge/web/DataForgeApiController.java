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
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DataForgeApiController(DefaultAnalysisAgent analysisAgent, DefaultGenerationAgent generationAgent,
                                  DataLlm llm, Scorer scorer,
                                  GeneratorProperties props, HistoryService history, Publisher publisher) {
        this.analysisAgent = analysisAgent;
        this.generationAgent = generationAgent;
        this.llm = llm;
        this.scorer = scorer;
        this.props = props;
        this.history = history;
        this.publisher = publisher;
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
            ProjectRules existing = currentRules();
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
        // 落盘 project_rules.json（唯一参数源：后续生成/校验自动加载，刷新不丢失）
        try {
            java.nio.file.Path pf = java.nio.file.Path.of("project_rules.json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(pf.toFile(), result.rules());
            resp.put("saved", true);
        } catch (Exception e) {
            resp.put("saved", false);
            log.warn("project_rules 落盘失败: {}", e.getMessage());
        }
        return resp;
    }

    /** 当前生效规则：优先已保存的 project_rules.json，否则内置默认 */
    private ProjectRules currentRules() {
        try {
            java.nio.file.Path pf = java.nio.file.Path.of("project_rules.json");
            if (java.nio.file.Files.exists(pf)) {
                return objectMapper.readValue(pf.toFile(), ProjectRules.class);
            }
        } catch (Exception e) {
            log.warn("project_rules.json 读取失败（回退内置默认）: {}", e.getMessage());
        }
        return ProjectRules.withDefaultCorrectionRules();
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

        // 闭包：指定表 + FK 父表（未指定用推荐行数）
        Map<String, TableMeta> byName = tables.stream().collect(Collectors.toMap(TableMeta::name, t -> t));
        Set<String> closure = new LinkedHashSet<>(rows.keySet());
        Deque<String> queue = new ArrayDeque<>(rows.keySet());
        while (!queue.isEmpty()) {
            TableMeta meta = byName.get(queue.poll());
            if (meta == null) continue;
            for (var fk : meta.foreignKeys()) {
                if (byName.containsKey(fk.refTable()) && closure.add(fk.refTable())) queue.add(fk.refTable());
            }
        }
        Map<String, Integer> rowCounts = new LinkedHashMap<>();
        for (String t : closure) rowCounts.put(t, rows.getOrDefault(t, 10));
        List<TableMeta> genTables = tables.stream().filter(t -> closure.contains(t.name())).toList();

        // 配置驱动：分析产出生成计划 → 设到生成 agent
        AnalysisAgent.AnalysisResult analyzed = analysisAgent.analyzeTables(genTables);
        generationAgent.setPlan(analyzed.plan());

        GenerationAgent.GenerationResult result = generationAgent.generate(
                new GenerationAgent.GenerationRequest(genTables, currentRules(), rowCounts,
                        IncrementalConfig.full(), props.seed(), props.batchSize(), props.maxConcurrency()),
                false, true);
        // 动态落库 + 校验
        s.staging.drop(schema);
        s.staging.create(schema, genTables);
        s.staging.load(schema, result.data());
        ValidationReport report = s.validator.validate(genTables,
                currentRules(), schema);

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
                currentRules(), schema);
        CorrectionEngine engine = new CorrectionEngine(s.jdbc, s.staging, s.validator, llm);
        CorrectionAgent.CorrectionResult fix = engine.run(new CorrectionAgent.CorrectionRequest(
                tables, data, initial, currentRules(), schema, null),
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
        Scorer.ScoreReport report = scorer.score(generated, readRealTables(s.jdbc, schema, tables));
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

    private Map<String, GeneratedData> readRealTables(MysqlJdbcDataSource jdbc, String schema,
                                                      List<TableMeta> tables) {
        Map<String, GeneratedData> real = new LinkedHashMap<>();
        for (TableMeta t : tables) {
            List<Map<String, Object>> rows = jdbc.query(
                    "SELECT * FROM `" + schema + "`.`" + t.name() + "`");
            List<DataRow> dataRows = rows.stream().map(r -> {
                Map<String, SourceTag> lineage = new LinkedHashMap<>();
                for (ColumnDef c : t.columns()) lineage.put(c.name(), SourceTag.SEED);
                return new DataRow(new LinkedHashMap<>(r), lineage);
            }).toList();
            real.put(t.name(), new GeneratedData(t.name(), t.columns(), dataRows));
        }
        return real;
    }

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
