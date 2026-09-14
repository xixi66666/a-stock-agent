# 统一模型选择器与启动连通性探测设计

## 1. 目标

所有调用大模型的生成入口（FinRobot 官方/旧引擎、周期研究、UZI 投研、财报分析）共用顶部一个全局模型选择器；后端启动时对每个已配置模型做一次连通性探测，UI 用绿点/红点/灰点展示，之后不再主动探测。

## 2. 范围

### 2.1 包含

- 顶部 header 的统一模型选择器（替换原只读 chip 与各模块模型下拉）。
- 启动时一次性连通性探测（异步、串行、每模型超时），结果存内存。
- `GET /api/ai/models` 统一目录（安全字段 + 连通性）。
- 选择持久化到 `localStorage`，各模块请求显式携带 `modelId`。
- `POST /api/agent/financial-report` 恢复（含可选 `modelId`）。
- UZI 支持可选 `modelId`。
- 各模块自带模型下拉移除，仅保留全局选择。

### 2.2 不包含

- 定时/周期探测、页面加载探测、手动探测按钮（刷新只重拉目录，不触发模型调用）。
- 真实调用后的被动状态更新（保持探测结果只来自启动）。
- 服务端持久化模型选择（仅浏览器 localStorage）。
- 多用户/多租户模型偏好。

## 3. 后端设计

### 3.1 连通性状态

```java
public final class ModelConnectivityRegistry {
    public enum State { UNKNOWN, OK, FAILED }
    public record Connectivity(State state, Long latencyMs, Instant checkedAt, String errorCode) {}
    // 线程安全 Map<String, Connectivity>；recordOk / recordFailed / get / snapshot
}
```

- `errorCode` 复用 `ModelFailureClassifier` 的稳定错误码（MODEL_TIMEOUT / MODEL_CONNECTION_FAILED / MODEL_HTTP_ERROR / MODEL_REQUEST_FAILED / MODEL_EMPTY_RESPONSE…），消息脱敏，不含 key、baseUrl。

### 3.2 启动探测

```java
public final class ModelConnectivityProbe {
    ModelConnectivityProbe(NamedChatClientRegistry registry, ModelConnectivityRegistry states,
            ModelFailureClassifier classifier, Clock clock, Duration timeout, ProbeCall call)
    public void probeAll();   // 串行遍历目录；单模型失败不影响其他模型
    @FunctionalInterface interface ProbeCall { String ping(ChatClient client); }
}
```

- 默认 `ProbeCall` 发最小请求：`client.prompt().user("ping").options(maxTokens=1).call().content()`。
- `probeAll()` 用虚拟线程执行并在每模型上用 `orTimeout(timeout)` 限时；超时映射 `MODEL_TIMEOUT`。
- `ModelConnectivityStartupProbe` 监听 `ApplicationReadyEvent`，用虚拟线程异步调用 `probeAll()`，不阻塞/不影响启动。
- 未配置任何模型时目录为空，探测空跑。

### 3.3 统一目录接口

`GET /api/ai/models`：

```json
{"models":[{"id":"deepseek","modelName":"deepseek-flash","defaultModel":true,
  "roles":["finrobot-research","financial-report","cycle-report","uzi-research"],
  "connectivity":{"state":"OK","latencyMs":412,"checkedAt":"...","errorCode":null}}]}
```

- 复用 `NamedChatClientRegistry.availableModels(null)` 与新增 `roles()` 访问器（反转 role→model）。
- 响应绝不含 apiKey、baseUrl、completionsPath。
- 已有 `/api/finrobot/models`、`/api/agent/cycle/models`、`/api/uzi/models` 保留兼容，不改行为。

### 3.4 模块接入

