# 编排层重构设计（LangGraph4j）

> 状态：待确认 ｜ 目标：把散在 `DataForgeApiController` 的编排逻辑抽成图，替代手写编排
> 前置实测：`D:\codex\.tmp\lg4j-poc`（POC-1/2/3 全部通过）

## 一、目标与范围

**做什么**：引入 `langgraph4j-core`（经 BOM 锁 1.9.0），把生成主链路改成图编排。
`langgraph4j-mysql-saver` 待 T6 落地时再加（当前检查点用内存版，先加会是无消费方的依赖）。

**不做什么**：
- 不引入 `langchain4j`（与 Spring AI 功能重叠）
- **不引入 `langgraph4j-spring-ai`**（实测会拉 Spring Framework 7 + Jackson 3，与 Spring Boot 3.5.16 冲突）
- 不改前端交互（第一版图内部一口气跑完，人工介入作为能力预留）

## 二、现状（代码证据）

`DataForgeApiController.java` 592 行 / 18 端点，编排逻辑内联在端点方法里：

| 端点 | 行号 | 一个方法里干了什么 |
|------|------|-------------------|
| `/api/generate` | 319-360 | 建连 → 读表 → 算闭包 → **重跑分析(含 LLM)** → 设计划 → 生成 → drop/create/load staging → 校验 → 记 history |
| `/api/incremental` | 364-445 | 同上 + 读 baseline + 增量语义 + 可选 replan + 修正循环 |
| `/api/correct` | 449-473 | 建连 → 读 staging → 校验 → 新建 Engine → 修正循环 |
| `/api/score` | 477-497 | 建连 → 读 staging → 读真实表 → 评分 |

**已确认的问题**（本次顺带修）：
- `/api/generate` 每次都重跑分析 Agent（含真实 LLM 调用），且用户③确认的 plan ≠ ④实际用的 plan
- `RunReport` 是孤儿（`src/main` 零调用），删 CLI 时没接回 Web 流程
- 无编排层，业务逻辑与 HTTP 层耦合，无法单测

## 三、目标架构

```
START → analyze → generateStage → validate ─┬─ (收敛 / 全量模式) ──→ report → END
                                            └─ (增量且有错) ──────→ correct → report → END
```

> 实现说明（与初稿的差异）：图中的 `generate` 与 `stage` **合并为一个节点 `generateStage`**
> （合并理由：数据必须落库才算一个可恢复的完成点，拆成两节点会出现"生成完但没落库"的检查点）；
> 五个节点**同处 `ForgeNodes` 一个类**（节点是纯编排转发，拆六个类只会增加跳转成本）；
> `correct` 之后**直达 `report`，无回边**——收敛循环留在 `CorrectionEngine` 内部（见 §八决策）。

| 节点 | 职责 | 复用的现有组件 |
|------|------|---------------|
| `analyze` | 读表结构 → 闭包 → 产出 rules + plan + 表名 | `SchemaReader` / `GenerationSupport.referenceClosure` / `DefaultAnalysisAgent.analyzeTables` |
| `generateStage` | 生成数据 + drop/create/load staging | `DefaultGenerationAgent.generate / generateIncremental` + `MysqlStagingManager` |
| `validate` | 确定性校验 | `SqlValidator.validate` |
| `correct` | 修正一跳（内部是现有收敛循环） | `CorrectionEngine.run` |
| `report` | 汇总指标 + 落盘 | `RunReport` |

**条件边** `afterValidate`：全量模式 或 `errors == 0` → `report`；否则（增量且有错）→ `correct`。
轮数上限由 `CorrectionEngine` 内判定，图不按轮数分叉（否则继承的旧 `iteration` 会静默跳过修正）。

## 四、硬约束（违反即返工）

1. **状态里禁止放不可序列化对象**（连接、DataSource）。例外：用 `StateSerializer.declareTransientAttributes()` 显式声明为瞬态。
2. **状态里禁止放全量数据**（`Map<String,GeneratedData>` 十万行会让检查点 JSON 爆炸）。状态只存**引用与摘要**（表名、行数、错误数、耗时），真实数据在 staging 库——契合项目"staging 是 source of truth"的既有设计。
3. **响应格式必须与现网逐字段一致**，前端（`src/main/resources/static/index.html`）无需改动。
4. **`mvn -o test` 必须保持 144 用例全绿**，不许改现有测试的断言。
5. 图实例为 Spring 单例（POC-3 已验证：30 并发 0 串扰），不得每请求新建。
6. 单次运行一个 `threadId`，运行时对象经 `RunnableConfig.metadata` 传入。

## 五、状态契约

