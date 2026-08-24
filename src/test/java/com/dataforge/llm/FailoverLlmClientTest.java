package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多模型 failover 单测（P8）：主模型 5xx/超时 → 自动切换备模型（PLAN 决策 #12）。
 */
class FailoverLlmClientTest {

    private static final List<ColumnDef> COLS = List.of(
            new ColumnDef("username", "VARCHAR(50)", false, false, false, "用户名", List.of()));

    /** 主模型失败（user 表抛异常模拟 5xx/超时）→ failover 到备模型成功 */
    @Test
    void primaryFailsAndFailoverToBackup() {
        MockLlmClient primary = new MockLlmClient()
                .failOnTable("user", new RuntimeException("主模型 5xx 超时"));
        MockLlmClient backup = new MockLlmClient();
        FailoverLlmClient failover = new FailoverLlmClient(primary, backup);

        List<Map<String, Object>> rows = failover.generateRows("user", COLS, 3, null);

        assertEquals(3, rows.size(), "备模型应成功生成 3 行");
        assertEquals(1, failover.failoverCount(), "主失败应触发 1 次 failover");
    }

    /** 主模型正常 → 用主模型（不 failover） */
    @Test
    void primaryHealthyNoFailover() {
        MockLlmClient primary = new MockLlmClient();
        MockLlmClient backup = new MockLlmClient();
        FailoverLlmClient failover = new FailoverLlmClient(primary, backup);

        failover.generateRows("product", COLS, 5, null);

        assertEquals(0, failover.failoverCount(), "主模型正常不应 failover");
    }

    /** 备模型连续 failover：主始终失败，多次调用都走备 */
    @Test
    void multipleFailoversAccumulate() {
        MockLlmClient primary = new MockLlmClient()
                .failOnTable("user", new RuntimeException("持续失败"));
        MockLlmClient backup = new MockLlmClient();
        FailoverLlmClient failover = new FailoverLlmClient(primary, backup);

        for (int i = 0; i < 3; i++) {
            failover.generateRows("user", COLS, 2, null);
        }
        assertEquals(3, failover.failoverCount(), "3 次调用主均失败应累计 3 次 failover");
    }
}
