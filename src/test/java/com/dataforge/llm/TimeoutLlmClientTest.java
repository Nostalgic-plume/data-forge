package com.dataforge.llm;

import com.dataforge.model.ColumnDef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调用超时保护单测：正常调用不超时；慢调用超时抛异常（→ 上层 batch 自适应降批兜底）。
 */
class TimeoutLlmClientTest {

    private static final List<ColumnDef> COLS = List.of(
            new ColumnDef("username", "VARCHAR(50)", false, false, false, "用户名", List.of()));

    /** 正常调用：不超时，返回委托结果 */
    @Test
    void normalCallNoTimeout() {
        MockLlmClient delegate = new MockLlmClient();
        TimeoutLlmClient client = new TimeoutLlmClient(delegate, 5);

        List<Map<String, Object>> rows = client.generateRows("user", COLS, 3, null);

        assertEquals(3, rows.size(), "正常调用应返回委托结果");
    }

    /** 慢调用（3s）配 1s 超时 → 抛超时异常（触发上层降批） */
    @Test
    void slowCallTimesOut() {
        TimeoutLlmClient client = new TimeoutLlmClient(sleepingStub(3_000), 1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> client.generateRows("user", COLS, 1, null));
        assertTrue(ex.getMessage().contains("超时"), "超时应带超时提示: " + ex.getMessage());
    }

    /** 阻塞的委托（模拟 LLM 调用挂死不返回） */
    private static DataLlm sleepingStub(long millis) {
        return new DataLlm() {
            @Override
            public List<Map<String, Object>> generateRows(String tableName, List<ColumnDef> llmCols,
                                                          int rowCount, String fkContext) {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            }

            @Override
            public boolean isMock() {
                return false;
            }
        };
    }
}
