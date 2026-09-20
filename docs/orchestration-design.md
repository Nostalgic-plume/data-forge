# 编排层重构设计（LangGraph4j）

> 状态：待确认 ｜ 目标：把散在 `DataForgeApiController` 的编排逻辑抽成图，替代手写编排
> 前置实测：`D:\codex\.tmp\lg4j-poc`（POC-1/2/3 全部通过）

## 一、目标与范围

**做什么**：引入 `langgraph4j-core` + `langgraph4j-mysql-saver`，把生成主链路改成图编排。

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
                    ┌─────────────────────────────────────────┐
START → analyze → generate → stage → validate ──┐             │
                    ↑                            │             │
                    │   (未收敛且有错)            │(收敛/达上限) │
                    └── correct ←────────────────┘             │
                                                 ↓             │
                                              report → END ────┘
```

| 节点 | 职责 | 复用的现有组件 |
|------|------|---------------|
| `analyze` | 产出 rules + plan | `DefaultAnalysisAgent.analyzeTables` |
| `generate` | 生成数据 | `DefaultGenerationAgent.generate / generateIncremental` |
| `stage` | drop/create/load staging | `MysqlStagingManager` |
| `validate` | 确定性校验 | `SqlValidator.validate` |
| `correct` | 修正一轮（内部仍是现有循环） | `CorrectionEngine.run` |
| `report` | 汇总指标 + 落盘 | `RunReport` |

**条件边** `afterValidate`：`errors == 0 || iteration >= maxIterations` → `report`；否则 → `correct`。

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
    String  schema;          // 目标库名
    String  mode;            // full | incremental
    Map<String,Integer> rows;// 请求的每表行数
    long    runId;           // 本次运行标识（threadId）

    // ---- 节点产出 ----
    List<String> tableNames; // 闭包后的表名（不放 TableMeta 对象，避免体积）
    int     ruleCount;       // 分析产出的规则数
    int     iteration;       // 修正轮数
    int     errorCount;      // 当前错误数
    boolean converged;       // 是否收敛
    Map<String,Object> report; // RunReport 摘要

    // ---- 瞬态（declareTransientAttributes 声明，不落盘）----
    transient DbSession session;   // 数据库会话，恢复时由调用方重新注入
    transient List<TableMeta> tables; // 表元数据，恢复时按 schema 重读
}
```

**恢复语义**：跨进程恢复时瞬态字段为空，调用方需在 resume 前重建 `DbSession` 并写回状态。

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
| 4 | 跑完检查点被清 | `getStateHistory()` 为空 | 需留痕时 `releaseThread(false)` |
| 5 | 历史顺序 | `getStateHistory()` 为倒序 | 取最新用 `.stream().findFirst()` |
| 6 | 状态合并语义 | 默认覆盖，日志类字段会被冲掉 | 累加字段用 `Channels.appender()` |
| 7 | 条件边返回值 | 返回的 key 不在 mapping 中 → 运行时异常 | key 与 mapping 严格对应 |

## 八、关键决策与取舍

| 决策 | 选择 | 理由 |
|------|------|------|
| 大对象进不进状态 | **不进** | 检查点序列化成本；且 staging 库已是唯一真相源 |
| 运行时对象怎么传 | `RunnableConfig.metadata` | POC-3 验证按运行隔离；不进状态即不被序列化 |
| 图实例生命周期 | Spring 单例 | POC-3 验证并发安全，避免每请求编译图 |
| 人工介入要不要现在做 | **先不做，预留** | 会改变前端交互形态（同步→两段式），风险大于收益；能力保留，随时可开 |
| 修正循环放图内还是节点内 | 节点内保留现有 `CorrectionEngine` | 第一版先搬编排、不动算法，降低回归面；后续可再把循环下沉为图上的回边 |

## 九、风险与回退

- **风险**：图引入后 Web 行为漂移 → 用"响应字段集断言测试"兜住
- **风险**：LangGraph4j API 仍在快速演进（1.8.0→1.8.27 共 27 个补丁版本）→ 锁定 1.9.0，升级需回归
- **回退**：编排逻辑集中在 `orchestration` 包 + 图 Bean，回退只需让 Controller 重新直连 Agent，删包即可