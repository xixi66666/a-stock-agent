# Selectable Overall Report Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to choose any configured named model when generating an overall report while preserving the `overall-report` role as the backward-compatible default.

**Architecture:** Extend `NamedChatClientRegistry` with safe model lookup and metadata-only catalog operations. Resolve the selected model per request in `OverallReportService`, expose a read-only model catalog through `AgentController`, and render that catalog as a frontend selector whose selected ID is included in the existing overall-report request.

**Tech Stack:** Java 21, Spring Boot, Spring AI `ChatClient`, MockMvc, JUnit 5, AssertJ, vanilla HTML/CSS/JavaScript, Playwright.

---

## File structure

- Modify `src/main/java/com/astock/agent/agent/model/NamedChatClientRegistry.java`: safe lookup by model ID, default model ID resolution, and metadata-only catalog.
- Modify `src/test/java/com/astock/agent/agent/model/NamedChatClientRegistryTest.java`: registry selection and catalog contract.
- Create `src/main/java/com/astock/agent/agent/model/ModelNotAvailableException.java`: explicit client-input error for unknown or unavailable model IDs.
- Modify `src/main/java/com/astock/agent/agent/overall/OverallReportService.java`: resolve a generator from the selected named model for every request.
- Modify `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java`: explicit selection, default selection, and no-fetch rejection tests.
- Modify `src/main/java/com/astock/agent/agent/AgentConfiguration.java`: inject the registry into the service instead of constructing a fixed DeepSeek generator.
- Modify `src/main/java/com/astock/agent/web/AgentController.java`: model catalog endpoint and optional `modelId` request field.
- Modify `src/main/java/com/astock/agent/web/ApiExceptionHandler.java`: stable `MODEL_NOT_AVAILABLE` Problem Details response.
- Modify `src/test/java/com/astock/agent/web/AgentControllerTest.java`: Web contract, compatibility, and metadata redaction tests.
- Modify `src/main/resources/static/js/api.js`: model catalog call and selected-model request body.
- Modify `src/main/resources/static/js/views.js`: accessible model selector and provider-neutral copy.
- Modify `src/main/resources/static/js/app.js`: catalog state, selection synchronization, and request routing.
- Modify `src/main/resources/static/styles.css`: compact selector styling across required viewports.
- Modify `src/test/java/com/astock/agent/web/StaticResourceTest.java`: static contract no longer hard-codes DeepSeek and includes the model selector API.
- Modify `tests/ui/dashboard.spec.js`: dynamic options, request body, empty catalog, and provider-neutral rendering.
- Modify `README.md`: document selectable overall-report models and default fallback semantics.

## Verification prerequisite

The repository declares Java 21 in `pom.xml`. Before running any Maven red/green step, run:

```powershell
.\mvnw.cmd -version
```

Expected: `Java version: 21`. If Maven reports Java 8, 11, or 17, stop and point `JAVA_HOME` and the current PowerShell process `Path` to an installed JDK 21. Do not lower `<java.version>` and do not treat `UnsupportedClassVersionError` as a test failure for the behavior under development.

### Task 1: Add safe named-model lookup and catalog metadata

**Files:**
- Modify: `src/test/java/com/astock/agent/agent/model/NamedChatClientRegistryTest.java`
- Modify: `src/main/java/com/astock/agent/agent/model/NamedChatClientRegistry.java`

- [ ] **Step 1: Write the failing registry tests**

Add these tests to `NamedChatClientRegistryTest`:

```java
@Test
void exposesSafeCatalogAndMarksRoleDefault() {
    ChatClient primary = mock(ChatClient.class);
    ChatClient mimo = mock(ChatClient.class);
    NamedChatClientRegistry registry = new NamedChatClientRegistry(
            Map.of(
                    "primary", new NamedChatClientRegistry.NamedModel(primary, "gpt-5"),
                    "mimo", new NamedChatClientRegistry.NamedModel(mimo, "mimo-v2.5-pro")),
            Map.of("overall-report", "mimo"));

    assertThat(registry.availableModels("overall-report"))
            .extracting(
                    NamedChatClientRegistry.ModelReference::id,
                    NamedChatClientRegistry.ModelReference::modelName,
                    NamedChatClientRegistry.ModelReference::defaultModel)
            .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("mimo", "mimo-v2.5-pro", true),
                    org.assertj.core.groups.Tuple.tuple("primary", "gpt-5", false));
    assertThat(registry.modelIdForRole("overall-report")).contains("mimo");
}

@Test
void resolvesOnlyRegisteredModelsById() {
    ChatClient client = mock(ChatClient.class);
    NamedChatClientRegistry registry = new NamedChatClientRegistry(
            Map.of("primary", new NamedChatClientRegistry.NamedModel(client, "gpt-5")),
            Map.of());

    assertThat(registry.byId(" primary ").orElseThrow().client()).isSameAs(client);
    assertThat(registry.byId("missing")).isEmpty();
    assertThat(registry.byId(" ")).isEmpty();
}
```

- [ ] **Step 2: Run the test and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=NamedChatClientRegistryTest' test
```

Expected: compilation fails because `availableModels`, `modelIdForRole`, `byId`, and `ModelReference` do not exist.

- [ ] **Step 3: Implement the minimal safe registry API**

Add the imports and methods below to `NamedChatClientRegistry`:

```java
import java.util.Comparator;
import java.util.List;

public Optional<NamedModel> byId(String modelId) {
    if (modelId == null || modelId.isBlank()) {
        return Optional.empty();
    }
    return Optional.ofNullable(models.get(modelId.trim()));
}

public Optional<String> modelIdForRole(String role) {
    if (role == null || role.isBlank()) {
        return Optional.empty();
    }
    return Optional.ofNullable(roles.get(role))
            .filter(modelId -> models.containsKey(modelId));
}

public List<ModelReference> availableModels(String defaultRole) {
    String defaultId = modelIdForRole(defaultRole).orElse(null);
    return models.entrySet().stream()
            .map(entry -> new ModelReference(
                    entry.getKey(),
                    entry.getValue().modelName(),
                    entry.getKey().equals(defaultId)))
            .sorted(Comparator.comparing(ModelReference::id))
            .toList();
}

public record ModelReference(String id, String modelName, boolean defaultModel) {
}
```

Keep `forRole` and `availability` unchanged so existing callers remain compatible.

- [ ] **Step 4: Run the focused test and verify GREEN**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=NamedChatClientRegistryTest' test
```

Expected: all `NamedChatClientRegistryTest` tests pass.

- [ ] **Step 5: Commit the registry slice**

```powershell
git add src/main/java/com/astock/agent/agent/model/NamedChatClientRegistry.java src/test/java/com/astock/agent/agent/model/NamedChatClientRegistryTest.java
git commit -m "feat: expose safe named model catalog"
```

### Task 2: Resolve the overall-report generator per request

**Files:**
- Create: `src/main/java/com/astock/agent/agent/model/ModelNotAvailableException.java`
- Modify: `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java`
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallReportService.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`

- [ ] **Step 1: Replace fixed-generator test setup with selection tests**

In `OverallReportServiceTest`, add imports for `NamedChatClientRegistry`, `ChatClient`, `HashMap`, and Mockito. Add these tests; keep the existing report validation and failure-diagnostic assertions, adapting their calls to `service(Map.of(...), defaultId)`:

```java
@Test
void explicitModelSelectionUsesRequestedGenerator() {
    AtomicInteger primaryCalls = new AtomicInteger();
    AtomicInteger mimoCalls = new AtomicInteger();
    OverallReportGenerator primary = generator("gpt-5", primaryCalls);
    OverallReportGenerator mimo = generator("mimo-v2.5-pro", mimoCalls);

    OverallReportResponse response = service(
            Map.of("gpt-5", primary, "mimo-v2.5-pro", mimo), "gpt-5")
            .generate("600519", "mimo");

    assertThat(response.report().modelName()).isEqualTo("mimo-v2.5-pro");
    assertThat(primaryCalls).hasValue(0);
    assertThat(mimoCalls).hasValue(1);
}

