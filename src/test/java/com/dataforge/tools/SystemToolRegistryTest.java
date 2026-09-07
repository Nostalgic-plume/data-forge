package com.dataforge.tools;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 系统工具目录（声明层）测试：纯内存，不连库。 */
class SystemToolRegistryTest {

    @Test
    void 注册了核心三工具_校验修正派生() {
        List<SystemTool> tools = SystemToolRegistry.tools();
        assertEquals(3, tools.size());
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("validate")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("fix")));
        assertTrue(tools.stream().anyMatch(t -> t.name().equals("derive")));
    }

    @Test
    void 每个工具字段契约完整_且指向确定性实现() {
        for (SystemTool t : SystemToolRegistry.tools()) {
            assertNotNull(t.category(), "工具类别缺失: " + t.name());
            assertFalse(t.description().isBlank(), "工具描述缺失: " + t.name());
            assertFalse(t.paramsSchema().isBlank(), "工具参数契约缺失: " + t.name());
            assertFalse(t.returns().isBlank(), "工具返回契约缺失: " + t.name());
            assertNotNull(t.impl(), "底层实现声明缺失: " + t.name());
        }
    }

    @Test
    void 按名查找与防编造() {
        assertNotNull(SystemToolRegistry.find("validate"));
        assertTrue(SystemToolRegistry.isKnown("fix"));
        assertNull(SystemToolRegistry.find("drop_table"));   // 未注册工具名 → null（决策校验拦截）
        assertFalse(SystemToolRegistry.isKnown("不存在的工具"));
    }

    @Test
    void 目录文本可生成_含三工具名() {
        String catalog = SystemToolRegistry.catalogText();
        assertTrue(catalog.contains("validate"));
        assertTrue(catalog.contains("fix"));
        assertTrue(catalog.contains("derive"));
    }
}
