# 财报分析(Financial Report Analysis)设计

## 1. 目标

在主页新增「财报分析」功能:针对单只 A 股,基于已配置数据源(Sina Finance 财务报表接口)真实可得的财报数据,由 Java 完成确定性计算(财务质量评分 + 多期趋势分析),由 DeepSeek 生成受限中文叙事,并在模型失败时提供确定性文字回退。

评分与趋势全部由后端计算,模型不算数、只写叙事。所有数字必须可追溯到字段、公式、观察时间和来源。

## 2. 范围

### 2.1 本阶段包含

- 改造 `SinaFinanceClient`,保留三张表(利润表 lrb / 资产负债表 fzb / 现金流 llb)各 8 期历史,按报告期对齐为新域记录 `FinancialStatementHistory`。
- 新增财务质量评分引擎:Piotroski F-Score 的 A 股 TTM 适配版,9 个信号,总分 0-9。
- 新增多期趋势引擎:8 期序列的同比增速、加速度、方向、波动率。
- 新增 DeepSeek 叙事管线:角色 `financial-report`,确定性证据包 → 叙事 → 校验 → 修复一次 → 确定性回退。
- 新增 `POST /api/agent/financial-report` 接口。
- 主页新增第 8 个 Tab「财报分析」+「生成分析」按钮。

### 2.2 本阶段不包含

- 杜邦分解、Beneish M-Score、盈利预期差(实绩 vs 一致预期)。
- 财报原文/公告文本解析。
- 全市场批量扫描、组合排名。
- CAGR 计算(本期聚焦同比与 TTM 口径,避免多口径混淆,如实标注)。
- 扣除非经常性损益口径(实测 Sina 响应不含该字段,信号 4 采用 Piotroski 原始定义)。

任何所需数据缺失时必须返回结构化不可用状态(UNVERIFIED / UNAVAILABLE),不能补造数值。

## 3. 架构

```text
POST /api/agent/financial-report
        |
        v
FinancialReportService
        |
        +-- FinancialStatementHistory (Sina 三表 x 8 期, 6h 缓存)
        +-- FinancialQualityScorer   (9 信号 F-Score, 纯函数)
        +-- FinancialTrendCalculator (趋势与统计, 纯函数)
        +-- FinancialEvidencePackage (Java 事实包, 带单位与窗口)
        +-- optional SpringAiFinancialNarrativeGenerator (DeepSeek)
        +-- FinancialNarrativeValidator -> 修复一次 -> DETERMINISTIC_FALLBACK
        |
        v
FinancialReportAnalysis (结构化响应)
```

模型配置复用 `NamedChatClientRegistry`,新增角色 `financial-report`(映射 deepseek),不新增模型目录或 API Key 体系。模型不接收工具调用,只接收有界事实包。

## 4. 数据层

### 4.1 多期历史

现有 `SinaFinanceClient.fetchStatements()` 已对 lrb/fzb/llb 各发起一次 `getFinanceReport2022?type=0&page=1&num=8`,响应 `report_list` 按报告期 `YYYYMMDD` 分键、每期含 `item_title/item_value/item_tongbi`。实测(2026-08-31,600519)返回 8 期(20260630 至 20240930),字段清单:

- lrb:营业总收入、营业成本、净利润、归属于母公司所有者的净利润、营业利润等。
- fzb:资产总计、负债合计、流动资产合计、流动负债合计、实收资本(或股本)、所有者权益(或股东权益)合计等。
- llb:经营活动产生的现金流量净额等。

改造方式:`num` 调整为 **12**(接口免费返回 4 年历史,支撑 TTM 同比),三张表各保留全部期数,按报告期对齐为 `FinancialStatementHistory(securityId, List<FinancialPeriodStatement>, provenance)`;`FinancialPeriodStatement` 仅保留本功能所需字段。逐期解析逻辑与现有单期解析保持一致,单表异常按 section-local 处理。

**累计口径与 TTM 组法(量化标准做法)**:lrb/llb 报告期数值为年初至今累计,资产负债表为时点值。TTM 组法:

- 当期若为年报(Q4):TTM = 当期累计值。
- 否则:TTM = 当期累计 + 上年年报 − 上年同期累计。
- 平均资产 = (当期总资产 + 上年同期总资产) ÷ 2。