@Test
void missingModelIdUsesOverallReportRoleDefault() {
    AtomicInteger calls = new AtomicInteger();
    OverallReportResponse response = service(
            Map.of("mimo-v2.5-pro", generator("mimo-v2.5-pro", calls)), "mimo-v2.5-pro")
            .generate("600519");

    assertThat(response.report().modelName()).isEqualTo("mimo-v2.5-pro");
    assertThat(calls).hasValue(1);
}

@Test
void unknownExplicitModelIsRejectedBeforeSnapshotFetch() {
    AtomicInteger fetches = new AtomicInteger();
    StockAgentTools tools = new StockAgentTools(id -> {
        fetches.incrementAndGet();
        return snapshot;
    });
    NamedChatClientRegistry registry = registry("gpt-5");
    OverallReportService service = new OverallReportService(
            registry, tools, new OverallReportValidator(), new ModelFailureClassifier(),
            named -> generator(named.modelName(), new AtomicInteger()));

    assertThatThrownBy(() -> service.generate("600519", "unknown"))
            .isInstanceOf(ModelNotAvailableException.class);
    assertThat(fetches).hasValue(0);
}
```

Use these complete helpers in the same test class:

```java
private OverallReportService service(Map<String, OverallReportGenerator> generators, String defaultModelName) {
    NamedChatClientRegistry registry = registry(defaultModelName);
    return new OverallReportService(
            registry,
            new StockAgentTools(id -> snapshot),
            new OverallReportValidator(),
            new ModelFailureClassifier(),
            named -> generators.get(named.modelName()));
}

private NamedChatClientRegistry registry(String defaultModelName) {
    Map<String, NamedChatClientRegistry.NamedModel> models = new java.util.LinkedHashMap<>();
    models.put("primary", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "gpt-5"));
    models.put("mimo", new NamedChatClientRegistry.NamedModel(mock(ChatClient.class), "mimo-v2.5-pro"));
    String defaultId = "mimo-v2.5-pro".equals(defaultModelName) ? "mimo" : "primary";
    return new NamedChatClientRegistry(models, Map.of("overall-report", defaultId));
}

private OverallReportGenerator generator(String modelName, AtomicInteger calls) {
    return new OverallReportGenerator() {
        @Override
        public OverallReportDraft generate(StockResearchSnapshot ignored) {
            calls.incrementAndGet();
            return validDraft();
        }

        @Override
        public String modelName() {
            return modelName;
        }
    };
}
```

- [ ] **Step 2: Run the service test and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportServiceTest' test
```

Expected: compilation fails because the registry-based constructor, `generate(code, modelId)`, and `ModelNotAvailableException` do not exist.

- [ ] **Step 3: Add the explicit selection exception**

Create `ModelNotAvailableException.java`:

```java
package com.astock.agent.agent.model;

/** 请求指定的命名模型未注册或不可用。 */
public final class ModelNotAvailableException extends IllegalArgumentException {

    public ModelNotAvailableException() {
        super("Selected model is not available");
    }
}
```

- [ ] **Step 4: Refactor `OverallReportService` to select a model before data access**

Replace the fixed `generator` field and constructor with:

```java
private static final String OVERALL_REPORT_ROLE = "overall-report";

@FunctionalInterface
interface GeneratorFactory {
    OverallReportGenerator create(NamedChatClientRegistry.NamedModel model);
}

private final NamedChatClientRegistry registry;
private final StockAgentTools tools;
private final OverallReportValidator validator;
private final ModelFailureClassifier classifier;
private final GeneratorFactory generatorFactory;

public OverallReportService(
        NamedChatClientRegistry registry,
        StockAgentTools tools,
        OverallReportValidator validator,
        ModelFailureClassifier classifier) {
    this(registry, tools, validator, classifier,
            model -> new SpringAiOverallReportGenerator(model.client(), model.modelName()));
}

OverallReportService(
        NamedChatClientRegistry registry,
        StockAgentTools tools,
        OverallReportValidator validator,
        ModelFailureClassifier classifier,
        GeneratorFactory generatorFactory) {
    this.registry = Objects.requireNonNull(registry, "registry is required");
    this.tools = Objects.requireNonNull(tools, "tools is required");
    this.validator = Objects.requireNonNull(validator, "validator is required");
    this.classifier = Objects.requireNonNull(classifier, "classifier is required");
    this.generatorFactory = Objects.requireNonNull(generatorFactory, "generatorFactory is required");
}

public java.util.List<NamedChatClientRegistry.ModelReference> availableModels() {
    return registry.availableModels(OVERALL_REPORT_ROLE);
}

public OverallReportResponse generate(String code) {
    return generate(code, null);
}

public OverallReportResponse generate(String code, String modelId) {
    boolean explicitSelection = modelId != null && !modelId.isBlank();
    java.util.Optional<NamedChatClientRegistry.NamedModel> selected = explicitSelection
            ? registry.byId(modelId)
            : registry.forRole(OVERALL_REPORT_ROLE);
    if (selected.isEmpty()) {
        if (explicitSelection) {
            throw new ModelNotAvailableException();
        }
        return new OverallReportResponse(
                OverallReportStatus.MODEL_NOT_CONFIGURED,
                null,
                null,
                "总体报告模型未配置");
    }
    OverallReportGenerator generator = generatorFactory.create(selected.orElseThrow());
    return generate(code, generator);
}
```

Move the current timed generation body into a private `generate(String code, OverallReportGenerator generator)` method. Preserve snapshot acquisition, validation, report assembly, diagnostics, and trace IDs exactly. Change only the provider-specific failure message to:

```java
"总体报告生成失败"
```

Add imports for `NamedChatClientRegistry` and `ModelNotAvailableException`.

- [ ] **Step 5: Simplify Spring configuration**

Replace the fixed generator creation in `AgentConfiguration.overallReportService` with:

```java
@Bean
OverallReportService overallReportService(
        NamedChatClientRegistry registry,
        StockAgentTools tools) {
    return new OverallReportService(
            registry,
            tools,
            new OverallReportValidator(),
            new ModelFailureClassifier());
}
```

Remove the now-unused imports for `OverallReportGenerator`, `SpringAiOverallReportGenerator`, and the fixed overall-report construction path.

- [ ] **Step 6: Run focused service and configuration tests and verify GREEN**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportServiceTest,NamedChatClientRegistryTest' test
```

Expected: all selected tests pass; explicit unknown selection performs zero snapshot fetches.

- [ ] **Step 7: Commit the routing slice**

```powershell
git add src/main/java/com/astock/agent/agent/model/ModelNotAvailableException.java src/main/java/com/astock/agent/agent/overall/OverallReportService.java src/main/java/com/astock/agent/agent/AgentConfiguration.java src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java
git commit -m "feat: route overall reports by selected model"
```

### Task 3: Expose the model directory and selected-model Web contract

**Files:**
- Modify: `src/test/java/com/astock/agent/web/AgentControllerTest.java`
- Modify: `src/main/java/com/astock/agent/web/AgentController.java`
- Modify: `src/main/java/com/astock/agent/web/ApiExceptionHandler.java`

- [ ] **Step 1: Write failing MockMvc tests**

Add the following tests to `AgentControllerTest`:

```java
@Test
void listsOnlySafeOverallReportModelMetadata() throws Exception {
    OverallReportService overall = mock(OverallReportService.class);
    when(overall.availableModels()).thenReturn(List.of(
            new NamedChatClientRegistry.ModelReference("deepseek", "deepseek-chat", true),
            new NamedChatClientRegistry.ModelReference("mimo", "mimo-v2.5-pro", false)));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
            new AgentStatusService(new MockEnvironment()), null, overall)).build();

    mvc.perform(get("/api/agent/models").param("capability", "overall-report"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.models[0].id").value("deepseek"))
            .andExpect(jsonPath("$.models[0].modelName").value("deepseek-chat"))
            .andExpect(jsonPath("$.models[0].defaultModel").value(true))
            .andExpect(jsonPath("$.models[0].apiKey").doesNotExist())
            .andExpect(jsonPath("$.models[0].baseUrl").doesNotExist());
}

@Test
void overallReportPassesSelectedModelIdToService() throws Exception {
    OverallReportService overall = mock(OverallReportService.class);
    when(overall.generate("600519", "mimo")).thenReturn(new OverallReportResponse(
            OverallReportStatus.MODEL_NOT_CONFIGURED, null, null, "test"));
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
            new AgentStatusService(new MockEnvironment()), null, overall)).build();

    mvc.perform(post("/api/agent/overall-report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"600519\",\"modelId\":\"mimo\"}"))
            .andExpect(status().isOk());

    org.mockito.Mockito.verify(overall).generate("600519", "mimo");
}

