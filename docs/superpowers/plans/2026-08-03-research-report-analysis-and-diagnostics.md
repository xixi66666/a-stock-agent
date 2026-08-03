# Research Report Analysis and Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build evidence-backed module analyses and concrete core drivers, while returning safe but actionable model failure diagnostics instead of the generic `model unavailable` fallback.

**Architecture:** Keep normalized provider data and `Provenance` in `StockResearchSnapshot`. Add deterministic, independently testable module analyzers that produce a shared report contract; aggregate their signals into core drivers, pass the same bounded facts to the model, validate optional model prose, and always assemble a complete deterministic report. Isolate model invocation behind a small interface so request, mapping, validation, and assembly failures can be classified without mocking Spring AI fluent chains.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI 1.1, Jackson, AssertJ/JUnit 5, plain ES modules, Playwright.

---

## Working Tree Constraint

The repository already contains uncommitted report/UI work, including `ReportFact`, module `facts`, composer changes, and frontend rendering. Treat those edits as user-owned input. Do not reset, overwrite, or include unrelated files in a commit. Before every edit, re-read the target diff. Because several implementation files already contain inseparable user edits, implementation commits are optional; only commit a slice when `git diff --cached` proves it contains exactly that slice.

## File Map

**Create**

- `src/main/java/com/astock/agent/analysis/institutional/AnalysisModule.java`: stable module identifiers.
- `src/main/java/com/astock/agent/analysis/institutional/AnalysisSignal.java`: one evidence-backed directional signal.
- `src/main/java/com/astock/agent/analysis/institutional/ModuleAnalysis.java`: shared deterministic module output.
- `src/main/java/com/astock/agent/analysis/institutional/CoreDriver.java`: concrete ranked driver exposed by the report.
- `src/main/java/com/astock/agent/analysis/institutional/ModuleAnalysisFactory.java`: derives module analyses from snapshot and scores.
- `src/main/java/com/astock/agent/agent/report/ModelFailureStage.java`: stable failure stages.
- `src/main/java/com/astock/agent/agent/report/ModelDiagnostic.java`: safe API diagnostic payload.
- `src/main/java/com/astock/agent/agent/report/ModelFailureClassifier.java`: maps exceptions/validation failures to diagnostics.
- `src/main/java/com/astock/agent/agent/report/NarrativeGenerator.java`: testable model boundary.
- `src/main/java/com/astock/agent/agent/report/SpringAiNarrativeGenerator.java`: Spring AI implementation.
- `src/test/java/com/astock/agent/analysis/institutional/ModuleAnalysisFactoryTest.java`.
- `src/test/java/com/astock/agent/agent/report/ModelFailureClassifierTest.java`.

**Modify**

- `src/main/java/com/astock/agent/agent/report/ReportFact.java`: add stable fact ID and optional observation time while retaining the existing constructor.
- `src/main/java/com/astock/agent/analysis/institutional/DeterministicAssessment.java`: carry module analyses and concrete core drivers.
- `src/main/java/com/astock/agent/analysis/institutional/ResearchJudgementEngine.java`: build module analyses and rank concrete signals.
- `src/main/java/com/astock/agent/agent/report/ReportEvidencePackage.java`: send module analyses and drivers to the model.
- `src/main/java/com/astock/agent/agent/report/InstitutionalResearchReport.java`: expose `CoreDriver` and optional `ModelDiagnostic`.
- `src/main/java/com/astock/agent/agent/report/TechnicalAndFlowAnalysis.java`.
- `src/main/java/com/astock/agent/agent/report/FundamentalExpectationAnalysis.java`.
- `src/main/java/com/astock/agent/agent/report/ValuationIndustryAnalysis.java`.
- `src/main/java/com/astock/agent/agent/report/InstitutionalReportComposer.java`: assemble shared module outputs; stop creating generic evidence placeholders.
- `src/main/java/com/astock/agent/agent/report/ReportValidator.java`: validate each narrative field and return field-level issues.
- `src/main/java/com/astock/agent/agent/StockAnalysisAgent.java`: orchestrate generation, validation, classified fallback, and safe logging.
- `src/main/java/com/astock/agent/agent/AgentConfiguration.java`: wire `NarrativeGenerator`.
- `src/main/resources/static/js/app.js`: render drivers, methodology, counter-evidence, limitations, and diagnostics.
- `src/main/resources/static/styles.css`: compact diagnostic and analysis layouts.
- Existing focused tests and `tests/ui/dashboard.spec.js`.

