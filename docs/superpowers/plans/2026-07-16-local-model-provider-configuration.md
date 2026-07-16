# Local Model Provider Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a Git-ignored local Spring AI configuration with manually switchable OpenAI, DeepSeek, and Xiaomi MiMo templates, and document the workflow in README.

**Architecture:** Reuse the existing Spring AI OpenAI-compatible client for all three providers. Keep one active YAML block and two commented alternatives, mirror the placeholder-only structure in the tracked example, and lock the configuration contract with an offline test.

**Tech Stack:** Java 21, Spring Boot 3.5.16, Spring AI 1.1.8, JUnit 5, AssertJ, YAML

---

## File Structure

- Create `config/application-local.yml`: ignored local configuration that users edit directly.
- Modify `config/application-local.yml.example`: tracked placeholder-only source for recreating the local file.
- Create `src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java`: offline contract test for YAML syntax, provider templates, and secret placeholders.
- Modify `README.md`: setup, manual switching, provider endpoints, and safety instructions.

### Task 1: Lock And Generate Provider Templates

**Files:**
- Create: `src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java`
- Modify: `config/application-local.yml.example`
- Create: `config/application-local.yml`

- [ ] **Step 1: Write the failing configuration contract test**

```java
package com.astock.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class LocalModelConfigurationExampleTest {

    private static final Path EXAMPLE = Path.of("config", "application-local.yml.example");

    @Test
    void providesThreeOpenAiCompatibleTemplatesWithoutRealSecrets() throws Exception {
        String yaml = Files.readString(EXAMPLE);

        assertThat(yaml).contains(
                "https://api.openai.com",
                "https://api.deepseek.com",
                "https://api.xiaomimimo.com/v1",
                "deepseek-chat",
                "mimo-v2.5-pro",
                "completions-path: \"/chat/completions\"");
        assertThat(yaml).doesNotContainPattern("sk-[A-Za-z0-9_-]{12,}");

        var sources = new YamlPropertySourceLoader()
                .load("local-model-example", new FileSystemResource(EXAMPLE));
        assertThat(sources).hasSize(1);

        PropertySource<?> active = sources.getFirst();
        assertThat(active.getProperty("spring.ai.model.chat")).isEqualTo("openai");
        assertThat(active.getProperty("spring.ai.openai.base-url")).isEqualTo("https://api.openai.com");
        assertThat(active.getProperty("spring.ai.openai.api-key"))
                .isEqualTo("replace-with-openai-api-key");
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
$env:JAVA_HOME = (Resolve-Path '.\.tools\jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=LocalModelConfigurationExampleTest' test
```

Expected: FAIL because the current example does not contain the three provider endpoints and templates.

- [ ] **Step 3: Replace the example and create the ignored local file**

Use this exact content for both files:

```yaml
# 当前启用：OpenAI。填写 API Key 和账号可用的模型名称后重启应用。
spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: "replace-with-openai-api-key"
      base-url: "https://api.openai.com"
      chat:
        options:
          model: "replace-with-openai-model"
          temperature: 0.2

# DeepSeek 模板：切换时注释上面的 OpenAI 配置，再取消下面整段注释。
# spring:
#   ai:
#     model:
#       chat: openai
#     openai:
#       api-key: "replace-with-deepseek-api-key"
#       base-url: "https://api.deepseek.com"
#       chat:
#         options:
#           model: "deepseek-chat"
#           temperature: 0.2

# Xiaomi MiMo 模板：Spring AI 默认追加 /v1/chat/completions，
# 因此官方 /v1 Base URL 需要把 completions-path 改为 /chat/completions。
# spring:
#   ai:
#     model:
#       chat: openai
#     openai:
#       api-key: "replace-with-mimo-api-key"
#       base-url: "https://api.xiaomimimo.com/v1"
#       chat:
#         completions-path: "/chat/completions"
#         options:
#           model: "mimo-v2.5-pro"
#           temperature: 0.2
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command again.

Expected: PASS with one test and zero failures.

- [ ] **Step 5: Verify the real local file remains ignored**

Run:

```powershell
git check-ignore -v config/application-local.yml
git status --short
```

Expected: `.gitignore` matches `config/application-local.yml`; only the example, test, README, design, and plan are eligible for Git status.

### Task 2: Document Manual Provider Switching

**Files:**
- Modify: `README.md:39`

- [ ] **Step 1: Replace the current model configuration section**

Document these exact steps:

1. Run `Copy-Item config/application-local.yml.example config/application-local.yml` if the local file does not exist.
2. Open `config/application-local.yml` and keep exactly one complete `spring:` block uncommented.
3. Fill in only the selected provider's `api-key` and model name.
4. Use OpenAI `https://api.openai.com`, DeepSeek `https://api.deepseek.com`, or MiMo `https://api.xiaomimimo.com/v1` with `chat.completions-path: /chat/completions`.
5. Restart with `.\start.ps1` after every switch.
6. Never copy real keys into the example, README, logs, tests, or commits.

Include the complete three-template YAML from Task 1 so the README remains directly usable.

- [ ] **Step 2: Verify README names and commands**

Run:

```powershell
rg -n "application-local.yml|api.openai.com|api.deepseek.com|api.xiaomimimo.com|completions-path|start.ps1" README.md
```

Expected: every provider, the MiMo path override, the local filename, and restart command are present.

### Task 3: Fresh Offline Verification And Commit

**Files:**
- Verify all changed files.

- [ ] **Step 1: Run the complete offline test suite**

```powershell
$env:JAVA_HOME = (Resolve-Path '.\.tools\jdk-21').Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' test
```

Expected: `BUILD SUCCESS` with no external-tagged tests executed.

- [ ] **Step 2: Run whitespace and secret checks**

```powershell
git diff --check
rg -n "sk-[A-Za-z0-9_-]{12,}|Bearer [A-Za-z0-9_-]{12,}" README.md config/application-local.yml.example src/test docs/superpowers
```

Expected: `git diff --check` exits successfully and the secret-pattern scan returns no matches.

- [ ] **Step 3: Review final status**

```powershell
git status --short
git diff -- README.md config/application-local.yml.example src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java docs/superpowers
```

Expected: the ignored `config/application-local.yml` is absent from the diff; all tracked changes match the approved design.

- [ ] **Step 4: Commit the tracked implementation**

```powershell
git add README.md config/application-local.yml.example src/test/java/com/astock/agent/config/LocalModelConfigurationExampleTest.java docs/superpowers
git commit -m "docs: add local model provider setup"
```

Expected: one commit containing only placeholder-safe tracked files; the real local configuration stays untracked and ignored.

