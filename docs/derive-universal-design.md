# DataForge 通用派生改造 · 设计文档（硬约束 / 契约 / 坑）

> 任务：把「派生/金额计算」从分散硬编码（OrdersExpander + Reconciliation）收敛为**规则驱动的通用派生**，遵守你的信任模型：**工具 = 无语义纯计算器；怎么算/约束 = LLM（分析 Agent）提议 + 代码编译校验；执行 = 代码**。
> 配套计划书：`docs/derive-universal-plan.md`。本文档为**硬约束与契约**，实现以本文档为准。

---

## 1. 目标与范围

### 1.1 目标
1. **行内派生表达式（expr）**：`order_item.subtotal = round(price * quantity, 2)` 这类「一列由同表其它列算出」→ 由配置表达式驱动，脱离手写。
2. **跨表反向聚合派生**：`orders.total_amount = Σ(order_item.subtotal GROUP BY order_id)` → 由 `AGG_EQUAL` 规则驱动**生成后回填**（一份规则两处复用：生成回填 + 修正复核）。
3. **分析 Agent 产规则**：LLM 提炼 `FORMULA`(expr) / `AGG_EQUAL`(聚合) 规则 → 代码编译校验 → 落到 `GenerationPlan.ColumnPlan` / 回填执行。
4. **迁移订单域并剪尾巴**：`OrdersExpander` 不再写金额占位、`Reconciliation` 硬编码删除，口径不变。

### 1.2 明确不做（防范围蔓延）
- ❌ 不引入通用 SQL/脚本表达式执行能力（只做白名单算术表达式 + 结构化聚合参数）。
- ❌ 不做字符串拼接/非数值列的表达式派生（expr 仅数值列）。
- ❌ 不碰 PK/FK/唯一键生成、不碰 `CUSTOM_SQL`、不重写修正闭环。
- ❌ 分析侧 LLM 的表达式**只提议、不执行**——所有表达式必须过编译与白名单校验。

---

## 2. 硬约束（不可违反，违反视为设计错误）

### H1 工具无语义
`expr` 求值器 / 聚合回填器**不感知业务**（不知道"这是钱/这是订单"），只按入参（表达式 / `AGG_EQUAL` params）计算。任何业务语义只允许出现在**规则**（LLM/人工给出）里。

### H2 表达式白名单（安全）
`expr` 语法仅允许：
- 数字常量、十进制小数
- **列引用**：本表已生成数值列（标识符）
- 运算符 `+ - * /`、括号 `( )`
- 单函数 `round(x, n)`（n 为字面量整数，1~6）
**禁止**：任意函数调用、方法调用、字符串字面量、布尔/逻辑运算符、`sum/max/min` 等聚合进 expr（聚合走 AGG_EQUAL）、位运算。实现为**手写词法+语法解析**（不 `eval`、不反射、不正则拼 SQL）。

### H3 精度与取整
- 所有数值运算用 `BigDecimal`，禁止 double 累积金额。
- `round(x, n)` 的 n 未给时取列 `scale`（DECIMAL(M,D) 的 D；非 DECIMAL 默认 2）。中间除法默认 `MathContext`/`RoundingMode.HALF_UP`，scale 取 **max(被操作数 scale, 8)** 防过早截断，最终按列 scale 收口。

### H4 显式失败，不静默占位（沿用现有铁律）
以下情况抛 `ExpressionException`（向上传导 → 表进 failedTables 隔离），**绝不**静默填 0/ null：
- 语法错误 / 未知 token / 括号不匹配
- 引用不存在的列 / 引用 `mode=llm` 的列（该列值生成时为 null，不可算）
- 除数为零（`x/0`，含整型/十进制零）
- 列值非数值（无法转 BigDecimal）
- expr 之间存在循环依赖（环）

### H5 血缘
expr 列生成值标 `SourceTag.EXPAND`；聚合回填覆盖值标 `SourceTag.CORRECTED`（与现 Reconciliation 一致）。

### H6 确定性
expr 求值**纯函数**：相同表达式 + 相同列值 → 相同结果，不得引入无 seed 随机。随机只允许出现在参数层（权重/系数，现状已如此）。

