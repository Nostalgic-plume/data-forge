package com.dataforge.pipeline;

import com.dataforge.config.GeneratorProperties;
import com.dataforge.llm.DataLlm;
import com.dataforge.model.ColumnDef;
import com.dataforge.model.ProjectRules;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 批次2 单测（决策 D）：generationRules（前置约束）必须注入生成 prompt。
 *
 * <p>修 D1：生成 agent 不再只吃 plan，规则链路（project_rules → prompt）打通。
 * 用记录型 DataLlm 断言 generateRows 收到的 fkContext 含该表前置约束文本。</p>
 */
class GenerationRulesInjectionTest {

    /** 记录 generateRows 收到的 fkContext（第 4 参），验证规则注入 */
    static final class RecordingLlm implements DataLlm {
        final List<String> contexts = new ArrayList<>();

        @Override
        public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                      int rowCount, String fkContext) {
            contexts.add(fkContext);
            List<Map<String, Object>> rows = new ArrayList<>(rowCount);
            for (int i = 0; i < rowCount; i++) {
                Map<String, Object> r = new LinkedHashMap<>();
                for (ColumnDef c : llmCols) r.put(c.name(), "测试值" + i);
                rows.add(r);
            }
            return rows;
        }

        @Override
        public boolean isMock() {
            return true;
        }
    }

    private static GeneratorProperties props() {
        // (mockLlm, seed, batchSize, maxConcurrency, pkStrategy, cacheDir, minParentRows, maxIterations)
        return new GeneratorProperties(true, 42L, 50, 4, "sequential", null, 5, 10);
    }

    @Test
    void generationRulesInjectedIntoPrompt() throws Exception {
        String ddl = "CREATE TABLE member (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '会员ID',\n"
                + "  name VARCHAR(50) NOT NULL COMMENT '会员姓名',\n"
                + "  status TINYINT DEFAULT 0 COMMENT '0正常 1停用'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        Map<String, Integer> rows = Map.of("member", 5);

        // 分析产物：该表一条前置约束（generationRules）
        ProjectRules rules = new ProjectRules(
                List.of(ProjectRules.Rule.simple("member", "name", "USER_NOTE",
                        "会员姓名必须加品牌前缀，如'旗舰店-李四'", 1.0, ProjectRules.Rule.Source.AGENT)),
                List.of(), List.of(), 1);

        PipelineContext ctx = new PipelineContext(tables, rows, 42L);
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(ctx);

        RecordingLlm recording = new RecordingLlm();
        ContentGen.GenOutcome out = ContentGen.execute(ctx, recording, props(),
                null, null, null, null, rules);

        // 表生成成功，且 generateRows 收到的上下文包含前置约束
        assertTrue(out.results().containsKey("member"), "member 应生成成功");
        assertFalse(recording.contexts.isEmpty(), "应至少发生一次 LLM 生成调用");
        assertTrue(recording.contexts.stream().anyMatch(c -> c != null && c.contains("品牌前缀")),
                "generationRules 应注入生成 prompt，实际上下文: " + recording.contexts);
    }

    @Test
    void noRulesMeansNoConstraintsInPrompt() throws Exception {
        String ddl = "CREATE TABLE book (\n"
                + "  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '书ID',\n"
                + "  title VARCHAR(100) NOT NULL COMMENT '书名'\n"
                + ");";
        List<TableMeta> tables = new DdlParser().parse(ddl).tables();
        Map<String, Integer> rows = Map.of("book", 3);

        RecordingLlm recording = new RecordingLlm();
        PipelineContext ctx = new PipelineContext(tables, rows, 42L);
        PkGen.execute(ctx, PkGen.Strategy.SEQUENTIAL);
        FkAlloc.execute(ctx);
        ContentGen.GenOutcome out = ContentGen.execute(ctx, recording, props(),
                null, null, null, null, ProjectRules.empty());

        assertTrue(out.results().containsKey("book"));
        System.out.println("DEBUG book contexts = " + recording.contexts);
        // 无规则时上下文不含"业务约束"标题
        assertTrue(recording.contexts.stream().allMatch(c -> c == null || !c.contains("业务约束")),
                "无规则时不应注入约束段: " + recording.contexts);
    }
}