## Task 1: Shared Deterministic Analysis Contract

**Files:**

- Create the four contract records under `analysis/institutional`.
- Modify `agent/report/ReportFact.java`.
- Test `src/test/java/com/astock/agent/analysis/institutional/ModuleAnalysisFactoryTest.java`.

- [ ] **Step 1: Write the failing contract test**

```java
@Test
void moduleAnalysisCopiesCollectionsAndRejectsFactsWithoutEvidenceIdentity() {
    ReportFact fact = new ReportFact("technical-sma20", "SMA20", "100", "technical", null);
    AnalysisSignal signal = new AnalysisSignal("trend-confirmed", Direction.STRONGER, 60,
            "SMA20高于SMA60", "中期均线多头排列支持趋势延续",
            List.of(fact.id()), "SMA20跌破SMA60");

    ModuleAnalysis result = new ModuleAnalysis(AnalysisModule.TECHNICAL_PRICE_VOLUME,
            Direction.STRONGER, "趋势与动量互相确认", "HIGH",
            List.of(fact), List.of(signal), List.of("趋势跟随与动量确认"),
            List.of("量能未同步放大"), List.of("仅基于日线样本"), List.of("technical"));

    assertThat(result.facts()).containsExactly(fact);
    assertThat(result.signals()).containsExactly(signal);
    assertThatThrownBy(() -> new ReportFact("", "SMA20", "100", "technical", null))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ModuleAnalysisFactoryTest' test
```

Expected: compilation fails because the shared contract types and five-argument `ReportFact` constructor do not exist.

- [ ] **Step 3: Implement the minimal immutable records**

Use these signatures:

```java
public enum AnalysisModule {
    TECHNICAL_PRICE_VOLUME, FUND_FLOW_CAPITAL, EVENT_CATALYST,
    FUNDAMENTAL_EXPECTATION, VALUATION_INDUSTRY
}

public record AnalysisSignal(String id, Direction direction, int impact,
        String conclusion, String rationale, List<String> factIds, String invalidation) { }

public record ModuleAnalysis(AnalysisModule module, Direction direction,
        String conclusion, String confidence, List<ReportFact> facts,
        List<AnalysisSignal> signals, List<String> methodology,
        List<String> counterEvidence, List<String> limitations,
        List<String> sourceIds) { }

public record CoreDriver(String id, AnalysisModule module, Direction direction,
        String conclusion, String rationale, List<String> factIds,
        String invalidation) { }

public record ReportFact(String id, String label, String value,
        String sourceId, Instant observedAt) {
    public ReportFact(String label, String value, String sourceId) {
        this(slug(label), label, value, sourceId, null);
    }

    private static String slug(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? "fact-" + Integer.toUnsignedString(Objects.toString(value, "").hashCode()) : normalized;
    }
}
```

Validate nonblank IDs/conclusions, `impact` in `0..100`, defensive-copy every collection, and retain the existing three-argument `ReportFact` constructor.

- [ ] **Step 4: Run the focused test and verify GREEN**

Expected: `ModuleAnalysisFactoryTest` passes without network access.

- [ ] **Step 5: Inspect the diff before continuing**

Run `git diff --check` and confirm no user-owned report behavior was removed.

## Task 2: Evidence-Backed Module Analysis

**Files:**

- Create `ModuleAnalysisFactory.java`.
- Modify `ResearchJudgementEngine.java` and `DeterministicAssessment.java`.
- Test `ModuleAnalysisFactoryTest.java` and `ResearchJudgementEngineTest.java`.

