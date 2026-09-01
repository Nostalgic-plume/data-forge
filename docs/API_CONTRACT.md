# DataForge 接口契约（实现级规格）

> 定位：跨模块接口契约，实现代码必须遵守。随实现演化，不是墓碑——发现不合理就改，改后同步此文档。
> 前置：先读 [PLAN.md](./PLAN.md)（设计蓝图），本契约把设计决策落成签名与数据结构。

## 1. 三层 Agent 入口

### 1.1 AnalysisAgent（分析 agent，P6 实现，接口先行）

```java
public interface AnalysisAgent {
    /** 项目路径 + 库名 → project_rules + 行数推荐 + 行构建器。内部子任务并行。 */
    AnalysisResult analyze(AnalysisRequest req);
}
```

```java
// 请求（skill 为可选增强：未勾选走通用清单，领域检测仅做建议加载）
record AnalysisRequest(
    String projectPath,          // 源码目录
    String schemaName,           // 目标库名（MCP 读 information_schema）
    List<String> skills          // 可选：用户勾选的垂直 skill
) {}

// 结果
record AnalysisResult(
    ProjectRules rules,              // project_rules.md 母版
    Map<String, Integer> rowRecommend,  // 推荐行数（LLM 参考值）
    List<BuilderDef> builders,       // 行构建器
    CacheFingerprint fingerprint     // 分析时的指纹快照（判定缓存用）
) {}
```

**FingerprintService（缓存判定）：**

```java
// schema 是【决定性】失效信号；rules 是【用户偏好】信号；git 第一版不做
record CacheFingerprint(
    String schemaFingerprint,   // information_schema 结构 hash（表名+列名+类型+注释+索引+FK）
    String rulesFileHash        // project_rules.md 内容 hash
) {}

enum CacheStatus { HIT, RULES_CHANGED, SCHEMA_CHANGED }

// 判定（加载缓存时重新算当前指纹，与快照对比）：
//   schema 一致 + rules 一致    → HIT（静默复用）
//   schema 一致 + rules 不一致  → RULES_CHANGED（用户改过规则 → 询问：保留修改 / 强制复用缓存）
//   schema 不一致               → SCHEMA_CHANGED（客观失效，复用旧结构必错 → 必须重分析，无复用选项）
// SCHEMA_CHANGED 重分析时：先备份用户修改 → 分析 → merge（同规则保留用户版，规则消失则提示）
```

**用户修改规则的三层防护：**

```
① 试编译：保存时 RuleCompiler 编译 + Druid SQL 语法验证，失败当场拒绝
② Diff + 回滚：对比 agent 原始版展示 diff，一键"恢复为 agent 建议"
③ 试运行：用户修改的规则进小样本试跑，通过率曲线客观反馈（拦语义错误）
```

**触发时机：**

```
配置页打开 → 自动检测缓存状态（轻量，information_schema 秒级）→ 界面状态徽标：
   ✅ 缓存有效（分析时间 / 表数）   ⚠️ 库结构已变化（需重分析）   ⚠️ 规则被修改（保留/覆盖）
开始生成 → 分析是流水线首步：HIT 静默复用 / SCHEMA_CHANGED 自动重分析（无需额外按钮）
强制重分析按钮 → 跳过缓存，主动更新规则
```

**一次运行全流程（整合版）：**