```java
class ForgeState extends AgentState {
    // ---- 入参（调用方写入）----
    String  schema;             // 目标库名
    String  mode;               // full | incremental
    Map<String,Integer> rows;   // 请求的每表行数
    Boolean cacheEnabled;       // 缓存开关；null = 按 dataforge.cache-dir 配置
    Integer minParentRows;      // 闭包父表缺省行数；null = 取配置
    boolean replan;             // 增量是否重跑分析规划（默认 true）
    // threadId 由 RunnableConfig 承载，不进状态

    // ---- 节点产出（全部为可 JSON 序列化的轻量值）----
    List<String> tableNames;    // 闭包后的表名（不放 TableMeta 对象，避免体积）
    GenerationPlan plan;        // 生成计划（纯数据 record，体量小）
    Map<String,Integer> rowCounts, actualCounts;
    List<String>  failedTables;
    Boolean baselineEmpty;      // 增量：现有数据是否为空
    Long    scopeMinId;         // 增量修正作用域下界
    int     ruleCount, iteration, errorCount, initialErrors, fixCount;
    boolean converged, rolledBack;   // converged 默认 false（未校验不得宣称收敛）
    Map<String,Object> report;  // RunReport 摘要
    List<String> log;           // LOG 通道累加（Channels.appender）

    // ---- 运行时对象：走 RunnableConfig.metadata，不进状态、不落检查点 ----
    // metadata[ForgeState.META_SESSION] = DbSession
    // metadata[ForgeState.META_REPORT]  = RunReport
}
```

**恢复语义**：跨进程恢复时 metadata 为空，调用方需在 resume 前重建 `DbSession` 并重新 `addMetadata`。
表结构不入状态——各节点按 `schema` 重读信息架构后按 `tableNames` 过滤（`ForgeNodes.tablesOf`）。
可空字段一律用 `AgentState.value(key)`（Optional）取值：双参重载传 `null` 会命中 `Supplier` 版本 → NPE。

## 六、错误处理

- 节点抛出的业务异常经框架包装为 `GraphRunnerException`，需在编排入口**还原原始异常类型与消息**，保证 `@ExceptionHandler` 现有响应格式不变。
- **注意**：`GraphInterruptException` 是 checked 异常，但 `invoke()` 未声明抛出——编译期无法 `catch`，需用 `GraphInterruptException.of(Throwable)` 探测。
- 中断不作为错误（正常返回），通过 `lastStateOf().next()` 判断停在哪。
- 节点内失败隔离：单表生成失败沿用现有 `failedTables` 机制，不让整图失败。

## 七、坑清单（POC 实测，不是推测）

| # | 坑 | 表现 | 应对 |
|---|----|------|------|
| 1 | `langgraph4j-spring-ai` 版本冲突 | 拉入 Spring Framework 7 + Jackson 3 | 不引入该模块 |
| 2 | 中断不抛异常 | `catch (GraphInterruptException)` 编译不过 | `catch (Exception)` + `GraphInterruptException.of()` |
| 3 | 恢复方式错误 | `invoke(Map.of(), cfg)` 会**从头重跑** | 必须用 `GraphInput.resume()` |
| 4 | 跑完检查点被清 | `getStateHistory()` 为空 | 框架**只在走到 END 时**释放线程；中断/异常仍保留（可 resume）。本项目无人读取历史轨迹 → 取 `releaseThread(true)`，避免单例 saver 内存滞留。若要事后查轨迹再改 false，并配套清理策略 |
| 5 | 历史顺序 | `getStateHistory()` 为倒序 | 取最新用 `.stream().findFirst()` |
| 6 | 状态合并语义 | 默认覆盖，日志类字段会被冲掉 | 累加字段用 `Channels.appender()` |
| 7 | 条件边返回值 | 返回的 key 不在 mapping 中 → 运行时异常 | key 与 mapping 严格对应 |

## 八、关键决策与取舍

| 决策 | 选择 | 理由 |
|------|------|------|
| 大对象进不进状态 | **不进** | 检查点序列化成本；且 staging 库已是唯一真相源 |
| 运行时对象怎么传 | `RunnableConfig.metadata` | POC-3 验证按运行隔离；不进状态即不被序列化 |
| 图实例生命周期 | Spring 单例 | POC-3 验证并发安全，避免每请求编译图 |
| 生成计划放哪 | **随请求/状态流转，不放单例** | 单例（ExpansionEngine）挂可变 plan 会让并发运行互相覆盖 → 表分类/列模式错配 |
| 检查点跑完留不留 | **留（`releaseThread(true)`）** | 框架只在走到 END 时释放；中断仍保留可 resume。留则单例 saver 在长驻 Web 里线性涨堆，且无人读取该轨迹 |
| 人工介入要不要现在做 | **先不做，预留** | 会改变前端交互形态（同步→两段式），风险大于收益；能力保留，随时可开 |
| 修正循环放图内还是节点内 | 节点内保留现有 `CorrectionEngine`，**图上不回边** | 引擎的收敛四要素含跨轮"问题清单闭环"（首轮问题签名基准）；拆到图回边会丢跨轮基准，且图回边 × 引擎内循环 = 轮数平方放大（上限 10 → 最坏 100 轮 + 200 余次全库扫描）。轮级回边是后续方案：需先把跨轮基准移交状态 |

