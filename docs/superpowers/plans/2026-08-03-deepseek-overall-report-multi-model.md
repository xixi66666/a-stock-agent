# DeepSeek 总体报告与多模型并存 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留现有机构研究报告，新增由 DeepSeek 独立生成、显示在页面左侧的总体报告，并让多个 OpenAI 兼容模型通过本地命名配置和角色映射同时运行。

**Architecture:** 使用 `AiModelProperties`、`NamedChatClientRegistry` 和角色映射隔离模型提供方与业务功能；现有报告使用 `institutional-report`，总体报告使用 `overall-report`。总体报告链路为“完整规范化快照 → 专业提示词 → 结构化草稿 → 确定性校验 → 最多一次修复 → 独立响应”，前端使用两个按钮、两个状态和左右分栏呈现。

**Tech Stack:** Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、Jackson、JUnit 5、AssertJ、MockMvc、原生 HTML/CSS/JavaScript、Playwright。

---

## 执行约束

- 当前工作区已经存在未提交的报告增强改动，执行时先检查 `git status --short`，不得覆盖或回滚这些改动。
- `AgentConfiguration.java`、`StockAnalysisAgent.java`、`app.js`、`styles.css` 和相关测试当前已修改；这些文件不得直接整文件暂存并提交，以免把既有改动误归入本功能。
- 每个行为变更严格执行 RED → GREEN → REFACTOR；每个测试必须先观察到预期失败。
- 默认测试不得访问 DeepSeek、OpenAI、MiMo 或任何外部模型。
- `config/application-local.yml` 是本地忽略文件，只追加占位配置，不输出、不暂存、不提交其中的任何值。
- 所有模型错误必须局部化；总体报告失败不能改变现有报告结果。

## 文件职责图

### 新增后端文件

- `src/main/java/com/astock/agent/agent/model/AiModelProperties.java`：绑定命名模型和角色配置。
- `src/main/java/com/astock/agent/agent/model/NamedChatClientRegistry.java`：按模型 ID、角色解析 `ChatClient` 和模型名。
- `src/main/java/com/astock/agent/agent/model/AiModelConfiguration.java`：使用 Spring AI 1.1.8 API 创建 OpenAI 兼容模型；提供旧单模型配置的兼容入口。
- `src/main/java/com/astock/agent/agent/overall/OverallReportDraft.java`：模型结构化草稿。
- `src/main/java/com/astock/agent/agent/overall/OverallSourceReference.java`：总体报告来源引用。
- `src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java`：通过校验后返回的总体报告。
- `src/main/java/com/astock/agent/agent/overall/OverallReportStatus.java`：成功、未配置、模型失败、校验失败状态。
- `src/main/java/com/astock/agent/agent/overall/OverallReportResponse.java`：报告与局部诊断响应。
- `src/main/java/com/astock/agent/agent/overall/OverallReportGenerator.java`：可替换、可测试的生成接口。
- `src/main/java/com/astock/agent/agent/overall/SpringAiOverallReportGenerator.java`：DeepSeek 提示词和结构化调用适配层。
- `src/main/java/com/astock/agent/agent/overall/OverallReportValidator.java`：事实边界、安全、来源和数字校验。
- `src/main/java/com/astock/agent/agent/overall/OverallReportService.java`：完整快照、一次修复、诊断和响应编排。

### 修改后端文件

- `src/main/java/com/astock/agent/agent/AgentConfiguration.java`：用角色注册表装配现有报告与总体报告。
- `src/main/java/com/astock/agent/agent/AgentStatusService.java`：返回角色级模型状态。
- `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`：保留现有逻辑，接收角色指定的模型名和客户端。
- `src/main/java/com/astock/agent/web/AgentController.java`：新增 `/overall-report`，扩展状态响应。
- `src/main/resources/application.yml`：保留默认离线模型状态，增加空角色默认值。
- `config/application-local.yml.example`：改为多模型并存占位模板。
- `README.md`：说明多模型、角色和 DeepSeek 配置。

### 前端文件

- `src/main/resources/static/js/api.js`：新增总体报告请求。
- `src/main/resources/static/js/views.js`：建立左侧操作/总体报告、右侧现有报告的语义容器。
- `src/main/resources/static/js/app.js`：独立按钮、独立状态、切股清理和报告渲染。
- `src/main/resources/static/styles.css`：报告排版、折叠区和四档响应式布局。
- `tests/ui/dashboard.spec.js`：交互、布局、可访问性和响应式回归测试。

---

### Task 0: 建立脏工作区基线

**Files:**
- Inspect only: entire repository

- [ ] **Step 1: 记录现有改动，不修改文件**

Run:

```powershell
git status --short
git diff --stat
```

Expected: 能看到既有报告增强改动；不得执行 reset、checkout 或 clean。

- [ ] **Step 2: 运行现有聚焦测试作为基线**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StockAnalysisAgentTest,InstitutionalReportComposerTest,ReportValidatorTest,LocalModelConfigurationExampleTest,StaticResourceTest' test
```

Expected: PASS；若基线失败，记录失败并先判断是否属于既有改动，不能把本功能实现建立在未知失败上。

- [ ] **Step 3: 运行现有 UI 测试基线**

Run:

```powershell
npm.cmd run test:ui
```

Expected: PASS。若本地服务端口由 Playwright 配置启动方式决定，使用项目当前 `UI_BASE_URL`，不要占用已运行服务。

---

### Task 1: 定义命名模型配置

**Files:**
- Create: `src/main/java/com/astock/agent/agent/model/AiModelProperties.java`
- Create: `src/test/java/com/astock/agent/agent/model/AiModelPropertiesTest.java`
- Modify: `src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java`
- Modify: `config/application-local.yml.example`（Step 5）

- [ ] **Step 1: 写配置对象失败测试**

```java
package com.astock.agent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AiModelPropertiesTest {

    @Test
    void keepsMultipleModelsAndResolvesStableRoles() {
        AiModelProperties properties = new AiModelProperties(
                Map.of(
                        "primary", new AiModelProperties.Model(true, "https://api.openai.com",
                                "test-primary", "/v1/chat/completions", "gpt-test", 0.2),
                        "deepseek", new AiModelProperties.Model(true, "https://api.deepseek.com",
                                "test-deepseek", "/v1/chat/completions", "deepseek-chat", 0.1)),
                Map.of("institutional-report", "primary", "overall-report", "deepseek"));

        assertThat(properties.modelForRole("institutional-report").orElseThrow().getKey()).isEqualTo("primary");
        assertThat(properties.modelForRole("overall-report").orElseThrow().getKey()).isEqualTo("deepseek");
        assertThat(properties.models()).hasSize(2);
    }

    @Test
    void placeholderOrBlankKeyDoesNotEnableAClient() {
        AiModelProperties.Model placeholder = new AiModelProperties.Model(true,
                "https://api.deepseek.com", "replace-with-deepseek-api-key",
                "/v1/chat/completions", "deepseek-chat", 0.1);

        assertThat(placeholder.configured()).isFalse();
    }
}
```

- [ ] **Step 2: 更新示例配置失败测试**

将 `LocalModelConfigurationExampleTest` 的核心断言改为：

```java
assertThat(active.getProperty("app.ai.models.primary.base-url")).isEqualTo("https://api.openai.com");
assertThat(active.getProperty("app.ai.models.deepseek.base-url")).isEqualTo("https://api.deepseek.com");
assertThat(active.getProperty("app.ai.models.deepseek.model")).isEqualTo("deepseek-chat");
assertThat(active.getProperty("app.ai.models.mimo.model")).isEqualTo("mimo-v2.5-pro");
assertThat(active.getProperty("app.ai.roles.institutional-report")).isEqualTo("primary");
assertThat(active.getProperty("app.ai.roles.overall-report")).isEqualTo("deepseek");
assertThat(yaml).doesNotContainPattern("sk-[A-Za-z0-9_-]{12,}");
```

- [ ] **Step 3: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AiModelPropertiesTest,LocalModelConfigurationExampleTest' test
```