```
输入：AnalysisRequest{ 项目路径, 库名, skills(可选) }
  │
  ▼
① 缓存检查（快速路径）
   配置页打开即自动检测 → 界面状态徽标
   三态判定：HIT 静默复用 / RULES_CHANGED 询问 / SCHEMA_CHANGED 重分析
  │
  ▼
② skill 加载（可选增强）
   用户勾选 > 自动检测建议；未勾选走通用清单
  │
  ▼
③ 子任务并行（虚拟线程）
   表结构 / 枚举状态 / 关联关系 / 列语义(规格体系) / 业务规则
   通过 MCP 读库结构 + 源码读取/搜索
  │
  ▼
④ 合并 + 置信度标注
   5 子任务结果 → project_rules 母版
   每条规则标置信度，低置信度项标记"优先人工确认"
  │
  ▼
⑤ 迭代自检循环 ★（在人工检查点【前】）
   检查遗漏（表/字段/规则）→ 追加"再查 X" → 补查 → 再自检
   直到无遗漏 —— 保证人工确认的是完整版本
  │
  ▼
⑥ 行数推荐 + 行构建器
   按关联推导（user 300 → address ~600、order_item ≈ 订单×2.5）
   提炼规格蓝图（品类 → 规格列表 + 价格系数）
  │
  ▼
⑦ 人工检查点（质量兜底）
   约束清单确认/修改/补充（用户声明 > agent 推断）
   三层防护：① 试编译（语法拒绝）② Diff + 回滚（防误删）③ 试运行（语义反馈）
  │
  ▼
⑧ 缓存落盘
   project_rules.md + CacheFingerprint 快照（schemaFingerprint + rulesFileHash）
   下次进入①走快速路径
```

> **P6 落地（2026-08-14）**：读库结构走 SchemaReader（确定性 JDBC 读 information_schema，
> 替代 MCP fallback；McpClient 接口位置在 §4 预留，未来 LLM 自主读库的工具层）。
> 子任务并行：LLM 规则提炼（DataLlm.extractRules，结构化输出含置信度）+ 确定性行数推荐。
> 产出 project_rules 母版（generationRules 全量 + correctionRules 可执行 AGG_EQUAL + builders），
> CLI `--analyze <schema>` 展示确认清单（低置信度 <0.7 标 ⚠️）+ 落盘 project_rules.json，
> 后续生成/校验/修正经 currentRules() 优先加载分析规则（替代内置默认）。
> 已实现规则类型：AGG_EQUAL / VALUE_RANGE / NON_NULL / FORMAT / STATUS_FLOW（编译执行，Validation V2）；
> ENUM_WEIGHT（提炼展示，权重编译待扩展）。

### 1.2 GenerationAgent（生成 agent，P0 核心）

```java
public interface GenerationAgent {
    /** 表结构 + 规则 + 增量配置 → 各表数据。全量是增量的特例（mode=full）。 */
    GenerationResult generate(GenerationRequest req);
}
```

```java
record GenerationRequest(
    List<TableMeta> tables,          // DDL 解析出的全部表（含 FK/唯一键）
    ProjectRules rules,              // 分析产物（或用户手写）
    Map<String, Integer> rowCounts,  // 每表目标行数（用户设定或推荐值）
    IncrementalConfig incremental,   // 全量模式传 mode=full
    long seed,                       // 确定性种子（规则层随机源，可复现）
    int batchSize,                   // 初始批次（batch 自适应会动态调整）
    int maxConcurrency               // 表内批次并行度（防限流，6-8）
) {}

record GenerationResult(
    Map<String, GeneratedData> data,  // 表名 → 数据（修正前）
    List<String> failedTables,        // 失败表（失败隔离，不 abort 全局）
    long elapsedMs,
    Map<String, Integer> actualCounts, // 每表实际行数
    Map<String, TableStat> tableStats  // 每表生成统计（CLI 汇总/调试）
) {}

// 单表生成统计（P0 实现演化新增）
record TableStat(
    int target, int actual, long elapsedMs,
    int llmBatches,   // LLM 调用批次数
    int downshifts,   // batch 自适应降批次数
    int gapFills,     // 补差次数（补差不重试优化）
    boolean cacheHit  // 生成缓存/断点续跑命中
) {}
```

**PK 生成策略（per-table，软错误定位）：**