### 4.2 缓存与来源

新增 Caffeine `financialHistoryCache`(6 小时 TTL,财报低频变动),Provenance 全程保留并随响应输出。数据源仅 Sina 主源,失败即 section-local UNAVAILABLE,与 provider-matrix 现状一致,不新增 provider。

### 4.3 期数门槛

各信号按所需输入**动态判定可算性**,输入缺失即标 UNVERIFIED,不设固定期数档位:

- 期数 < 4(次新股):整体返回「数据不足」状态,不输出评分档位。
- TTM 类信号(1/2/4)需要当期 TTM 可组(当期 + 上年同期 + 上年年报)。
- Δ 类信号(3/5/6/7/8/9)需要当期与上年同期两组值均可算。
- 12 期(4 年)窗口内全量可算;少于 12 期时按上述规则逐信号降级。

## 5. 财务质量评分(F-Score 9 信号)

口径:流量类信号(ROA、现金流、毛利率、周转率)采用 TTM 组法(累计 + 上年年报 − 上年同期;年报期即全年累计);Δ 为当期相对上年同期的变化。时点类信号(杠杆、流动性、股本)取最新报告期相对上年同期的变化。

| # | 信号 | 判定 | 字段(已实测) |
|---|---|---|---|
| 1 | TTM ROA > 0 | 归母净利_TTM ÷ 平均资产总计 | lrb 归母净利; fzb 资产总计 |
| 2 | TTM 经营现金流 > 0 | 经营现金流净额 TTM 求和 | llb |
| 3 | ΔTTM ROA > 0 | TTM(后 4 期)vs TTM(前 4 期) | 同 1 |
| 4 | 利润质量 | TTM 经营现金流 > TTM 净利润(Piotroski 原始定义,无扣非字段) | llb + lrb 净利润 |
| 5 | 杠杆改善 | Δ资产负债率 < 0(负债合计 ÷ 资产总计) | fzb |
| 6 | 流动性改善 | Δ流动比率 > 0(流动资产 ÷ 流动负债) | fzb |
| 7 | 无股本稀释 | 实收资本(或股本)未增加 | fzb |
| 8 | 毛利率改善 | Δ毛利率 > 0((营业总收入 − 营业成本)÷ 营业总收入) | lrb |
| 9 | 效率改善 | Δ资产周转率 > 0(营收_TTM ÷ 平均资产) | lrb + fzb |

规则:

- 每个信号状态为 `PASS | FAIL | UNVERIFIED`;UNVERIFIED 不计分、不计入分母,并在 UI 如实展示原因。
- 总分 0-9,档位:0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优。
- 平均资产 = (最新期 + 4 期前)÷ 2,期数不足则该信号 UNVERIFIED。
- 金融行业(银行/保险/证券/多元金融,按既有行业分类识别):信号 8、9 无传统口径意义,标 UNVERIFIED 并在 UI 注明;其余信号保留。
- 每个信号附 `evidence`(指标、数值、单位、窗口),供叙事与展示引用。

## 6. 趋势引擎

对多期序列逐项计算:营业总收入、归母净利润、净利润、毛利率、净利率、ROE(TTM,归母净利_TTM ÷ 归母股东权益)、经营现金流、资产负债率。流量项保留累计口径并在 UI 标注,同比为当期累计 vs 上年同期累计。

- 同比增速:当期 vs 4 期前同期。
- 加速度:同比增速的一阶变化。
- 方向:同比符号一致性(最近 3 个可比期)。
- 波动率:可比同比序列标准差。
- CAGR:不做(样本不足),响应中标注原因。

输出 `FinancialTrendResult`(序列 + 统计 + 可图表化结构),全部为确定性纯函数。

## 7. DeepSeek 叙事与校验

事实层与叙述层分离:

```text
FinancialEvidencePackage      Java 事实:评分、信号、趋势、来源、限制、UNVERIFIED 清单
FinancialNarrativeDraft       模型生成的受限中文段落
FinancialReportAnalysis        Java 合并校验后的最终响应
```

模型只能输出:评分档位解读、信号通过/未通过归因、趋势描述、风险提示段落。模型不得新增事实、数字、日期、来源或交易建议。

