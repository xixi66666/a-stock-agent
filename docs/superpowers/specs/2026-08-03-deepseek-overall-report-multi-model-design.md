# DeepSeek 总体报告与多模型并存设计

## 1. 背景

当前 Agent 页面通过“生成研究报告”生成机构风格的结构化研究报告。报告链路使用单一 Spring AI ChatModel，本地配置中的 OpenAI、DeepSeek 和 MiMo 只能通过注释、取消注释的方式切换，不能同时启用。

本次改动在完整保留现有研究报告内容和生成行为的基础上：

- 新增独立的“生成总体报告”按钮；
- 使用固定分配给 `overall-report` 角色的 DeepSeek 模型生成总体报告；
- 将总体报告显示在页面左侧、两个生成按钮下方；
- 允许多个 OpenAI 兼容模型在本地配置中同时存在，并按功能角色选择模型；
- 优化现有研究报告的视觉层级、阅读节奏和响应式排版。

## 2. 目标与非目标

### 2.1 目标

- 现有结构化研究报告及其字段、来源、诊断和免责声明保持完整。
- 总体报告使用独立 API、独立按钮、独立加载状态和独立错误状态。
- DeepSeek 接收当前股票完整的规范化 `StockResearchSnapshot`，包括所有已经聚合的数据分区、数据状态、问题、时间和 `Provenance`。
- 总体报告同时呈现事实、计算结果、正反证据、冲突、缺失数据、风险、条件式情景和来源。
- 模型输出经过结构校验、安全校验、引用校验和数字依据校验。
- OpenAI、DeepSeek、MiMo 等模型可以在 `config/application-local.yml` 中命名并存，通过角色映射分配用途。
- 所有默认测试保持离线，真实模型请求继续标记为 `external`。

### 2.2 非目标

- 不允许模型改变 Java 侧生成的数据状态、来源、计算指标或确定性判断。
- 不向模型开放任意 URL、HTTP 客户端、Shell、文件系统或无界工具。
- 不生成目标价、买卖指令、仓位建议、收益保证或个性化投资建议。
- 不在页面上提供运行时密钥编辑器或将密钥返回给浏览器。
- 不提交真实 API Key、Token、本地配置或私有端点。

## 3. 总体架构

采用“命名模型 Bean + 固定职责”的实现。

### 3.1 模型配置边界

新增类型化配置：

```yaml
app:
  ai:
    models:
      primary:
        enabled: true
        base-url: "https://api.openai.com"
        api-key: "replace-with-primary-api-key"
        model: "replace-with-primary-model"
        temperature: 0.2
        completions-path: "/v1/chat/completions"

      deepseek:
        enabled: true
        base-url: "https://api.deepseek.com"
        api-key: "replace-with-deepseek-api-key"
        model: "deepseek-chat"
        temperature: 0.1
        completions-path: "/v1/chat/completions"

      mimo:
        enabled: false
        base-url: "https://api.xiaomimimo.com/v1"
        api-key: "replace-with-mimo-api-key"
        model: "mimo-v2.5-pro"
        temperature: 0.2
        completions-path: "/chat/completions"

    roles:
      institutional-report: primary
      overall-report: deepseek
```

每个已启用且配置有效的命名模型创建独立的 `ChatModel` 和 `ChatClient`。角色解析层根据 `institutional-report`、`overall-report` 等稳定角色名选择模型，不让业务代码依赖提供方名称。

`spring.ai.model.chat` 保持为 `none`，避免 Spring AI 自动配置再创建一个隐式的全局聊天模型。现有报告改为显式获取 `institutional-report` 角色；总体报告显式获取 `overall-report` 角色。

真实配置只存在于 Git 忽略的 `config/application-local.yml`。`config/application-local.yml.example` 和 README 只使用占位符。

### 3.2 独立总体报告链路

新增独立接口：

```text
POST /api/agent/overall-report
Content-Type: application/json

{ "code": "600519" }
```

处理流程：

1. 校验六位证券代码。
2. 通过现有 `StockAgentTools` / `ResearchAggregationService` 获取完整规范化快照。
3. 将快照序列化为 DeepSeek 用户输入，不裁剪已经聚合的数据。
4. 使用专业系统提示词约束证据、结构、安全和来源引用。
5. 将响应解析为结构化 `OverallResearchReport`。
6. 执行确定性校验；首次出现可修复问题时最多发起一次定向修复。
7. 校验通过后返回报告；失败时返回总体报告局部诊断，不影响现有报告接口。

DeepSeek 不获得 Spring AI tools。模型只能读取服务端传入的完整快照 JSON，不能主动重新抓取数据。