```java
// PK 策略是【软错误】：FK 引用不管 PK 什么风格都成立，判错只影响观感
// （该雪花的表用了 1..N 一眼假），不值得复杂自动判定链
enum PkStrategy { SEQUENTIAL, SNOWFLAKE }

// 判定（第一版，从最便宜的信号走）：
//   DDL autoIncrement == true → SEQUENTIAL（自增语义，可靠）
//   DDL 非自增                 → 默认 SNOWFLAKE（现代系统非自增 bigint 多为雪花）
//   判定结果放进【人工检查点】展示（每表 PK 策略）→ 用户一眼看到、错了就改（用户覆盖）

// 起始值：
//   SEQUENTIAL：全量从 1，增量从 MAX(id)+1
//   SNOWFLAKE：雪花算法生成（含查重兜底），增量不与现有冲突

// P6 增强：分析 agent 读 Entity @TableId(type=ASSIGN_ID/AUTO) 做代码级判定，
//          归入列语义提炼（id 列取值模式是列语义的特例），降低人工确认负担
```

**传参边界 + RuleSetters（生成 agent = 文档驱动的执行器）：**

```java
// 生成 agent 读文档 → 只对【有语义价值的规则参数】传参，格式类直接调规则工具
//  传参类：PK 生成类型+初始值、枚举值+权重、价格锚点、规格蓝图
//  格式类：地址/电话/邮箱/日期/图片 → Faker/模板直接生成，不传参
//  硬缺口（分析+修正都覆盖不了）→ 上报人工（HITL）

// RuleSetters（重构定稿 2026-08-22：方式 1 结构化输出，非 agentic 工具调用）
// 定位不变：分析 agent 定参（归属分析阶段），生成 agent 只读参数不自主发明。
// 落地形态：LLM 一次性输出结构化 params（extractRules 已含 weights/min-max 等）
//   → analysis/RuleSetters.apply(plan, rules) 映射到 GenerationPlan 列模式。
// 已落地映射：
//   ENUM_WEIGHT(params.weights) → 列模式 weighted
//   VALUE_RANGE(params.min/max)  → 列模式 valueRange
//   SourceScanner @TableId       → plan pkStrategy（PkGen 逐表消费，fallback 全局）
//   priceAnchor                  → 列模式 anchorPrice（父表基准价 × 系数，PlanBuilder 启发式）
//   skuBlueprint（规格模板）     → 部分（价格联动已参数化；specs 模板留后续增强）
```

**一次运行全流程（整合版，P0 为基础流程）：**

```
输入：GenerationRequest{ 表结构, rules, 行数, 增量配置(可选), seed(可选), batchSize(可选), 并发度(可选) }
  │
  ▼
① 缓存 / 断点恢复（一个机制两个用途）
   样本+参数缓存命中 → 直接产出（跨任务复用）
   本次任务中断 → 读 checkpoint 跳过已完成表（同任务续跑）
  │
  ▼
② PK 骨架（per-table 策略）
   SEQUENTIAL：全量从 1，增量 MAX+1；SNOWFLAKE：雪花生成（含查重）
   判定：DDL 自增→连续，非自增→默认雪花（人工检查点可覆盖）
  │
  ▼
③ FK 分配（幂律权重长尾，Zipf）
   少数父值被引用多，多数少——真实感；引用有效性不受影响
  │
  ▼
④ 拓扑排序 → 生成 DAG 图（该图同时供修正 agent 使用）
  │
  ▼
⑤ 拓扑 DAG 并行调度（虚拟线程池）
   表间：无依赖同层并行；表内：批次并行（6-8 路防限流）
   batch 自适应（截断自动降批）+ 补差（差 ≤2% 接受，不整批重试）
  │
  ▼
⑥ 后处理（行内格式：时间顺序 / 价格比率 / 基础格式）
   —— 只管"行内合理"，跨表一致交给修正 agent
  │
  ▼
⑦ 产出（各表数据 + 血缘标注 + 失败表清单——单表失败不 abort 全局）
  │
  ▼
P1 版在此基础升级（✅ 已落地 2026-08-12）：
  ⑤ 后插入：样本预检（SamplePreflight：行数/NOT NULL/唯一键/FK 引用；失败重生成）
             + 唯一键确定性去重（UniqueDeduper：zhangsan→zhangsan2，替代整表重生成防重试放大）
  ⑥ 前插入：展开器（ExpansionEngine + Expander 接口，规格蓝图×锚点→全量，零 LLM）
            —— LLM 只产 3 张语义源表样本（category/user/product），其余 9 表全规则派生
            —— 落地：Sku/Orders/OrderItem/Address/Cart/UserRole/Shop 7 个 Expander + FixedSamples(role/admin)
  生成恒走两阶段（LLM 语义样本 + 确定性展开器），无纯 LLM fallback
```