Expected: FAIL，因为 `AiModelProperties` 尚不存在且示例仍是单模型切换格式。

- [ ] **Step 4: 创建最小配置对象**

```java
package com.astock.agent.agent.model;

import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.ai")
public record AiModelProperties(Map<String, Model> models, Map<String, String> roles) {

    public AiModelProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    public Optional<Map.Entry<String, Model>> modelForRole(String role) {
        String modelId = roles.get(role);
        if (modelId == null) return Optional.empty();
        Model model = models.get(modelId);
        return model == null ? Optional.empty() : Optional.of(Map.entry(modelId, model));
    }

    public record Model(boolean enabled, String baseUrl, String apiKey,
            String completionsPath, String model, double temperature) {

        public Model {
            completionsPath = completionsPath == null || completionsPath.isBlank()
                    ? "/v1/chat/completions" : completionsPath;
            temperature = Math.max(0.0, Math.min(temperature, 1.0));
        }

        public boolean configured() {
            return enabled
                    && baseUrl != null && !baseUrl.isBlank()
                    && model != null && !model.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && !apiKey.startsWith("replace-with-");
        }
    }
}
```

- [ ] **Step 5: 将示例文件改为并存配置**

`config/application-local.yml.example` 使用以下有效 YAML，所有密钥均为占位符：

```yaml
spring:
  ai:
    model:
      chat: none
      embedding: none
      image: none
      moderation: none
      audio:
        speech: none
        transcription: none

app:
  ai:
    models:
      primary:
        enabled: true
        base-url: "https://api.openai.com"
        api-key: "replace-with-primary-api-key"
        completions-path: "/v1/chat/completions"
        model: "replace-with-primary-model"
        temperature: 0.2
      deepseek:
        enabled: true
        base-url: "https://api.deepseek.com"
        api-key: "replace-with-deepseek-api-key"
        completions-path: "/v1/chat/completions"
        model: "deepseek-chat"
        temperature: 0.1
      mimo:
        enabled: false
        base-url: "https://api.xiaomimimo.com/v1"
        api-key: "replace-with-mimo-api-key"
        completions-path: "/chat/completions"
        model: "mimo-v2.5-pro"
        temperature: 0.2
    roles:
      institutional-report: primary
      overall-report: deepseek
```

- [ ] **Step 6: 运行测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AiModelPropertiesTest,LocalModelConfigurationExampleTest' test
```

Expected: PASS。

- [ ] **Step 7: 安全检查点**

Run:

```powershell
git diff --check -- config/application-local.yml.example src/main/java/com/astock/agent/agent/model/AiModelProperties.java src/test/java/com/astock/agent/agent/model/AiModelPropertiesTest.java src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java
```

Expected: 无输出。只允许在能够确认暂存区不含既有用户改动时提交新增文件和未脏文件；否则不提交。

---

### Task 2: 创建模型注册表并迁移现有报告角色

**Files:**
- Create: `src/main/java/com/astock/agent/agent/model/NamedChatClientRegistry.java`
- Create: `src/main/java/com/astock/agent/agent/model/AiModelConfiguration.java`
- Create: `src/test/java/com/astock/agent/agent/model/NamedChatClientRegistryTest.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentStatusService.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`
- Modify: `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`
- Modify: `src/test/java/com/astock/agent/agent/AgentStatusServiceTest.java`
- Modify: `src/test/java/com/astock/agent/agent/StockAnalysisAgentTest.java`

- [ ] **Step 1: 写注册表与角色隔离失败测试**

```java
package com.astock.agent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class NamedChatClientRegistryTest {

    @Test
    void resolvesIndependentClientsByRole() {
        ChatClient primary = mock(ChatClient.class);
        ChatClient deepseek = mock(ChatClient.class);
        NamedChatClientRegistry registry = new NamedChatClientRegistry(
                Map.of(
                        "primary", new NamedChatClientRegistry.NamedModel(primary, "gpt-test"),
                        "deepseek", new NamedChatClientRegistry.NamedModel(deepseek, "deepseek-chat")),
                Map.of("institutional-report", "primary", "overall-report", "deepseek"));

        assertThat(registry.forRole("institutional-report").orElseThrow().client()).isSameAs(primary);
        assertThat(registry.forRole("overall-report").orElseThrow().client()).isSameAs(deepseek);
        assertThat(registry.forRole("overall-report").orElseThrow().modelName()).isEqualTo("deepseek-chat");
    }

    @Test
    void missingRoleIsUnavailableWithoutDisablingOtherRoles() {
        NamedChatClientRegistry registry = new NamedChatClientRegistry(Map.of(),
                Map.of("institutional-report", "primary", "overall-report", "deepseek"));

        assertThat(registry.forRole("overall-report")).isEmpty();
        assertThat(registry.availability("overall-report")).isEqualTo(com.astock.agent.agent.AgentAvailability.DISABLED_CONFIGURATION_MISSING);
    }
}
```

- [ ] **Step 2: 写现有报告使用 primary 角色的失败测试**

在 `StockAnalysisAgentTest` 添加一个使用模型名构造器的测试：

```java
@Test
void namedPrimaryModelIsReportedByInstitutionalResult() {
    StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
    NarrativeGenerator generator = new NarrativeGenerator() {
        @Override
        public ReportNarrativeDraft generate(ReportEvidencePackage evidence) {
            return new ReportNarrativeDraft("摘要", "技术", "基本面", "估值", List.of(), List.of());
        }
        @Override public String modelName() { return "primary-model"; }
    };

    StockAnalysisAgent agent = new StockAnalysisAgent(generator,
            readyStatus(), new StockAgentTools(id -> snapshot));

    assertThat(agent.analyzeInstitutional(snapshot).modelName()).isEqualTo("primary-model");
}
```

同时把测试类现有 `readyStatus()` helper 改为注册表版本，helper 只存在于测试代码：

```java
private static AgentStatusService readyStatus() {
    ChatClient client = org.mockito.Mockito.mock(ChatClient.class);
    NamedChatClientRegistry registry = new NamedChatClientRegistry(
            Map.of("primary", new NamedChatClientRegistry.NamedModel(client, "primary-model")),
            Map.of("institutional-report", "primary"));
    return new AgentStatusService(registry);
}
```

- [ ] **Step 3: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=NamedChatClientRegistryTest,StockAnalysisAgentTest' test
```

Expected: FAIL，因为注册表和角色级状态尚不存在。

- [ ] **Step 4: 实现注册表**