### H7 聚合回填只认规则，不猜列
生成后回填**只**消费 `correctionRules` 中 `type=AGG_EQUAL` 且表在生成集内的规则；没有规则就不回填、不按列名猜（`Reconciliation` 现「列名探测」逻辑仅作迁移过渡，迁移完成后删除）。

### H8 生成侧列序
同一派生表内：`mode=llm` 与普通确定性列先生成，`expr` 列最后生成，且 expr 只引用 **非 llm、非 expr** 列（V1 不做 expr 链式；若未来放开，需加轻量拓扑 + 环检测，本次禁止）。依赖被违反 → 启动即抛错（分析/计划构造错误）。

### H9 兼容与回归
- 现有 `ColumnPlan` mode 白名单增加 `expr`，**旧计划不含 expr 时行为零变化**。
- 迁移完成后订单金额口径必须与现状一致（total = Σ subtotal 两位小数；actual = total − discount + freight 两位）。
- 表名/列名在规则 params 中，引擎代码禁止出现 `orders/order_item/total_amount` 字面量（除测试夹具与文档示例）。

---

## 3. 入参 / 传参契约

### 3.1 表达式列（GenerationPlan.ColumnPlan，现有 record 扩展）
| 字段 | 类型 | 说明 |
|---|---|---|
| `column` | String | 目标列名 |
| `mode` | String | `"expr"` |
| `params.expr` | String | 表达式字符串，如 `"price * quantity"` / `"round(price*quantity, 2)"` |
| `params.scale`（可选） | String | 覆盖列 scale（默认取 DECIMAL D / 2） |

### 3.2 ExpressionEvaluator（新增，无语义纯计算器）
```java
// 输入：表达式 + 本行已生成列值（键=列名）；引用列必须在 rowValues 中存在且非 null，否则抛 ExpressionException
BigDecimal eval(String expr, Map<String, BigDecimal> rowValues);
```
- 调用方负责只传入 expr **已声明引用**的列（引用集由 eval 内部解析声明，调用方可先 `referencedColumns(expr)` 校验后再取数）。
- 提供静态 `Set<String> referencedColumns(String expr)`（解析列引用集合，供生成侧做顺序/存在性校验）。

### 3.3 FORMULA 规则（ProjectRules.Rule，新增 type）
| 字段 | 值 |
|---|---|
| `type` | `"FORMULA"` |
| `table` / `column` | 目标表.列 |
| `params.expr` | 表达式 |
| `source` | `AGENT`（LLM 提议）→ 需人工确认 → `USER_CONFIRMED` 才落 plan |

映射：`RuleSetters` 对 `FORMULA` → `ColumnPlan.of(col, "expr", {expr})`（参照现有 ENUM_WEIGHT→weighted 分支）。

### 3.4 AGG_EQUAL 规则（现有，回填消费方补充）
字段不变：`table/column/type=AGG_EQUAL/params{ref_table,sum_col,join_col}`。
新消费方 = **生成侧回填器**（见 4.2）。

### 3.5 回填器入参（重构 Reconciliation 为新引擎）
```java
void apply(Map<String, GeneratedData> data, List<ProjectRules.Rule> aggRules);
```
语义：对 data 中每条 `AGG_EQUAL`（且 ref 表与目标表都在 data 内）执行内存聚合回填（等价现 Reconciliation 的跨表 total/actual 计算，但表名列名来自规则，且**支持任意表/列组合**）。

---

## 4. 出参与返回值

| 组件 | 成功返回 | 说明 |
|---|---|---|
| `ExpressionEvaluator.eval` | `BigDecimal` | 按列 scale 收口 |
| `referencedColumns` | `Set<String>` | expr 引用列名集合 |
| expr 列生成 | `BigDecimal` 写入 `values[column]`，血缘 EXPAND | 由 GenericExpander 循环内调用 |
| 回填器 apply | `void`（就地改 DataRow） | 覆盖列标 CORRECTED；返回统计日志（可选 record `{appliedTables, skipped}` 便于日志/测试断言） |
| GenericExpander（新 expr 分支） | 行 DataRow | 与现有一致 |

---

## 5. 错误返回（显式失败路径）

