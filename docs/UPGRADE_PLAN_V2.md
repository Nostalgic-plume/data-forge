# DataForge 校验体系升级方案（Validation V2）+ 生成质量强化

> 状态：**可执行方案**（2026-08-23）｜来源：多轮架构评审讨论
> 前置：先读 [PLAN.md](./PLAN.md)（设计蓝图）与 [API_CONTRACT.md](./API_CONTRACT.md)（接口契约）。
>
> **文档状态说明**：
> - **PLAN.md 是设计意图基线**（核心决策 29 项仍有效，如信任模型红线、两阶段生成、staging 先行），不随实现频繁改
> - **API_CONTRACT.md 是随实现演化的活文档**，已标注 P0-P8 落地情况；本次升级的新接口契约（见 §4）需补进 API_CONTRACT.md
> - 两者部分内容已被代码实现覆盖，**涉及实现细节一律以代码为准**

## 1. 背景与现状问题

现状校验器只有 3 类检查，存在四个真实问题：

| 问题 | 现状 | 影响 |
|---|---|---|
| **能力集天花板** | `MetaRuleCompiler.compileProjectRule` 只编译 `AGG_EQUAL`；STATUS_FLOW/VALUE_RANGE 分析 agent 提炼了但引擎编译不了 | 状态联动、范围等约束无法校验/修正，带病落库 |
| **关联问题散桶** | 按 (table,column,errorType) 分桶，`rootCause` 只是字符串标注非分组 | "父表失败"导致 FK+金额同根因散落多桶，无法批量处理 |
| **写死业务名** | `Reconciliation` 用列名探测（total_amount/order_id/subtotal） | 图书借阅等新域对账失效，通用性弱 |
| **语义盲区** | 无任何语义层校验 | "状态联动不合理""关联语义不匹配"SQL 表达不了，无人发现 |

## 2. 升级目标

1. 校验器从"固定能力集"升级为**可扩展通用规则模式**（规则类型 1 → 6 个）
2. **根因分桶**——同根因批量处理
3. **分层校验**——L1 确定性 SQL 裁判（收敛依据）+ L2 LLM 语义哨兵（不参与收敛）
4. **通用性修正**——Reconciliation 参数化 + 规则类型通用模式
5. 全程守住信任模型红线：**LLM 只提炼规则/表达/定值，判定和执行永远确定性代码**

## 3. 总体架构（分层校验）

```
┌────────────────────────────────────────────────────────┐
│ L1 确定性 SQL 裁判（收敛依据，判定权归代码）                │
│   ├─ 结构规则：DDL 自动推导（FK / 唯一 / 非空 / 格式）      │
│   ├─ 项目规则：分析 agent 提炼参数 → 编译执行              │
│   │   （AGG_EQUAL / VALUE_RANGE / STATUS_FLOW）          │
│   └─ 流程：编译 → JDBC 全量扫描 → 分桶(errorType+根因)     │
│         → ValidationReport → 修正 agent（totalErrors==0）│
├────────────────────────────────────────────────────────┤
│ L2 LLM 语义哨兵（不参与收敛，判定权仍归代码）               │
│   ├─ 形态A 被动抽样：抽 N 行 → LLM 判断语义合理性 → ⚠️报告  │
│   └─ 形态B 主动定位：LLM 经 MCP 自主写校验 SQL             │
│        → 写-验-改循环（Druid 结构闸门 + 代码结果复核）      │
│        → 问题清单 → 上报人工 / 提炼规则                    │
├────────────────────────────────────────────────────────┤
│ 规则闭环：L2 发现新规则 → 提炼成结构化规则 → 编译进 L1       │
│          → 以后 L1 确定性全量盯住（LLM 发现为主、代码执行兜底）│
└────────────────────────────────────────────────────────┘
```

## 4. 接口契约细化（可执行依据）

### 4.1 规则类型参数结构（Rule.params）