```java
package com.astock.agent.agent.model;

import com.astock.agent.agent.AgentAvailability;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.client.ChatClient;

public final class NamedChatClientRegistry {
    private final Map<String, NamedModel> models;
    private final Map<String, String> roles;

    public NamedChatClientRegistry(Map<String, NamedModel> models, Map<String, String> roles) {
        this.models = models == null ? Map.of() : Map.copyOf(models);
        this.roles = roles == null ? Map.of() : Map.copyOf(roles);
    }

    public Optional<NamedModel> forRole(String role) {
        return Optional.ofNullable(roles.get(role)).map(models::get);
    }

    public AgentAvailability availability(String role) {
        return forRole(role).isPresent()
                ? AgentAvailability.READY
                : AgentAvailability.DISABLED_CONFIGURATION_MISSING;
    }

    public record NamedModel(ChatClient client, String modelName) {
        public NamedModel {
            if (client == null) throw new IllegalArgumentException("client is required");
            modelName = modelName == null || modelName.isBlank() ? "configured-chat-model" : modelName;
        }
    }
}
```

- [ ] **Step 5: 使用 Spring AI 1.1.8 API 创建多个客户端**

`AiModelConfiguration` 的核心必须是以下 API，不增加新依赖：

```java
OpenAiApi api = OpenAiApi.builder()
        .baseUrl(settings.baseUrl())
        .apiKey(settings.apiKey())
        .completionsPath(settings.completionsPath())
        .build();
OpenAiChatOptions options = OpenAiChatOptions.builder()
        .model(settings.model())
        .temperature(settings.temperature())
        .build();
OpenAiChatModel model = OpenAiChatModel.builder()
        .openAiApi(api)
        .defaultOptions(options)
        .build();
ChatClient client = ChatClient.create(model);
```

将上面逻辑封装为同一配置类中的完整 helper：

```java
private static NamedChatClientRegistry.NamedModel create(AiModelProperties.Model settings) {
    OpenAiApi api = OpenAiApi.builder()
            .baseUrl(settings.baseUrl())
            .apiKey(settings.apiKey())
            .completionsPath(settings.completionsPath())
            .build();
    OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(settings.model())
            .temperature(settings.temperature())
            .build();
    OpenAiChatModel model = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build();
    return new NamedChatClientRegistry.NamedModel(ChatClient.create(model), settings.model());
}
```

完整 Bean 方法按已配置模型构建 Map，并兼容当前本地旧配置：如果 `app.ai.models.primary` 未配置但 Spring 自动配置提供了 `ChatClient.Builder`，将其注册为 `primary`；新命名模型仍由上面的 API 创建。兼容逻辑只能读取旧配置，不得记录 key、base URL 或请求参数：

```java
@Bean
NamedChatClientRegistry namedChatClientRegistry(
        AiModelProperties properties,
        ObjectProvider<ChatClient.Builder> legacyBuilder,
        Environment environment) {
    Map<String, NamedChatClientRegistry.NamedModel> clients = new LinkedHashMap<>();
    properties.models().forEach((id, settings) -> {
        if (settings.configured()) clients.put(id, create(settings));
    });
    ChatClient.Builder builder = legacyBuilder.getIfAvailable();
    if (!clients.containsKey("primary") && builder != null) {
        String name = environment.getProperty("spring.ai.openai.chat.options.model", "legacy-primary");
        clients.put("primary", new NamedChatClientRegistry.NamedModel(builder.build(), name));
    }
    return new NamedChatClientRegistry(clients, properties.roles());
}
```

- [ ] **Step 6: 迁移角色状态和 Agent 装配**

`AgentStatusService` 改为持有注册表，并保留现有无参数角色查询，不再读取密钥：

```java
public AgentAvailability status() { return status("institutional-report"); }
public AgentAvailability status(String role) { return registry.availability(role); }
public String details() { return details("institutional-report"); }
public String details(String role) {
    return status(role) == AgentAvailability.READY
            ? role + " model is configured"
            : role + " model is not configured";
}
```

更新 `AgentStatusServiceTest`：未配置测试使用空注册表；就绪测试使用只包含 `primary` 的注册表，并断言详情不包含模型配置值。更新 `StockAnalysisAgentTest` 中两个未配置场景，使用以下 helper 代替 `MockEnvironment`：

```java
private static AgentStatusService unavailableStatus() {
    return new AgentStatusService(new NamedChatClientRegistry(
            Map.of(), Map.of("institutional-report", "primary")));
}
```

`AgentConfiguration` 从注册表解析 `institutional-report`，同时把同一个 `ChatClient` 和真实模型名交给 `StockAnalysisAgent`，确保旧 `analyze()` 和现有 `analyzeInstitutional()` 都不丢失模型能力：

```java
var model = registry.forRole("institutional-report").orElse(null);
return model == null
        ? new StockAnalysisAgent((ChatClient) null, status, tools)
        : new StockAnalysisAgent(model.client(), model.modelName(), status, tools);
```

在 `StockAnalysisAgent` 增加对应构造器：

```java
public StockAnalysisAgent(ChatClient chatClient, String modelName,
        AgentStatusService statusService, StockAgentTools tools) {
    this(chatClient,
            chatClient == null ? null : new SpringAiNarrativeGenerator(chatClient, modelName),
            statusService, tools, new ResearchJudgementEngine(), new InstitutionalReportComposer(),
            new ReportValidator(), new ModelFailureClassifier());
}
```

- [ ] **Step 7: 运行聚焦测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=NamedChatClientRegistryTest,AgentStatusServiceTest,StockAnalysisAgentTest,AStockAgentApplicationTest' test
```

Expected: PASS，应用在未配置模型时仍能启动。

---

### Task 3: 定义总体报告领域对象和校验器

**Files:**
- Create: `src/main/java/com/astock/agent/agent/overall/OverallSourceReference.java`
- Create: `src/main/java/com/astock/agent/agent/overall/OverallReportDraft.java`
- Create: `src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java`
- Create: `src/main/java/com/astock/agent/agent/overall/OverallReportStatus.java`
- Create: `src/main/java/com/astock/agent/agent/overall/OverallReportResponse.java`
- Create: `src/main/java/com/astock/agent/agent/overall/OverallReportValidator.java`
- Create: `src/test/java/com/astock/agent/agent/overall/OverallReportValidatorTest.java`

- [ ] **Step 1: 写校验失败测试**

测试必须分别覆盖交易指令、未知来源、快照外数字和缺失免责声明：

```java
@Test
void rejectsTradeInstructionUnknownSourceUnsupportedNumberAndWrongDisclaimer() {
    StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
    OverallReportDraft draft = validDraft(
            "建议买入，目标价999元",
            List.of(new OverallSourceReference("quote", "invented-provider", null, null)),
            "免责声明缺失");

    OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

    assertThat(result.issues()).contains(
            "TRADE_INSTRUCTION", "UNKNOWN_SOURCE_REFERENCE",
            "UNSUPPORTED_NUMBER", "INVALID_DISCLAIMER");
}

