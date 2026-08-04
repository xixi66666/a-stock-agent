# DeepSeek Overall Report Non-Blocking Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 DeepSeek 总体报告在任何内容校验问题下仍返回模型正文，将问题作为警告展示，并取消二次模型修复请求与仓库原有报告安全限制。

**Architecture:** 保留 `OverallReportValidator` 作为只读诊断器，`OverallReportService` 将其所有 issue 转换为 `MODEL_NARRATIVE_VALIDATION_WARNING`，但始终组装成功报告；只有模型调用或响应映射异常仍返回失败。`OverallResearchReport` 不再覆盖模型免责声明，前端在成功报告正文中附加警告诊断。

**Tech Stack:** Java 21、Spring Boot 3.5、Spring AI、JUnit 5、AssertJ、JavaScript、Playwright

---

## File Map

- Modify: `AGENTS.md` — 删除已确认取消的交易表述和固定免责声明规则。
- Modify: `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java` — 锁定所有校验问题非阻断且不调用修复的服务行为。
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallReportService.java` — 单次生成、收集警告、直接组装成功报告。
- Modify: `src/test/java/com/astock/agent/agent/overall/OverallResearchReportTest.java` — 锁定模型免责声明原值的保存行为；若文件不存在则创建。
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java` — 取消构造器强制覆盖免责声明。
- Modify: `tests/ui/dashboard.spec.js` — 验证成功总体报告同时显示正文和警告诊断。
- Modify: `src/main/resources/static/js/app.js` — 将警告诊断渲染在成功总体报告中，并取消缺失免责声明时的固定文案回填。

### Task 1: 调整仓库规则

**Files:**
- Modify: `AGENTS.md:24-30`

- [ ] **Step 1: 删除两条已确认取消的规则**

将 `Agent Safety` 保留为：

```markdown
## Agent Safety

- Spring AI tools accept bounded structured inputs such as a six-digit security code. Do not expose arbitrary URL fetching, shell execution, filesystem access, or unrestricted HTTP clients as model tools.
- Deterministic calculations, validation, quality scoring, and source selection belong outside the model.
- Reports must distinguish facts, computed indicators, conflicts, missing data, and conclusions, and must cite sources.
```

- [ ] **Step 2: 检查规则变更范围**

Run: `git diff -- AGENTS.md`

Expected: 只删除交易指令/保证收益/目标仓位/个性化建议禁令和固定免责声明两行。

- [ ] **Step 3: 提交规则变更**

```powershell
git add -- AGENTS.md
git commit -m "docs: relax overall report safety constraints"
```

### Task 2: 总体报告校验改为非阻断警告

