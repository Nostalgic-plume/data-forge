# 编排层重构计划（LangGraph4j）

> 配套设计：`docs/orchestration-design.md`
> 原则：每步独立可验证，任一步失败可停在原地不影响现网行为

## 前置条件

- [x] POC 验证通过（循环/条件边/断点续跑/人工介入/并发安全/依赖零冲突）
- [x] 设计文档确认
- [ ] `mvn -o test` 当前基线：144 用例全绿

---

## T1 依赖接入 + 图骨架

**改动**：
- `pom.xml`：加 `langgraph4j-core:1.9.0` + `langgraph4j-mysql-saver:1.9.0`（经 `langgraph4j-bom` 统一版本）
- 新建 `com.dataforge.orchestration.ForgeState`（状态契约，见设计文档第五节）
- 新建 `com.dataforge.orchestration.ForgeGraphConfig`（图装配为 Spring Bean）

**验收**：
- `mvn -o dependency:tree` 无 conflict 警告，仅新增 `async-generator`
- `mvn -o test` 仍 144 全绿
- Spring 上下文能启动（新增 `@SpringBootTest` 冒烟或复用现有上下文测试）

## T2 六个节点实现（暂不接 Web）

**改动**：新建 `orchestration/node/` 下 analyze / generate / stage / validate / correct / report 六个节点类，内部调用现有 Agent / Validator / Engine，**不复制业务逻辑**

**验收**：
- 新增图级集成测试：mock LLM 下走完 `analyze → generate → stage → validate → report`，断言状态字段
- 新增循环测试：构造必错数据，断言 `correct` 被触发且最终收敛
- `mvn -o test` 全绿（新旧用例合计 > 144）

## T3 接入 `/api/generate`

**改动**：`DataForgeApiController.generate` 瘦身为「收参数 → 调图 → 组装响应」

**验收**：
- **响应字段集断言测试**：改造前后响应 key 完全一致（`schema`/`actualCounts`/`elapsedMs`/`failedTables`/`validation`）
- 手工验证：Web 向导第③步功能不变

## T4 接入 `/api/correct` 与 `/api/incremental`

**改动**：同上，两个端点分别接入

**验收**：
- 响应字段集断言：`correct` → `schema`/`converged`/`iterations`/`fixCount`/`finalErrors`/`elapsedMs`；`incremental` → 11 个字段（见现网代码 428-440 行）
- 增量语义回归：`baselineEmpty` 退化路径正确

## T5 修 P0-3 + RunReport 进响应

**改动**：
- 图中 `analyze` 节点仅执行一次，生成阶段复用其 plan（消除重复 LLM 调用与 plan 不一致）
- `report` 节点汇总 `RunReport` 并 `save()`，摘要以 `runReport` key 进响应

**验收**：
- 断言单次 generate 调用中 `analysisAgent` 仅被调用 1 次
- 响应含 `runReport`，且 `run-reports/{ts}.json` 有落盘
- 断点续跑：`/api/generate` 中断后用同 threadId 恢复，`analyze` 不重跑

## T6 检查点持久化（进阶，可选）

**改动**：`MemorySaver` → `MysqlSaver`（新建检查点表），运行时对象经 transient 恢复注入

**验收**：
- 进程重启后仍能从检查点恢复
- 瞬态字段不落入检查点表（查库验证无连接对象）

## T7 文档与收尾

**改动**：更新 `docs/API_CONTRACT.md`（新响应字段 `runReport`）、`AGENTS.md`（架构描述）

**验收**：
- 文档与实现一致
- 提交入库

---

## 验收总表

| 子任务 | 验收方式 | 通过标准 |
|--------|---------|---------|
| T1 | `mvn -o test` + `dependency:tree` | 144 全绿 + 零冲突 |
| T2 | 新增图级测试 | 全链路 + 循环收敛用例通过 |
| T3 | 响应字段集断言 | 字段完全一致 |
| T4 | 响应字段集断言 | 字段完全一致 |
| T5 | 调用计数断言 | analyze 仅 1 次 + runReport 落盘 |
| T6 | 重启恢复验证 | 跨进程恢复成功 |
| T7 | 文档一致性检查 | 无残留描述 |

## 回退方案

任何一步出问题：编排逻辑集中在 `com.dataforge.orchestration` 包，Controller 若需回退为直连 Agent，删除该包 + 还原端点方法即可，不影响其他模块。