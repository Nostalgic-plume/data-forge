# DataForge 分析 Agent 工具调用通道方案（调研版）

> 状态：**调研方案，不落地**（2026-08-26）｜来源：多轮架构评审讨论
> 前置：先读 [PLAN.md](./PLAN.md)（设计蓝图）、[API_CONTRACT.md](./API_CONTRACT.md)（接口契约）、[UPGRADE_PLAN_V2.md](./UPGRADE_PLAN_V2.md)（L2 语义哨兵）。
>
> **本文档目的**：回答"项目要不要让 agent 调用工具、加在哪、怎么加、怎么验收"。结论是**只给分析 agent 加一个可选的读结构工具通道**，核心管线（生成/校验/修正）不加。本文给出完整设计供决策，不写代码。

---

## 1. 背景与现状

### 1.1 现状盘点（代码事实）

全项目**没有任何标准 LLM 工具调用**（function calling / tool calling / MCP）：

- `grep` 全 src：`.function(` / `@Tool` / `.tools(` / `ToolCalling` **零命中**
- pom 仅依赖 `spring-ai-starter-model-openai`（ChatClient 结构化输出），无工具扩展
- 所有 LLM 交互（[RealLlmClient](src/main/java/com/dataforge/llm/RealLlmClient.java)）都是 `.call().entity(Xxx.class)`——**结构化输出**，LLM 一次性输出 JSON，执行与结果回喂都由代码控制
- 分析 agent 读库走确定性 JDBC（[SchemaReader](src/main/java/com/dataforge/db/SchemaReader.java)），注释明确"MCP 是分析 agent 的 LLM 自主工具层，McpClient 接口位置在 API_CONTRACT §4 预留"

### 1.2 项目里"工具"的真实含义

项目大量使用"工具"一词，但指的是**确定性代码生成器**（[ToolRegistry](src/main/java/com/dataforge/expansion/ToolRegistry.java) 的 fk/seq/weighted/... 12 个），LLM 只产出"选哪个工具"的**决策**（`decideColumnTools` / `ColumnToolDecision`），执行永远是代码。这是"伪工具调用 / 路由决策"，不是模型运行时自主调用。

### 1.3 为什么核心管线不加（决策依据）

| 现有卖点 | 加真工具调用后 |
|---|---|
| 性能：12527 行 3 分 41 秒，batch 自适应 + 并行 | 每个决策点变 LLM 往返，工具链拉长即分钟级 |
| 可复现：确定性展开、固定种子、缓存指纹命中 | LLM 自主循环不可复现，`GenerationCache` fingerprint 失效 |
| 确定性验收：FK=0 / 唯一=0 / 金额=0 | 质量变 LLM 随机数，验收标准无法写死 |

**结论**：工具调用解决"LLM 自主性"，核心诉求是"可验证、高性能、可复现"，方向相反。

---

## 2. 设计决策（决策记录）

| # | 决策 | 内容 | 理由 |
|---|---|---|---|
| **D1** | **双通道默认** | 确定性 JDBC 全量读为默认；工具通道可选项（`--mcp` 开启） | 性能 + 可复现是默认承诺；工具通道是能力演示 |
| **D2** | **工具只读不写** | 分析期工具只读库结构（information_schema），不触库表数据、不落库 | 写操作仍走确定性 JDBC 通道（信任模型红线延伸："读可自主、写必受控"） |
| **D3** | **结果复核** | LLM 自主探查的产物仍落结构化 `TableMeta` 集，与确定性模式同构、可审计、可回放 | 保证两种模式产出同构，下游（PlanBuilder/extractRules）零改动 |
| **D4** | **信任模型红线维持** | 工具通道**只出现在分析 agent**；生成/校验/修正核心不动 | 收敛的单调性与终止必须确定性（[CorrectionEngine](src/main/java/com/dataforge/correction/CorrectionEngine.java) 四要素），裁判与动作不外放 |
| **D5** | **指纹驱动缓存** | MCP 模式探查完成后，仍跑一次确定性 `readTables` 生成完整 `schemaFingerprint` 作为缓存 key | LLM 自主探查可能遗漏表，指纹必须代表**真实完整** schema，否则增量缓存失效 |
| **D6** | **不重复造 L2 形态 B** | 本文只做"读结构"通道；"LLM 经 MCP 自主写校验 SQL"是 [UPGRADE_PLAN_V2](./UPGRADE_PLAN_V2.md) 的 L2 形态 B（阶段 C），**不在本方案范围** | 形态 B 涉及写 SQL 与结构闸门，风险高一档，单独立项 |