| type | params 字段 | 说明 | 阶段 |
|---|---|---|---|
| `AGG_EQUAL` | `ref_table` / `sum_col` / `join_col` | 目标列 = Σ引用表 by join（已有） | A |
| `VALUE_RANGE` | `min` / `max` | 数值常量边界（表达式归阶段 C） | A |
| `NON_NULL` | — | NOT NULL 列自动推导，无参数 | A |
| `FORMAT` | `type` 或 `regex` | type ∈ phone/email/url/idcard；或直接给正则 | A |
| `STATUS_FLOW` | `flow`（逗号分隔时间列）/ `trigger_status` | 触发状态下时间列严格递增 | A |
| `ENUM_WEIGHT` | `weights` | 权重分布（已有，仅展示） | 待编译 |

### 4.2 新增数据模型

```java
// L2 被动抽样问题
record SemanticIssue(String table, Long rowId, String column, String description, double confidence) {}

// L2 主动定位问题
record SqlProbeIssue(String table, Long rowId, String description, String sql, double confidence) {}

// Bucket 增加 rootCauseKey（规范化根因，同根因合并）
record Bucket(String table, String column, String errorType, int count, double ratio,
              List<Map<String,Object>> samples, String rootCause, String rootCauseKey) {}
```

### 4.3 rootCauseKey 规范

- 格式：`{ROOT_CAUSE_TYPE}:{目标}`，如 `FK_PARENT_FAIL:product`、`AMOUNT_MISMATCH`、`UNIQUE_DUP`、`RANGE_VIOLATION`
- 生成：`SqlValidator` Aggregate 阶段按错误类型 + 上下文推导（FK 悬挂 → 记录父表名）
- 作用：`CorrectionEngine` 同 `rootCauseKey` 桶合并为一批，一次决策批量修正

### 4.4 新增/修改接口签名

```java
// ── L1：RuleCompiler 扩展（MetaRuleCompiler 实现）──
// compileProjectRule 新增 case + 统一参数结构校验：
private void validateParams(Rule rule, TableMeta table);   // 编译前调用，错参数 throw + 上报

// ── L2 被动抽样（阶段 B）──
List<SemanticIssue> inspectSemantics(String tableName, List<Map<String,Object>> samples);  // DataLlm 新方法

// ── L2 主动定位（阶段 C）──
class SqlProbeExecutor {   // 新增，含 McpClient（落地 P6 预留接口）
    List<SqlProbeIssue> probe(List<TableMeta> tables, String stagingSchema, int maxIterations);
}
```

## 5. 完整 SQL 模板（每个新类型 check/apply 成对）

> 以下模板为设计稿，表名/列名均为参数占位，实现时按 `CorrectionSqls` 现有模式（`q()` 引号包裹 + `scopeMinId` 限定）落地。

### 5.1 VALUE_RANGE（范围钳制）

```sql
-- check：col 超出 [min,max]
SELECT t.`id` FROM `{staging}`.`{table}` t
WHERE t.`{col}` < {min} OR t.`{col}` > {max};

-- apply：范围钳制到边界
UPDATE `{staging}`.`{table}`
SET `{col}` = LEAST(GREATEST(`{col}`, {min}), {max})
WHERE `{col}` < {min} OR `{col}` > {max};
```

### 5.2 NON_NULL（非空补默认）

```sql
-- check
SELECT `id` FROM `{staging}`.`{table}` WHERE `{col}` IS NULL;

-- apply：补类型默认值（params.default_value 或类型默认：数值 0 / 字符串 'N/A'）
UPDATE `{staging}`.`{table}` SET `{col}` = {default_value} WHERE `{col}` IS NULL;
```

### 5.3 FORMAT（格式重生成）

```sql
-- check（phone 示例）
SELECT `id` FROM `{staging}`.`{table}` WHERE `{col}` NOT REGEXP '^1[3-9][0-9]{9}$';

-- apply：走规则通道内存重生成（复用 RuleDataGenerator），SQL 难表达 → 逐行 UPDATE
UPDATE `{staging}`.`{table}` SET `{col}` = ? WHERE `id` = ?;  -- 值由 RuleDataGenerator 生成
```

### 5.4 STATUS_FLOW（状态联动时间线）