### 1.3 CorrectionAgent（修正 agent，P3 核心）

```java
public interface CorrectionAgent {
    /** 数据 + 校验报告 → 修正后数据。内部 ReAct 收敛循环。 */
    CorrectionResult correct(CorrectionRequest req);
}
```

```java
// P3 实现演化补充：tables（修正 SQL/再校验用）+ schemaName（staging 库名）加入请求；
// rolledBack（回归回滚标记）加入结果。
record CorrectionRequest(
    List<TableMeta> tables,               // 表结构（修正 SQL / 再校验）
    Map<String, GeneratedData> data,      // 待修正数据（staging 内存表示，回滚快照）
    ValidationReport initialReport,       // 首轮校验报告
    ProjectRules rules,                   // 后置修正规则节
    String schemaName,                    // 目标 schema（staging = schemaName_staging）
    Long scopeMinId                       // 增量作用域下界（增量时限定，全量为 null）
) {}

record CorrectionResult(
    Map<String, GeneratedData> data,      // 修正后数据（从 staging 读回，含 CORRECTED 血缘）
    ValidationReport finalReport,         // 收敛后报告
    boolean converged,                    // 是否在 maxIterations 内收敛
    boolean rolledBack,                   // 是否发生回归回滚（新错误 → 重建工作区）
    int iterations,
    List<FixCommand> appliedFixes         // 全部修正记录（审计/回滚）
) {}
```

收敛控制：问题清单闭环（新错误=回归=回滚）+ 通过率上升判定 + maxIterations。修正作用域 `WHERE id > scopeMinId`（增量时现有数据只读）。收敛目标：硬错误 ≥98% + 残余写入最终报告。

**修正 SQL 化（修正执行 = 受控 SQL 方法）：**

```java
// 修正执行不靠 LLM 逐桶生成修正值，而是"规则匹配优先 + SQL 批量"（秒级）
// 修正 agent 工具集（受控 SQL 方法，预定义模板 + 参数，非 LLM 自由写 SQL）：
//   check(rule, scope)      → 返回问题行数/列表（先判断有没有问题）
//   apply(sqlTpl, params, scope) → 执行修正（WHERE 限定问题行，只改有问题的）
//   → 再 check 验证（check → apply → check 循环）

// 桶处理分流：
//   规则通道（90%+，零 LLM）：错误类型 → 自动匹配修正 SQL（重算金额/唯一后缀去重/FK 重分配）
//     —— P3 落地：CorrectionSqls 受控模板（amount/unique/fkOrphan）+ 父表 PK 池
//   LLM 通道（极少数）：修正值需语义理解（如 original_price 改成多少，要看 sku_name）
//                     → DataLlm.correctValues(table, columns, samples) 判断值 → 转 UPDATE（保留 id 锚定）
// 分布修正（软问题桶）：SQL 难表达"按权重重分布"，走规则引擎内存重分布（P3 未做，上报残留）

// SQL 化硬前提：修正全程在 staging 库（数据必须落库，非内存）——接受，因定位连库
// 缓解：StagingManager 管生命周期 + 批量导入（一次 INSERT）+ 混合执行器
//      （SQL 主修硬错误 + 内存规则引擎补分布修正）

// P4 流水线修正（表完成事件触发，小表修正不阻塞大表生成）：
//   ContentGen 表完成 → TableCompletedListener → CorrectionPipeline
//   修正任务依赖图（表对粒度，只计算生成集内的 FK 父表 ∪ correctionRules 引用表）
//   每张表完成即落库 → 依赖满足 → 该表校验+修正（虚拟线程并行）
//   跨表规则（AGG_EQUAL 金额）等 ref_table 就绪后重算
//   CLI：--correct --pipeline；回归回滚只恢复请求涉及的表（DELETE+重载快照，不 drop 整个库）
```