- [ ] **Step 1: Write failing tests for concrete conclusions**

Add fixtures already used by `ResearchJudgementEngineTest` and assert:

```java
@Test
void explainsTechnicalTrendWithFactsMethodAndCounterEvidence() {
    ModuleAnalysis result = engine.assess(snapshotWithStrongTrendAndOutflow())
            .moduleAnalysis(AnalysisModule.TECHNICAL_PRICE_VOLUME);

    assertThat(result.conclusion()).contains("SMA20").contains("SMA60");
    assertThat(result.facts()).extracting(ReportFact::label)
            .contains("SMA20", "SMA60", "20日收益", "20日量比");
    assertThat(result.methodology()).anyMatch(value -> value.contains("趋势") && value.contains("动量"));
    assertThat(result.sourceIds()).contains("technical");
}

@Test
void doesNotTreatCapitalRecordCountsAsDirectionalEvidence() {
    ModuleAnalysis result = engine.assess(snapshotWithCapitalCountsOnly())
            .moduleAnalysis(AnalysisModule.FUND_FLOW_CAPITAL);

    assertThat(result.direction()).isEqualTo(Direction.INSUFFICIENT);
    assertThat(result.limitations()).anyMatch(value -> value.contains("规模") || value.contains("变化"));
}

@Test
void explainsValuationPremiumAndComparabilityLimit() {
    ModuleAnalysis result = engine.assess(completeSnapshot(true))
            .moduleAnalysis(AnalysisModule.VALUATION_INDUSTRY);

    assertThat(result.facts()).extracting(ReportFact::label)
            .contains("个股PE(TTM)", "行业PE中位数", "PE相对溢价");
    assertThat(result.methodology()).anyMatch(value -> value.contains("相对估值"));
}
```

Add this complete fixture to `ResearchJudgementEngineTest` so the capital test does not depend on live data:

```java
private static StockResearchSnapshot snapshotWithCapitalCountsOnly() {
    SecurityId id = SecurityId.parse("600519");
    Quote quote = new Quote(id, "贵州茅台", bd(100), bd(99), bd(99), bd(101), bd(98),
            bd(1), bd(1), bd(1000), bd(100000), bd(1), bd(2), bd(1), bd(20), bd(20),
            bd(5), bd(1000000), bd(900000), bd(110), bd(90), Instant.now());
    List<DailyBar> bars = List.of(new DailyBar(LocalDate.of(2026, 7, 30),
            bd(99), bd(101), bd(98), bd(100), bd(1000), bd(100000)));
    CapitalData capital = new CapitalData(
            List.of(new CapitalData.MarginRecord(LocalDate.of(2026, 7, 30),
                    null, null, null, null)),
            List.of(new CapitalData.BlockTrade(LocalDate.of(2026, 7, 30),
                    null, null, null, null, null, "", "")),
            List.of(), List.of(), List.of(), List.of());
    return new StockResearchSnapshot(id, DataSection.healthy(quote, SOURCE),
            DataSection.healthy(bars, SOURCE), DataSection.unavailable("technical missing"),
            DataSection.unavailable("sectors missing"), DataSection.unavailable("valuation missing"),
            DataSection.unavailable("flow missing"), DataSection.healthy(capital, SOURCE),
            DataSection.unavailable("fundamentals missing"), DataSection.unavailable("research missing"),
            DataSection.unavailable("news missing"), DataSection.unavailable("announcements missing"),
            null, true, true, false, Instant.now());
}
```

- [ ] **Step 2: Run the two focused test classes and verify RED**

Expected: factory is missing and `DeterministicAssessment` has no module analysis accessor.

- [ ] **Step 3: Implement deterministic analysis rules**

`ModuleAnalysisFactory.analyze(snapshot, dimensions)` returns an `EnumMap<AnalysisModule, ModuleAnalysis>` and uses only normalized snapshot values:

- Technical: compare SMA20/SMA60, MACD state, RSI/RETURN, volume ratio, NATR and drawdown; include the theory boundary that one indicator is insufficient.
- Flow/capital: calculate available 5/20-day flows; compare first/last financing balances; inspect shareholder change, unlock ratio, block-trade premium and dragon-tiger net buy only when values and dates exist.
- Events: classify announcement title/type and capital events separately; preserve positive and negative signals.
- Fundamentals: evaluate revenue/profit/cash-flow YoY when present; combine consensus coverage and EPS medians without inventing growth rates.
- Valuation: calculate `(target / median - 1) * 100` only for positive comparable values; state sample exclusions and cross-check against fundamentals.

Do not add external calls. Each fact must carry the relevant section source ID and observation time when available.

- [ ] **Step 4: Store module analyses in `DeterministicAssessment`**

Add:

```java
public ModuleAnalysis moduleAnalysis(AnalysisModule module) { return moduleAnalyses.get(module); }
public Map<AnalysisModule, ModuleAnalysis> moduleAnalyses() { return moduleAnalyses; }
```

Keep existing score accessors for compatibility and internal aggregation.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-Dtest=ModuleAnalysisFactoryTest,ResearchJudgementEngineTest' test
```

Expected: all focused tests pass offline.

## Task 3: Concrete Core Drivers and Rich Evidence Package

**Files:**

- Modify `ResearchJudgementEngine.java`, `DeterministicAssessment.java`, `ReportEvidencePackage.java`, `InstitutionalReportComposer.java`, module report records, and their tests.

- [ ] **Step 1: Write failing driver and serialization tests**

```java
@Test
void coreDriversAreConcreteSignalsWithoutInternalScoresOrPlaceholders() {
    DeterministicAssessment result = engine.assess(completeSnapshot(true));

    assertThat(result.coreDrivers()).isNotEmpty();
    assertThat(result.coreDrivers()).allSatisfy(driver -> {
        assertThat(driver.conclusion()).isNotBlank();
        assertThat(driver.rationale()).isNotBlank();
        assertThat(driver.factIds()).isNotEmpty();
    });
    assertThat(writeJson(result.coreDrivers()))
            .doesNotContain("规则方向分", "共同判断", "internalScore");
}
```

Add a composer assertion that serialized `ReportEvidencePackage` contains `moduleAnalyses`, actual SMA/valuation facts, methodology, and concrete drivers.

- [ ] **Step 2: Run focused tests and verify RED**

Expected: `coreDrivers()` still returns `ReportEvidence` and the evidence package lacks module analyses.

- [ ] **Step 3: Rank concrete signals**

Flatten usable module signals, rank by `abs(weightedContribution)`, signal impact, freshness/availability, and conflict discount. Select at most six while allowing both positive and negative directions. Map each selected signal to `CoreDriver`; never expose raw scores.

- [ ] **Step 4: Assemble module report sections from shared analysis**

Extend the three existing report-section records with conclusion, signals, methodology, counter-evidence and limitations while retaining compatibility constructors. Use `ModuleAnalysis` facts rather than duplicating fact extraction inside `InstitutionalReportComposer`.

Add event analysis to `catalysts`, risks, and conflicts without converting record counts into conclusions.

- [ ] **Step 5: Expand `ReportEvidencePackage`**

Add immutable `moduleAnalyses` and `coreDrivers` fields. Populate the evidence catalog with each fact and signal using stable IDs and real interpretations. Remove the generic interpretation `该分区已提供可追溯数据。` from model evidence.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run composer, validator, and judgement engine tests. Expected: actual facts are serialized, placeholders and internal scores are absent.

## Task 4: Model Failure Classification and Safe Diagnostics

**Files:**

- Create `ModelFailureStage.java`, `ModelDiagnostic.java`, `ModelFailureClassifier.java`.
- Modify `InstitutionalResearchReport.java`.
- Test `ModelFailureClassifierTest.java`.

- [ ] **Step 1: Write failing classification tests**

```java
@Test
void classifiesTimeoutAndPreservesSafeRootCause() {
    Throwable failure = new RuntimeException("request failed",
            new java.net.http.HttpTimeoutException("timed out after 30s"));

    ModelDiagnostic result = classifier.classify(failure, "gpt-test", 30123, "trace-1");

    assertThat(result.failureStage()).isEqualTo(ModelFailureStage.REQUEST);
    assertThat(result.errorCode()).isEqualTo("MODEL_TIMEOUT");
    assertThat(result.exceptionType()).isEqualTo("HttpTimeoutException");
    assertThat(result.message()).contains("30s");
}