**Files:**
- Modify: `src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java:57-88`
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallReportService.java:30-73`

- [ ] **Step 1: 写失败测试**

用下面的测试替换 `repairsAtMostOnceAndReturnsValidationFailureWhenRepairStillInvalid`：

```java
@Test
void invalidDraftReturnsReportWithWarningWithoutRepair() {
    AtomicInteger repairs = new AtomicInteger();
    OverallReportGenerator generator = new OverallReportGenerator() {
        @Override
        public OverallReportDraft generate(StockResearchSnapshot ignored) {
            return invalidDraft();
        }

        @Override
        public OverallReportDraft repair(StockResearchSnapshot ignored,
                OverallReportDraft draft, List<String> issues) {
            repairs.incrementAndGet();
            throw new AssertionError("非阻断校验不得触发模型修复");
        }

        @Override
        public String modelName() {
            return "deepseek-chat";
        }
    };

    OverallReportResponse response = service(generator).generate("600519");

    assertThat(repairs).hasValue(0);
    assertThat(response.status()).isEqualTo(OverallReportStatus.MODEL_ASSISTED);
    assertThat(response.report()).isNotNull();
    assertThat(response.report().overallConclusion()).isEqualTo("建议买入");
    assertThat(response.diagnostic()).isNotNull();
    assertThat(response.diagnostic().errorCode())
            .isEqualTo("MODEL_NARRATIVE_VALIDATION_WARNING");
    assertThat(response.diagnostic().validationIssues())
            .contains("TRADE_INSTRUCTION", "INVALID_DISCLAIMER");
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportServiceTest#invalidDraftReturnsReportWithWarningWithoutRepair' test
```

Expected: FAIL；当前实现会调用 `repair()`，测试抛出“非阻断校验不得触发模型修复”。

- [ ] **Step 3: 写最小实现**

将 `OverallReportService.generate` 中生成后的校验与组装部分改为：

```java
OverallReportDraft draft = generator.generate(snapshot);
OverallReportValidator.Validation validation = validator.validate(draft, snapshot);
ModelDiagnostic diagnostic = validation.issues().isEmpty()
        ? null
        : classifier.validationWarning(
                validation.issues(), modelName, elapsedMillis(started), traceId);

OverallResearchReport report = OverallResearchReport.from(
        draft,
        modelName,
        snapshot.fetchedAt(),
        Instant.now(),
        SpringAiOverallReportGenerator.PROMPT_VERSION);
return new OverallReportResponse(
        OverallReportStatus.MODEL_ASSISTED,
        report,
        diagnostic,
        validation.issues().isEmpty() ? "总体报告已生成" : "总体报告已生成，存在校验警告");
```

删除 `generator.repair(...)` 和 `VALIDATION_FAILED` 返回分支，但保留外围 `catch`。

- [ ] **Step 4: 运行聚焦测试并确认 GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallReportServiceTest' test
```

Expected: PASS；模型异常测试仍返回 `MODEL_FAILED`，无警告草稿仍返回空诊断。

- [ ] **Step 5: 提交服务变更**

```powershell
git add -- src/main/java/com/astock/agent/agent/overall/OverallReportService.java src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java
git commit -m "feat: make overall report validation non-blocking"
```

### Task 3: 保留模型免责声明原值

**Files:**
- Create or Modify: `src/test/java/com/astock/agent/agent/overall/OverallResearchReportTest.java`
- Modify: `src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java:20-39`

- [ ] **Step 1: 写失败测试**

```java
package com.astock.agent.agent.overall;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverallResearchReportTest {

    @Test
    void preservesModelDisclaimerWithoutServerOverride() {
        OverallReportDraft draft = new OverallReportDraft(
                "结论", "数据质量", "公司与基本面", "技术与资金", "估值与行业", "事件与情绪",
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of(), "模型自定义说明");

        OverallResearchReport report = OverallResearchReport.from(
                draft, "deepseek-chat", Instant.EPOCH, Instant.EPOCH, "overall-v1");

        assertThat(report.disclaimer()).isEqualTo("模型自定义说明");
    }
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallResearchReportTest' test
```

Expected: FAIL；实际值仍为 `仅供学习研究，不构成投资建议`。

- [ ] **Step 3: 写最小实现**

从 `OverallResearchReport` 紧凑构造器删除：

```java
disclaimer = REQUIRED_DISCLAIMER;
```

保留 `REQUIRED_DISCLAIMER` 常量，以免现有测试辅助数据和调用方发生无关破坏。

- [ ] **Step 4: 更新既有服务断言**

在 `validDraftReturnsModelAssistedReport` 中继续断言有效草稿的免责声明等于常量；该草稿本身传入相同值，行为仍应通过。

- [ ] **Step 5: 运行总体报告单元测试**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=OverallResearchReportTest,OverallReportServiceTest,OverallReportValidatorTest' test
```

Expected: PASS。

- [ ] **Step 6: 提交免责声明变更**

```powershell
git add -- src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java src/test/java/com/astock/agent/agent/overall/OverallResearchReportTest.java
git commit -m "feat: preserve overall report disclaimer"
```

### Task 4: 成功报告展示校验警告

**Files:**
- Modify: `tests/ui/dashboard.spec.js:255-276`
- Modify: `src/main/resources/static/js/app.js:250-267`

- [ ] **Step 1: 将失败场景测试改成成功警告场景**

用以下测试替换 `DeepSeek overall validation failure exposes its diagnostic issues`：

```javascript
test("DeepSeek overall report remains visible with validation warnings", async ({ page }) => {
  await page.route("**/api/agent/overall-report", (route) => route.fulfill({ json: {
    status: "MODEL_ASSISTED",
    message: "总体报告已生成，存在校验警告",
    report: {
      overallConclusion: "模型原始总体结论",
      dataQualitySummary: "数据质量说明",
      companyAndFundamentals: "公司与基本面",
      technicalAndCapital: "技术与资金",
      valuationAndIndustry: "估值与行业",
      eventsAndSentiment: "事件与情绪",
      bullishEvidence: [], bearishEvidence: [], riskFactors: [], scenarios: {},
      conflictsAndMissingData: [], sourceReferences: [], modelName: "deepseek-chat",
      snapshotAt: "2026-08-03T02:00:00Z", disclaimer: "模型自定义说明",
    },
    diagnostic: {
      failureStage: "VALIDATION", errorCode: "MODEL_NARRATIVE_VALIDATION_WARNING",
      exceptionType: "ReportValidationWarning", message: "模型叙述存在校验警告",
      validationIssues: ["UNSUPPORTED_NUMBER", "UNKNOWN_SOURCE_REFERENCE"], modelName: "deepseek-chat",
      durationMs: 120, occurredAt: "2026-08-03T02:00:00Z", traceId: "overall-trace-test-1",
    },
  } }));

  await page.goto("/");
  await page.locator('[data-symbol="600519"]').click();
  await page.getByRole("tab", { name: "Agent 分析" }).click();
  await page.getByRole("button", { name: "生成总体报告 DeepSeek" }).click();

  await expect(page.locator("#overall-report-output")).toContainText("模型原始总体结论");
  await expect(page.locator("#overall-report-output")).toContainText("MODEL_NARRATIVE_VALIDATION_WARNING");
  await expect(page.locator("#overall-report-output")).toContainText("UNSUPPORTED_NUMBER");
  await expect(page.locator("#overall-report-output")).toContainText("模型自定义说明");
});
```

- [ ] **Step 2: 运行 UI 测试并确认 RED**

Run:

```powershell
npm.cmd run test:ui -- --grep "remains visible with validation warnings"
```

Expected: FAIL；当前成功渲染分支没有输出 `response.diagnostic`。

- [ ] **Step 3: 写最小前端实现**

将 `renderOverallReportResponse` 的成功分支增加：

```javascript
const diagnosticMarkup = response.diagnostic
  ? renderModelDiagnostic(response.diagnostic, "总体报告校验提示")
  : "";
```

并在 `</article>` 前渲染：

```javascript
${report.disclaimer ? `<small class="report-disclaimer">${escapeText(report.disclaimer)}</small>` : ""}
${diagnosticMarkup}
```

替换当前固定免责声明回填表达式，避免模型未返回免责声明时由前端重新添加。

- [ ] **Step 4: 运行聚焦 UI 测试并确认 GREEN**

Run:

```powershell
npm.cmd run test:ui -- --grep "remains visible with validation warnings"
```

Expected: PASS；正文、警告码、具体 issue 和模型免责声明同时可见。

- [ ] **Step 5: 提交前端变更**

```powershell
git add -- src/main/resources/static/js/app.js tests/ui/dashboard.spec.js
git commit -m "feat: show overall report validation warnings"
```

### Task 5: 完整验证与收尾

**Files:**
- Verify only: all changed files

- [ ] **Step 1: 运行后端完整离线测试**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
```

Expected: PASS；不运行标记为 `external` 的真实模型测试。

- [ ] **Step 2: 验证打包**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
```

Expected: `BUILD SUCCESS`。

- [ ] **Step 3: 运行数据验证**

Run:

```powershell
.\scripts\verify-data.cmd
```

Expected: PASS；若外部数据不可用，按脚本既定状态记录，不伪造替代值。

- [ ] **Step 4: 运行完整 UI 测试**

Run:

```powershell
npm.cmd run test:ui
```

Expected: PASS；检查 1440x1000、1024x768、768x1024、390x844 截图无溢出。

- [ ] **Step 5: 检查差异与秘密信息**

Run:

```powershell
git diff --check
git status --short
git diff -- AGENTS.md src/main/java/com/astock/agent/agent/overall/OverallReportService.java src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java src/main/resources/static/js/app.js src/test/java/com/astock/agent/agent/overall tests/ui/dashboard.spec.js
```

Expected: 无空白错误；未跟踪的用户 `out/` 保持未暂存；没有密钥、Token、Cookie 或本地配置进入差异。

- [ ] **Step 6: 必要时提交验证修正**

仅当验证产生了必要的小修正时执行：

```powershell
git add -- AGENTS.md src/main/java/com/astock/agent/agent/overall/OverallReportService.java src/main/java/com/astock/agent/agent/overall/OverallResearchReport.java src/main/resources/static/js/app.js src/test/java/com/astock/agent/agent/overall/OverallReportServiceTest.java src/test/java/com/astock/agent/agent/overall/OverallResearchReportTest.java tests/ui/dashboard.spec.js
git commit -m "test: verify non-blocking overall reports"
```

不得暂存 `out/`、`config/application-local.yml`、`.m2/`、`target/` 或截图产物。