## 2. 核心数据契约

### 2.1 TableMeta（表结构元数据，DdlParser/SchemaReader 产出）

```java
record TableMeta(
    String name,
    List<ColumnDef> columns,
    List<ForeignKeyDef> foreignKeys,   // 显式声明 + 隐式推断合并
    List<String> uniqueKeys            // 唯一键列组（如 ["username"] / ["user_id","sku_id"]）
) {}

record ColumnDef(
    String name, String type,          // type: BIGINT/DECIMAL(10,2)/VARCHAR(50)...
    boolean nullable, boolean primaryKey, boolean autoIncrement,
    String comment,                    // COMMENT 是 LLM 语义的主要来源
    List<String> enumValues            // 显式 ENUM 类型的值
) {}

record ForeignKeyDef(
    String column, String refTable, String refColumn
) {}
```

### 2.2 GeneratedData（行数据 + 血缘标注）

```java
record GeneratedData(
    String tableName,
    List<ColumnDef> columns,
    List<DataRow> rows
) {}

record DataRow(
    Map<String, Object> values,
    Map<String, SourceTag> lineage      // 每列来源标注（调试/面试）
) {}

enum SourceTag { RULE, FK, PK, LLM, EXPAND, CORRECTED, SEED }
```

### 2.3 ProjectRules（对应 project_rules.md 母版）

```java
record ProjectRules(
    List<Rule> generationRules,    // 前置约束节（注入生成 prompt）
    List<Rule> correctionRules,    // 后置修正规则节（编译成校验 SQL）
    List<BuilderDef> builders,     // 行构建器（P1 展开器执行）
    int version                    // 缓存/增量版本号
) {}

record Rule(
    String table, String column,
    String type,                   // AGG_EQUAL / ENUM_WEIGHT / VALUE_RANGE / FORMULA / STATUS_FLOW / CUSTOM_SQL ...
    String expression,             // 规则描述（人类可读，喂修正 agent 作 rootCause）
    double confidence,             // 分析 agent 置信度（低置信度优先人工确认）
    Source source,                 // AGENT / USER_CONFIRMED / USER_OVERRIDE
    Map<String, String> params     // P4 结构化参数（AGG_EQUAL: ref_table/sum_col/join_col）
) {}

// CUSTOM_SQL（自定义校验，LLM 产 SQL，过 SqlGate 结构闸门后执行）：
//   params: check_sql（只读 SELECT，表名用 {schema} 占位符，结果必须含目标表 id 列）
//           fix_sql（可选修复 UPDATE：UPDATE {schema}.表 SET ... WHERE id = {id}，id 锚定防全表）
//           fix_column（修正目标列，默认 rule.column()）

// AGG_EQUAL（聚合相等）：target.column = SUM(ref_table.sum_col GROUP BY ref_table.join_col 对 target.id)
//   引擎按 type 编译检查/修正 SQL，业务参数全走 params，不写死表名（P4 通用化）
//   便捷工厂：Rule.aggEqual(table, column, refTable, sumCol, joinCol, expression, source)

record BuilderDef(                 // 行构建器（YAML，展开器执行）
    String table,
    String templateRef,            // 规格蓝图引用（品类 → 规格列表 + 价格系数）
    String anchorColumn            // 锚点列（LLM 定锚点，规则派生变体）
) {}
// 注（通用化后遗留 2026-08-22）：生成实际由 GenerationPlan 驱动，builders 仅作展示
//   （历史 P1 概念），无消费方；列级配置走 plan / generation-plan.json 人工确认。
```