@Test
void acceptsEvidenceBoundDraftAndPreservesUnavailableSections() {
    StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
    OverallReportDraft draft = validDraft(
            "核心行情不可用，无法形成高强度判断",
            List.of(), "仅供学习研究，不构成投资建议");

    OverallReportValidator.Validation result = new OverallReportValidator().validate(draft, snapshot);

    assertThat(result.blocking()).isFalse();
}
```

测试类中的完整 fixture helper：

```java
private static OverallReportDraft validDraft(String conclusion,
        List<OverallSourceReference> sources, String disclaimer) {
    return new OverallReportDraft(
            conclusion,
            "quote 与 bars 均不可用，数据质量不足",
            "基本面数据不可用",
            "技术与资金数据不可用",
            "估值与行业数据不可用",
            "事件数据不可用",
            List.of(), List.of(), List.of(), Map.of(),
            List.of("quote 不可用", "bars 不可用"),
            sources, disclaimer);
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportValidatorTest' test
```

Expected: FAIL，因为总体报告类型和校验器尚不存在。

- [ ] **Step 3: 创建结构化草稿**

```java
public record OverallReportDraft(
        String overallConclusion,
        String dataQualitySummary,
        String companyAndFundamentals,
        String technicalAndCapital,
        String valuationAndIndustry,
        String eventsAndSentiment,
        List<String> bullishEvidence,
        List<String> bearishEvidence,
        List<String> riskFactors,
        Map<String, String> scenarios,
        List<String> conflictsAndMissingData,
        List<OverallSourceReference> sourceReferences,
        String disclaimer) {
    public OverallReportDraft {
        bullishEvidence = safe(bullishEvidence);
        bearishEvidence = safe(bearishEvidence);
        riskFactors = safe(riskFactors);
        scenarios = scenarios == null ? Map.of() : Map.copyOf(scenarios);
        conflictsAndMissingData = safe(conflictsAndMissingData);
        sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
    }
    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
```

`OverallSourceReference` 使用：

```java
public record OverallSourceReference(
        String section, String provider, String sourceUrl, Instant fetchedAt) {}
```

`OverallResearchReport` 包含草稿的全部内容，并由服务端附加 `modelName`、`snapshotAt`、`generatedAt`、`promptVersion`；构造时强制免责声明：

```java
public record OverallResearchReport(
        String overallConclusion,
        String dataQualitySummary,
        String companyAndFundamentals,
        String technicalAndCapital,
        String valuationAndIndustry,
        String eventsAndSentiment,
        List<String> bullishEvidence,
        List<String> bearishEvidence,
        List<String> riskFactors,
        Map<String, String> scenarios,
        List<String> conflictsAndMissingData,
        List<OverallSourceReference> sourceReferences,
        String modelName,
        Instant snapshotAt,
        Instant generatedAt,
        String promptVersion,
        String disclaimer) {

    public OverallResearchReport {
        disclaimer = "仅供学习研究，不构成投资建议";
    }

    public static OverallResearchReport from(OverallReportDraft draft, String modelName,
            Instant snapshotAt, Instant generatedAt, String promptVersion) {
        return new OverallResearchReport(
                draft.overallConclusion(), draft.dataQualitySummary(),
                draft.companyAndFundamentals(), draft.technicalAndCapital(),
                draft.valuationAndIndustry(), draft.eventsAndSentiment(),
                draft.bullishEvidence(), draft.bearishEvidence(), draft.riskFactors(),
                draft.scenarios(), draft.conflictsAndMissingData(), draft.sourceReferences(),
                modelName, snapshotAt, generatedAt, promptVersion, draft.disclaimer());
    }
}
```

`OverallReportResponse` 使用：

```java
public record OverallReportResponse(
        OverallReportStatus status,
        OverallResearchReport report,
        ModelDiagnostic diagnostic,
        String message) {}
```

状态枚举严格为：

```java
public enum OverallReportStatus {
    MODEL_ASSISTED, MODEL_NOT_CONFIGURED, MODEL_FAILED, VALIDATION_FAILED
}
```

- [ ] **Step 4: 实现确定性校验**

`OverallReportValidator` 使用现有报告相同的交易词边界：

```java
private static final Pattern TRADE = Pattern.compile(
        "买入|卖出|加仓|减仓|仓位|止盈|止损|目标价|保证收益|收益保证|稳赚");
private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z])[-+]?\\d+(?:\\.\\d+)?%?");
private static final String DISCLAIMER = "仅供学习研究，不构成投资建议";
```

实现顺序：

1. 拼接草稿的所有文本字段；
2. 空核心章节加入 `EMPTY_REQUIRED_SECTION`；
3. 命中交易词加入 `TRADE_INSTRUCTION`；
4. 免责声明不完全相等加入 `INVALID_DISCLAIMER`；
5. 从 `quote`、`bars`、`technical`、`sectors`、`industryValuation`、`fundFlow`、`capital`、`fundamentals`、`research`、`news`、`announcements` 建立允许的 `(section, provider)` 集合；
6. 来源不在集合中加入 `UNKNOWN_SOURCE_REFERENCE`；
7. 使用 Jackson 将完整快照序列化，建立规范化数字集合；报告出现而快照不存在的数字加入 `UNSUPPORTED_NUMBER`；
8. `quote` 或 `bars` 为 `UNAVAILABLE` 时，`conflictsAndMissingData` 必须明确包含对应分区，否则加入 `MISSING_CORE_DATA_LIMITATION`。

`Validation` 返回不可变问题列表和 `blocking()`：

```java
public record Validation(List<String> issues) {
    public Validation { issues = issues == null ? List.of() : List.copyOf(issues); }
    public boolean blocking() { return !issues.isEmpty(); }
}
```

数字规范化只比较输入与输出中明确出现的数字文本，不执行模型侧派生计算；日期中的年月日按完整 token 比较，避免将年份误判为外部事实。

- [ ] **Step 5: 运行校验测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportValidatorTest' test
```

Expected: PASS。

---

### Task 4: 实现 DeepSeek 专业提示词与完整快照输入

**Files:**
- Create: `src/main/java/com/astock/agent/agent/overall/OverallReportGenerator.java`
- Create: `src/main/java/com/astock/agent/agent/overall/SpringAiOverallReportGenerator.java`
- Create: `src/test/java/com/astock/agent/agent/overall/SpringAiOverallReportGeneratorTest.java`

- [ ] **Step 1: 写完整快照与提示词失败测试**

为避免 mock Spring AI fluent API，生成器提供包级 `ModelInvoker` 构造器：

```java
@Test
void sendsEveryNormalizedSnapshotSectionAndProfessionalConstraints() throws Exception {
    AtomicReference<String> system = new AtomicReference<>();
    AtomicReference<String> user = new AtomicReference<>();
    OverallReportDraft expected = validDraft();
    SpringAiOverallReportGenerator generator = new SpringAiOverallReportGenerator(
            (systemText, userText) -> {
                system.set(systemText);
                user.set(userText);
                return expected;
            }, "deepseek-chat");

    generator.generate(StockResearchSnapshot.empty(SecurityId.parse("600519")));

    assertThat(user.get()).contains(
            "\"quote\"", "\"bars\"", "\"technical\"", "\"sectors\"",
            "\"industryValuation\"", "\"fundFlow\"", "\"capital\"",
            "\"fundamentals\"", "\"research\"", "\"news\"", "\"announcements\"",
            "\"quality\"", "\"fetchedAt\"");
    assertThat(system.get()).contains(
            "唯一事实边界", "HEALTHY", "UNAVAILABLE", "正反证据",
            "不得输出买入", "只返回 OverallReportDraft 对应的 JSON");
}
```

测试类的 `validDraft()` helper 使用无外部数字、明确核心缺失项的有效草稿：

```java
private static OverallReportDraft validDraft() {
    return new OverallReportDraft(
            "核心数据不足，无法形成高强度结论",
            "quote 与 bars 均不可用",
            "基本面不可用", "技术与资金不可用", "估值与行业不可用", "事件不可用",
            List.of(), List.of(), List.of(), Map.of(),
            List.of("quote 不可用", "bars 不可用"), List.of(),
            "仅供学习研究，不构成投资建议");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=SpringAiOverallReportGeneratorTest' test
```

Expected: FAIL，因为生成器尚不存在。

- [ ] **Step 3: 创建生成接口**

