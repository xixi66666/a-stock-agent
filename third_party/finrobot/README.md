# FinRobot 官方源码边界

来源：https://github.com/AI4Finance-Foundation/FinRobot

固定提交：`6d6ccd32c1b8b1904dc656cf06897438aba3daec`。

本目录保留官方 `finrobot_equity/core/src/modules/equity_agents/` 全部角色与管理器，
以及 `financial_data_processor.py`，许可证见 LICENSE。文件内容未改写，仅统一换行。
UPSTREAM.json 记录每个随附源码文件的 SHA-256；Worker 在加载前验证。

这是官方 Equity 开源组件的集成，不是官方 Desktop/V2 产品的完整副本。
适配逻辑位于项目 scripts/finrobot_worker.py：沿用官方 Agent 定义和 Pydantic 输出，
以官方 Agents SDK Runner 执行；使用显式 Chat Completions 模型替代上游管理器中
`ModelSettings(model=...)` 的旧接口调用。A 股证据、JSON Object 传输、中文边界、
独立失败状态、来源附件和 HTML 导出属于本项目适配层。

未引入上游 FMP 数据客户端、美元报告模板与默认假设估值引擎；不能把当前集成描述为
完整复刻官方全套功能。缺少输入的预测和 DCF 必须明确不可用。

升级时先审阅上游差异，再更新固定提交、文件及摘要，并运行 Python、Java、UI 回归测试。
