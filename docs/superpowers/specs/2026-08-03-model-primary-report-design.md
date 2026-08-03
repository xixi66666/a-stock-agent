# 模型主叙述与分层安全校验设计

## 背景

当前 `ReportValidator` 将所有叙述字段拼接后统一校验；任意一个未知证据、交易关键词或冲突遗漏都会让整份报告进入 `DETERMINISTIC_FALLBACK`。这保证了安全边界，但无法满足“模型叙述作为主报告、确定性分析作为参考底座”的使用方式。

## 目标

- 模型叙述作为报告主内容。
- 确定性分析继续拥有方向、证据状态、事实、冲突、缺失数据、风险和来源。
- 校验按字段执行，单个模块失败不拖垮其他有效模型模块。
- 未知证据、证据外数字、交易指令和结构化响应错误仍是阻断问题；缺少引用或冲突复述改为非阻断警告。
- 页面显示模型状态和警告，保留可追溯的确定性事实。

## 设计

`ReportValidator` 按 `executiveSummary`、`technicalAndFlow`、`fundamentals`、`valuationAndIndustry`、`catalysts`、`risks` 六个字段返回字段级问题、阻断问题和警告。证据引用、数字和交易关键词在字段内检查；冲突在全文中检查，缺少冲突只产生全局警告。

`StockAnalysisAgent` 根据结果选择模式：无问题为 `MODEL_ASSISTED`；只有警告为 `MODEL_ASSISTED_WITH_WARNINGS`；存在阻断问题时调用报告组装器，仅对失败字段使用确定性叙述，其余字段保留模型文本并标记 `MODEL_ASSISTED_PARTIAL`。

存在阻断问题时，先向同一模型发起一次带有校验问题和原始草稿的修复请求；修复后的草稿重新校验，最多只修复一次，避免无限重试和额度消耗。修复成功则按新的校验结果组装，修复失败才进入字段级回退。

交易指令不直接展示。确定性报告不再作为整份主报告覆盖模型文本，而是作为失败字段、事实卡片、冲突和来源的参考/兜底。

## 非目标

- 不允许模型修改 Java 侧的方向、证据状态、冲突和来源。
- 不放宽 API Key、任意 URL、Shell 或模型工具的安全边界。
- 不把模型生成的交易建议原样展示给用户。

## 验证

- `ReportValidatorTest` 覆盖字段级阻断和非阻断警告。
- `StockAnalysisAgentTest` 覆盖完整模型、警告模型和局部回退。
- `InstitutionalReportComposerTest` 覆盖三种模型生成模式及失败字段保留确定性叙述。
- UI 测试覆盖 `MODEL_ASSISTED_WITH_WARNINGS` 与 `MODEL_ASSISTED_PARTIAL` 的诊断显示。