```java
public interface OverallReportGenerator {
    OverallReportDraft generate(StockResearchSnapshot snapshot) throws Exception;
    default OverallReportDraft repair(StockResearchSnapshot snapshot,
            OverallReportDraft draft, List<String> issues) throws Exception {
        return draft;
    }
    default String modelName() { return "configured-overall-model"; }
}
```

- [ ] **Step 4: 实现完整专业提示词**

`SpringAiOverallReportGenerator.SYSTEM_PROMPT` 必须逐字包含设计文档第 5 节的四组内容：唯一事实边界、数据质量规则、分析要求、禁止内容和 JSON 输出格式。用户消息必须直接拼接完整 Jackson JSON，不调用 `limit()`、`subList()`、摘要器或 token 截断器：

```java
String user = "以下是证券 " + snapshot.security().code()
        + " 的完整规范化研究快照。请严格按照系统约束生成 OverallReportDraft JSON：\n"
        + MAPPER.writeValueAsString(snapshot);
return invoker.invoke(SYSTEM_PROMPT, user);
```

生产构造器适配 `ChatClient`：

```java
public SpringAiOverallReportGenerator(ChatClient client, String modelName) {
    this((system, user) -> client.prompt()
            .system(system)
            .user(user)
            .call()
            .entity(OverallReportDraft.class), modelName);
}
```

`repair` 使用同一完整快照、上一次草稿和问题列表，提示“只修复列出问题”，且不包含任何外部工具。

- [ ] **Step 5: 运行测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=SpringAiOverallReportGeneratorTest' test
```

Expected: PASS。

---

### Task 5: 编排总体报告、一次修复和局部诊断

**Files:**
- Create: `src/main/java/com/astock/agent/agent/overall/OverallReportService.java`
- Create: `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentConfiguration.java`

- [ ] **Step 1: 写独立成功、未配置和一次修复失败测试**

```java
@Test
void missingDeepseekReturnsLocalStatusWithoutFabricatingReport() {
    OverallReportService service = new OverallReportService(null,
            new StockAgentTools(id -> snapshot), new OverallReportValidator(), new ModelFailureClassifier());

    OverallReportResponse response = service.generate("600519");

    assertThat(response.status()).isEqualTo(OverallReportStatus.MODEL_NOT_CONFIGURED);
    assertThat(response.report()).isNull();
}

@Test
void repairsAtMostOnceThenReturnsValidationFailureWithoutDraft() {
    AtomicInteger repairs = new AtomicInteger();
    OverallReportGenerator generator = new OverallReportGenerator() {
        @Override public OverallReportDraft generate(StockResearchSnapshot ignored) { return invalidDraft(); }
        @Override public OverallReportDraft repair(StockResearchSnapshot ignored,
                OverallReportDraft draft, List<String> issues) {
            repairs.incrementAndGet();
            return invalidDraft();
        }
        @Override public String modelName() { return "deepseek-chat"; }
    };
    OverallReportService service = service(generator);

    OverallReportResponse response = service.generate("600519");

    assertThat(repairs).hasValue(1);
    assertThat(response.status()).isEqualTo(OverallReportStatus.VALIDATION_FAILED);
    assertThat(response.report()).isNull();
    assertThat(response.diagnostic().modelName()).isEqualTo("deepseek-chat");
}
```

测试类的 fixture 和 service helper 明确定义为：

```java
private final StockResearchSnapshot snapshot =
        StockResearchSnapshot.empty(SecurityId.parse("600519"));

private OverallReportService service(OverallReportGenerator generator) {
    return new OverallReportService(generator, new StockAgentTools(id -> snapshot),
            new OverallReportValidator(), new ModelFailureClassifier());
}

private static OverallReportDraft invalidDraft() {
    return new OverallReportDraft(
            "建议买入", "数据质量未知", "基本面未知", "技术未知", "估值未知", "事件未知",
            List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(),
            "免责声明缺失");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportServiceTest' test
```

Expected: FAIL，因为服务尚不存在。

- [ ] **Step 3: 实现服务流程**

核心流程严格为：

```java
public OverallReportResponse generate(String code) {
    if (generator == null) {
        return new OverallReportResponse(MODEL_NOT_CONFIGURED, null, null,
                "总体报告模型未配置");
    }
    StockResearchSnapshot snapshot = tools.getResearchSnapshot(code);
    long started = System.nanoTime();
    String traceId = "overall-" + UUID.randomUUID();
    try {
        OverallReportDraft draft = generator.generate(snapshot);
        OverallReportValidator.Validation validation = validator.validate(draft, snapshot);
        if (validation.blocking()) {
            draft = generator.repair(snapshot, draft, validation.issues());
            validation = validator.validate(draft, snapshot);
        }
        if (validation.blocking()) {
            ModelDiagnostic diagnostic = classifier.validation(validation.issues(),
                    generator.modelName(), elapsedMillis(started), traceId);
            return new OverallReportResponse(VALIDATION_FAILED, null, diagnostic,
                    "DeepSeek 总体报告未通过证据校验");
        }
        OverallResearchReport report = OverallResearchReport.from(
                draft, generator.modelName(), snapshot.fetchedAt(), Instant.now(), "overall-v1");
        return new OverallReportResponse(MODEL_ASSISTED, report, null, "总体报告已生成");
    } catch (Exception failure) {
        ModelDiagnostic diagnostic = classifier.classify(failure, generator.modelName(),
                elapsedMillis(started), traceId);
        return new OverallReportResponse(MODEL_FAILED, null, diagnostic,
                "DeepSeek 总体报告生成失败");
    }
}
```

同一服务类增加耗时 helper：

```java
private static long elapsedMillis(long started) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
}
```

日志只记录 `traceId`、错误阶段、错误码和异常类型；沿用 `ModelFailureClassifier.sanitize`，不得记录快照 JSON 或模型请求。

- [ ] **Step 4: 从 overall-report 角色装配生成器**

在 `AgentConfiguration` 添加：

```java
@Bean
OverallReportService overallReportService(NamedChatClientRegistry registry,
        StockAgentTools tools) {
    OverallReportGenerator generator = registry.forRole("overall-report")
            .<OverallReportGenerator>map(model ->
                    new SpringAiOverallReportGenerator(model.client(), model.modelName()))
            .orElse(null);
    return new OverallReportService(generator, tools,
            new OverallReportValidator(), new ModelFailureClassifier());
}
```

- [ ] **Step 5: 运行测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportServiceTest,StockAnalysisAgentTest' test
```

Expected: PASS。

---

### Task 6: 暴露独立总体报告 API 和角色状态

**Files:**
- Modify: `src/main/java/com/astock/agent/web/AgentController.java`
- Modify: `src/test/java/com/astock/agent/web/AgentControllerTest.java`
- Modify: `src/main/java/com/astock/agent/agent/AgentStatusService.java`

- [ ] **Step 1: 写接口失败测试**

使用 standalone MockMvc 和可控服务，覆盖成功、无效代码和未配置：

```java
@Test
void overallReportUsesIndependentEndpoint() throws Exception {
    mvc.perform(post("/api/agent/overall-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"600519\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("MODEL_ASSISTED"))
            .andExpect(jsonPath("$.report.modelName").value("deepseek-chat"));
}

@Test
void invalidOverallReportCodeUsesProblemDetails() throws Exception {
    mvc.perform(post("/api/agent/overall-report")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"ABC\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_SECURITY_CODE"));
}
```

现有 controller 测试构造器同步增加第三个参数；状态测试使用空 `NamedChatClientRegistry`，现有研究报告测试传 `null` overall service：