```sql
-- check（flow=pay_time,deliver_time,receive_time，trigger_status=4）
SELECT `id` FROM `{staging}`.`{table}`
WHERE `{trigger_status_col}` = {trigger_status}
  AND (pay_time IS NULL OR deliver_time IS NULL OR receive_time IS NULL
       OR pay_time >= deliver_time OR deliver_time >= receive_time);

-- apply：保守补正——仅当可确定性补正时执行，否则上报残留（防引入新错）
UPDATE `{staging}`.`{table}`
SET `deliver_time` = ADDTIME(`pay_time`, '01:00:00')
WHERE `{trigger_status_col}` = {trigger_status}
  AND `deliver_time` IS NOT NULL AND `pay_time` IS NOT NULL
  AND `deliver_time` <= `pay_time`;
-- 注意：'+1h' 是业务假设；无法确定合理增量 → 该桶上报残留（CorrectionEngine 收敛控制兜底）
```

## 6. 任务步骤（含依赖标注）

### 阶段 A（本次核心：L1 扩展 + 根因分桶 + rule 优先）

| 步骤 | 内容 | 文件 | 复杂度 | 依赖 |
|---|---|---|---|---|
| A1 | `classifyLlmColumns` 显式 rule 优先 | `pipeline/ContentGen.java` | 低 | 无（独立） |
| A2 | `MetaRuleCompiler` 编译 VALUE_RANGE/NON_NULL/FORMAT | `validation/MetaRuleCompiler.java` | 低 | 无（独立） |
| A3 | `MetaRuleCompiler` 编译 STATUS_FLOW + `validateParams` 参数结构校验 | 同上 | 中 | A2 |
| A4 | `CorrectionSqls` 补 4 类 check/apply 模板 | `correction/CorrectionSqls.java` | 中 | A2、A3 |
| A5 | `CorrectionEngine.planOne` 桶分派扩展 | `correction/CorrectionEngine.java` | 低 | A4 |
| A6 | 分析 agent 与引擎能力同步（extractRules prompt + Mock 启发式） | `llm/RealLlmClient.java`、`llm/MockLlmClient.java` | 低 | A2、A3 |
| A7 | 根因分桶（Bucket+rootCauseKey + Aggregate 分组 + 桶合并） | `validation/SqlValidator.java`、`model/ValidationReport.java`、`correction/CorrectionEngine.java` | 中 | 无（独立，建议 A4 后） |

**推荐执行顺序**：A1 → A2 → A3 → A4 → A5 → A6 → A7（A1/A7 可并行）。

### 阶段 B（后续：L2 语义哨兵 + 通用性修正）

| 步骤 | 内容 | 文件 | 复杂度 |
|---|---|---|---|
| B1 | `DataLlm.inspectSemantics`（L2 被动抽样）+ `SemanticIssue` | `llm/*`、`model/SemanticIssue.java` | 中 |
| B2 | `Reconciliation` 参数化（消除列名探测） | `pipeline/Reconciliation.java` | 中 |
| B3 | 语义问题报告通道（⚠️ 不进收敛，上报人工/提炼规则） | `validation/`、`web/` | 中 |

### 阶段 C（远期：L2 主动定位 + 声明式校验）

| 步骤 | 内容 | 文件 | 复杂度 |
|---|---|---|---|
| C1 | `SqlProbeExecutor`（LLM 写校验 SQL + 写-验-改循环） | 新增，落地 `McpClient` | 高 |
| C2 | 声明式校验表达式（`{col, op, ref}` → 解释执行） | 新增 `validation/declarative/` | 高 |

## 7. 影响面分析

