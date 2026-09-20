package com.dataforge.orchestration;

import com.dataforge.model.TableMeta;
import org.bsc.langgraph4j.state.AgentState;

import java.util.List;
import java.util.Map;

/**
 * 编排状态：一次生成运行的"流转单据"。
 *
 * <p><b>设计约束</b>（见 docs/orchestration-design.md 第四节）：</p>
 * <ul>
 *   <li>只放<b>轻量、可序列化</b>数据；<b>不放全量生成数据</b>——数据落在 staging 库，
 *       状态里只留"哪个表、多少行、几个错"这类摘要（否则检查点 JSON 会被十万行数据撑爆）</li>
 *   <li><b>不放数据库连接等运行时对象</b>——它们走 {@link org.bsc.langgraph4j.RunnableConfig}
 *       的 metadata（{@link #META_SESSION}），从根上避免被序列化</li>
 * </ul>
 */
public class ForgeState extends AgentState {

    // ---- 入参 ----
    public static final String SCHEMA = "schema";
    public static final String MODE = "mode";
    public static final String ROWS = "rows";
    public static final String CACHE_ENABLED = "cacheEnabled";

    // ---- 分析产出 ----
    public static final String TABLES = "tables";
    public static final String ROW_COUNTS = "rowCounts";
    public static final String RULE_COUNT = "ruleCount";

    // ---- 生成产出 ----
    public static final String ACTUAL_COUNTS = "actualCounts";
    public static final String FAILED_TABLES = "failedTables";
    /** 增量作用域下界（现有数据 MAX(id)+1，只修新增区间；全量为空） */
    public static final String SCOPE_MIN_ID = "scopeMinId";

    // ---- 校验/修正产出 ----
    public static final String ERROR_COUNT = "errorCount";
    public static final String INITIAL_ERRORS = "initialErrors";
    public static final String ITERATION = "iteration";
    public static final String CONVERGED = "converged";
    public static final String ROLLED_BACK = "rolledBack";
    public static final String FIX_COUNT = "fixCount";

    // ---- 报告 ----
    public static final String REPORT = "report";
    public static final String LOG = "log";

    /** 运行时对象在 RunnableConfig.metadata 中的键（DbSession 等，不进状态） */
    public static final String META_SESSION = "session";
    /** RunReport 实例的 metadata 键（运行期对象，不落检查点） */
    public static final String META_REPORT = "runReport";

    public static final String MODE_FULL = "full";
    public static final String MODE_INCREMENTAL = "incremental";

    public ForgeState(Map<String, Object> init) {
        super(init);
    }

    public String schema() { return this.<String>value(SCHEMA, "shop"); }
    public String mode() { return this.<String>value(MODE, MODE_FULL); }
    public boolean incremental() { return MODE_INCREMENTAL.equals(mode()); }

    public Map<String, Integer> rows() { return this.<Map<String, Integer>>value(ROWS, Map.of()); }
    public boolean cacheEnabled() { return this.<Boolean>value(CACHE_ENABLED, false); }

    public List<TableMeta> tables() { return this.<List<TableMeta>>value(TABLES, List.of()); }
    public Map<String, Integer> rowCounts() { return this.<Map<String, Integer>>value(ROW_COUNTS, Map.of()); }
    public int ruleCount() { return this.<Integer>value(RULE_COUNT, 0); }

    public Map<String, Integer> actualCounts() { return this.<Map<String, Integer>>value(ACTUAL_COUNTS, Map.of()); }
    public List<String> failedTables() { return this.<List<String>>value(FAILED_TABLES, List.of()); }
    public Long scopeMinId() { return this.<Long>value(SCOPE_MIN_ID, null); }

    public int errorCount() { return this.<Integer>value(ERROR_COUNT, 0); }
    public int initialErrors() { return this.<Integer>value(INITIAL_ERRORS, 0); }
    public int iteration() { return this.<Integer>value(ITERATION, 0); }
    public boolean converged() { return this.<Boolean>value(CONVERGED, true); }
    public boolean rolledBack() { return this.<Boolean>value(ROLLED_BACK, false); }
    public int fixCount() { return this.<Integer>value(FIX_COUNT, 0); }

    public Map<String, Object> report() { return this.<Map<String, Object>>value(REPORT, Map.of()); }
    public List<String> log() { return this.<List<String>>value(LOG, List.of()); }
}