## 九、风险与回退

- **风险**：图引入后 Web 行为漂移 → 用"响应字段集断言测试"兜住
- **风险**：LangGraph4j API 仍在快速演进（1.8.0→1.8.27 共 27 个补丁版本）→ 锁定 1.9.0，升级需回归
- **回退**：编排逻辑集中在 `orchestration` 包 + 图 Bean，回退只需让 Controller 重新直连 Agent，删包即可

## 十、复核修复与遗留（2026-09-29，提交 db581c3 后代码复核）

已修（同一批提交，`ForgeNodes` / `ForgeState` / `ForgeGraphConfig` / `RunReport` / `pom.xml` / 生成侧 plan 流转）：

| 问题 | 修法 |
|------|------|
| 空请求会 `drop` 掉用户已生成的 staging | `analyze` 入口拦 `rows` 为空；`generateStage` 落库前再挡空表集（防检查点带空集进节点） |
| 修正轮数平方放大（图回边 × 引擎内循环） | 去掉 `correct → validate` 回边，轮数上限只由 `CorrectionEngine` 判定 |
| 单例 saver 检查点永久滞留堆（无人读取） | `releaseThread(true)`；新增"跑完检查点已释放"断言用例 |
| 生成计划挂在单例 `ExpansionEngine` 可变字段 → 并发运行互相覆盖 | 计划随 `GenerationRequest` → `PipelineContext` 流转；`ExpansionEngine` 判定方法显式收 plan 参数（引擎变无状态） |
| `List<TableMeta>` 进状态（违反 §四.1、卡死 T6） | 状态只存 `tableNames`；节点按 schema 重读（`tablesOf`）。plan 为纯数据 record，可序列化 |
| 状态缺 `minParentRows` / `replan` / `baselineEmpty` | 三者补入状态契约并实际生效（`minParentRows` 缺省回落配置；`replan=false` 跳过 LLM 规划并告警） |
| 全量分支把 `cacheEnabled` 写死 false | 状态 `cacheEnabled` 改可空，null 时按 `dataforge.cache-dir` 决定（与现网默认一致） |
| `RunReport` setter 覆盖语义 → 多轮修正只剩最后一轮 | 轮数/条数/耗时改累加；新增 `correctMs` 计入 `totalMs` |
| `RunReport.analysis` 未判空 → 先解引用后判空 | 报告侧判空，调用点兜底不再不可达 |
| 编排路径从不设 `mode` → 落盘恒为 `unknown` | `report` 节点写 `runReport.mode(s.mode())` |
| `converged` 默认 true → 全量带错收工仍宣称收敛 | 默认 false；`validate` 节点按"残留错误 = 0"写回 |
| `iteration` 无入口复位（复用 threadId 会继承旧值） | `analyze` 复位 iteration/errorCount/fixCount/converged/rolledBack；图分支不再依赖 iteration 判定上限（上限在引擎内） |
| 依赖 `langgraph4j-mysql-saver` 无消费方 | 移除，T6 落地时再加 |
| 测试只做图结构字符串断言，节点/机制零覆盖 | 补：无回边断言、状态默认值、metadata 透传、LOG 累加、条件边路由、跑完释放检查点（共 5 例，全量 154 用例绿） |

**遗留（不得宣称已完成）**：
- **图级全链路集成测试**（计划 T2 验收项）仍未做：走完 `analyze → generateStage → validate → report` 需要真实 MySQL（`DbSession` 的 `SchemaReader`/`MysqlStagingManager`/`SqlValidator` 为具体类，字段为 `final` 无法打桩），与"单测不依赖外部库"的项目约定冲突。现以**同构 stub 图**覆盖框架机制层，DB 链路留待 T3 接入 Web 后随真实库手工链路验证。
- `AgentState.value(key, default)` 已被标记 deprecated for removal（未来升级需整改为单参 `value(key)`）。
- T3/T4/T5 未开始：图尚未接入 Controller，响应字段集与 `minParentRows`/`baselineEmpty` 的**对外契约**要等接入时按现网逐字段对齐。
- `replan=false` 的语义只做到"跳过 LLM 重规划"：计划来源退回空计划（内置模板 fallback），**尚未做计划落盘复用**
  （`/analyze` 只落 `project_rules.json`）。修前语义是"复用上一次请求留在单例上的 plan"——跨 schema 会串味，属必须修掉的 bug；
  真要"复用落盘计划"，需在 `/analyze` 落 `generation-plan.json` 并在生成入口加载（小改动，未做）。