### 与既有文档的关系

- **补上**：API_CONTRACT §4 预留的 McpClient 位置、PLAN 决策 #15"读 MCP、写 JDBC"的 MCP 半边
- **互补**：UPGRADE_PLAN_V2 的 L2 形态 B 是"校验期 LLM 自主写 SQL"，本方案是"分析期 LLM 自主读结构"，两个不同阶段
- **不冲突**：P6 已知边界"MCP 客户端未接入"在本文落地后部分关闭

---

## 3. 工具清单设计（@Tool）

设计原则：**读结构、可下钻、数量少**。粒度上"先看全局再下钻单表"，让 LLM 有探查路径但不过度开放。

| 工具 | name | 参数 | 返回 | 适用场景 |
|---|---|---|---|---|
| 列表所有表 | `listTables` | `schemaName` | 表名清单 + 行数上限（信息差） | LLM 先摸清库的整体形状，决定下钻顺序 |
| 读单表结构 | `getTableMeta` | `schemaName`, `tableName` | 单表 `TableMeta`：列/类型/COMMENT/枚举/FK/唯一键（含隐式 FK 推断） | 核心工具：与 [SchemaReader.readTables](src/main/java/com/dataforge/db/SchemaReader.java#L31-L84) 单表切片同构 |
| 搜索相关表 | `searchTables` | `schemaName`, `keyword` | 列名/表名含 keyword 的表清单 | LLM 按业务词联想相关表（如搜"amount"找订单/明细） |

> 说明：
> - 三个工具全部只读 `information_schema`，复用 `SchemaReader` 的现有解析逻辑（`toColumnDef`/`parseEnumValues`/隐式 FK 推断），**零新解析代码**
> - 不提供"读表数据"工具——业务数据探查会引入隐私、性能和"LLM 被数据带偏"三类风险，且分析阶段不需要
> - 工具描述（description）用中文，喂给 LLM 的清单文本参考 `ToolRegistry.catalogText()` 的格式

---

## 4. 运行时流程对比

### 4.1 现有（确定性默认）

```
analyzeCore
  ├─ readTables(schema)   ← 一次性全量读，秒级
  ├─ buildSchemaSummary   ← 紧凑文本
  ├─ llm.extractRules(summary)   ← 单次结构化输出
  └─ PlanBuilder + RuleSetters + ColumnToolDecider
```

### 4.2 MCP / 工具通道（--mcp 可选）

```
analyzeCore（--mcp 分支）
  ├─ llm 自主探查循环：
  │    listTables → getTableMeta(表A) → getTableMeta(表B) → ...
  │    （边探查边决策，可反复下钻；工具结果回喂 LLM 继续推理）
  ├─ llm.extractRules(...)   ← 复用现有提炼通道（输入来自自主探查）
  ├─ readTables(schema)      ← D5：仍跑一次确定性全量读，只为生成完整 schemaFingerprint
  └─ PlanBuilder + RuleSetters + ColumnToolDecider   ← 与默认模式共用，零改动
```

**关键点**：
- 下游（PlanBuilder/extractRules/ColumnToolDecider）两种模式**完全共用**，只在"LLM 怎么拿到 schema 上下文"这一点分支
- 行数推荐（确定性 `recommendRowCounts`）不受影响，仍并行跑
- 子任务并行结构保留：探查循环与行数推荐并行

---

## 5. CLI 开关与装配要点

```
java -jar dataforge.jar --analyze shop            # 现有：确定性全量读
java -jar dataforge.jar --analyze shop --mcp      # 新增：LLM 工具通道自主探查
```

装配（Spring AI 1.1.8 方向，实现细节以官方文档为准）：

- 新增 `analysis/SchemaToolProvider`（或等价 @Tool bean）：`listTables` / `getTableMeta` / `searchTables` 三个 @Tool 方法，内部委托 `SchemaReader`
- `DataLlm.extractRules` 增加带工具上下文的入口（或新增方法 `extractRulesWithTools`），`ChatClient.prompt().tools("listTables","getTableMeta","searchTables")`
- CLI 解析 `--mcp` → `DefaultAnalysisAgent` 分支装配
- 返回类型不新增：仍是 `AnalysisResult`（ProjectRules + builders + plan + CacheFingerprint），**对外契约零变化**

---

## 6. 验收标准

| 维度 | 验收 |
|---|---|
| **功能** | 同一库（shop）默认模式 vs `--mcp` 模式提炼规则**一致或合理差异**（差异限于置信度/表述，类型与 params 结构一致） |
| **性能** | `--mcp` 模式分析耗时 ≤ 2min（参考：确定性模式秒级）；默认模式回归不受影响 |
| **可复现** | 固定探查序列可回放：同一输入 → 同一工具调用序列 → 同一规则集（mock LLM 单测） |
| **指纹** | `--mcp` 模式产出的 schemaFingerprint 与默认模式**一致**（D5 保证） |
| **回归** | 全量测试（现 75 用例）全过；确定性路径零改动，行为不变 |
| **信任红线** | 工具通道只读不写：测试断言 `--mcp` 全程无写库调用（JDBC execute 次数 = 0） |

---

## 7. 面试话术（为什么这么设计）

**Q：为什么分析 agent 用工具通道，生成/校验/修正不用？**
> 工具调用解决的是"LLM 自主性"问题，而自主性的价值只在**信息不对称**的场景。分析阶段面对的是未知库结构，LLM 需要自主探查、按需下钻，这是工具的价值区；生成/校验/修正是对**已知结构**的确定性加工，那里的诉求是性能、可复现、收敛保证，工具调用是负收益。所以我把工具通道收敛在分析 agent 一个点。

**Q：为什么默认还是确定性读，MCP 只是可选？**
> 默认承诺是"给定连接 → 秒级出规则 → 可复现"。确定性全量读 + schemaFingerprint 缓存是这条承诺的地基；MCP 通道是能力演示与超大库（几百张表）的按需探查路径，两者可切换。这是"确定性兜底保证性能与可复现，工具通道演示自主 agent 能力"的双通道架构。

**Q：工具会不会绕过"LLM 只发现、代码执行"红线？**
> 不会，红线反而被延伸得更清晰：**读可自主（D2），写必受控**。工具通道只读 information_schema，LLM 探查到的结构仍走既有的规则编译/生成/校验/修正链路，动作层（写入、校验、修正 SQL）全部是确定性代码。探查可以自主，裁决和动作从不交给 LLM。

---

## 8. 风险与边界（诚实声明）

| 风险 | 说明 | 对策 |
|---|---|---|
| LLM 探查遗漏表 | 自主下钻可能漏读，规则提炼不完整 | D5 指纹仍用确定性全量读；产出规则与默认模式对账可见差异 |
| 工具调用不可完全复现 | LLM 采样导致调用序列漂移 | 固定 seed + mock 单测锚定序列；真实模式接受合理差异 |
| 成本上升 | 多轮工具往返 token > 一次性读 | `--mcp` 为显式开关，成本由用户选择承担；默认不变 |
| 循环失控 | LLM 反复探查不收敛 | 工具调用轮数上限（如 20 次）+ 超时，超限回退确定性全量读 |

**明确不做**：
- 生成/校验/修正核心接入工具调用（档位 3，负收益）
- 修正 agent 读数据工具（档位 2，样本已由 SQL 模板喂给 LLM，收益有限）
- L2 形态 B"LLM 自主写校验 SQL"（UPGRADE_PLAN_V2 阶段 C，写操作风险高一档）

---

## 9. 结论

**能加，且只该加在分析 agent 一个点**。设计已收敛为：
1. 三个只读结构工具（listTables / getTableMeta / searchTables），复用 SchemaReader 零新解析
2. `--mcp` 显式开关，双通道可切换，下游与对外契约零改动
3. D1-D6 六条决策守住：性能默认、读可自主写必受控、指纹驱动缓存、红线维持
4. 验收五维：功能一致 / 性能上限 / 可复现 / 指纹一致 / 全量回归 + 零写库断言

实现成本预估：1 个 @Tool 类 + CLI 开关分支 + 2-3 个单测，约 2-3 个文件。**当前不落地，本文档作为决策依据留档**。
