# 统一模型选择器与启动连通性探测实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 顶部一个全局模型选择器覆盖全部生成入口；后端启动时探测一次模型连通性，UI 显示绿/红/灰点。

**Architecture:** 新增 `ModelConnectivityRegistry`（内存状态）+ `ModelConnectivityProbe`（启动一次性探测）+ `AiModelController`（统一目录）；恢复 `FinancialReportController` 并给财报/UZI 补 `modelId`；前端新增 `model-selection.js` 共享选择状态与 header 选择器，移除各模块下拉。

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI ChatClient, JUnit 5 + AssertJ + Mockito, 原生 JS, Playwright。

**设计文档:** `docs/superpowers/specs/2026-09-14-unified-model-selector-design.md`

## Global Constraints

- 只在启动探测一次；不新增定时任务、页面探测或手动探测。
- 探测结果只含稳定错误码与延迟，绝不泄露 apiKey/baseUrl/请求头。
- 探测失败不阻塞、不回滚应用启动。
- 默认测试离线；外部调用标 `external`，`-Pexternal` 才跑。
- 前端颜色不是唯一信息载体（圆点必须带文字 aria/title）。
- Maven 命令统一先设置 JDK21（同前）。
- 提交需用户确认。

---

### Task 1: 连通性状态注册表

**Files:**
- Create: `src/main/java/com/astock/agent/agent/model/ModelConnectivityRegistry.java`
- Create: `src/test/java/com/astock/agent/agent/model/ModelConnectivityRegistryTest.java`

**Interfaces:**
- Produces: `enum State {UNKNOWN, OK, FAILED}`；`record Connectivity(State state, Long latencyMs, Instant checkedAt, String errorCode)`；`recordOk(id, latencyMs, at)`、`recordFailed(id, errorCode, at)`、`get(id)`、`snapshot()`

- [x] **Step 1: 写失败测试**

```java
@Test void recordsOkFailedAndDefaultsToUnknown() {
    var registry = new ModelConnectivityRegistry();
    var now = Instant.parse("2026-09-14T02:00:00Z");
    assertThat(registry.get("deepseek").state()).isEqualTo(ModelConnectivityRegistry.State.UNKNOWN);
    registry.recordOk("deepseek", 412L, now);
    assertThat(registry.get("deepseek").state()).isEqualTo(ModelConnectivityRegistry.State.OK);
    assertThat(registry.get("deepseek").latencyMs()).isEqualTo(412L);
    registry.recordFailed("mimo", "MODEL_TIMEOUT", now);
    assertThat(registry.get("mimo").state()).isEqualTo(ModelConnectivityRegistry.State.FAILED);
    assertThat(registry.get("mimo").errorCode()).isEqualTo("MODEL_TIMEOUT");
    assertThat(registry.snapshot()).containsOnlyKeys("deepseek", "mimo");
    assertThat(registry.get(null).state()).isEqualTo(ModelConnectivityRegistry.State.UNKNOWN);
}
```

- [x] **Step 2: 运行确认失败** → 编译失败
- [x] **Step 3: 实现**（ConcurrentHashMap；`get` 对 null/未记录返回 `Connectivity.unknown()`；`snapshot()` 返回不可变副本）
- [x] **Step 4: 通过**

---

### Task 2: 启动探测

**Files:**
- Create: `src/main/java/com/astock/agent/agent/model/ModelConnectivityProbe.java`
- Create: `src/main/java/com/astock/agent/agent/model/ModelConnectivityStartupProbe.java`
- Modify: `src/main/java/com/astock/agent/agent/model/NamedChatClientRegistry.java`（新增 `roles()` 访问器）
- Modify: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`（注册三个 Bean）
- Create: `src/test/java/com/astock/agent/agent/model/ModelConnectivityProbeTest.java`

**Interfaces:**
- Produces:
  - `ModelConnectivityProbe(NamedChatClientRegistry, ModelConnectivityRegistry, ModelFailureClassifier, Clock, Duration, ProbeCall)`
  - `ProbeCall.ping(ChatClient) → String`
  - `probeAll()`
  - `ModelConnectivityStartupProbe(@EventListener(ApplicationReadyEvent.class))`

- [x] **Step 1: 写失败测试**

```java
@Test void probesEachModelSeriallyAndRecordsLatencyAndFailures() {
    var states = new ModelConnectivityRegistry();
    var registry = new NamedChatClientRegistry(
            Map.of("deepseek", named("deepseek-chat"), "mimo", named("mimo-v2")), Map.of());
    var order = new ArrayList<String>();
    var probe = new ModelConnectivityProbe(registry, states, new ModelFailureClassifier(),
            Clock.fixed(Instant.parse("2026-09-14T02:00:00Z"), ZoneOffset.UTC), Duration.ofSeconds(2),
            client -> { order.add("call"); return "pong"; });
    probe.probeAll();
    assertThat(order).hasSize(2);
    assertThat(states.get("deepseek").state()).isEqualTo(State.OK);
    assertThat(states.get("deepseek").latencyMs()).isNotNull();
    assertThat(states.get("deepseek").checkedAt()).isEqualTo(Instant.parse("2026-09-14T02:00:00Z"));
}