| 现有路径 | 影响 | 处理 |
|---|---|---|
| `--correct` | `CorrectionEngine.planOne` 加 case——**只增不改** | 兼容 |
| `--pipeline` | `CorrectionPipeline` 调 `CorrectionEngine`，接口不变 | 兼容 |
| `--incremental` | 新 SQL 模板带上 `scopeMinId`（现有模式） | 兼容 |
| Web 层 `DataForgeApiController` | 展示 `ValidationReport`，`rootCauseKey` 加字段 | 需同步序列化；检查 history/*.json 兼容 |
| `Scorer`（软评分） | 与 L2 语义互补：Scorer 全量分布、L2 抽样语义 | 无冲突，共用 DataLlm 但不同方法 |
| 分析 agent | `extractRules` prompt 更新 | 分析链路微调，`AnalysisAgentTest` 需回归 |

## 8. 测试计划

### 8.1 新增测试类

| 测试类 | 覆盖 |
|---|---|
| `validation/MetaRuleCompilerV2Test` | VALUE_RANGE/NON_NULL/FORMAT/STATUS_FLOW 编译正确性 + `validateParams` 拦截错参数 |
| `correction/CorrectionSqlsV2Test` | 4 类 check/apply SQL 正确性（含 scopeMinId 限定） |
| `correction/CorrectionEngineV2Test` | 新类型桶决策 + 收敛（同批同行走两桶不覆盖） |
| `validation/RootCauseBucketTest` | 根因分桶：同 rootCauseKey 合并、父表失败→FK+金额同批 |
| `llm/LlmSemanticInspectorTest`（阶段 B） | L2 mock 确定性产出 SemanticIssue |
| `correction/SqlProbeExecutorTest`（阶段 C） | 写-验-改：mock LLM 产错 SQL → Druid 拦截打回 → 重写成功 |

### 8.2 新域验证（图书借阅，阶段 A 验收）

- **DDL**：`docs/data-gen/lib_schema.sql`（member/book/borrow，borrow 含 `status` + `borrow_date/return_date` 时间线）
- **命令**：
  ```bash
  # 1. 分析产出规则（STATUS_FLOW/VALUE_RANGE 应被提炼 + 编译执行）
  java -jar target/dataforge.jar --mock-llm --analyze lib --ddl docs/data-gen/lib_schema.sql
  # 2. 配置驱动生成 + 校验修正
  java -jar target/dataforge.jar --mock-llm --ddl docs/data-gen/lib_schema.sql \
      --rows member=20,book=30,borrow=40 --staging lib --correct
  ```
- **通过标准**：`--analyze` 显示 STATUS_FLOW/VALUE_RANGE 规则且不再 throw；生成 FK=0；`--correct` 校验报告出现时间线桶且修正收敛；全量测试回归通过

## 9. 硬约束

1. **信任模型红线**：LLM 只提炼规则/写声明/定值，判定和执行永远确定性代码
2. **收敛判定唯一标准**：L1 的 `totalErrors == 0`（L2 不进收敛循环）
3. **参数结构校验**：编译前对照 TableMeta 校验，错参数拒绝 + 上报
4. **引擎不写死业务**：表名/列名/聚合列全走 params（新类型照 AGG_EQUAL 参数化做）
5. **通用模式原则**：只支持"通用约束模式"（聚合相等/范围/时间线/非空/格式），业务经 params 注入
6. **mock 可复现**：新校验 SQL、根因分组、L2 mock 输出确定性可断言

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| VALUE_RANGE 表达式参数编译复杂 | 先支持数值常量，表达式归阶段 C 声明式 |
| STATUS_FLOW 修正补正引入新错 | 能确定的补正（如时间列重排），不能确定的上报残留（防回归） |
| STATUS_FLOW 触发状态未知 | `trigger_status` 参数显式声明；未声明时默认校验时间列递增不限定状态 |
| L2 抽样 LLM 误报 | 不进收敛，只上报人工，误报不污染数据 |
| LLM 写校验 SQL 逻辑错（join/条件） | 结果复核（编译 SQL 复抽）客观拦截 + 打回重写 |
| 根因判定不准 | rootCauseKey 规范化 + 人工确认清单 |
| Bucket 加字段破坏 history JSON 兼容 | 检查反序列化兼容，不兼容则重建报告 |
| Web 层序列化 rootCauseKey 遗漏 | 影响面分析已列，改动时同步 DataForgeApiController |

## 11. 验收清单（可量化）

**阶段 A**：
1. `MetaRuleCompilerV2Test` 全过（4 类编译 + 参数拦截）
2. `--analyze shop` 提炼的 VALUE_RANGE/STATUS_FLOW 编译执行（不再 throw）
3. 图书借阅域（§8.2）STATUS_FLOW/VALUE_RANGE 校验生效 + 修正收敛，FK=0
4. `RootCauseBucketTest`：同根因合并批量修正
5. `ContentGen` rule 优先重构，**59 用例回归全过**

**阶段 B**：
6. `LlmSemanticInspectorTest`：L2 mock 确定性产出语义报告（不进收敛、不阻塞）
7. `Reconciliation` 参数化后图书借阅域对账生效

**阶段 C**：
8. `SqlProbeExecutorTest`：mock LLM 产错 SQL → Druid 拦截打回 → 重写成功
9. 逻辑错 SQL → 结果复核拦截打回

## 12. 步骤依赖与实施顺序

```
阶段 A：A1 → A2 → A3 → A4 → A5 → A6 → A7（A1/A7 可并行）
        每步 mvn test 回归；A2/A3 是后续的基座，先做
阶段 B：B1 → B2 → B3（相对独立）
阶段 C：C1 → C2（C1 需 MCP 接入，风险最高，最后）
```

**风险排序**：STATUS_FLOW（A3/A4）最复杂 → 建议先做 A2（简单类型）验证流程，再做 A3。

## 13. 关键设计决策（多轮评审沉淀）

1. **校验 SQL 来源**：结构规则（代码从 DDL 推导）+ 项目规则（分析 agent 提炼参数 → `MetaRuleCompiler` 代码编译）。**LLM 只产规则参数，SQL 永远是代码编译的受控模板**
2. **修正 SQL 来源**：`CorrectionSqls` 手写受控模板（check+apply 成对）；LLM 只在 LLM 通道提供"修正值"（`correctValues`），执行转受控 `UPDATE ... WHERE id=?`
3. **"LLM 写校验 SQL"必须配受控通道**：Druid 结构闸门（语法/表名列名/只读，出错显式可捕获）+ 代码结果复核（拦业务逻辑错）+ 人工确认（高影响）——LLM 有表达自由，判定归代码
4. **信任模型红线的校验器读法**："LLM 发现规则为主、代码执行规则为兜底"——L2 发现新规则 → 提炼 → 编译进 L1 → 确定性盯住
5. **MCP 能读影子库**（连同一 MySQL + `schema_staging` 前缀），但必须只读账号锁死（防 LLM 越权写）
6. **SQL 校验有表达局限（百分百存在）**：确定性层管住能判的（收敛根基）+ 表达层补管不到的（发现+报告）+ 闭环收缩盲区 + 残余始终报告——目标不是"无盲区"，是"盲区可控、残余可见、持续扩张"
7. **分包原则**：按职责域内聚（llm/validation/correction 各含自己的 SQL/prompt），**不按技术类型堆包**（prompt 包/SQL 包是反模式）
8. **Druid 定位**：语法/结构闸门（可靠、出错显式），不是语义裁判（管不了业务逻辑）
9. **L2 目的**：不参与收敛（LLM 说"没问题"不是证明），参与"发现问题 + 让问题规则化"——它是校验范围持续扩张的引擎

## 14. 工具借鉴记录

| 工具 | 借鉴点 | 不引入原因 |
|---|---|---|
| Great Expectations | expectation 类型库（非空/唯一/范围/集合/正则）→ 补全 L1 规则类型 | Python 生态；无业务规则提炼；无修正闭环 |
| SDMetrics | 业务规则违规检测报告 → 校验报告设计 | Python 生态；需真实数据样本 |
| Soda Core | 检查声明→扫描→报告三层 → 与 RuleCompiler→SqlValidator→ValidationReport 同构 | Python 生态；固定 check 无 LLM 发现机制 |

> 结论：核心能力（业务规则提炼 + 校验修正闭环）现成工具没有，自研正确；借鉴的是**规则类型库设计**和**校验报告形态**。

## 15. 死代码与兼容

- **阶段 A**：纯扩展，无死代码；`compileProjectRule` 的 `throw` 分支被具体实现替换
- **阶段 B**：`Reconciliation` 参数化后，旧的列名探测分支删除（grep 确认无消费方）
- **兼容**：`Bucket` 加 `rootCauseKey` → 检查 history/*.json 反序列化兼容；`DataForgeApiController` 序列化同步

## 16. 文档同步清单

- [ ] `API_CONTRACT.md`：补 §4 新规则类型参数、`SemanticIssue`/`SqlProbeIssue`/`rootCauseKey`、`inspectSemantics`/`SqlProbeExecutor` 签名
- [ ] `CLAUDE.md`：现状与下一步记录阶段 A/B/C
- [ ] `INTERVIEW.md`：可选，补充"校验体系分层"面试叙事
- [ ] 新建 `docs/data-gen/lib_schema.sql`：图书借阅新域验证 DDL
