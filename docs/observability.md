# 点击、请求与投研任务日志

在 IDEA 重启 `AStockAgentApplication`，浏览器强制刷新页面（Ctrl+F5）。日志进入
Spring Boot 标准控制台，无需开启全局 DEBUG，不新增数据库或外部遥测服务。

## 如何排查

1. 搜索 `UI_EVENT`：查看首页/工作台进入、模块切换、按钮点击、表单提交和控件变更。
   `view=technical` 表示技术分析，`view=uzi` 表示 UZI 投研。
2. 复制 `interactionId` 搜索：查看这一操作后发起的请求。纯前端渲染的切换也会有
   `UI_EVENT`，但可能没有业务请求。
3. 搜索 `HTTP_START` / `HTTP_END`：按 `requestId` 配对，查看 HTTP 方法、路径、
   Controller 方法、状态码和 `durationMs`。只有开始日志时，请求可能尚未返回。
   浏览器网络面板的响应头 `X-Request-Id` 与控制台一致。
4. UZI、周期研究、官方 FinRobot 返回 202 只表示任务已提交。搜索 `TASK_CREATED` 的
   `taskId`，继续查看 `TASK_START`、`TASK_COMPLETED`、`TASK_PARTIAL`、`TASK_FAILED`、
   `TASK_TIMEOUT` 或 `TASK_CANCELLED`（视引擎支持的状态）。
   `TASK_REUSED` 表示复用了已有任务；`TASK_ERROR` 提供异常类型及应用内代码位置。

示例（ID 为示意）：

```text
UI_EVENT requestId=r1 pageId=p1 interactionId=i1 type=click path=/workbench.html view=uzi target=button.view-tab[view=uzi] code=600519
HTTP_START requestId=r2 interactionId=i1 pageId=p1 method=GET path=/api/uzi/status
HTTP_END requestId=r2 interactionId=i1 pageId=p1 method=GET path=/api/uzi/status handler=UziController#status status=200 durationMs=12 failureType=none
```

## 覆盖范围与关联含义

- 所有入站请求路径（含静态资源、404、失败请求）统一记录；有效的遥测上报只输出
  一条 `UI_EVENT`，避免重复的访问日志。HTTP 4xx 使用 WARN，5xx 使用 ERROR。
- 前端采用捕获阶段事件委托，覆盖动态控件及嵌套图标。表单 Enter 提交也记录；
  `change` 记录变更动作，不记录填写的值；图表画布点击记录画布目标，不解析图表数据点。
- `pageId` 是每次页面加载的 ID；`interactionId` 是请求发起时最近的页面操作，
  **不是异步因果关系的证明**。后台轮询可能带最近一次其他操作的 ID，应以
  `taskId` 跟踪原任务。任务创建时冻结原始请求/操作 ID，执行线程继续使用这些 ID。
- 页面加载记录 `page`，浏览器历史/锚点变化记录 `navigation`。
  后端断开、浏览器退出或 keepalive 队列满时上报可能丢失，不阻塞原操作、不无限重试。
- 此功能跟踪交互、HTTP 边界和三类后台任务生命周期，不是逐个 Java 方法的调用栈记录。
  未扩展 Python 进程或第三方模型内部日志。

## 上报接口与隐私

`POST /api/observability/events`，`Content-Type: application/json`：

```json
{
  "type": "click",
  "pageId": "page-1",
  "interactionId": "click-1",
  "path": "/workbench.html",
  "view": "technical",
  "target": "button.view-tab[view=technical]",
  "code": "600519"
}
```

成功返回 204，无响应体；字段非法返回 400。前端忽略遥测失败，业务 API 保持原来的
错误处理语义。字段限制长度与字符集；证券代码只允许六位数字或 `-`。

新增日志不记录查询参数、请求体、认证头、表单内容、页面文本、模型输出或原始异常消息。
本地配置文件不参与上报。排查结束后如需关闭这组日志，可在本地配置中设置：

```yaml
logging:
  level:
    com.astock.agent.observability: "OFF"
```

默认级别为 INFO；若本地配置关闭了此包，请改为 INFO。关闭日志不会停止浏览器上报。

## 离线验证

先按根目录 AGENTS.md 配置 JDK 21，再运行：

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=RequestTracingTest,UziResearchServiceTest,CycleResearchServiceTest,OfficialFinRobotServiceTest' test
npx.cmd playwright test tests/ui/interaction-tracing.spec.js
```

浏览器测试使用当前应用地址；可用 `UI_BASE_URL` 指向测试实例，`PLAYWRIGHT_CHANNEL`
选择已安装的浏览器，例如 `msedge`。业务 API 在浏览器测试中使用离线 fixture。