@Test void recordsFailureClassificationAndEmptyResponses() {
    // 一个 throw new RuntimeException("connection refused")，一个返回 "" 
    // → MODEL_CONNECTION_FAILED / MODEL_EMPTY_RESPONSE；错误码不含异常原文中的敏感值
}

@Test void timesOutHungModelsWithoutStoppingOthers() {
    // ProbeCall 对第一个模型 sleep 5s，超时 100ms；第二个正常返回
    // → 第一个 FAILED + MODEL_TIMEOUT，第二个 OK
}

private static NamedChatClientRegistry.NamedModel named(String name) {
    return new NamedChatClientRegistry.NamedModel(org.mockito.Mockito.mock(ChatClient.class), name);
}
```

- [x] **Step 2: 运行确认失败**
- [x] **Step 3: 实现**

```java
public final class ModelConnectivityProbe {
    @FunctionalInterface public interface ProbeCall { String ping(ChatClient client); }

    public ModelConnectivityProbe(NamedChatClientRegistry registry, ModelConnectivityRegistry states,
            ModelFailureClassifier classifier, Clock clock, Duration timeout) {
        this(registry, states, classifier, clock, timeout, ModelConnectivityProbe::defaultPing);
    }

    static String defaultPing(ChatClient client) {
        return client.prompt().user("ping")
                .options(ChatOptions.builder().maxTokens(1).build())
                .call().content();
    }

    public void probeAll() {
        for (var reference : registry.availableModels(null)) {
            probe(reference.id(), reference.modelName());
        }
    }

    private void probe(String id, String modelName) {
        long started = System.nanoTime();
        try {
            ChatClient client = registry.byId(id).orElseThrow(ModelNotAvailableException::new).client();
            var future = new CompletableFuture<String>();
            Thread.ofVirtual().start(() -> {
                try { future.complete(call.ping(client)); }
                catch (Throwable failure) { future.completeExceptionally(failure); }
            });
            String content = future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
            long latency = elapsedMillis(started);
            if (content == null || content.isBlank()) {
                states.recordFailed(id, "MODEL_EMPTY_RESPONSE", clock.instant());
            } else {
                states.recordOk(id, latency, clock.instant());
            }
        } catch (RuntimeException failure) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
            var diagnostic = classifier.classify(cause, modelName, elapsedMillis(started),
                    "probe-" + id);
            states.recordFailed(id, diagnostic.errorCode(), clock.instant());
        }
    }
}
```

```java
/** 启动后异步探测一次；失败只记录状态，不影响应用。 */
public final class ModelConnectivityStartupProbe {
    private final ModelConnectivityProbe probe;
    public ModelConnectivityStartupProbe(ModelConnectivityProbe probe) { this.probe = probe; }

    @EventListener(ApplicationReadyEvent.class)
    public void probeOnStartup() {
        Thread.ofVirtual().name("model-connectivity-probe").start(probe::probeAll);
    }
}
```

`NamedChatClientRegistry` 增加：

```java
public Map<String, String> roles() { return roles; }
```

`AgentConfiguration` 增加 Bean：

```java
@Bean ModelConnectivityRegistry modelConnectivityRegistry() { return new ModelConnectivityRegistry(); }

@Bean ModelConnectivityProbe modelConnectivityProbe(NamedChatClientRegistry registry,
        ModelConnectivityRegistry states, ModelFailureClassifier classifier, Clock clock) {
    return new ModelConnectivityProbe(registry, states, classifier, clock, Duration.ofSeconds(10));
}

