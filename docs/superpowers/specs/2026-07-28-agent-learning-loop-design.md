# Agent 学习闭环设计

**日期：** 2026-07-28  
**状态：** 已确认设计，待执行实现计划

## 1. 目标

在现有 A 股研究工作台中增加一个默认离线可运行的 Agent 学习闭环，覆盖 Chat Memory、Advisor 链、Embedding、Vector Store、RAG 和 MCP 六类能力。新能力必须保持可选，不改变现有行情、技术分析、数据源和既有 Agent API 的默认行为。

## 2. 当前系统约束

- 现有 Spring Boot 3.5.16、Spring AI 1.1.8 和 Java 21 版本保持不变。
- 默认没有模型密钥时应用仍然可以启动，离线测试不能访问外部模型或公开数据源。
- 数据源访问继续遵循 `ProviderThrottle`、来源保留和分区失败原则。
- Agent 工具只能接收有边界的股票代码、枚举或有限文本，禁止任意 URL、Shell、文件系统和不受限 HTTP 工具。
- 报告继续保留 `Provenance`、缺失数据和免责声明 `仅供学习研究，不构成投资建议`。

## 3. 架构方案

新能力放在现有 Spring Boot 单体中，通过 `app.agent.learning.*` 配置进行开关控制，不拆分微服务。

```text
学习 Agent REST API
        |
        v
LearningAgentFacade
        |
        +--> Advisor 链
        |      +--> TraceAdvisor
        |      +--> MemoryAdvisor
        |      +--> RagAdvisor
        |
        +--> ChatClient（有模型配置时）
        |
        +--> 离线确定性回答（无模型配置时）

RAG 链：研究快照/本地文档 -> Deterministic Embedding -> InMemory Vector Store -> 检索上下文

MCP 链：受限工具描述 -> 参数校验 -> Research 服务或 RAG 服务 -> 结构化工具结果
```

新入口使用独立路径 `/api/agent/learning/chat`，避免修改现有 `/api/agent/chat` 的语义。

## 4. 模块职责

### 4.1 Chat Memory

使用 Spring AI 的内存会话能力，按 `conversationId` 管理消息窗口，默认最多保留 12 轮对话。该模块只负责消息的保存、读取、裁剪和清理，不负责知识检索。

### 4.2 Advisor 链

通过 Spring AI Advisor 接口组合三个可测试的横切步骤：

- `TraceAdvisor`：记录当前请求经过的阶段、耗时和结果状态。
- `MemoryAdvisor`：读取历史消息并在调用后保存本轮消息。
- `RagAdvisor`：对用户问题执行检索，并将带来源的上下文加入模型请求。

Advisor 只在学习 Agent 入口中启用；既有 Agent 继续使用当前调用链。

### 4.3 Embedding

实现固定维度的本地确定性 `EmbeddingModel`。文本通过稳定的分词和哈希映射生成向量，不发起网络请求。同一输入必须得到同一向量，空文本和维度必须有明确测试行为。

### 4.4 Vector Store

实现进程内向量存储，使用 Spring AI `Document` 保存正文和元数据，使用余弦相似度排序。元数据至少包括股票代码、内容类型、来源、来源时间和抓取时间。支持添加、查询、按股票代码过滤和清空索引。

### 4.5 RAG

由索引器、检索器和上下文对象组成：

1. 从股票研究快照和脱敏本地文档生成可检索文档。
2. 生成文档向量并写入内存 Vector Store。
3. 将用户问题向量化并检索 Top-K 文档。
4. 生成包含正文、来源、时间和相似度的 `RagContext`。
5. 由 `RagAdvisor` 注入模型，模型不可伪造检索来源。

没有模型配置时，接口仍返回检索结果和确定性证据摘要。

### 4.6 MCP

增加本地受限研究工具提供器，抽象工具描述、参数校验和执行结果。工具包括股票搜索、研究快照、技术指标、事件研究和知识库检索。所有输入在进入业务服务前校验。MCP 网络适配器作为可选层实现，默认不启动，不让外部 MCP 服务成为应用启动依赖。

## 5. 配置

增加以下默认关闭配置，放入 `application.yml`，并在本地示例中提供说明：

```yaml
app:
  agent:
    learning:
      enabled: false
      memory-enabled: true
      advisor-enabled: true
      rag-enabled: true
      mcp-enabled: true
      max-history-messages: 24
      top-k: 4
      embedding-dimension: 128
```

配置缺失时使用安全默认值。关闭学习模块时，不创建学习入口所需的可选 Bean，也不改变既有 Bean。

## 6. 离线接口行为

`POST /api/agent/learning/chat` 接收：

```json
{
  "code": "600519",
  "message": "解释最近的盈利和技术风险",
  "conversationId": "demo-600519"
}
```

响应至少包含：

- `conversationId`
- `answer`
- `modelUsed`
- `advisorTrace`
- `memoryMessages`
- `retrievedDocuments`
- `toolExecutions`
- `disclaimer`

没有模型配置时，`modelUsed` 为离线确定性实现，响应仍能证明 Memory、Advisor、Embedding、Vector Store、RAG 和工具执行链路可用。

## 7. 错误与安全边界

- 非法股票代码、超长消息、空会话 ID 和未知时间范围返回参数错误。
- 向量检索失败只影响 RAG 上下文，不能删除行情和基础研究结果。
- Memory 存储失败时学习请求降级为无历史上下文，并在 trace 中记录状态。
- 工具执行失败返回结构化错误，不把异常堆栈交给模型。
- MCP 工具不接受 URL、命令、文件路径或任意 HTTP 参数。
- 日志中不输出 API Key、Token、Secret、Authorization 等敏感值。

## 8. 测试策略

所有行为变更采用 red-green-refactor：

- Memory：窗口裁剪、会话隔离、清理。
- Embedding：固定维度、确定性、空文本。
- Vector Store：相似度排序、Top-K、元数据过滤。
- RAG：索引、来源保留、无结果和部分结果。
- Advisor：执行顺序、上下文注入、失败降级。
- MCP：工具描述、参数边界、结构化结果。
- Learning Agent：无密钥离线完整链路。
- Web：新接口的 MockMvc 契约。
- 回归：现有离线 Maven 测试全部通过，外部测试仍保持 `external` 排除。

## 9. 验收标准

1. 不创建本地模型配置时，应用可启动，现有接口行为不变。
2. 新学习接口可在无网络和无 API Key 情况下返回确定性结果及完整 trace。
3. 每个模块有独立单元测试，测试不依赖真实模型、真实向量数据库或外部 MCP Server。
4. RAG 结果保留来源和时间，无法检索时明确返回缺失状态。
5. MCP 工具边界经过测试，不允许任意 URL、Shell、文件系统或 HTTP 访问。
6. 文档说明如何打开各模块、如何运行离线演示和如何切换到真实模型。

## 10. 非目标

- 本阶段不引入 MySQL、Redis、Milvus、PGVector 等外部持久化服务。
- 本阶段不实现生产级 Embedding 质量或大规模向量索引。
- 本阶段不提供自动交易、买卖指令或个性化投资建议。
- 本阶段不修改既有股票研究聚合规则和供应商适配器。