### 2.4 ValidationReport（校验报告，分桶压缩）

```java
record ValidationReport(
    List<Bucket> buckets,          // 分桶（给 LLM 看的就是这个，不看全量）
    int totalErrors, long elapsedMs
) {}

record Bucket(
    String table, String column, String errorType,  // FK_ORPHAN / UNIQUE_CONFLICT / MISMATCH / NULL...
    int count, double ratio,
    List<Map<String, Object>> samples,   // 3 条整行快照（含 id，供 id 锚定决策）
    String rootCause                      // 规则引擎第一层根因推断
) {}
```

### 2.5 FixCommand（修正指令，id 锚定）

```java
record FixCommand(
    String table,
    long id,                       // 目标行（精确落位）
    Map<String, Object> fixes,     // 列级修改（最小干预）
    String reason                  // 修正理由（审计）
) {}
```

### 2.6 IncrementalConfig（增量配置）

```java
record IncrementalConfig(
    String mode,                   // "full" | "incremental"（全量是特例）
    Map<String, Long> baseline,    // 各表现有 MAX(id)（必须从目标库读，不手填）
    List<String> scope,            // 本次新增哪些表
    Map<String, Integer> newRows   // 新增表的目标行数
) {}
```

增量语义：现有池引用（读目标库现有行当池子）+ 起始 ID=MAX+1 + 修正作用域限定 + 校验跨现有。缓存 key 含 baseline，目标库变化即失效。

// P5 落地（2026-08-13，全量是增量的特例）：
//   1. 起始 ID=MAX+1：PipelineContext.startIds → PkGen SEQUENTIAL 起点（追加不冲突）
//   2. 现有池引用：FkAlloc 父表池 = existingPools(现有) ∪ pkSkeletons(新增)
//   3. 跨现有引用：ContentGen 预填 completedData（展开器/LLM prompt 可见现有父表数据）
//   4. 只生成用户指定表：闭包父表 rowCount=0 → 不生成，引用现有
//   5. 业务唯一键续号：展开器按现有最大续（OrdersExpander.order_no）
//   6. 修正作用域：CorrectionRequest.scopeMinId = 各表现有 MAX+1（只动新区间，不误伤现有）
//   7. 缓存 key 含 baseline：runFingerprint 加现有数据特征（目标库变化即失效）
//   8. 跨现有对账：Reconciliation 对 baseline ∪ 新增（现有金额正确行重算不变）

## 3. 校验器 + RuleCompiler

```java
// 校验器：规则引擎，非 LLM。确定性、全量 SQL、可并发。
public interface Validator {
    ValidationReport validate(List<TableMeta> tables, Map<String, GeneratedData> data, ProjectRules rules);
}

// 编译：规则 → 检查 SQL（RuleCompiler 是"生成 SQL 的代码"，必须被单测覆盖）
public interface RuleCompiler {
    /** 通用规则：从 DDL 元数据推导（FK 悬挂 JOIN / 唯一 GROUP BY / 非空 / 格式） */
    String compileMetaCheck(TableMeta table, MetaCheckType type);
    /** 项目规则：从 correctionRules 编译（金额对账 / 状态联动 / 自定义 SQL）
     *  @param tables 全量表结构（可为 null：Web 试编译跳过表/列存在性，只做结构/语法校验） */
    String compileProjectRule(Rule rule, List<TableMeta> tables, String stagingSchema);
}
```

执行通道：JdbcDataSource（确定性 JDBC，MySQL，方言层预留）。分桶在内存做（Detect 全量 SQL → Aggregate 分组）。

### 3.1 Validation V2：规则类型扩展 + 参数校验 + 根因分桶（阶段 A 已落地）

