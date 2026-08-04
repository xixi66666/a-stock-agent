# DeepSeek 总体报告非阻断校验设计

## 背景

DeepSeek 总体报告当前采用“生成草稿、确定性校验、最多修复一次、再次校验”的流程。任何校验问题都会阻断报告返回，并可能触发一次耗时较长的模型修复请求。实际运行中，模型草稿因证据数字、来源引用或安全文案规则未通过校验时，接口返回 `MODEL_NARRATIVE_VALIDATION_FAILED`，用户无法查看模型原始报告。

## 目标

- DeepSeek 总体报告只要成功生成并解析为 `OverallReportDraft`，就返回报告。
- 所有总体报告校验问题均降级为诊断警告，不阻断返回。
- 取消总体报告的模型修复调用，避免额外延迟。
- 删除仓库对报告交易表述、保证收益、目标仓位、个性化投资建议和固定免责声明的强制限制。
- 保持机构研究报告链路不变。

## 非目标

- 不修改行情数据采集、规范化快照或 `Provenance` 的传递规则。
- 不修改机构研究报告使用的 `ReportValidator` 和修复流程。
- 不取消模型请求失败、响应解析失败等技术错误处理。
- 不删除 `OverallReportValidator`；它继续提供可观察的诊断信息。

## 仓库规则调整

从 `AGENTS.md` 的 `Agent Safety` 删除以下规则：

- 禁止输出直接交易指令、保证收益、目标仓位或个性化投资建议。
- 强制保留 `仅供学习研究，不构成投资建议` 免责声明。

其余工具边界、确定性计算、事实与结论区分、来源引用和秘密信息规则保持不变。

## 后端流程

`OverallReportService.generate(code)` 调整为：

1. 获取完整 `StockResearchSnapshot`。
2. 调用 `OverallReportGenerator.generate(snapshot)` 生成一次草稿。
3. 调用 `OverallReportValidator.validate(draft, snapshot)` 收集问题。
4. 不论问题类型，均不调用 `repair()`，也不返回 `VALIDATION_FAILED`。
5. 将草稿转换为 `OverallResearchReport`。
6. 有校验问题时，通过 `ModelFailureClassifier.validationWarning(...)` 生成警告诊断；没有问题时诊断为空。
7. 返回状态 `MODEL_ASSISTED` 和报告正文。

模型请求异常、连接失败或响应无法解析时，仍按现有逻辑返回局部失败诊断。

## 数据结构与接口

`POST /api/agent/overall-report` 的成功状态保持 `MODEL_ASSISTED`。存在校验问题时，响应同时包含：

```json
{
  "status": "MODEL_ASSISTED",
  "report": {},
  "diagnostic": {
    "failureStage": "VALIDATION",
    "errorCode": "MODEL_NARRATIVE_VALIDATION_WARNING",
    "validationIssues": ["UNSUPPORTED_NUMBER"]
  }
}
```

报告中的免责声明采用模型草稿原值，不再由总体报告服务强制校正或拒绝。

## 前端行为

前端继续渲染总体报告正文。若响应携带警告诊断，则在总体报告区域显示可展开的“总体报告校验提示”，但不将报告标记为不可用，也不隐藏正文。

## 测试设计

按 red-green-refactor 实施：

1. 新增或修改服务测试，构造包含交易指令、错误免责声明、未知来源、快照外数字和缺失章节的草稿。
2. 先验证测试因当前返回 `VALIDATION_FAILED` 或调用 `repair()` 而失败。
3. 最小修改服务编排，使报告返回 `MODEL_ASSISTED`、保留正文并携带警告诊断。
4. 断言 `repair()` 未被调用。
5. 保留模型异常和正常无警告路径测试。
6. 运行总体报告聚焦测试、完整默认离线测试和前端 UI 测试。

## 风险

- 模型生成的数字和来源可能无法由快照证明。
- 模型可能输出直接交易建议、保证性表述或不含免责声明。
- 下游调用方必须将 `diagnostic.validationIssues` 视为报告可信度提示，而不是成功背书。
- 取消修复会降低延迟和模型调用量，但不会尝试自动纠正报告问题。
