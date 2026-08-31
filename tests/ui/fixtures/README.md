# UI Fixture 说明

`partial-snapshot.json` 是离线页面测试用的规范化股票快照，不是 Provider 原始响应。

它刻意包含：

- 健康的行情和资金流；
- 降级的板块分区；
- 可用的 provenance 和 fetchedAt；
- 空 K 线和空技术指标，便于验证“缺失数据不伪造为 0”；
- 不包含 Cookie、Token、请求追踪信息或本地账号。

`dashboard.spec.js` 会在部分场景中复制这份 Fixture，再补充指标卡和模型响应，确保 UI 测试离线可重复。