@Test
void redactsCredentialsAndSensitiveQueryValues() {
    RuntimeException failure = new RuntimeException(
            "401 url=https://example.test/v1?token=secret-value Authorization: Bearer abc");

    ModelDiagnostic result = classifier.classify(failure, "gpt-test", 20, "trace-2");

    assertThat(result.message()).doesNotContain("secret-value", "Bearer abc")
            .contains("[REDACTED]");
}
```

Also test connection failures, HTTP status exceptions, empty response, mapping failure, validation issues and assembly failure.

- [ ] **Step 2: Run classifier tests and verify RED**

Expected: diagnostic types do not exist.

- [ ] **Step 3: Implement failure types and sanitizer**

`ModelDiagnostic` fields are exactly `failureStage`, `errorCode`, `exceptionType`, `message`, `validationIssues`, `modelName`, `durationMs`, `occurredAt`, and `traceId`. Walk to the most specific cause, preserve HTTP status and non-sensitive reason text, redact sensitive names case-insensitively, redact bearer/basic values, and sanitize URL query values.

Provide a separate factory for validation diagnostics so `ReportValidator.ValidationResult` does not need to masquerade as an exception.

- [ ] **Step 4: Add optional diagnostic to report contract**

Retain an overloaded compatibility constructor that defaults `modelDiagnostic` to `null` so existing controller tests keep compiling.

- [ ] **Step 5: Run classifier and report serialization tests and verify GREEN**

Expected: every failure category has a stable code and no fixture credential appears in JSON.

## Task 5: Testable Model Boundary and Section-Local Validation

**Files:**

- Create `NarrativeGenerator.java` and `SpringAiNarrativeGenerator.java`.
- Modify `ReportValidator.java`, `StockAnalysisAgent.java`, `AgentConfiguration.java`, `InstitutionalReportComposer.java`.
- Test `ReportValidatorTest.java` and `StockAnalysisAgentTest.java`.

- [ ] **Step 1: Write failing orchestration tests using a fake generator**

```java
@Test
void timeoutReturnsCompleteDeterministicReportWithRealDiagnostic() {
    NarrativeGenerator generator = evidence -> { throw new HttpTimeoutException("model timed out after 30s"); };
    StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
    AgentStatusService status = new AgentStatusService(new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "test-value")
            .withProperty("spring.ai.model.chat", "openai"));
    StockAnalysisAgent agent = new StockAnalysisAgent(generator, status,
            new StockAgentTools(id -> snapshot));

    InstitutionalResearchReport report = agent.analyzeInstitutional(snapshot);

    assertThat(report.generationMode()).isEqualTo(GenerationMode.REPORT_UNAVAILABLE);
    assertThat(report.modelDiagnostic().errorCode()).isEqualTo("MODEL_TIMEOUT");
    assertThat(report.disclaimer()).isEqualTo("仅供学习研究，不构成投资建议");
}