```java
// 项目规则类型（MetaRuleCompiler.compileProjectRule 支持，全参数化不写死业务）：
//   AGG_EQUAL    → params: ref_table / sum_col / join_col（目标列 = Σ引用表 by join）
//   VALUE_RANGE  → params: min / max（数值常量，或引用列/表达式）
//   NON_NULL     → params: default_value（可选，补值；缺省按列类型推导 0 / 'N/A'）
//   FORMAT       → params: regex（列值 NOT REGEXP regex 即问题）
//   STATUS_FLOW  → params: flow（逗号分隔 ≥2 时间列）/ trigger_status（可选）/ trigger_status_col（默认 status）
//   CUSTOM_SQL   → params: check_sql（只读 SELECT，{schema} 占位符，结果含 id）/ fix_sql（可选修复
//                  UPDATE：UPDATE {schema}.表 SET ... WHERE id = {id}，id 锚定）/ fix_column
//                  —— LLM 产 SQL，过 SqlGate（Druid 双模式闸门：语法/SELECT-only/UPDATE-only/危险函数/
//                     表列存在性/id 锚定）后执行；修正侧走 fix_sql 参数化逐行，缺失则上报残留

// 参数结构校验（编译前，错参数拒绝 + 上报，不静默）：
void validateParams(Rule rule, TableMeta table);   // 表/列存在、min≤max、regex/flow 存在

// 根因分桶（Aggregate 阶段）：
record Bucket(..., String rootCause, String rootCauseKey) {}
//   FK_ORPHAN → "FK_PARENT_FAIL:{父表}"（同父表失败的悬挂归一根因）
//   UNIQUE_CONFLICT → "UNIQUE_DUP"
//   AGG_EQUAL → "AMOUNT_MISMATCH"
//   其他 → rule.type()

// 修正 SQL 模板（CorrectionSqls，check + apply 成对，均带 scopeMinId 增量限定）：
//   valueRangeCheckSql/ApplySql（范围钳制 LEAST/GREATEST）
//   nonNullCheckSql/ApplySql（补默认）
//   formatCheckSql（重生成走内存 RuleDataGenerator，apply 逐行 id 锚定）
//   statusFlowCheckSql（apply 保守无——时间线补正需业务假设，桶上报残留）

// Web /compile-rules 试编译：对所有支持类型编译（不再只 AGG_EQUAL）
```

## 4. 连库层

```java
// 读路径：分析 agent 用（P6 实现），LLM 自主调工具
public interface McpClient {
    List<TableMeta> readSchema(String schemaName);   // 走 mysql-mcp
    String readSource(String path);                  // 源码读取/搜索
}

// 确定性 JDBC 通道：校验/修正应用/发布/增量 baseline（P2 实现，MySQL，方言层预留）
public interface JdbcDataSource {
    List<Map<String,Object>> query(String sql, Object... params);
    int execute(String sql, Object... params);       // UPDATE/DELETE（修正应用）
    long maxId(String schema, String table);         // 增量 baseline
    void transaction(Supplier<Void> work);           // 修正事务
}

// staging 工作库生命周期（P3 加 read：修正 agent 的 source of truth 读取，血缘简化标 SEED）
public interface StagingManager {
    String stagingSchema(String schemaName);         // shop → shop_staging
    void create(String schemaName, List<TableMeta> tables);   // 建库建表
    void load(String schemaName, Map<String, GeneratedData> data);
    Map<String, GeneratedData> read(String schemaName, List<TableMeta> tables);
    void drop(String schemaName);                    // 回滚 = 重建
}

// 发布：按保留策略落目标库 + 安全预检
public interface Publisher {
    void publish(StagingData staging, String targetSchema,
                 Map<String, Policy> policies);      // REPLACE/APPEND/KEEP
    void preflight(String targetSchema, Map<String, Policy> policies); // 行数统计 + 二次确认
    void backup(String targetSchema);                // 危险操作前 mysqldump
}
```

## 4.5 对话式询问（HITL，human-in-the-loop）

