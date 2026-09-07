# DataForge 通用派生改造 · 计划书（子任务分批 + 验收）

> 配套设计文档：`docs/derive-universal-design.md`（硬约束/契约以该文档为准）。
> 执行原则：**每批可独立验收、可回滚**；纯新增先行，删除/迁移风险后置；全程不破坏现有订单口径。

## 顶层子任务（7 个）

| # | 子任务 | 依赖 | 分批 |
|---|---|---|---|
| T1 | 表达式语言核心 `ExpressionEvaluator` | — | 批 1 |
| T2 | `GenericExpander` expr 列模式（列序 + 求值接入） | T1 | 批 1 |
| T3 | FORMULA 规则接入（分析侧提炼 → 校验 → 落 expr） | T1, T2 | 批 2 |
| T4 | 通用聚合回填器（消费 AGG_EQUAL 规则） | — | 批 3 |
| T5 | Reconciliation 收敛 / 订单域接线 + 删硬编码 | T4 | 批 3 |
| T6 | 迁移回归与口径校验（全量测试绿 + 金额口径一致） | T2, T5 | 批 4 |
| T7 | 文档与答辩口径收尾 | 全部 | 批 4 |

## 分批顺序（按关联性）

```
批1 [纯新增，风险最低]       批2 [分析侧]        批3 [风险集中：回填/删除]     批4 [收尾]
  T1 表达式核心        ──►    T3 FORMULA接入      T4 聚合回填器                T6 全量回归
  T2 GenericExpander expr         (依赖T1/T2) ──► T5 Reconciliation收敛/删除     T7 文档/口径
```

---

## T1 · 表达式语言核心（ExpressionEvaluator）
**做什么**：手写词法+语法解析的最小算术求值器（设计文档 §2 H2/H3/H4）。
- 支持：数字常量、列引用、`+ - * /`、括号、`round(x,n)`。
- 提供 `eval(expr, colDefs, rowValues) -> BigDecimal` 与 `referencedColumns(expr) -> Set<String>`。
- 白名单拒绝：任意函数/方法/字符串/聚合/位运算。
- 显式失败：语法错、未知列、除零、非数值、`round` n 非字面量 → 抛 `ExpressionException`。
- BigDecimal + scale 处理（H3：除法定中间 scale=max(操作数scale,8)，收口按列 scale/默认 2）。

**产出文件**（新增，建议 `com.dataforge.derivation` 包）：
- `derivation/ExpressionEvaluator.java`、`derivation/ExpressionException.java`
**验收**：
- 新单测 `ExpressionEvaluatorTest`：合法（`price*quantity`、`round(a*b,2)`、嵌套括号、小数精度断言 BigDecimal 两位）；非法（`a/0`→抛、`sin(x)`→白名单拒、`"str"`→拒、未知列→抛、括号不匹配→抛、`round(a)` n 缺失→抛）。命令：`mvn -o test -Dtest=ExpressionEvaluatorTest`

## T2 · GenericExpander expr 列模式
**做什么**：让派生表计划里 `mode=expr` 的列被生成。
- 列序：`generate()` 内把 `plan.columns()` 拆成「非 expr」先算、「expr」收尾（H8）；expr 只允许引用非 llm/非 expr 列，违者在**计划装载时预检**抛错。
- expr 求值：取本行已生成列值 → `ExpressionEvaluator.eval` → 写入 values，血缘 EXPAND。
- 错误传播：`ExpressionException` → 包成 `IllegalStateException(table/col/expr)` → 沿用失败表隔离（不写坏数据）。
- `ToolRegistry` 补 `expr` 工具定义（类别=派生/锚点加工，params 说明），供分析侧后续感知。

**改动文件**：`expansion/GenericExpander.java`、`expansion/ToolRegistry.java`
**验收**：
- 新单测 `GenericExpanderExprTest`：构造最小派生表计划（列A=const 3、列B=valueRange、列C expr `A*B`），断言 C=B×3 且血缘 EXPAND、确定性（同 seed 复现）；expr 引用 llm 列 → 计划装载抛错。
- 现有派生表不带 expr 的测试不回归（无 expr 旧计划行为零变化）。

## T3 · FORMULA 规则接入（分析侧）
**做什么**：让 LLM 提炼的规则能产出表达式列。
- `DataLlm.extractRules` 提示词（`llm/PromptBuilder`）支持产出 `type=FORMULA, params.expr`（仅数值列、示例引导：`subtotal = price*quantity`）。
- `RuleSetters`：`FORMULA` → `ColumnPlan.of(col,"expr",{expr})`（仿 ENUM_WEIGHT 分支）；加护栏：FORMULA 命中的列，其 VALUE_RANGE/ENUM_WEIGHT 规则忽略（H8/坑9）。
- `RuleFilter`：FORMULA 放行（非 VALUE_RANGE 类型已放行）；`source=AGENT` 的 FORMULA **不直接落 plan**，需人工确认（走现有 `refineRules` / USER_CONFIRMED）才生效（坑9）。

**改动文件**：`llm/DataLlm.java`（文档注释，可不动实现）、`llm/PromptBuilder.java`、`analysis/RuleSetters.java`、`analysis/RuleFilter.java`（如需）
**验收**：
- `RuleSettersTest` 扩展：FORMULA 规则 → ColumnPlan mode=expr、params.expr 正确；FORMULA 与 VALUE_RANGE 同列互斥。
- 用 `MockLlmClient`（现有）喂一条 FORMULA 规则走分析路径的集成单测：规则能落到计划 expr 列、且 AGENT 未确认不落。