@Bean ModelConnectivityStartupProbe modelConnectivityStartupProbe(ModelConnectivityProbe probe) {
    return new ModelConnectivityStartupProbe(probe);
}
```

（`ModelFailureClassifier` bean 已有；`Clock` bean 已有。若 `AgentConfiguration` 已有 Clock 参数冲突，按现有 bean 风格调整。）

- [x] **Step 4: 通过**

---

### Task 3: 统一模型目录接口

**Files:**
- Create: `src/main/java/com/astock/agent/web/AiModelController.java`
- Create: `src/test/java/com/astock/agent/web/AiModelControllerTest.java`

**Interfaces:**
- Consumes: `NamedChatClientRegistry`、`ModelConnectivityRegistry`
- Produces: `GET /api/ai/models` → `{models:[{id, modelName, defaultModel, roles, connectivity}]}`

- [x] **Step 1: 写失败测试**

```java
@Test void catalogMergesRolesAndConnectivityWithoutSecrets() throws Exception {
    var registry = new NamedChatClientRegistry(
            Map.of("deepseek", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "deepseek-chat")),
            Map.of("financial-report", "deepseek", "finrobot-research", "deepseek"));
    var states = new ModelConnectivityRegistry();
    states.recordOk("deepseek", 412L, Instant.parse("2026-09-14T02:00:00Z"));
    var mvc = MockMvcBuilders.standaloneSetup(new AiModelController(registry, states)).build();
    mvc.perform(get("/api/ai/models"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.models[0].id").value("deepseek"))
            .andExpect(jsonPath("$.models[0].modelName").value("deepseek-chat"))
            .andExpect(jsonPath("$.models[0].defaultModel").value(true))
            .andExpect(jsonPath("$.models[0].roles", containsInAnyOrder("financial-report", "finrobot-research")))
            .andExpect(jsonPath("$.models[0].connectivity.state").value("OK"))
            .andExpect(jsonPath("$.models[0].connectivity.latencyMs").value(412))
            .andExpect(content().string(not(containsString("apiKey"))));
}
```

- [x] **Step 2: 运行确认失败**
- [x] **Step 3: 实现**

```java
@RestController
@RequestMapping("/api/ai")
public final class AiModelController {
    private final NamedChatClientRegistry registry;
    private final ModelConnectivityRegistry connectivity;

    @GetMapping("/models")
    public ModelsResponse models() {
        Map<String, List<String>> rolesByModel = new HashMap<>();
        registry.roles().forEach((role, modelId) ->
                rolesByModel.computeIfAbsent(modelId, ignored -> new ArrayList<>()).add(role));
        var models = registry.availableModels(null).stream()
                .map(reference -> new ModelStatus(reference.id(), reference.modelName(),
                        reference.defaultModel(),
                        List.copyOf(rolesByModel.getOrDefault(reference.id(), List.of())),
                        connectivity.get(reference.id())))
                .toList();
        return new ModelsResponse(models);
    }

    public record ModelsResponse(List<ModelStatus> models) {}
    public record ModelStatus(String id, String modelName, boolean defaultModel,
            List<String> roles, ModelConnectivityRegistry.Connectivity connectivity) {}
}
```

- [x] **Step 4: 通过**

---

### Task 4: 恢复财报接口并支持 modelId

**Files:**
- Create: `src/main/java/com/astock/agent/web/FinancialReportController.java`
- Modify: `src/main/java/com/astock/agent/agent/financial/FinancialReportService.java`（`generate(code, modelId)`）
- Create: `src/test/java/com/astock/agent/web/FinancialReportControllerTest.java`
- Modify: `src/test/java/com/astock/agent/agent/financial/FinancialReportServiceTest.java`

**Interfaces:**
- Produces: `POST /api/agent/financial-report` `{code, modelId?}` → `FinancialReportAnalysis`
- `generate(String code, String modelId)`

- [x] **Step 1: 写失败测试**
  - Controller：200 + `generationMode` 字段；非法 code → 400 `INVALID_SECURITY_CODE`；service 异常 → problem details。
  - Service：`generate(code, "mimo")` 使用 `byId("mimo")`；`generate(code, "unknown")` 抛 `ModelNotAvailableException` 且不取财报；`generate(code, null)` 走 `forRole("financial-report")`。
- [x] **Step 2: 运行确认失败**
- [x] **Step 3: 实现**

`FinancialReportService`：

```java
public FinancialReportAnalysis generate(String code) { return generate(code, null); }

public FinancialReportAnalysis generate(String code, String modelId) {
    SecurityId security = SecurityId.parse(code);      // 保持现有逻辑
    ... // 财报数据检查、评分、趋势、证据包
    Optional<NamedModel> model = resolveModel(modelId);
    if (model.isEmpty()) {
        if (modelId != null && !modelId.isBlank()) throw new ModelNotAvailableException();
        return compose(GenerationMode.DETERMINISTIC_FALLBACK, pack, section, null, null, null);
    }
    ... // 其余不变
}

private Optional<NamedModel> resolveModel(String modelId) {
    return modelId == null || modelId.isBlank() ? registry.forRole(ROLE) : registry.byId(modelId);
}
```

Controller：

```java
@RestController
@RequestMapping("/api/agent")
public final class FinancialReportController {
    private final FinancialReportService service;

    @PostMapping("/financial-report")
    public FinancialReportAnalysis financialReport(@RequestBody Request request) {
        return service.generate(request.code(), request.modelId());
    }

    public record Request(String code, String modelId) {}
}
```

（`ApiExceptionHandler` 已处理 `IllegalArgumentException`/`ModelNotAvailableException` 与 `FinancialDataUnavailableException`，实现时确认映射存在。）

- [x] **Step 4: 通过**

---

### Task 5: UZI 支持 modelId

**Files:**
- Modify: `src/main/java/com/astock/agent/web/UziController.java`
- Modify: `src/main/java/com/astock/agent/agent/uzi/UziResearchService.java`
- Modify: `src/test/java/com/astock/agent/web/UziControllerTest.java`
- Modify: `src/test/java/com/astock/agent/agent/uzi/UziResearchServiceTest.java`

**Interfaces:**
- `Request(code, depth, school, modelId)`；`start(code, depth, school, modelId)`

- [x] **Step 1: 写失败测试**：显式 `modelId` 解析到对应模型并把 `modelName` 传给 worker；未知显式 ID 拒绝；未传走角色默认；请求体 JSON 缺省 `modelId` 时兼容。
- [x] **Step 2: 运行确认失败**
- [x] **Step 3: 实现**：`start` 内 `modelId` 非空时 `byId`，为空时 `forRole(ROLE)`；保留旧三参重载委托 `(code, depth, school, null)`。Controller record 增加 `modelId`，并保留三参构造以便旧前端。
- [x] **Step 4: 通过**

---

### Task 6: 前端统一选择器与模块接入

**Files:**
- Create: `src/main/resources/static/js/model-selection.js`
- Modify: `src/main/resources/static/js/api.js`（`aiApi.models()`、`startUzi(..., modelId)`、`financialReport(code, modelId)`）
- Modify: `src/main/resources/static/workbench.html`（header picker）
- Modify: `src/main/resources/static/js/app.js`（picker 渲染/状态；删除旧 FinRobot 模型函数）
- Modify: `src/main/resources/static/js/views.js`（删除旧引擎 `#finrobot-model-select`）
- Modify: `src/main/resources/static/js/cycle-view.js`、`finrobot-official-view.js`、`uzi-view.js`、`financial-view.js`（去掉模块选择器/只读模型，提交携带全局 modelId）
- Modify: `src/main/resources/static/styles.css`（picker 样式与响应式）

**Interfaces:**
- `getSelectedModelId()` / `setSelectedModelId(id)`（localStorage key `astock.selectedModelId`）
- `aiApi.models()` → `{models:[...]}`

- [x] **Step 1: model-selection.js**

```js
const KEY = "astock.selectedModelId";

export function getSelectedModelId() {
  try { return window.localStorage.getItem(KEY) || null; } catch { return null; }
}

export function setSelectedModelId(modelId) {
  try {
    if (modelId) window.localStorage.setItem(KEY, modelId);
    else window.localStorage.removeItem(KEY);
  } catch { /* 隐私模式忽略 */ }
  window.dispatchEvent(new CustomEvent("model-selection-change", { detail: { modelId } }));
}
```

- [x] **Step 2: header markup**（替换 `#finrobot-model-status` chip）

```html
<div class="model-picker" id="model-picker">
  <button id="model-picker-toggle" class="status-chip model-picker-toggle" type="button"
          aria-haspopup="listbox" aria-expanded="false" aria-label="选择大模型">
    <span class="status-dot" data-state="unknown"></span>
    <span id="model-picker-label">模型：读取中</span>
  </button>
  <button id="model-picker-refresh" class="icon-button icon-button-sm" type="button"
          aria-label="重新读取模型列表" title="重新读取模型列表">
    <i data-lucide="refresh-cw" aria-hidden="true"></i>
  </button>
  <div id="model-picker-menu" class="model-picker-menu" role="listbox" hidden></div>
</div>
```

- [x] **Step 3: app.js**

```js
import { getSelectedModelId, setSelectedModelId } from "./model-selection.js";

async function loadModelCatalog() {
  try {
    const payload = await aiApi.models();
    state.modelCatalog = Array.isArray(payload?.models) ? payload.models : [];
    renderModelPicker();
  } catch {
    state.modelCatalog = [];
    renderModelPicker({ failed: true });
  }
}

function renderModelPicker({ failed = false } = {}) {
  const models = state.modelCatalog;
  const stored = getSelectedModelId();
  let selected = models.find((m) => m.id === stored) || models.find((m) => m.defaultModel) || models[0] || null;
  if (selected && selected.id !== stored) setSelectedModelId(selected.id);
  // 按钮：圆点 + `${id} · ${modelName}`；失败时「模型目录不可用」
  // 菜单：每项圆点（ok/failed/unknown）+ 名称 + 延迟文本；点击 setSelectedModelId 并关闭
  // 键盘：ArrowUp/Down/Enter/Escape；aria-expanded 同步
}
```

- [x] **Step 4: 模块接入**
  - `finrobot-official-view.js`：`start()` 请求体加 `modelId: getSelectedModelId()`；删除 `#official-finrobot-model` 相关代码。
  - `cycle-view.js`：同上（删除 `#cycle-model` 与 models 拉取）。
  - `views.js`/`app.js`：删除旧引擎 select 及 `loadFinRobotModels` 等函数，`#run-finrobot` 提交加 `modelId`。
  - `uzi-view.js`：删除只读模型行；`startUzi(code, depth, school, getSelectedModelId())`。
  - `financial-view.js`：`financialReport(code, getSelectedModelId())`。
  - `api.js`：`export const aiApi = { models() { return request('/api/ai/models'); } };`；UZI/财报签名加 modelId。
- [x] **Step 5: styles.css**：`.model-picker`（relative、flex、gap）、`.model-picker-menu`（absolute、surface、hairline、shadow、role=listbox）、`.model-picker-option`（flex、圆点、hover）、`.status-dot[data-state=ok|failed|unknown]`（绿/红/灰，沿用 down/up 令牌语义色或新中性色）、≤520 宽度菜单右对齐且不溢出；≤1180 保持可见。
- [x] **Step 6: 版本号更新**：`workbench.html` 中 `app.js?v=...` 与新 CSS 版本号。

---

### Task 7: UI 测试更新与新增

**Files:**
- Create: `tests/ui/model-picker.spec.js`
- Create: `tests/ui/fixtures/ai-models.json`
- Modify: `tests/ui/dashboard.spec.js`、`cycle-research.spec.js`、`uzi-research.spec.js`、`finrobot-official.spec.js`、`financial-report.spec.js`

- [x] **Step 1: fixture**：3 个模型（deepseek OK/default、mimo FAILED、primary UNKNOWN）+ roles。
- [x] **Step 2: model-picker.spec.js**
  - 四视口：绿/红/灰点与文本状态（`aria-label`/`title`），菜单不溢出页面；
  - 选择 `mimo` 后刷新页面仍选中（localStorage）；
  - 选择后依次切换四个模块提交，断言请求体含 `modelId:"mimo"`（沿用各 spec 的 mock 结构）；
  - 点击刷新只请求一次 `/api/ai/models`，不产生任何 `/probe` 调用。
- [x] **Step 3: 更新既有 spec**：把 `#finrobot-model-status`/`#finrobot-model-select`/`#official-finrobot-model`/`#cycle-model` 相关断言改为统一选择器；UZI 请求体断言加 `modelId`；财报断言不变并确认路由 mock 仍生效。
- [x] **Step 4: 运行**

```
npx playwright test tests/ui/model-picker.spec.js
npx playwright test tests/ui/dashboard.spec.js
npx playwright test tests/ui/<其余 spec...>
```

Expected: 全绿；截图人工检查。

---

### Task 8: 文档与全量验证

**Files:**
- Modify: `docs/architecture/data-flow.md`（如描述模型配置则补一段）、`README.md`（若描述模型选择）

- [x] **Step 1: 文档**：说明启动探测、统一目录、localStorage 选择与各模块 modelId。
- [x] **Step 2: 本地人工验证**：`start.ps1` 启动，浏览器检查选择器、绿/红点、财报 Tab 可生成（404 已恢复）。
- [x] **Step 3: 全量**

```
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
npm.cmd run test:ui
git diff --check
```

- [x] **Step 4: 汇总并请求提交确认**