@Test
void unknownModelUsesStableProblemDetails() throws Exception {
    OverallReportService overall = mock(OverallReportService.class);
    when(overall.generate("600519", "unknown")).thenThrow(new ModelNotAvailableException());
    MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentController(
                    new AgentStatusService(new MockEnvironment()), null, overall))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    mvc.perform(post("/api/agent/overall-report")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"600519\",\"modelId\":\"unknown\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MODEL_NOT_AVAILABLE"));
}
```

Update the existing compatibility stub from `when(overall.generate("600519"))` to `when(overall.generate("600519", null))`, then verify that the old `{ "code": "600519" }` request calls `generate("600519", null)`.

- [ ] **Step 2: Run the Web test and verify RED**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AgentControllerTest' test
```

Expected: tests fail because the models endpoint and `OverallReportRequest.modelId` routing are absent, and the exception still maps to `INVALID_SECURITY_CODE`.

- [ ] **Step 3: Implement the typed Web contract**

Add `RequestParam`, `List`, `NamedChatClientRegistry`, and `ModelNotAvailableException` imports. Add this endpoint and response record to `AgentController`:

```java
@GetMapping("/models")
public ModelsResponse models(
        @RequestParam(defaultValue = "overall-report") String capability) {
    if (!"overall-report".equals(capability)) {
        throw new IllegalArgumentException("Unsupported model capability");
    }
    if (overallReports == null) {
        return new ModelsResponse(List.of());
    }
    return new ModelsResponse(overallReports.availableModels());
}

public record ModelsResponse(List<NamedChatClientRegistry.ModelReference> models) {
    public ModelsResponse {
        models = models == null ? List.of() : List.copyOf(models);
    }
}
```

Replace the overall-report request method and add its request record:

```java
@PostMapping("/overall-report")
public OverallReportResponse overallReport(@RequestBody OverallReportRequest request) {
    SecurityId.parse(request.code());
    if (overallReports == null) {
        throw new IllegalStateException("Overall report service is unavailable");
    }
    return overallReports.generate(request.code(), request.modelId());
}

public record OverallReportRequest(String code, String modelId) {
}
```

Add this handler above the general `IllegalArgumentException` handler in `ApiExceptionHandler`:

```java
@ExceptionHandler(ModelNotAvailableException.class)
ResponseEntity<ProblemDetail> modelNotAvailable(ModelNotAvailableException exception) {
    return problem(
            HttpStatus.BAD_REQUEST,
            "MODEL_NOT_AVAILABLE",
            "模型不可用",
            exception.getMessage());
}
```

- [ ] **Step 4: Run focused Web and service tests and verify GREEN**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AgentControllerTest,OverallReportServiceTest' test
```

Expected: all tests pass, old requests remain compatible, and the catalog JSON contains no connection data.

- [ ] **Step 5: Commit the API slice**

```powershell
git add src/main/java/com/astock/agent/web/AgentController.java src/main/java/com/astock/agent/web/ApiExceptionHandler.java src/test/java/com/astock/agent/web/AgentControllerTest.java
git commit -m "feat: expose selectable overall report models"
```

### Task 4: Add the accessible frontend model selector

**Files:**
- Modify: `tests/ui/dashboard.spec.js`
- Modify: `src/test/java/com/astock/agent/web/StaticResourceTest.java`
- Modify: `src/main/resources/static/js/api.js`
- Modify: `src/main/resources/static/js/views.js`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/styles.css`