@Test
void validationFailureReturnsExactIssueCodes() {
    NarrativeGenerator generator = evidence -> new ReportNarrativeDraft(
            "不存在的999亿元 [quote]", "技术 [quote]", "基本面 [quote]", "估值 [quote]",
            List.of(), List.of());
    StockResearchSnapshot snapshot = StockResearchSnapshot.empty(SecurityId.parse("600519"));
    AgentStatusService status = new AgentStatusService(new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "test-value")
            .withProperty("spring.ai.model.chat", "openai"));
    InstitutionalResearchReport report = new StockAnalysisAgent(generator, status,
            new StockAgentTools(id -> snapshot)).analyzeInstitutional(snapshot);

    assertThat(report.modelDiagnostic().failureStage()).isEqualTo(ModelFailureStage.VALIDATION);
    assertThat(report.modelDiagnostic().validationIssues()).contains("UNSUPPORTED_NUMBER");
}
```

- [ ] **Step 2: Run agent and validator tests and verify RED**

Expected: agent still depends directly on `ChatClient` and diagnostics are absent.

- [ ] **Step 3: Implement the model boundary**

```java
@FunctionalInterface
public interface NarrativeGenerator {
    ReportNarrativeDraft generate(ReportEvidencePackage evidence) throws Exception;
}
```

`SpringAiNarrativeGenerator` owns the prompt, JSON serialization and `.entity(ReportNarrativeDraft.class)` mapping. Configuration creates it only when a ready `ChatClient.Builder` exists.

- [ ] **Step 4: Return field-level validation issues**

Change validation output to include `Map<String, List<String>> issuesByField` plus flattened issue codes. Validate executive summary, technical, fundamentals and valuation independently. Missing citations and unsupported numbers identify their field. Unknown evidence, trading instructions, or an unparseable response remain global failures.

- [ ] **Step 5: Replace the broad generic catch**

Measure duration, generate a trace ID, and classify the actual failure. Log the failure stage/code/type and a sanitized causal chain with stack frames; never log prompt JSON, credentials, headers, or raw upstream bodies. Pass `ModelDiagnostic` into deterministic fallback.

When the generator is disabled by configuration, report deterministic mode without fabricating a runtime error; expose the existing agent status separately.

- [ ] **Step 6: Run focused tests and verify GREEN**

Expected: timeout, connection, parse and validation tests return distinct diagnostics; deterministic sections remain complete.

## Task 6: Research-Focused UI Rendering

**Files:**

- Modify `src/main/resources/static/js/app.js`, `styles.css`, `index.html`, and `tests/ui/dashboard.spec.js`.

- [ ] **Step 1: Add a failing Playwright report fixture**

Mock a report containing two concrete drivers, module facts/signals/methodology/counter-evidence/limitations, and a validation diagnostic. Assert visible text for `SMA20高于SMA60`, `趋势跟随与动量确认`, `反证与限制`, `MODEL_NARRATIVE_VALIDATION_FAILED`, and `trace-test-1`.

- [ ] **Step 2: Run the focused UI test and verify RED**

Run:

```powershell
npx.cmd playwright test tests/ui/dashboard.spec.js -g "renders evidence-backed report diagnostics"
```

Expected: current renderer omits the new analysis and diagnostic fields.

- [ ] **Step 3: Implement compact renderers**

Add dedicated functions:

```javascript
function renderCoreDrivers(drivers) {
  if (!Array.isArray(drivers) || !drivers.length) return renderAgentList("核心驱动", []);
  return `<section class="report-block report-drivers"><h4>核心驱动</h4><ol>${drivers.map((driver) =>
    `<li><strong>${escapeText(driver.conclusion)}</strong><p>${escapeText(driver.rationale)}</p>` +
    `${driver.invalidation ? `<small>失效条件：${escapeText(driver.invalidation)}</small>` : ""}</li>`
  ).join("")}</ol></section>`;
}

function renderModuleAnalysis(title, section = {}) {
  const list = (label, values) => Array.isArray(values) && values.length
    ? `<div class="report-subsection"><strong>${escapeText(label)}</strong><ul>${values.map((value) =>
        `<li>${escapeText(typeof value === "string" ? value : value.conclusion || value.rationale || "")}</li>`
      ).join("")}</ul></div>` : "";
  return `<section class="report-block report-analysis"><h4>${escapeText(title)}</h4>` +
    `${renderReportFacts(section.facts)}<p>${escapeText(section.narrative || section.conclusion || "证据不足")}</p>` +
    `${list("判断依据", section.signals)}${list("方法依据", section.methodology)}` +
    `${list("反证", section.counterEvidence)}${list("数据限制", section.limitations)}</section>`;
}