```java
AgentStatusService status = new AgentStatusService(new NamedChatClientRegistry(
        Map.of(), Map.of("institutional-report", "primary", "overall-report", "deepseek")));
AgentController controller = new AgentController(status, agent, overallReportService);
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AgentControllerTest' test
```

Expected: FAIL，接口尚不存在。

- [ ] **Step 3: 新增接口和角色状态**

`AgentController` 增加构造参数 `OverallReportService overallReports` 和：

```java
@PostMapping("/overall-report")
public OverallReportResponse overallReport(@RequestBody AnalyzeRequest request) {
    SecurityId.parse(request.code());
    return overallReports.generate(request.code());
}
```

`GET /api/agent/status` 保留现有 `status`、`details`，并增加：

```java
"institutionalReport", statusService.status("institutional-report").name(),
"overallReport", statusService.status("overall-report").name()
```

不要返回模型 URL、密钥、请求路径或本地配置值。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AgentControllerTest,StaticResourceTest' test
```

Expected: PASS。

---

### Task 7: 建立双按钮和独立前端状态

**Files:**
- Modify: `src/main/resources/static/js/api.js`
- Modify: `src/main/resources/static/js/views.js`
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/test/java/com/astock/agent/web/StaticResourceTest.java`
- Modify: `tests/ui/dashboard.spec.js`

- [ ] **Step 1: 在编辑 UI 前加载 Impeccable 上下文**

Run:

```powershell
node C:\Users\xixixiaozi\.agents\skills\impeccable\scripts\context.mjs --target src/main/resources/static/js/views.js
```

随后完整阅读 `reference/layout.md`，并在实际编辑 UI 前完整阅读 `reference/craft-floor.md`。这是 `impeccable` 技能要求，输出中的项目视觉约束优先于通用建议。

- [ ] **Step 2: 写独立按钮和接口失败测试**

先在 `dashboard.spec.js` 增加可复用 fixture 和导航 helper：

```javascript
function institutionalReportFixture() {
  return {
    direction: "NEUTRAL", generationMode: "MODEL_ASSISTED", evidenceStatus: "SUFFICIENT",
    executiveSummary: "结构化判断内容", coreDrivers: [],
    technicalAndFlow: { narrative: "技术与资金分析内容", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    fundamentals: { narrative: "基本面分析内容", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    valuationAndIndustry: { narrative: "估值与行业分析内容", facts: [], signals: [], methodology: [], counterEvidence: [], limitations: [] },
    catalysts: [], risks: [], conflicts: [], missingData: [], invalidationConditions: [], sources: [],
    disclaimer: "仅供学习研究，不构成投资建议",
  };
}

function overallReportFixture() {
  return { status: "MODEL_ASSISTED", message: "总体报告已生成", report: {
    overallConclusion: "总体结论样例", dataQualitySummary: "数据质量样例",
    companyAndFundamentals: "公司与基本面样例", technicalAndCapital: "技术与资金样例",
    valuationAndIndustry: "估值与行业样例", eventsAndSentiment: "事件与情绪样例",
    bullishEvidence: ["支持证据"], bearishEvidence: ["反向证据"], riskFactors: ["风险证据"],
    scenarios: { stronger: "偏强条件", neutral: "中性条件", weaker: "偏弱条件" },
    conflictsAndMissingData: ["缺失项"], sourceReferences: [], modelName: "deepseek-chat",
    snapshotAt: "2026-08-03T02:00:00Z", generatedAt: "2026-08-03T02:00:01Z",
    promptVersion: "overall-v1", disclaimer: "仅供学习研究，不构成投资建议",
  }};
}

async function openAgentTab(page) {
  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
}

async function mockBothReports(page) {
  await page.route("**/api/agent/analyze", (route) => route.fulfill({ json: institutionalReportFixture() }));
  await page.route("**/api/agent/overall-report", (route) => route.fulfill({ json: overallReportFixture() }));
}
```

在 Playwright 测试添加：

```javascript
test("research and DeepSeek overall reports use independent controls", async ({ page }) => {
  let institutionalCalls = 0;
  let overallCalls = 0;
  await page.route("**/api/agent/analyze", (route) => {
    institutionalCalls += 1;
    return route.fulfill({ json: institutionalReportFixture() });
  });
  await page.route("**/api/agent/overall-report", (route) => {
    overallCalls += 1;
    return route.fulfill({ json: overallReportFixture() });
  });

  await openAgentTab(page);
  await page.getByRole("button", { name: "生成总体报告 DeepSeek" }).click();

  expect(overallCalls).toBe(1);
  expect(institutionalCalls).toBe(0);
  await expect(page.locator("#overall-report-output")).toContainText("总体结论样例");
  await expect(page.locator("#agent-output")).toContainText("等待生成");
});
```

静态资源测试分别读取 `/js/api.js` 和 `/js/views.js`，增加：

```java
assertThat(apiScript).contains("overallReport(code)", "/api/agent/overall-report");
assertThat(viewsScript).contains("run-overall-report", "overall-report-output");
```

- [ ] **Step 3: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StaticResourceTest' test
npm.cmd run test:ui -- --grep "independent controls"
```

Expected: 两项均 FAIL，因为按钮、API 和输出容器尚不存在。

- [ ] **Step 4: 增加 API 方法**

```javascript
overallReport(code) {
  return request("/api/agent/overall-report", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ code }),
  });
},
```

- [ ] **Step 5: 建立语义布局**

`renderAgent` 改为：

```javascript
function renderAgent() {
  return `<section>${heading("SPRING AI · EVIDENCE BOUNDED", "Agent 研究", null)}
    <div class="agent-layout">
      <aside class="agent-sidebar" aria-label="报告生成与总体报告">
        <div class="agent-controls">
          <h3>研究报告</h3>
          <p>两份报告独立生成，并保留数据来源、缺失项与风险边界。</p>
          <button id="run-agent" class="primary-command agent-command" type="button">
            <i data-lucide="sparkles" aria-hidden="true"></i><span>生成研究报告</span>
          </button>
          <button id="run-overall-report" class="secondary-command agent-command" type="button">
            <i data-lucide="file-chart-column" aria-hidden="true"></i><span>生成总体报告</span><small>DeepSeek</small>
          </button>
        </div>
        <div id="overall-report-output" class="overall-report-output" aria-live="polite">
          <span class="source-status" data-status="UNAVAILABLE"><span></span>等待生成</span>
          <p>DeepSeek 将读取当前股票的完整规范化快照。</p>
        </div>
      </aside>
      <div id="agent-output" class="agent-output" aria-live="polite">
        <span class="source-status" data-status="UNAVAILABLE"><span></span>等待生成</span>
        <p>现有结构化研究报告将在这里显示。</p>
      </div>
    </div>
  </section>`;
}
```

- [ ] **Step 6: 增加互不覆盖的状态**

`state` 增加：

```javascript
institutionalReport: null,
overallReportResponse: null,
```

新增 `bindOverallReportAction()`，只禁用 `#run-overall-report`，只更新 `#overall-report-output`。现有 `bindAgentAction()` 只更新 `#agent-output`。`loadStock(code)` 在设置新代码前执行：

```javascript
state.institutionalReport = null;
state.overallReportResponse = null;
```

成功响应保存到 state，切换 tab 再回来时重新渲染；切换股票后不得显示旧报告。

