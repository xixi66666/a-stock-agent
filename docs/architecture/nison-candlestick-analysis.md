# 尼森蜡烛图分析模块

## 目标与边界

模块把已校验、带 `Provenance` 的 OHLCV 序列转换为可审计的蜡烛图证据链。形态识别、趋势、确认、结构位、风险报偿与汇聚分数均由 Java 确定性计算；大模型不参与命名形态或生成价格。

反转形态只表示原趋势可能变化的警告，不等于趋势必然立即反向。证据分表示规则覆盖程度，不是历史胜率或方向发生概率。蜡烛图本身不提供价格目标；接口中的目标仅来自最近 20 期结构高低点，并明确标注来源。

## 数据流

`Tencent/Baidu K 线 → MarketDataValidator → StockResearchSnapshot.bars → CandlestickAnalysisService → DataSection<CandlestickAnalysis> → REST/UI`

- 接口：`GET /api/stocks/{code}/candlestick?timeframe=DAILY|WEEKLY|MONTHLY`
- 日线在交易日 15:05 前排除当天未完成 K 线。
- 周线在当前周未完成时排除当前聚合周期；月线对当前月采取保守排除策略。
- 少于 20 根完整周期时返回 `UNAVAILABLE`，不补零、不强行命名。
- 派生结果继承 K 线分区的状态、问题和来源信息。

## 分析顺序

1. 前置趋势
2. 形态构成
3. 相对位置
4. 后续确认
5. 支撑、阻挡与失效
6. 风险报偿
7. 其他技术信号

## 当前形态目录

- 单根反转：锤子线、上吊线、倒锤子线、流星线
- 双线反转：看涨/看跌吞没、刺透形态、乌云盖顶
- 星线：启明星、黄昏星
- 十字线：上涨高位与下跌低位的语境化十字线
- 持续/结构：向上窗口、向下窗口
- 双线收缩：看涨/看跌孕线、看涨/看跌十字孕线

规则版本 `NISON-CANDLESTICK-1.1` 增加完整收盘失效追踪，已失效信号不再参与主方向汇聚，窗口失效不会因价格返回而自动撤销。
`methodology.knowledge` 附带可检索的书本方法笔记，详见 [书本方法知识库](book-knowledge.md)。

实际形态允许变体，但软件必须使用可复现阈值。所有阈值随响应的 `methodology.transparentThresholds` 返回；这部分是工程化近似，不伪装成原书提供的机械交易系统。

## 多技术相互验证

主信号分别与短期趋势、RSI 14、20 期量比、形态支撑/阻挡进行核对。不同来源的因子独立展示同向与未同向状态，不隐藏反证。汇聚分数按已对齐因子的固定权重求和，未使用回测概率。

## 原书映射

规则说明覆盖第三、四、五、六、七、八、九、十、十一、十三、十四、十五、十六、十七章。详细章节主题随 API 的 `methodology.bookReferences` 返回。项目实现参考用户本地提供的中文版 EPUB 抽取资料；正式出版引用应回到用户持有的版本核对页码和文字。

## 验证

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=CandlestickAnalysisServiceTest,StockControllerTest' test

$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm.cmd run test:ui
```

UI 需要检查 1440×1000、1024×768、768×1024、390×844 四种视口，确认无横向溢出、固定头遮挡、按钮截断，并检查完整数据、加载、不可用与未完成周期状态。