| 触发 | 抛出 | 处理位置 | 兜底 |
|---|---|---|---|
| expr 语法/白名单错 | `ExpressionException`（Runtime） | GenericExpander 捕获 → `IllegalStateException(table/column/expr)` | 上层失败表隔离 failedTables（现有机制），**不写坏数据** |
| 引用未知列 / 引用 llm 列 | `ExpressionException` | 同上；`referencedColumns` 与 plan 列比对，**分析/构造期即可预检** | 预检通过才进 plan；运行期仍兜 |
| 除零 / 非数值 / 环 | `ExpressionException` | 同上 | 同上 |
| 回填缺 ref 表/列 | 跳过 + warn（**不抛**） | 回填器内 | 与现 Reconciliation「跳过并提示」一致：ref 表可能不在生成集 |
| AGG_EQUAL 规则 params 缺 ref_table/sum_col/join_col | warn 跳过 | 回填器 | 修正侧 SqlValidator 同规则会 catch 跳过 |

---

## 6. 容易踩的坑（实现时逐条自查）

1. **BigDecimal 除法截断**：`1/3` 直接除会抛 `ArithmeticException`——必须先指定 scale + RoundingMode（H3），否则金额列偶发爆。
2. **列 scale 丢失**：DECIMAL(10,2) 的 D=2 要从 `ColumnDef` 取；拿不到别默认 0（会四舍五入成整数），默认 2。
3. **expr 引用 llm 列**：derived 表的 llm 语义列生成时为 null，ContentGen 后才回填——expr 若引用它必出 null/报错。必须在**预检**就拒绝（H4）。
4. **列序**：GenericExpander 现逐列循环。插入 expr 分支时若引用列排在 expr 之后，会拿到 null——必须**先算非 expr 列、expr 收尾**（H8），别在循环里打补丁。
5. **表名/列名写死**：新代码若再出现 `"orders"`/`"total_amount"` 字面量即违反 H9——用规则 params。但**测试夹具**允许写死（那是业务域样例）。
6. **Reconciliation 双份逻辑**：现 Reconciliation 同时做「行内 subtotal」与「跨表 total/actual」。迁移时**先确认 subtotal 归属**（行内属 expr 域，可留 Reconciliation 或迁 expr），别整个复制粘贴成两个半套。
7. **增量模式**：回填器对 `baseline ∪ 新增` 都要正确（现 reconcile 在增量路径已跑）——迁移后增量测试 `IncrementalSmokeTest` 必须仍绿。
8. **RuleFilter 的 isMoneyColumn 启发式**：现在靠列名猜金额并过滤 LLM 宽范围。引入 FORMULA 后**不要让 LLM 的 expr 与 VALUE_RANGE 同列互踩**（expr 列的 VALUE_RANGE 规则应被忽略，参照 RuleSetters 现有「weighted 不被 VALUE_RANGE 覆盖」护栏）。
9. **AGENT 规则落地前要人工确认**：FORMULA 表达式是 LLM 提议，默认 `source=AGENT`，**未确认不进 plan**（走 `refineRules` 人工检查点，现状已有 Web 入口）——防止 LLM 表达式直接驱动生成。
10. **迁移验收口径**：订单金额在迁移后输出数值必须与重构前完全一致（用现有 smoke 测试断言兜住），任何两位小数/取整差异都算失败，不能"差不多"。

---

## 7. 涉及文件（预判，以计划书为准）

**新增**：`validation/expr/ExpressionEvaluator`、`validation/expr/ExpressionException`、（建议包 `com.dataforge.derivation`）
**改动**：`expansion/GenericExpander`（expr case + 列序）、`analysis/RuleSetters`（FORMULA→expr）、`llm/DataLlm.extractRules`（提示产出 FORMULA）、`pipeline/Reconciliation`（→ 规则驱动回填器或收敛）、`expansion/ToolRegistry`（补 expr 工具）、`tools/SystemToolRegistry`（derive 描述）
**删除（迁移完成）**：`OrdersExpander` 金额占位逻辑、Reconciliation 业务硬编码
**测试**：`ExpressionEvaluatorTest`、`GenericExpanderExprTest`、`ReconcileEngineTest`、迁移回归 = 现有 3 个 SmokeTest
