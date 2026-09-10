# 周期研究模块

周期研究是独立标签、独立异步任务，复用现有股票快照。每次运行加载原始 `howard-marks-cycle/SKILL.md` 和资料导航；模型通过 Spring AI 1.1.8 的受控工具循环检索、阅读原书和检查市场证据。没有 Java 周期评分器，也没有把摘要冒充完整技能。

## 配置和运行

需要仓库 JDK 21、Python 3，以及完整本地书库。默认配置：

```yaml
app:
  ai:
    roles:
      cycle-report: deepseek
  cycle:
    skill-path: D:/codex-book/skills/howard-marks-cycle
    book-path: D:/codex-book/books/howard-marks-market-cycle
    python: python
    report-path: data/cycle-reports
```

将需要覆盖的字段合并到 `config/application-local.yml` 现有 `app` 节点，不能重复创建同名 YAML 节点。模型连接继续使用现有 `app.ai.models`；需支持 OpenAI 兼容工具调用。出现在模型目录只代表已配置，不表示工具能力已验证。

技能脚本还依赖 `D:/codex-book/tools/search_library.py`。迁移时保留书库根目录的 `skills/`、`books/`、`tools/` 结构。只复制 SKILL.md 不够。书籍原文不复制进仓库、不打包进 JAR。

运行 `./start.ps1`，选择股票，打开“周期研究”，选择模型并点击“生成周期研究”。切换标签或刷新会恢复当前任务或已落盘报告。旧报告明确保留原快照时间；重新生成会重新取快照。重启后只能恢复已落盘的成功报告。

## 接口

| 接口 | 方法与参数 | 返回 |
| --- | --- | --- |
| `/api/agent/cycle/models` | GET | id、modelName、defaultModel 数组 |
| `/api/agent/cycle/tasks` | POST，JSON `{"code":"600519","modelId":"deepseek"}`；modelId 可省略 | HTTP 202，Task |
| `/api/agent/cycle/tasks/{id}` | GET | 当前 Task；无记录返回 404 |
| `/api/agent/cycle/latest/{code}` | GET | 当前任务或磁盘最近成功报告；无记录返回 404 |

Task 包含 status（RUNNING / COMPLETED / FAILED）、stage、modelName、生成/快照时间、skillDigest、report、chapters、evidence、trace、limitations、error。失败时 report 为 null，不生成伪造的回退报告。无效输入返回 400，超过两项并发返回 429。任务最长五分钟、工具最多 48 次、模型最多 49 轮。对外错误使用固定消息，不返回模型端点、请求头或异常堆栈。

同一股票的进行中任务复用。最多保留 100 个内存任务，达到上限后淘汰已结束任务。最新成功报告按股票代码原子替换保存，默认目录已被 Git 忽略；保存失败会明确提示。

## 工作方式和边界

工具为 `cycleSearch(query)`、`cycleReadTopics()`、`cycleReadChapter(chapterId, offset)`、`cycleReadEvidence(section)`。不接受任意命令、URL、书库根路径。检索运行原技能脚本，使用参数数组而非 shell，UTF-8 输出、20 秒超时。文件通过真实路径校验限制在书库内。

完成前必须有三次不同关键词检索、主题导航、至少两个顺序完整阅读的章节、全部十二个证据分区检查。正文按页返回，nextOffset=-1 表示读完。

报告覆盖经济、盈利、行业、信贷、心理、风险态度、估值七个维度，区分书中观点、当前事实、我的分析。章节引用必须实际读完；事实引用必须实际读取且非 UNAVAILABLE。来源、质量和时间来自服务器保留的 DataSection，不使用模型生成的 URL。结构、引用或研究流程不合格时不返回完成报告。

结构检查不能证明自然语言事实或推断正确，也不校准概率，报告仍需人工复核。第一版没有宏观时间序列、整体信贷条件、行业供需历史工具，也不执行任意网页搜索；相关维度必须披露不足。后续增加受限数据适配器时，把新增数据和 Provenance 纳入同次证据集合。

## 验证

默认测试离线，覆盖工具结果回传、未读原书拒绝完成、完整研究、无效引用、路径边界、证据状态、任务接口以及四种视口。UI API 使用合成数据拦截，不能作为投资报告。

```powershell
$env:JAVA_HOME = (Resolve-Path '.tools/jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
# 测试需要本地启动应用；无 Chrome 时可使用已安装 Edge。
$env:PLAYWRIGHT_CHANNEL = 'msedge'
npm.cmd run test:ui
```

截图输出到 `target/cycle-ui/`。真实模型验证应单独执行并检查完成状态、执行记录和报告内容，不能仅凭 HTTP 202 认定完成。

真实技能烟测 `CycleReportLiveIT` 标记为 external，并要求显式设置 `cycle.live.enabled=true`；默认测试不会调用外部模型。该测试向配置的模型发送原始技能及选读章节，必须获得相应资料传输授权后再运行。它故意将市场证据全部置为不可用，只验证技能执行，不生成真实股票投资报告。

本次验证：224 项离线后端测试通过，44 项页面测试通过；获授权的一次 DeepSeek `deepseek-v4-flash` 烟测通过，执行 32 步工具操作、完整阅读 6 个章节、生成 7 个维度，当前事实数组全部为空。烟测结果保存在 `target/data-verification/cycle-skill-smoke.json`，仅供技术核验。真实行情加模型的完整研究尚未验证：首次尝试在沙箱中因核心数据获取失败停止。