- [ ] **Step 1: Make the default UI fixture serve the model directory**

Add this route to `mockApis(page)` in `dashboard.spec.js`:

```javascript
await page.route("**/api/agent/models?capability=overall-report", (route) => route.fulfill({ json: {
  models: [
    { id: "deepseek", modelName: "deepseek-chat", defaultModel: true },
    { id: "mimo", modelName: "mimo-v2.5-pro", defaultModel: false },
    { id: "primary", modelName: "gpt-5", defaultModel: false },
  ],
} }));
```

- [ ] **Step 2: Write the failing selection and request-routing UI test**

Replace the provider-specific overall-report UI test name and add request-body capture:

```javascript
test("overall report model can be selected per request", async ({ page }) => {
  let requestBody = null;
  await page.route("**/api/agent/overall-report", async (route) => {
    requestBody = route.request().postDataJSON();
    await route.fulfill({ json: {
      status: "MODEL_ASSISTED",
      report: {
        overallConclusion: "总体判断内容",
        dataQualitySummary: "数据质量摘要",
        companyAndFundamentals: "基本面",
        technicalAndCapital: "技术与资金",
        valuationAndIndustry: "估值与行业",
        eventsAndSentiment: "事件与情绪",
        bullishEvidence: [], bearishEvidence: [], riskFactors: [], scenarios: {},
        conflictsAndMissingData: [], sourceReferences: [],
        modelName: "mimo-v2.5-pro", disclaimer: "仅供学习研究，不构成投资建议",
      },
    } });
  });

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  const selector = page.getByLabel("总体报告模型");
  await expect(selector).toHaveValue("deepseek");
  await selector.selectOption("mimo");
  await page.getByRole("button", { name: /生成总体报告/ }).click();

  expect(requestBody).toEqual({ code: "600519", modelId: "mimo" });
  await expect(page.locator("#overall-report-output")).toContainText("mimo-v2.5-pro");
  await expect(page.locator("#overall-report-output")).not.toContainText("DeepSeek 总体报告");
});
```

- [ ] **Step 3: Write the failing empty-catalog UI test**

```javascript
test("overall report generation is disabled when no model is available", async ({ page }) => {
  await page.unroute("**/api/agent/models?capability=overall-report");
  await page.route("**/api/agent/models?capability=overall-report", (route) =>
    route.fulfill({ json: { models: [] } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();

  await expect(page.getByLabel("总体报告模型")).toBeDisabled();
  await expect(page.getByRole("button", { name: /生成总体报告/ })).toBeDisabled();
  await expect(page.locator("#overall-model-help")).toContainText("没有可用模型");
  await expect(page.getByRole("button", { name: "生成研究报告" })).toBeEnabled();
});
```

Add static assertions to `StaticResourceTest` that `views.js` contains `overall-model-select`, `api.js` contains `/api/agent/models?capability=overall-report`, and `app.js` contains `modelId`; assert the overall-report UI templates do not contain `DeepSeek 总体报告`.

- [ ] **Step 4: Run UI and static tests and verify RED**

Start the application using the repository's normal local process, then run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StaticResourceTest' test
npm.cmd run test:ui
```

Expected: static and Playwright tests fail because the selector, catalog request, and `modelId` request field are absent.

- [ ] **Step 5: Add API methods**

Replace the current `overallReport` method in `api.js` and add `overallModels`:

```javascript
overallModels() {
  return request("/api/agent/models?capability=overall-report");
},
overallReport(code, modelId) {
  return request("/api/agent/overall-report", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code, modelId }),
  });
},
```

- [ ] **Step 6: Replace provider-specific view markup**

In `views.js`, replace the Agent controls with this provider-neutral markup while retaining the surrounding two-column layout:

```html
<span class="section-kicker">INSTITUTIONAL + OVERALL</span>
<h3>研究报告</h3>
<p>两份报告独立生成，并保留数据来源、缺失项与风险边界。</p>
<button id="run-agent" class="primary-command agent-command" type="button">
  <i data-lucide="sparkles" aria-hidden="true"></i><span>生成研究报告</span>