function renderModelDiagnostic(diagnostic) {
  if (!diagnostic) return "";
  const issueLabels = {
    MISSING_EVIDENCE_REFERENCE: "缺少证据引用",
    UNKNOWN_EVIDENCE: "引用了未知证据",
    UNSUPPORTED_NUMBER: "包含证据包未支持的数字",
    TRADE_INSTRUCTION: "包含禁止的交易指令",
  };
  const issues = Array.isArray(diagnostic.validationIssues) && diagnostic.validationIssues.length
    ? `<dt>校验问题</dt><dd>${diagnostic.validationIssues.map((value) =>
        escapeText(issueLabels[value] || value)).join("；")}</dd>` : "";
  return `<details class="model-diagnostic"><summary>模型叙述回退 · ${escapeText(diagnostic.errorCode)}</summary>` +
    `<dl><dt>阶段</dt><dd>${escapeText(diagnostic.failureStage)}</dd>` +
    `<dt>原因</dt><dd>${escapeText(diagnostic.message)}</dd>` +
    `<dt>异常</dt><dd>${escapeText(diagnostic.exceptionType)}</dd>` +
    `<dt>模型</dt><dd>${escapeText(diagnostic.modelName || "--")}</dd>` +
    `<dt>耗时</dt><dd>${escapeText(diagnostic.durationMs == null ? "--" : `${diagnostic.durationMs} ms`)}</dd>` +
    `<dt>时间</dt><dd>${escapeText(diagnostic.occurredAt || "--")}</dd>${issues}` +
    `<dt>追踪 ID</dt><dd>${escapeText(diagnostic.traceId)}</dd></dl></details>`;
}
```

Use text labels alongside status color. Keep source links and disclaimer. Do not compute directions in JavaScript. Use a compact `<details>` diagnostic disclosure and ensure long exception names/trace IDs wrap.

- [ ] **Step 4: Run the focused UI test and verify GREEN**

Expected: new report fields and safe diagnostics render without raw JSON.

- [ ] **Step 5: Run all four viewport tests**

Expected: no horizontal overflow, fixed-header overlap, button overflow, or blank ECharts canvas at 1440x1000, 1024x768, 768x1024, and 390x844.

## Task 7: Regression, Security, and Live Diagnostic Verification

**Files:**

- Modify tests only if a regression exposes a real contract gap.
- Write generated screenshots/logs only under ignored `target/` paths.

- [ ] **Step 1: Run all offline tests**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' clean test
```

Expected: all non-`external` tests pass; no network requirement.

- [ ] **Step 2: Build the application**

```powershell
.\mvnw.cmd '-Dmaven.repo.local=.m2/repository' '-DskipTests' package
```

Expected: executable jar builds successfully.

- [ ] **Step 3: Run provider and UI verification**

```powershell
.\scripts\verify-data.cmd
npm.cmd run test:ui
```

Expected: provider contract checks and all UI tests pass.

- [ ] **Step 4: Run secret and placeholder audit**

Search tracked diffs for credential-shaped values and forbidden report placeholders. Confirm no `model unavailable`, `规则方向分`, `共同判断`, raw Authorization values, or local configuration content is introduced into report output.

- [ ] **Step 5: Inspect a real local model failure and success report**

With the configured local model, generate one report. Verify success uses `MODEL_ASSISTED`; if it fails, verify the returned diagnostic has a real stage/code/type/message/trace ID and the local sanitized stack can be correlated by trace ID. Never copy credentials or raw headers into test artifacts.

- [ ] **Step 6: Inspect generated screenshots and diffs**

Check the four viewport screenshots, `git diff --check`, `git status --short`, and every changed file. Confirm unrelated dirty worktree changes remain intact and unstaged unless explicitly included.