- [ ] **Step 7: 运行测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=StaticResourceTest' test
npm.cmd run test:ui -- --grep "independent controls"
```

Expected: PASS。

---

### Task 8: 渲染专业总体报告并优化现有报告排版

**Files:**
- Modify: `src/main/resources/static/js/app.js`
- Modify: `src/main/resources/static/styles.css`
- Modify: `tests/ui/dashboard.spec.js`

- [ ] **Step 1: 写内容保留、左侧位置和可访问性失败测试**

```javascript
test("overall report is below the left controls and existing report remains complete", async ({ page }) => {
  await mockBothReports(page);
  await openAgentTab(page);
  await page.getByRole("button", { name: "生成研究报告" }).click();
  await page.getByRole("button", { name: "生成总体报告 DeepSeek" }).click();

  const layout = await page.evaluate(() => {
    const controls = document.querySelector(".agent-controls").getBoundingClientRect();
    const overall = document.querySelector("#overall-report-output").getBoundingClientRect();
    const existing = document.querySelector("#agent-output").getBoundingClientRect();
    return { overallBelowControls: overall.top >= controls.bottom, overallLeftOfExisting: overall.right <= existing.left + 1 };
  });
  expect(layout).toEqual({ overallBelowControls: true, overallLeftOfExisting: true });
  await expect(page.locator("#overall-report-output")).toContainText("总体结论样例");
  await expect(page.locator("#overall-report-output")).toContainText("deepseek-chat");
  await expect(page.locator("#agent-output")).toContainText("技术与资金分析内容");
  await expect(page.locator("#agent-output")).toContainText("仅供学习研究，不构成投资建议");
  await expect(page.locator("#overall-report-output details summary").first()).toBeVisible();
});
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
npm.cmd run test:ui -- --grep "overall report is below"
```

Expected: FAIL，因为总体报告渲染和布局样式尚未完成。

- [ ] **Step 3: 实现总体报告渲染**

在 `app.js` 增加纯渲染函数，所有模型文本继续通过 `escapeText`：

```javascript
function renderOverallList(title, values, tone = "neutral") {
  const items = Array.isArray(values) ? values : [];
  return `<section class="overall-evidence" data-tone="${tone}"><h5>${escapeText(title)}</h5>${items.length
    ? `<ul>${items.map((value) => `<li>${escapeText(value)}</li>`).join("")}</ul>`
    : '<p class="muted">暂无可靠证据</p>'}</section>`;
}

function renderOverallSection(title, content, open = false) {
  return `<details class="overall-section" ${open ? "open" : ""}>
    <summary>${escapeText(title)}</summary>
    <p>${escapeText(content || "暂无可靠证据")}</p>
  </details>`;
}

function renderOverallScenarios(scenarios = {}) {
  const entries = [["偏强情景", scenarios.stronger], ["中性情景", scenarios.neutral], ["偏弱情景", scenarios.weaker]];
  return `<section class="overall-scenarios"><h5>条件式情景</h5>${entries.map(([label, value]) =>
    `<div><strong>${label}</strong><p>${escapeText(value || "条件不足")}</p></div>`).join("")}</section>`;
}

function renderOverallSources(sources) {
  const items = Array.isArray(sources) ? sources : [];
  return `<details class="overall-section overall-sources"><summary>来源引用</summary>${items.length
    ? `<ul>${items.map((source) => `<li><strong>${escapeText(source.section || "数据")}</strong> · ${escapeText(source.provider || "未知来源")}${source.sourceUrl
      ? ` · <a href="${escapeText(source.sourceUrl)}" target="_blank" rel="noopener noreferrer">查看来源</a>` : ""}</li>`).join("")}</ul>`
    : '<p class="muted">当前报告未返回可验证来源</p>'}</details>`;
}

function renderOverallFailure(response = {}) {
  const diagnostic = response.diagnostic || {};
  return `<div class="overall-report-failure"><span class="source-status" data-status="UNAVAILABLE"><span></span>${escapeText(response.status || "MODEL_FAILED")}</span>
    <p>${escapeText(response.message || "总体报告暂不可用")}</p>
    ${diagnostic.traceId ? `<small>追踪 ID：${escapeText(diagnostic.traceId)}</small>` : ""}</div>`;
}

function renderOverallReportResponse(response) {
  if (response?.status !== "MODEL_ASSISTED" || !response.report) {
    return renderOverallFailure(response);
  }
  const report = response.report;
  return `<article class="overall-report">
    <header class="overall-report-header">
      <span class="source-status" data-status="HEALTHY"><span></span>DeepSeek 总体报告</span>
      <h3>总体结论</h3>
      <p>${escapeText(report.overallConclusion)}</p>
      <dl><dt>模型</dt><dd>${escapeText(report.modelName)}</dd><dt>快照</dt><dd>${escapeText(report.snapshotAt)}</dd></dl>
    </header>
    ${renderOverallSection("数据质量", report.dataQualitySummary, true)}
    ${renderOverallSection("公司与基本面", report.companyAndFundamentals)}
    ${renderOverallSection("技术与资金", report.technicalAndCapital)}
    ${renderOverallSection("估值与行业", report.valuationAndIndustry)}
    ${renderOverallSection("事件与情绪", report.eventsAndSentiment)}
    ${renderOverallList("支持证据", report.bullishEvidence, "support")}
    ${renderOverallList("反向证据", report.bearishEvidence, "oppose")}
    ${renderOverallList("风险因素", report.riskFactors, "risk")}
    ${renderOverallScenarios(report.scenarios)}
    ${renderOverallList("冲突与缺失", report.conflictsAndMissingData)}
    ${renderOverallSources(report.sourceReferences)}
    <small class="report-disclaimer">${escapeText(report.disclaimer)}</small>
  </article>`;
}
```

来源 URL 必须通过 `escapeText` 并使用 `target="_blank" rel="noopener noreferrer"`。错误渲染只显示响应中的 `message` 和已脱敏诊断字段。

- [ ] **Step 4: 实现报告排版**

`styles.css` 使用项目语义 token，核心布局如下：

```css
.agent-layout {
  display: grid;
  grid-template-columns: minmax(360px, 420px) minmax(0, 1fr);
  gap: 36px;
  padding: 22px 0;
  border-top: 1px solid var(--hairline);
}
.agent-sidebar { min-width: 0; border-right: 1px solid var(--hairline); padding-right: 28px; }
.agent-controls { display: grid; align-items: start; gap: 10px; }
.agent-command { width: 100%; min-height: 38px; justify-content: flex-start; }
.secondary-command {
  display: inline-flex; align-items: center; gap: 8px; padding: 0 12px;
  border: 1px solid var(--hairline-strong); border-radius: 5px;
  color: var(--ink); background: var(--surface); cursor: pointer;
}
.secondary-command small { margin-left: auto; color: var(--muted); }
.overall-report-output { min-height: 160px; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--hairline); }
.overall-report-header h3 { margin: 14px 0 7px; font-size: 20px; }
.overall-report-header p, .overall-section p { color: var(--body); line-height: 1.75; }
.overall-report-header dl { display: grid; grid-template-columns: 54px minmax(0, 1fr); gap: 4px 10px; font-size: 11px; }
.overall-report-header dt { color: var(--muted); }
.overall-report-header dd { margin: 0; overflow-wrap: anywhere; }
.overall-section { border-top: 1px solid var(--hairline); padding: 11px 0; }
.overall-section summary { cursor: pointer; font-weight: 650; }
.overall-evidence { border-top: 1px solid var(--hairline); padding-top: 12px; }
.overall-evidence[data-tone="support"] h5 { color: var(--up); }
.overall-evidence[data-tone="oppose"] h5 { color: var(--down); }
.overall-evidence[data-tone="risk"] h5 { color: var(--warning); }
.agent-output { min-width: 0; max-width: 980px; padding: 0; border: 0; }
.report-summary { max-width: 72ch; font-size: 14px; line-height: 1.8; }
.report-grid { gap: 22px 30px; }
.report-block { padding-top: 15px; }
```

响应式：

```css
@media (max-width: 900px) {
  .agent-layout { grid-template-columns: minmax(300px, 360px) minmax(0, 1fr); gap: 24px; }
  .agent-sidebar { padding-right: 20px; }
}
@media (max-width: 768px) {
  .agent-layout { grid-template-columns: 1fr; }
  .agent-sidebar { border-right: 0; padding-right: 0; }
  .agent-output { border-top: 1px solid var(--hairline); padding-top: 22px; }
}
@media (max-width: 520px) {
  .agent-command { width: 100%; }
  .agent-command span { display: inline; }
  .overall-report-header dl { grid-template-columns: 48px minmax(0, 1fr); }
}
```

注意现有全局 `.primary-command span { display: none; }` 移动端规则必须被 `.agent-command span { display: inline; }` 覆盖，保证按钮文字完整。

- [ ] **Step 5: 运行 UI 测试并确认 GREEN**

Run:

```powershell
npm.cmd run test:ui -- --grep "overall report is below"
```

Expected: PASS。

- [ ] **Step 6: 检查 Impeccable craft floor**

逐项确认：无渐变、无深色主题、无嵌套装饰卡片、无超大标题、无颜色单独传达状态、按钮有可见文本、折叠区可通过键盘操作、长来源可换行。

---

### Task 9: 更新本地并存配置和文档

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `README.md`
- Modify locally only, never stage: `config/application-local.yml`
- Test: `src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java`

- [ ] **Step 1: 写默认离线角色测试**

在应用启动测试断言未配置命名模型时上下文仍启动，两个角色均不可用，市场数据 Bean 不受影响。测试属性使用：

```java
@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "app.ai.roles.institutional-report=primary",
        "app.ai.roles.overall-report=deepseek"
})
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AStockAgentApplicationTest' test
```

Expected: 若角色默认值尚未接入，测试 FAIL。

- [ ] **Step 3: 增加安全默认角色**

`application.yml` 增加：

```yaml
app:
  ai:
    models: {}
    roles:
      institutional-report: primary
      overall-report: deepseek