</button>
<label class="overall-model-field" for="overall-model-select">
  <span>总体报告模型</span>
  <select id="overall-model-select" disabled>
    <option value="">正在加载可用模型</option>
  </select>
</label>
<small id="overall-model-help" class="overall-model-help">正在读取本地模型配置</small>
<button id="run-overall-report" class="secondary-command agent-command" type="button" disabled>
  <i data-lucide="file-chart-column" aria-hidden="true"></i>
  <span>生成总体报告</span><small id="overall-model-name">未选择</small>
</button>
```

Use this initial output copy:

```html
<div id="overall-report-output" class="overall-report-output" aria-live="polite">
  <span class="source-status" data-status="UNAVAILABLE"><span></span>等待生成</span>
  <p>所选模型将读取当前股票的完整规范化快照。</p>
</div>
```

- [ ] **Step 7: Add frontend model state and synchronization**

Add these state fields in `app.js`:

```javascript
overallModelsPhase: "idle",
overallModels: [],
selectedOverallModelId: null,
```

Add these functions:

```javascript
function syncOverallModelControls() {
  const select = $("#overall-model-select");
  const button = $("#run-overall-report");
  const help = $("#overall-model-help");
  const modelName = $("#overall-model-name");
  if (!select || !button || !help || !modelName) return;

  const models = state.overallModels;
  const selected = models.find((model) => model.id === state.selectedOverallModelId);
  select.innerHTML = models.length
    ? models.map((model) => `<option value="${escapeText(model.id)}">${escapeText(model.id)} · ${escapeText(model.modelName)}</option>`).join("")
    : '<option value="">没有可用模型</option>';
  select.value = selected?.id || "";
  select.disabled = !models.length || state.overallReportPhase === "loading";
  button.disabled = !selected || state.overallReportPhase === "loading";
  modelName.textContent = selected?.modelName || "未选择";
  help.textContent = models.length ? "请选择生成本次总体报告的模型" : "没有可用模型，请检查本地配置";
}

async function loadOverallModels() {
  if (state.overallModelsPhase === "loading" || state.overallModelsPhase === "ready") {
    syncOverallModelControls();
    return;
  }
  state.overallModelsPhase = "loading";
  syncOverallModelControls();
  try {
    const response = await stockApi.overallModels();
    state.overallModels = Array.isArray(response?.models) ? response.models : [];
    const stillSelected = state.overallModels.some((model) => model.id === state.selectedOverallModelId);
    if (!stillSelected) {
      state.selectedOverallModelId = state.overallModels.find((model) => model.defaultModel)?.id
        || state.overallModels[0]?.id
        || null;
    }
    state.overallModelsPhase = "ready";
  } catch {
    state.overallModels = [];
    state.selectedOverallModelId = null;
    state.overallModelsPhase = "failed";
  }
  syncOverallModelControls();
}