| 模块 | 改动 |
|---|---|
| FinRobot 官方/旧引擎 | 前端不再传模块下拉值，改传全局 `modelId`；后端已支持，不改 |
| 周期研究 | 同上，后端已支持 |
| 财报分析 | 恢复 `FinancialReportController`（`POST /api/agent/financial-report`，`{code, modelId?}`）；`FinancialReportService.generate(code, modelId)`：显式 ID → `byId`，未知显式 ID → `ModelNotAvailableException`，未传 → `forRole("financial-report")` |
| UZI | `Request` 增加可选 `modelId`；`start(code, depth, school, modelId)`：显式 ID → `byId`（未知即拒绝），未传 → `forRole("uzi-research")`；模型名继续经 `UZI_MODEL_NAME` 传给 Python |

## 4. 前端设计

### 4.1 选择状态（新模块 `js/model-selection.js`）

```js
const KEY = "astock.selectedModelId";
export function getSelectedModelId()   // localStorage 值，空则 null
export function setSelectedModelId(id) // 写入并派发 "model-selection-change"
```

模块视图在提交时调用 `getSelectedModelId()`，不直接依赖 app.js 状态。

### 4.2 顶部选择器

- `workbench.html` header：`#model-picker`（按钮 `#model-picker-toggle` + 菜单 `#model-picker-menu` + 刷新按钮 `#model-picker-refresh`），替换原 `#finrobot-model-status` chip 的展示职责。
- 按钮显示：所选模型 `id · modelName` + 状态圆点；未选择时用目录 `defaultModel`，并显示「默认」。
- 菜单项：模型名 + 圆点 + 延迟（如 `412ms`）；圆点颜色：绿=OK、红=FAILED、灰=UNKNOWN，`aria-label` 含文字状态（不能只靠颜色）。
- 键盘/无障碍：`aria-haspopup="listbox"`、上下键移动、Enter 选择、Escape 关闭、点击外部关闭。
- 刷新按钮只重新请求 `GET /api/ai/models`。
- 数据加载失败：按钮显示「模型目录不可用」，模块仍可按角色默认运行。

### 4.3 移除各模块选择器

- `finrobot-official-view.js`：删除 `#official-finrobot-model` 与目录拉取，仅保留 `/runtime`；提交携带全局 `modelId`。
- `cycle-view.js`：删除 `#cycle-model` 与目录拉取；提交携带全局 `modelId`。
- `views.js` + `app.js`：删除旧引擎 `#finrobot-model-select`、`loadFinRobotModels/syncFinRobotModelControls/bindFinRobotModelControls/renderFinRobotModelStatus` 与相关 state。
- `uzi-view.js`：删除只读模型展示；`api.js` 的 `startUzi` 增加 `modelId`。
- `financial-view.js`/`api.js`：`financialReport(code)` → `financialReport(code, modelId)`。

## 5. 测试与验证

- 单元：`ModelConnectivityRegistryTest`（OK/FAILED/UNKNOWN/快照）、`ModelConnectivityProbeTest`（成功/失败/超时/串行/不泄露）、`AiModelControllerTest`（目录+连通性、无敏感字段）、`FinancialReportControllerTest`（200/400/modelId）、`FinancialReportServiceTest`（显式模型/未知模型/默认角色）、`UziControllerTest`+`UziResearchServiceTest`（modelId）。
- UI：更新 `dashboard.spec.js`（选择器替换旧 chip/select）、`cycle-research.spec.js`、`uzi-research.spec.js`、`finrobot-official.spec.js`、`financial-report.spec.js`；新增 `model-picker.spec.js`（绿/红/灰点、选择持久化、各模块请求带 modelId、四视口不溢出、刷新只拉目录）。
- 全量：`clean test`、`package`、`npm run test:ui`、`git diff --check`；本地启动人工检查 header 选择器与实际探测结果。

## 6. 风险

- 启动探测会消耗少量 token（每模型 1 次最小请求）；失败不阻塞启动并在 UI 显示红点。
- 官方 FinRobot 执行时用 `AiModelProperties` 解析模型而非 registry，两套解析需保持模型 ID 一致（目录来自 registry 的已配置模型，两者同源 `app.ai.models`）。
- localStorage 中的模型 ID 可能已被移除：前端在目录加载后校验，失效则回退默认并更新存储。