```

保留现有 `app.agent.learning` 和 `app.market-data`，不得重复覆盖整个 `app` 节点。

- [ ] **Step 4: 更新 README**

README 明确说明：

- 复制 `application-local.yml.example`；
- 模型可以同时启用，不再通过注释整段切换；
- `institutional-report` 默认使用 `primary`；
- `overall-report` 默认使用 `deepseek`；
- DeepSeek 使用 `https://api.deepseek.com`、`/v1/chat/completions`、`deepseek-chat`；
- 真实密钥只能写入本地忽略文件；
- 修改模型或角色后重启应用；
- 总体报告输入为完整规范化快照，失败不影响现有报告。

- [ ] **Step 5: 安全地追加本地 DeepSeek 占位配置**

只在 `config/application-local.yml` 末尾追加以下结构，不打印或替换现有密钥，不复制任何既有配置值：

```yaml
app:
  ai:
    models:
      deepseek:
        enabled: false
        base-url: "https://api.deepseek.com"
        api-key: "replace-with-deepseek-api-key"
        completions-path: "/v1/chat/completions"
        model: "deepseek-chat"
        temperature: 0.1
    roles:
      institutional-report: primary
      overall-report: deepseek
```

如果本地文件已有顶层 `app`，必须合并到现有节点而不是创建重复顶层键。`enabled` 保持 `false`，直到用户自行填入真实 DeepSeek Key；实现过程不得要求用户在对话中发送 Key。

- [ ] **Step 6: 运行配置测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AiModelPropertiesTest,LocalModelConfigurationExampleTest,AStockAgentApplicationTest' test
```

Expected: PASS。

- [ ] **Step 7: 确认本地文件未进入 Git**

Run:

```powershell
git check-ignore -v config/application-local.yml
git status --short -- config/application-local.yml
```

Expected: 第一条显示忽略规则；第二条无输出。

---

### Task 10: 完整回归、视觉验证和秘密审计

**Files:**
- Verify: all changed files
- Inspect: `target/ui-screenshots/`
- Inspect locally: generated report responses without credentials

- [ ] **Step 1: 运行聚焦 Java 测试**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=AiModelPropertiesTest,NamedChatClientRegistryTest,OverallReportValidatorTest,SpringAiOverallReportGeneratorTest,OverallReportServiceTest,AgentControllerTest,StockAnalysisAgentTest,StaticResourceTest' test
```

Expected: PASS，0 failures，0 errors。

- [ ] **Step 2: 运行全量离线 Java 测试**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
```

Expected: PASS；日志中没有外部 DeepSeek 请求。

- [ ] **Step 3: 运行打包**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
```

Expected: BUILD SUCCESS。

- [ ] **Step 4: 运行数据规则验证**

Run:

```powershell
.\scripts\verify-data.cmd
```

Expected: PASS；没有新增并行 Eastmoney 调用，也没有丢失 `Provenance`。

- [ ] **Step 5: 运行全量 UI 测试**

Run:

```powershell
npm.cmd run test:ui
```

Expected: PASS，并生成四档截图。

- [ ] **Step 6: 人工检查四档截图**

检查 `1440x1000`、`1024x768`、`768x1024`、`390x844`：

- 桌面总体报告在左、现有报告在右；
- 总体报告位于两个按钮下方；
- 单列时顺序为操作区、总体报告、现有报告；
- 无水平溢出、头部覆盖和按钮文字裁切；
- A 股上涨为红、下跌为绿，并有文字标签；
- 所有原有报告章节仍可见。

- [ ] **Step 7: 运行差异检查**

Run:

```powershell
git diff --check
git status --short
```

Expected: `git diff --check` 无输出；`git status` 只显示预期文件和执行前已有改动。

- [ ] **Step 8: 运行不泄露内容的秘密审计**

使用 PowerShell 只输出命中的文件路径，不输出匹配行或密钥值；排除本地忽略配置、`.git`、`.m2`、`target`、`out`：

```powershell
$tracked = git ls-files
$suspects = $tracked | Where-Object { Test-Path -LiteralPath $_ } | Where-Object {
  Select-String -LiteralPath $_ -Pattern 'sk-[A-Za-z0-9_-]{12,}|api[_-]?key\s*[:=]\s*["'']?(?!replace-with-|\$\{)' -Quiet -ErrorAction SilentlyContinue
}
$suspects
```

Expected: 无输出。若有输出，只打开占位示例并确认不是真实秘密；不得在终端打印匹配内容。

- [ ] **Step 9: 检查需求清单**

逐项确认：

- 现有报告内容完整；
- 独立 DeepSeek 按钮和 API；
- 完整快照输入；
- 专业提示词已固化并有测试；
- 多模型并存且按角色分配；
- DeepSeek 总体报告在左侧按钮下方；
- 失败局部化；
- 固定免责声明存在；
- 无真实密钥进入 Git。

- [ ] **Step 10: 提交策略**

当前工作区存在执行前的重叠改动。只有在 `git diff --cached --name-only` 与审阅过的补丁完全一致时才能提交；否则保留未提交状态并向用户说明。不得通过整文件 `git add` 把执行前的用户改动混入本功能提交。
