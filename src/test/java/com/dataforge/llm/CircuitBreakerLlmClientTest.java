package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 熔断器单测：连续失败达阈值 → OPEN 快速失败（不再调 delegate）；
 * 冷却后半开探测成功 → 恢复 CLOSED。
 */
class CircuitBreakerLlmClientTest {

    private static final List<ColumnDef> COLS = List.of(
            new ColumnDef("username", "VARCHAR(50)", false, false, false, "用户名", List.of()));

    /** 连续失败达阈值 → OPEN，后续快速失败（不再调 delegate） */
    @Test
    void openAfterConsecutiveFailuresThenFastFail() {
        CountingFailingLlm delegate = new CountingFailingLlm();
        CircuitBreakerLlmClient cb = new CircuitBreakerLlmClient(delegate, 2, 60_000);

        assertThrows(RuntimeException.class, () -> cb.generateRows("user", COLS, 1, null));
        assertThrows(RuntimeException.class, () -> cb.generateRows("user", COLS, 1, null));
        assertEquals(2, delegate.count, "两次失败后 delegate 被调 2 次");
        assertEquals("OPEN", cb.state().name(), "连续失败达阈值应 OPEN");

        // OPEN：快速失败，不再调 delegate
        assertThrows(IllegalStateException.class, () -> cb.generateRows("user", COLS, 1, null));
        assertEquals(2, delegate.count, "OPEN 快速失败不应再调 delegate");
    }

    /** 正常调用保持 CLOSED，不误熔断 */
    @Test
    void healthyCallNeverTrips() {
        MockLlmClient delegate = new MockLlmClient();
        CircuitBreakerLlmClient cb = new CircuitBreakerLlmClient(delegate, 3, 60_000);

        for (int i = 0; i < 5; i++) {
            assertEquals(2, cb.generateRows("user", COLS, 2, null).size());
        }
        assertEquals("CLOSED", cb.state().name(), "正常调用保持 CLOSED");
    }

    /** 冷却后半开探测成功 → 恢复 CLOSED */
    @Test
    void recoversAfterCooldown() throws InterruptedException {
        MockLlmClient ok = new MockLlmClient();
        AtomicBoolean shouldFail = new AtomicBoolean(true);
        DataLlm flaky = new DataLlm() {
            @Override
            public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                          int rowCount, String fkContext) {
                if (shouldFail.get()) throw new RuntimeException("boom");
                return ok.generateRows(tableName, llmCols, rowCount, fkContext);
            }

            @Override
            public boolean isMock() {
                return false;
            }
        };
        CircuitBreakerLlmClient cb = new CircuitBreakerLlmClient(flaky, 2, 50);

        assertThrows(RuntimeException.class, () -> cb.generateRows("user", COLS, 1, null));
        assertThrows(RuntimeException.class, () -> cb.generateRows("user", COLS, 1, null));
        assertEquals("OPEN", cb.state().name(), "达阈值应 OPEN");

        // 冷却期内快速失败
        assertThrows(IllegalStateException.class, () -> cb.generateRows("user", COLS, 1, null));

        // 冷却结束 → 半开放行试探 → 服务恢复 → 成功回 CLOSED
        Thread.sleep(80);
        shouldFail.set(false);
        assertEquals(1, cb.generateRows("user", COLS, 1, null).size());
        assertEquals("CLOSED", cb.state().name(), "冷却后半开探测成功应恢复 CLOSED");

        // CLOSED 后正常调用
        assertEquals(2, cb.generateRows("user", COLS, 2, null).size());
    }

    /** 计数 + 恒失败 delegate（验证 OPEN 快速失败不再调 delegate） */
    private static class CountingFailingLlm implements DataLlm {
        int count = 0;

        @Override
        public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                      int rowCount, String fkContext) {
            count++;
            throw new RuntimeException("boom");
        }

        @Override
        public boolean isMock() {
            return false;
        }
    }
}