## T4 · 通用聚合回填器（ReconcileEngine）
**做什么**：把生成侧内存跨表聚合从 Reconciliation 硬编码抽成**规则驱动通用回填器**。
- 新增 `derivation/ReconcileEngine.apply(data, aggRules)`：遍历 `correctionRules` 中 `AGG_EQUAL`（ref 表与目标表都在 data 内）→ 内存 Map 聚合 `target.column = Σ(ref_table.sum_col by join_col==target.id)` → 覆盖血缘 CORRECTED；缺 ref 表/列 → warn 跳过（H7）。
- 语义与现 Reconciliation 跨表部分等价，但表名列名全来自规则 params（任意表/列可用）。

**产出文件**：新增 `derivation/ReconcileEngine.java`
**验收**：
- 新单测 `ReconcileEngineTest`：构造两个内存表 A/B（B.ref_id→A.id, sum_col），规则 `AGG_EQUAL(A,total,B,val,ref_id)` → 断言 A.total 为 Σ 且血缘 CORRECTED；ref 表不在 data → 跳过不抛；规则 params 不全 → warn 跳过。

## T5 · Reconciliation 收敛 / 订单域接线
**做什么**：迁移订单金额到通用回填并删除硬编码。
- 拆分现 Reconciliation 两职责：**行内 subtotal**（属 expr 域，本批先保留 Reconciliation 行内段或迁 T2 expr 都行，二选一并写清）；**跨表 total/actual** → 交给 T4 用默认 `AGG_EQUAL` 规则（`ProjectRules.withDefaultCorrectionRules()` 已有现成）驱动回填。
- 生成管线（`DefaultGenerationAgent` 全量/增量两路径）把 `Reconciliation.reconcile(data)` 替换为 `ReconcileEngine.apply(data, rules.correctionRules() AGG_EQUAL)`。
- 迁移完成且口径一致后：删除 Reconciliation 的跨表硬编码（保留行内部分则更名注释，否则整类收敛），OrdersExpander 的 total/actual 占位 0 逻辑删除。

**改动文件**：`pipeline/Reconciliation.java`、`agent/DefaultGenerationAgent.java`、`expansion/OrdersExpander.java`、`model/ProjectRules.java`（如需补充 actual 相关规则参数）
**验收**：
- 本批暂不改断言，跑 T6 前先本地冒烟：生成最小订单域，日志显示回填走 ReconcileEngine 且数值与重构前一致。

## T6 · 迁移回归与口径校验
**做什么**：确保整条链路不回归、金额口径与重构前完全一致。
- 跑全量现有测试：`mvn -o test`（`ExpansionSmokeTest` / `PipelineSmokeTest` / `IncrementalSmokeTest` 等）。
- 若 smoke 依赖本地 MySQL/staging，标注启动前置并在本地库跑一遍 CLI 冒烟（`orders`+`order_item` 生成），核对 total/actual/subtotal 两位小数口径。

**验收**：
- 全量单测绿，且任何 `total_amount/actual_amount/subtotal` 断言不因重构改变。
- 订单输出数值与迁移前一致（逐行 diff 关键金额列无差异）。
- 失败标准：金额两位小数/取整出现任何差异 → 回滚本批（T4/T5 各自可单独 revert）。

## T7 · 文档与答辩口径收尾
**做什么**：让新能力变成能讲清的项目资产。
- 更新 `docs/API_CONTRACT.md`（如有）与 README：新增术语 `expr 表达式派生`、`FORMULA 规则`、`通用聚合回填（AGG_EQUAL 一份规则两处复用）`、Reconciliation 去向。
- 更新 `tools/SystemToolRegistry.derive` 描述指向新引擎；补一段「派生工具 = 无语义纯计算器，怎么算由 LLM 规则给出」的答辩口径（面试可背）。

**验收**：文档 grep 无遗留 "Reconciliation 硬编码/写死 orders" 描述与实现不符处；SystemToolRegistry 描述与实际实现一致。

---

## 总验收（全部完成后一次性核对）
1. `mvn -o test` 全绿（含新增 3 组单测）。
2. 订单金额口径 diff 为 0。
3. 代码中 `orders/order_item/total_amount` 业务字面量仅存在于测试夹具与规则参数默认值，不在引擎逻辑。
4. 简历口径成立：「派生 = 规则驱动的 expr + 通用聚合回填，LLM 只提议怎么算，工具无语义、代码执行」。

## 风险与回滚
- 风险集中在批 3（删除 Reconciliation / 改订单占位）：T4 与 T5 各自独立提交，出问题单独 revert，不牵连批 1/2。
- FORMULA 若分析侧提炼不稳：T3 只加「人工确认」门槛，不稳也落不进 plan，安全。
- expr 若影响旧派生：无 expr 旧计划零变化（H9），批 1/2 可随时安全停。

## 执行顺序备注（对实现者的要求）
- 严格按 T1→T7，每完成一个子任务**先过该子任务验收再进入下一个**。
- 每个改动保持「小而可审」；BigDecimal/列序/血缘/失败路径以设计文档 §2/§5/§6 为准。