```java
// agent 执行中遇到需用户输入 → 调 ask_user 工具 → 系统展示 → 用户回答 → 回填继续
record AskUserRequest(
    String question, List<String> options,
    String defaultAnswer, String reason
) {}

// 触发门控：有默认值推默认，只有【有歧义 / 用户偏好影响大 / 需业务知识】才问
// 询问频率设置（用户全局）：ALWAYS（始终问）/ KEY_ONLY（只问关键）/ NEVER（全用默认）
// 应用点：分析 agent（参数偏好）、生成 agent（硬缺口上报）、修正 agent（不收敛上报）
```

## 5. 阶段数据流

```
用户输入（连接 + 项目路径 + skill）
  │
  ▼
AnalysisAgent ──(project_rules + 推荐行数 + 行构建器)──► GenerationAgent
  │ 子任务并行 + MCP 读库/读源码                       │ 管线：PK→FK(幂律)→DAG 并行→后处理
  │ 人工确认检查点（约束清单）                          │ 样本预检 / 断点续跑 / 失败隔离
  │                                                    ▼
  │                                            staging（JdbcDataSource 写）
  │                                                    │
  │                                                    ▼
  │                                          Validator（确定性 SQL 扫描）
  │                                                    │
  │                                            ┌───────┴────────┐
  │                                    收敛？───否──► CorrectionAgent
  │                                            │（ReAct：报告压缩→桶决策→id 锚定应用→再校验）
  │                                            └──是──┘
  │                                                    ▼
  │                                    收敛 + 人工确认报告
  │                                                    ▼
  └──────────────────────────────► Publisher（保留策略 + 预检 + 备份 → 目标库）
```

## 6. 实现顺序提醒（对齐 P0-P8）

- P0：JdbcDataSource 骨架 + 管线（TopologicalSorter/PkGen/FkAlloc/ContentGen）+ 5 优化（补差/缓存/断点/降批/隔离）
- P1：展开器（BuilderDef）+ FK 幂律权重 + 样本预检
- P2：Validator + RuleCompiler（通用+金额规则）+ StagingManager
- P3：CorrectionAgent 收敛循环 + FixCommand 应用
- P4：修正流水线（表完成事件）
- P5：增量（IncrementalConfig + scope 限定 + 跨现有校验）
- P6：AnalysisAgent + MCP 客户端 + skill + 置信度
- P7：软评分 + Web；P8：错误注入 + failover

> 测试纪律：每阶段验收含 mock LLM 单测（参考 real-world-data 的 DataGenerationIntegrationTest）+ 校验器规则测试 + 集成测试。

## 7. P7 落地（软评分 + Web 界面，2026-08-14）

```
// 软评分：与硬校验互补（硬校验查"对不对"，软评分量"像不像"）
validation/Scorer          # KS（数值列 ECDF 距离）+ JS（分类列分布距离）→ 每列/每表/总分 0-1
llm/DataLlm.scoreSamples   # 语义抽样 LLM 打分（0-1；-1 = 不评估）

// Web 界面（--web 启动，覆盖 web-application-type=servlet；CLI 模式仍 none）
web/DataForgeApiController # REST：POST /api/analyze|generate|correct|score + GET /api/history + GET /api/events(SSE)
web/HistoryService         # 历史记录 history/*.json（action/耗时/行数）
src/main/resources/static/index.html  # 静态前端调 API（完整 Vue3 五步向导为后续）
```

## 8. P8 落地（多模型 failover，2026-08-14）

```
llm/FailoverLlmClient  # 主备双链路装饰器：generateRows 主失败（5xx/限流/超时）→ 自动切备
                       #   failoverCount() 累计切换次数（成本/可靠性记录）
                       #   其余通道（extractRules/correctValues/scoreSamples）透传主
装配：primary=主模型客户端（LLM_BASE_URL/LLM_MODEL），backup=备模型（如 qwen DashScope 端点）
错误注入按用户要求移除（不实现）
```
