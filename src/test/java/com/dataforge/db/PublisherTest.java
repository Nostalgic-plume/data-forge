package com.dataforge.db;

import com.dataforge.model.DataRow;
import com.dataforge.model.GeneratedData;
import com.dataforge.model.TableMeta;
import com.dataforge.parser.DdlParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publisher 保留策略单测。
 *
 * <p>关注点全在 <b>REPLACE 的边界</b>：清空目标表不可逆，必须"显式指定策略 + 二次确认"
 * 双条件才生效；缺省与未知策略一律按 KEEP，确保策略名写错不会误清库。</p>
 */
class PublisherTest {

    /** 记录执行过的 SQL（不连真库） */
    private static class RecordingJdbc implements JdbcDataSource {
        final List<String> executed = new ArrayList<>();

        @Override public List<Map<String, Object>> query(String sql, Object... params) { return List.of(); }
        @Override public int execute(String sql, Object... params) { executed.add(sql); return 1; }
        @Override public long maxId(String schema, String table) { return 0; }
        @Override public void transaction(Supplier<Void> work) { work.get(); }
    }

    private static TableMeta orders() throws Exception {
        return new DdlParser()
                .parse("CREATE TABLE orders (id BIGINT PRIMARY KEY, amount DECIMAL(10,2));")
                .tables().get(0);
    }

    private static GeneratedData rows(TableMeta t, int count) {
        List<DataRow> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", (long) i);
            v.put("amount", i * 1.0);
            list.add(new DataRow(v, new LinkedHashMap<>()));
        }
        return new GeneratedData(t.name(), t.columns(), list);
    }

    /** policy 为 null 表示 policies 里不给该表任何策略（走缺省） */
    private static Publisher.Result run(RecordingJdbc jdbc, TableMeta t, String policy, boolean confirmed) {
        Map<String, String> policies = policy == null ? Map.of() : Map.of(t.name(), policy);
        return new Publisher().publish(jdbc, "shop", List.of(t),
                Map.of(t.name(), rows(t, 3)), policies, confirmed);
    }

    @Test
    void REPLACE_先清空再导入() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Publisher.Result r = run(jdbc, orders(), "REPLACE", true);

        assertEquals(1, r.published(), "REPLACE 应计为已发布");
        assertTrue(jdbc.executed.get(0).startsWith("TRUNCATE TABLE"),
                "REPLACE 的第一条语句必须是清空，实际: " + jdbc.executed.get(0));
        assertTrue(jdbc.executed.get(1).startsWith("INSERT INTO"),
                "清空之后才是导入");
        assertTrue(jdbc.executed.get(0).contains("`shop`") && jdbc.executed.get(0).contains("`orders`"),
                "库名表名应反引号包裹");
    }

    @Test
    void APPEND_不清空目标表() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Publisher.Result r = run(jdbc, orders(), "APPEND", true);

        assertEquals(1, r.published());
        assertTrue(jdbc.executed.stream().noneMatch(s -> s.startsWith("TRUNCATE")),
                "APPEND 绝不能清空目标表");
    }

    @Test
    void 未知策略_按KEEP跳过不误清库() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Publisher.Result r = run(jdbc, orders(), "REPLCACE", true);   // 故意拼错

        assertEquals(0, r.published(), "策略名拼错不应产生任何发布动作");
        assertEquals(List.of("orders"), r.skipped());
        assertTrue(jdbc.executed.isEmpty(), "更不该清空——拼错策略是最危险的误用场景");
    }

    @Test
    void 未二次确认_直接拒绝且不动库() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Publisher.Result r = run(jdbc, orders(), "REPLACE", false);

        assertEquals(0, r.published());
        assertTrue(jdbc.executed.isEmpty(), "未确认时一条语句都不该执行");
        assertTrue(r.errors().stream().anyMatch(e -> e.contains("未二次确认")));
    }

    @Test
    void 缺省策略是KEEP() throws Exception {
        RecordingJdbc jdbc = new RecordingJdbc();
        Publisher.Result r = run(jdbc, orders(), null, true);

        assertEquals(0, r.published(), "不给策略时默认 KEEP");
        assertTrue(jdbc.executed.isEmpty());
    }
}