## 4. 总体报告数据结构

`OverallResearchReport` 至少包含：

- `overallConclusion`：总体结论、证据强度和适用周期；
- `dataQualitySummary`：完整度、时效性、冲突和不可用分区；
- `companyAndFundamentals`：公司、财务、盈利质量和机构预期；
- `technicalAndCapital`：趋势、技术指标、成交和资金行为；
- `valuationAndIndustry`：估值、行业位置和可比信息；
- `eventsAndSentiment`：公告、新闻、研报和潜在催化；
- `bullishEvidence`：支持偏强判断的事实列表；
- `bearishEvidence`：支持偏弱判断的事实列表；
- `riskFactors`：风险、依据和触发条件；
- `scenarios`：偏强、中性、偏弱三种条件式情景；
- `conflictsAndMissingData`：冲突、缺失项和判断局限；
- `sourceReferences`：数据分区、提供方、来源时间和快照时间；
- `modelName`、`snapshotAt`、`generatedAt`、`promptVersion`；
- `disclaimer`：固定为 `仅供学习研究，不构成投资建议`。

情景分析只能描述“如果条件成立，则证据如何变化”，不得转化为交易操作、目标价或仓位建议。

## 5. DeepSeek 专业提示词

提示词由稳定的系统提示词和每次请求的完整快照组成。系统提示词建议如下：

```text
你是严谨、审慎、证据优先的 A 股研究分析师。你的任务是基于用户提供的完整规范化股票研究快照，生成一份专业总体研究报告。你不是投资顾问，不得提供个性化投资建议。

【唯一事实边界】
1. 只能使用输入 JSON 中已经出现的事实、数字、日期、计算指标、数据状态和来源元数据。
2. 不得使用训练记忆、常识补齐、外部新闻或自行推测来补充输入中不存在的信息。
3. 不得编造价格、财务数字、估值、公告、新闻、机构观点、来源或时间。
4. 空值、缺失值、不可用数据不得解释为 0，也不得用其他分区的数据代替。

【数据质量规则】
1. 必须区分 HEALTHY、DEGRADED、STALE、UNVERIFIED、UNAVAILABLE。
2. 必须说明关键数据的快照时间、提供方、时效性、缺失项和冲突。
3. 当核心行情或 K 线不可用时，明确降低结论强度；其他分区失败只能影响对应章节。
4. 结论强度不得高于输入中的证据质量和完整度。

【分析要求】
1. 综合分析行情、K 线、技术指标、资金流、筹码与资本事件、基本面、行业与估值、机构研报、公告和新闻。
2. 将事实、计算指标、模型解释、冲突、缺失数据和结论明确区分。
3. 同时列出支持偏强和支持偏弱的证据，不得选择性忽略反证。
4. 风险必须指出对应事实依据或数据限制。
5. 情景分析使用偏强、中性、偏弱三种条件式情景，只描述触发条件和证据变化。
6. 每项事实性结论必须在 sourceReferences 中引用输入里真实存在的数据分区和提供方。

【禁止内容】
不得输出买入、卖出、加仓、减仓、持仓比例、止盈、止损、目标价、收益保证或针对个人的投资建议。不得暗示确定性收益。

【输出格式】
只返回 OverallResearchReport 对应的 JSON 对象，不要输出 Markdown、代码围栏、前言或解释性文字。所有必填章节必须存在；没有可靠证据时使用空列表并在 conflictsAndMissingData 中说明，不得编造补齐。disclaimer 必须严格等于：仅供学习研究，不构成投资建议。
```

用户消息使用固定前缀：

```text
以下是证券 {securityCode} 的完整规范化研究快照。请严格按照系统约束生成 OverallResearchReport JSON：
{completeStockResearchSnapshotJson}
```

修复提示词只包含原始快照、上一次结构化草稿和确定性校验问题，要求仅修复列出的问题，最多调用一次。

## 6. 确定性校验

新增总体报告校验器，至少检查：

- 必填章节、模型元数据和固定免责声明；
- 空总体结论和异常超长字段；
- 买卖、仓位、目标价、收益保证等禁止内容；
- `sourceReferences` 只能引用输入快照中的真实分区和 `Provenance`；
- 报告中的事实数字必须能在完整快照中找到依据；
- 不可用分区不得被表述为已验证事实；
- 快照中存在的冲突、核心缺失项不能被总体报告静默删除。

校验失败后允许一次修复。修复后仍失败则不显示未经校验的模型正文，而是返回 `OverallReportDiagnostic`。不得使用确定性报告冒充 DeepSeek 总体报告。

