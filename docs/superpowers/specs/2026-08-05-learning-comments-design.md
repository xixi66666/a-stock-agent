# 全项目 Agent 学习型注释设计

## 目标

在不改变业务逻辑、接口契约和运行时行为的前提下，为整个 A 股研究 Agent 项目补充可用于学习的中文注释与架构文档。重点解释数据如何流动、Agent 为什么被限制在受控边界内，以及模型失败后系统如何保持可用。

## 覆盖范围

项目中的所有逻辑模块都纳入说明：

- `agent`：研究报告、总体报告、模型注册、Prompt、结构化输出、失败诊断。
- `agent.learning`：Advisor、RAG、Embedding、MCP、会话记忆和学习型 Facade。
- `analysis`：研究快照、数据聚合、质量评分和确定性分析。
- `analysis.institutional`：机构研究判断、模块评分、行业估值和证据状态。
- `marketdata`：规范化数据模型、Provider 适配、限流、冷却、校验与来源追踪。
- `technical`：技术指标与图表数据的确定性计算。
- `web`：REST 接口、参数边界和错误响应。
- 前端静态资源：页面状态、接口调用、报告渲染和图表交互。
- 测试与 Fixture：离线测试边界、数据契约和 UI 场景意图。

## 注释分层

### 第一层：模块级

每个 Java 包通过模块导航文档和核心类注释说明：

1. 模块职责。
2. 上游输入和下游输出。
3. 它在 Agent 学习路径中的位置。
4. 不应该放入该模块的职责。

前端和测试目录通过入口文件注释与学习总览文档覆盖。

### 第二层：类和接口级

核心类注释回答“为什么存在”和“由谁调用”，并标注：

- 依赖边界；
- 输入、输出和不变量；
- 模型调用与确定性代码的分工；
- 失败、缺失、降级和数据来源保留规则。

简单的 record、枚举和 DTO 只解释字段语义，不重复 Java 语法。

### 第三层：方法和算法级

对聚合、校验、Prompt 组装、Provider 请求、回退和前端异步状态等复杂方法，使用中文步骤注释说明执行顺序和设计原因。注释不描述显而易见的赋值，不把实现细节伪装成业务规则。

## 学习主线

文档和源码统一使用下面的学习路径：

```text
浏览器操作
  -> REST Controller
  -> ResearchAggregationService
  -> StockResearchSnapshot
  -> Deterministic Analysis
  -> Bounded Evidence Package
  -> ChatClient / LLM
  -> Structured Output Validation
  -> Report Assembly or Deterministic Fallback
```

总体报告、RAG/MCP 学习链路作为两条独立支线说明，不把它们和机构研究报告误认为同一个 Agent。

## 约束

- 不修改业务代码、Prompt 语义、模型路由、数据源优先级或 API 响应字段。
- 不在注释、文档和测试中写入真实 API Key、Token、Cookie 或本地私密配置。
- 不给 `target`、`out`、`node_modules` 等生成目录添加注释。
- 注释优先解释边界、原因和失败处理，不进行逐行翻译。
- JavaDoc 中的示例必须使用项目真实类型和方法名，避免创造不存在的 API。

## 验证方式

这次是注释和文档变更，不新增行为测试，也不执行完整测试套件。交付前执行：

1. `git diff --check`，检查空白和差异格式。
2. 使用仓库 JDK 21 执行 `mvnw.cmd -Dmaven.repo.local=.m2/repository -DskipTests compile`，确认注释没有破坏源码边界。
3. 复核 `git diff --stat` 和变更文件列表，确保没有修改生成目录和业务逻辑。