校验器(`FinancialNarrativeValidator`)检查:

- 文本中出现的数字是否可在事实包中找到。
- 是否引用评分档位(0-2 弱 / 3-5 中 / 6-7 良 / 8-9 优)。
- 是否出现交易指令、目标价、收益保证或个性化建议。
- 是否把 UNVERIFIED 写成确定结论。
- 是否包含免责声明与长度上限。

校验失败修复一次;仍失败或模型异常 → `DETERMINISTIC_FALLBACK`(由事实包组装的确定性文字)+ `ModelDiagnostic`(脱敏),始终返回 200,不暴露异常堆栈。

配置:application.yml 增加 `app.ai.roles.financial-report: deepseek`;真实 key 仅存于 `config/application-local.yml`(Git 忽略),example 无需新占位符。

## 8. API 契约

`POST /api/agent/financial-report`,请求体 `{code: "600519"}`(六位证券代码,复用现有 SecurityId 校验)。

响应 `FinancialReportAnalysis`:

```text
meta            证券、报告期区间、期数、生成时间、规则版本、提示词版本、来源
qualityScore    总分、档位、计分信号数、UNVERIFIED 清单、9 信号明细(状态+证据)
trends          趋势序列与统计(单位、窗口)
narrative       文字、generationMode(LIVE | DETERMINISTIC_FALLBACK)、引用
diagnostic      模型失败时的脱敏诊断
insufficientData  期数不足标记
disclaimer      免责声明
```

错误:代码非法 → `INVALID_SECURITY_CODE`;Sina 历史获取失败 → `FINANCIAL_DATA_UNAVAILABLE`(RFC 9457 问题详情,新增类型);模型缺失/失败 → 确定性回退,不报错。

## 9. 前端呈现

主页新增第 8 个 Tab「财报分析」,进入 Tab 后点击「生成分析」按需生成(与现有 Agent 分析一致,不随快照自动加载):

1. 头部:证券、报告期区间、数据截止、生成时间、生成模式徽标。
2. 评分卡:F-Score 大数字 + 档位标签 + 9 信号清单(PASS/FAIL/UNVERIFIED 状态文字 + 证据)。
3. 趋势图:ECharts 多期序列(营收/利润柱图、毛利率与 ROE 折线),红涨绿跌语义仅用于趋势方向标签,不单独依赖颜色。
4. DeepSeek 叙事卡:引用、确定性回退徽标、金融行业提示(如适用)。
5. 状态如实渲染:数据不足、Sina 不可用、模型回退均显示状态文字与原因,不渲染为 0 或空白。
6. 免责声明。

复用设计令牌与现有样式;新增 `static/js/financial-view.js`,扩展 `static/js/api.js`。图标按钮保留无障碍标签与 tooltip。

## 10. 测试与验收

- `SinaFinanceClient` 多期解析测试:8 期对齐、期数不足、跨表缺失。
- `FinancialQualityScorerTest`:9 信号 × pass/fail/缺失→UNVERIFIED、档位边界、金融行业规则、平均资产期数不足。
- `FinancialTrendCalculatorTest`:同比、加速度、方向、波动率、样本不足。
- `FinancialNarrativeValidatorTest`:编造数字、缺档位引用、交易指令、免责声明、长度。
- `FinancialReportServiceTest`:mock ChatClient 成功、模型缺失→回退、修复一次。
- Controller 测试:成功、非法代码、Sina 不可用。
- 夹具:sanitized 多期 lrb/fzb/llb 响应,仅公开市场数据。
- Playwright(`tests/ui/financial-report.spec.js`):mock 接口,验证 1440x1000、1024x768、768x1024、390x844,评分渲染、图表像素、回退徽标、错误态、无横向溢出。
- 默认 Maven 测试保持离线;实盘验证标记 `external`。

## 11. 验收标准

用户输入六位代码并点击生成后,可获得:总分 0-9 的 F-Score 及 9 信号明细、8 期趋势图与统计、DeepSeek 中文叙事。每个数字可追溯到字段、公式、窗口与来源;数据缺失处如实显示 UNVERIFIED/UNAVAILABLE 及原因;DeepSeek 未配置或失败时仍返回确定性文字报告;现有接口与页面不受影响。