## 7. 页面信息架构与排版

Agent 页面桌面端采用约 `420px + minmax(0, 1fr)` 的两栏布局：

### 7.1 左侧

1. 功能说明；
2. “生成研究报告”主按钮；
3. “生成总体报告 · DeepSeek”次按钮；
4. 总体报告状态、模型名、生成时间和快照时间；
5. DeepSeek 总体报告正文。

总体结论和数据质量默认展开。其余章节采用语义标题、细分隔线和原生可展开区域，避免在窄列中堆叠嵌套卡片。正反证据和风险除颜色外必须包含明确文字标签。

### 7.2 右侧

完整保留现有结构化研究报告，包括：

- 方向、周期、证据状态和执行摘要；
- 核心驱动；
- 技术与资金；
- 基本面与机构预期；
- 估值与行业；
- 催化剂、风险、证据冲突、缺失数据和失效条件；
- 来源、模型诊断和免责声明。

通过字号层级、留白、分隔线、事实列表和段落宽度优化阅读，不删除或改写现有事实内容。

### 7.3 响应式

- 大屏：左侧总体报告，右侧现有报告；
- 中间宽度：适当缩小左列，但保证按钮文字完整；
- `768px` 及以下：单列顺序为操作区、总体报告、现有报告；
- `390px`：所有内容无水平溢出，来源 URL 和长词可换行。

继续使用项目批准的语义色、Lucide 图标和 A 股红涨绿跌语义，不添加渐变、深色主题、装饰性背景或营销文案。

## 8. 状态与错误处理

两个报告的状态完全独立：

- 研究报告请求只禁用“生成研究报告”；
- 总体报告请求只禁用“生成总体报告”；
- 切换股票时同时清理两份旧报告，防止跨股票内容混淆；
- DeepSeek 未配置时显示角色级未配置状态；
- 认证失败、超时、限流、响应解析失败和校验失败分别记录阶段和错误码；
- API 返回脱敏后的模型名、阶段、耗时、发生时间和追踪 ID；
- 日志不记录 API Key、Authorization、完整请求头或本地配置内容；
- 总体报告失败不改变现有研究报告结果，现有报告失败也不清空总体报告。

如果完整快照超过模型上下文容量，返回总体报告局部失败，不静默裁剪或遗漏输入数据。

## 9. 测试设计

所有行为变更使用红—绿—重构。

### 9.1 后端单元测试

- 多个命名模型可以同时绑定；
- 角色映射选择正确的模型；
- 某个模型缺失密钥只禁用对应角色；
- DeepSeek 请求包含快照的全部分区、状态、问题和 `Provenance`；
- 系统提示词包含事实边界、数据质量、正反证据、禁止内容、结构化输出和免责声明约束；
- 总体报告校验器拒绝未知来源、无依据数字、禁止交易指令和缺少免责声明；
- 首次失败最多触发一次修复，修复失败时返回诊断；
- 总体报告失败不影响现有结构化报告。

### 9.2 Web 与静态资源测试

- `POST /api/agent/overall-report` 只接受有效六位代码；
- 未配置、成功、模型失败和校验失败响应保持稳定；
- 页面包含两个独立按钮和两个独立输出容器；
- 现有报告字段和兼容渲染仍然存在。

### 9.3 UI 测试

- 两个按钮发起不同接口请求；
- 两个加载和错误状态互不覆盖；
- 总体报告位于左侧按钮下方，现有报告位于右侧；
- 切换股票会清理两份旧报告；
- 折叠章节可通过键盘操作并具有可访问名称；
- 在 `1440x1000`、`1024x768`、`768x1024`、`390x844` 检查画布像素、水平溢出、固定头部覆盖和按钮文字适配。

真实 DeepSeek 调用仅存在于标记为 `external` 的测试中，默认测试不访问外部模型。

## 10. 验收标准

1. 本地配置中至少可以同时声明 `primary` 和 `deepseek`，应用启动时不要求二选一。
2. 现有研究报告继续使用 `institutional-report` 角色且内容不丢失。
3. 独立按钮通过 `overall-report` 角色调用 DeepSeek。
4. DeepSeek 收到当前股票完整规范化快照，报告通过确定性校验后才展示。
5. 总体报告在桌面端位于左侧按钮下方；现有报告位于右侧。
6. 任一模型未配置或失败时仅影响自己的报告区域。
7. 页面符合项目语义色、A 股颜色、可访问性和四档响应式要求。
8. 所有默认 Java 和 UI 测试离线通过，构建通过，秘密审计无新增敏感信息。