function bindOverallModelControls() {
  const select = $("#overall-model-select");
  if (!select) return;
  select.addEventListener("change", () => {
    state.selectedOverallModelId = select.value || null;
    syncOverallModelControls();
  });
  loadOverallModels();
}
```

Call `bindOverallModelControls()` before `bindOverallReportAction()` whenever the Agent view is rendered.

- [ ] **Step 8: Send the selected model and remove fixed DeepSeek copy**

In `bindOverallReportAction`, capture and submit the selected ID:

```javascript
const selectedModelId = state.selectedOverallModelId;
if (!selectedModelId) return;
state.overallReportPhase = "loading";
syncOverallModelControls();
output.innerHTML = '<span class="source-status" data-status="DEGRADED"><span></span>正在生成总体报告</span><p>所选模型正在读取当前股票的完整数据快照。</p>';
const payload = await stockApi.overallReport(reportCode, selectedModelId);
```

In `finally`, set the phase to `idle` and call `syncOverallModelControls()` instead of always enabling the button. Change the fallback error message to `请检查所选模型的本地配置`.

In `renderOverallReportResponse`, change the status label from `DeepSeek 总体报告` to `总体报告` and remove the hard-coded fallback model name; use `report.modelName || "未知模型"`.

- [ ] **Step 9: Style the selector without changing the design system**

Add to `styles.css`:

```css
.overall-model-field { display: grid; gap: 5px; margin-top: 4px; color: var(--body); font-size: 11px; }
.overall-model-field select { width: 100%; min-height: 34px; padding: 0 30px 0 10px; border: 1px solid var(--hairline-strong); border-radius: 5px; color: var(--ink); background: var(--surface); font: inherit; }
.overall-model-field select:focus-visible { outline: 2px solid var(--purple); outline-offset: 2px; }
.overall-model-field select:disabled { color: var(--muted); background: var(--canvas-soft); }
.overall-model-help { color: var(--muted); overflow-wrap: anywhere; }
```

Do not add a breakpoint-specific width; the existing single-column and sidebar rules allow the native selector to shrink at all required sizes.

- [ ] **Step 10: Run static and UI tests and verify GREEN**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StaticResourceTest' test
npm.cmd run test:ui
```

Expected: static tests pass; all Playwright tests pass, including model selection, empty catalog, existing report independence, and required viewport checks.

- [ ] **Step 11: Commit the UI slice**

```powershell
git add src/main/resources/static/js/api.js src/main/resources/static/js/views.js src/main/resources/static/js/app.js src/main/resources/static/styles.css src/test/java/com/astock/agent/web/StaticResourceTest.java tests/ui/dashboard.spec.js
git commit -m "feat: select overall report model in UI"
```

### Task 5: Update documentation and run full verification

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update user-facing configuration documentation**

Replace DeepSeek-only overall-report language with this behavior contract:

```markdown
总体报告页面会从后端读取所有已启用且配置完整的 `app.ai.models`，生成前可以选择具体模型。`app.ai.roles.overall-report` 只负责指定默认选项；旧客户端未提交 `modelId` 时仍使用该默认模型。浏览器只接收模型 ID、实际模型名和默认标记，不会接收 API Key、Base URL 或连接参数。
```

Update the endpoint table to include:

```markdown
| GET | `/api/agent/models?capability=overall-report` | 获取可用于总体报告的安全模型目录 |
| POST | `/api/agent/overall-report` | 使用请求选择的命名模型生成总体报告；`modelId` 可选 |
```

- [ ] **Step 2: Run focused backend verification**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=NamedChatClientRegistryTest,OverallReportServiceTest,AgentControllerTest,StaticResourceTest' test
```

Expected: all focused tests pass with zero failures and zero errors.

- [ ] **Step 3: Run full offline Java verification**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
```

Expected: both commands exit `0`; tests tagged `external` do not perform live model calls.

- [ ] **Step 4: Run data and UI verification**

```powershell
.\scripts\verify-data.cmd
npm.cmd run test:ui
```

Expected: data verification and all Playwright tests exit `0`. Inspect the four generated viewport screenshots rather than relying only on exit codes.

- [ ] **Step 5: Audit whitespace, secrets, and provider-specific copy**

```powershell
git diff --check
rg -n -i "api[-_]?key|authorization|bearer|token|secret" src/main src/test README.md docs/superpowers/plans/2026-08-04-selectable-overall-report-model.md
rg -n "DeepSeek 总体报告|生成总体报告.*DeepSeek|检查本地 DeepSeek 配置" src/main/resources/static src/main/java
```

Expected: `git diff --check` has no output; the secret scan shows only configuration property names or placeholder documentation and no credential values; the provider-specific UI copy scan has no matches.

- [ ] **Step 6: Review the final diff against the design acceptance criteria**

Confirm all eight acceptance criteria in `docs/superpowers/specs/2026-08-04-selectable-overall-report-model-design.md` with direct code or test evidence. Confirm `out/` remains untracked and is not included in any commit.

- [ ] **Step 7: Commit documentation**

```powershell
git add README.md
git commit -m "docs: explain selectable overall report models"
```
