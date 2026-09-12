package com.astock.agent.agent;

import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.analysis.institutional.DeterministicAssessment;
import com.astock.agent.analysis.institutional.ResearchJudgementEngine;
import com.astock.agent.agent.report.InstitutionalReportComposer;
import com.astock.agent.agent.report.InstitutionalResearchReport;
import com.astock.agent.agent.report.ReportEvidencePackage;
import com.astock.agent.agent.report.ReportNarrativeDraft;
import com.astock.agent.agent.report.ReportValidator;
import com.astock.agent.agent.report.ModelDiagnostic;
import com.astock.agent.agent.report.ModelFailureClassifier;
import com.astock.agent.agent.report.NarrativeGenerator;
import com.astock.agent.agent.report.SpringAiNarrativeGenerator;
import com.astock.agent.marketdata.model.DataSection;
import com.astock.agent.marketdata.model.Provenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 机构研究报告的主编排器。
 *
 * <p>这个类体现了本项目最重要的 Agent 设计：模型不是从零“想出”一份报告，
 * 而是先由 Java 获取快照、计算确定性方向、构造有界证据包，再让模型只补充语言叙述。
 * 因此即使模型没有配置、超时或返回不合规内容，系统也可以返回确定性回退报告。</p>
 *
 * <p>主流程是：快照 -> 固定规则判断 -> 证据包 -> 模型草稿 -> 校验/修复 -> 报告。
 * 页面“生成研究报告”走 {@link #analyzeInstitutional(String)}；旧版结构化 Agent 走
 * {@link #analyze(String)}。</p>
 */
public final class StockAnalysisAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger(StockAnalysisAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String SYSTEM_PROMPT = """
            你是一个用于学习研究的 A 股分析 Agent。只能依据工具返回的规范化数据和来源元数据回答。
            必须保留相互冲突的信号、不可用分区、数据时间和来源；不得编造实时价格、来源或缺失值。
            不提供直接买入卖出指令。结尾必须写：仅供学习研究，不构成投资建议。
            """;
    private final ChatClient chatClient;
    private final NarrativeGenerator narrativeGenerator;
    private final AgentStatusService statusService;
    private final StockAgentTools tools;
    private final ResearchJudgementEngine judgementEngine;
    private final InstitutionalReportComposer reportComposer;
    private final ReportValidator reportValidator;
    private final ModelFailureClassifier failureClassifier;

    public StockAnalysisAgent(ChatClient chatClient, AgentStatusService statusService, StockAgentTools tools) {
        this(chatClient, chatClient == null ? null : new SpringAiNarrativeGenerator(chatClient, "configured-chat-model"),
                statusService, tools, new ResearchJudgementEngine(), new InstitutionalReportComposer(),
                new ReportValidator(), new ModelFailureClassifier());
    }

    public StockAnalysisAgent(ChatClient chatClient, String modelName,
            AgentStatusService statusService, StockAgentTools tools) {
        this(chatClient, chatClient == null ? null : new SpringAiNarrativeGenerator(chatClient, modelName),
                statusService, tools, new ResearchJudgementEngine(), new InstitutionalReportComposer(),
                new ReportValidator(), new ModelFailureClassifier());
    }

    public StockAnalysisAgent(NarrativeGenerator narrativeGenerator, AgentStatusService statusService, StockAgentTools tools) {
        this(null, narrativeGenerator, statusService, tools, new ResearchJudgementEngine(),
                new InstitutionalReportComposer(), new ReportValidator(), new ModelFailureClassifier());
    }

    public StockAnalysisAgent(ChatClient chatClient, AgentStatusService statusService, StockAgentTools tools,
            ResearchJudgementEngine judgementEngine, InstitutionalReportComposer reportComposer,
            ReportValidator reportValidator) {
        this(chatClient, chatClient == null ? null : new SpringAiNarrativeGenerator(chatClient, "configured-chat-model"),
                statusService, tools, judgementEngine, reportComposer, reportValidator, new ModelFailureClassifier());
    }

    private StockAnalysisAgent(ChatClient chatClient, NarrativeGenerator narrativeGenerator,
            AgentStatusService statusService, StockAgentTools tools, ResearchJudgementEngine judgementEngine,
            InstitutionalReportComposer reportComposer, ReportValidator reportValidator,
            ModelFailureClassifier failureClassifier) {
        this.chatClient = chatClient;
        this.narrativeGenerator = narrativeGenerator;
        this.statusService = statusService;
        this.tools = tools;
        this.judgementEngine = judgementEngine;
        this.reportComposer = reportComposer;
        this.reportValidator = reportValidator;
        this.failureClassifier = failureClassifier;
    }

    /**
     * 根据证券代码加载一次研究快照并生成机构研究报告。
     *
     * <p>代码解析和数据聚合由受限工具完成，避免 Controller 直接接触 Provider。返回结果
     * 已经包含确定性判断、来源、缺失项和模型生成模式。</p>
     */
    public InstitutionalResearchReport analyzeInstitutional(String code) {
        return analyzeInstitutional(tools.getResearchSnapshot(code));
    }

    /** 固定报告流程：快照 -> 确定性判断 -> 有界证据 -> 受约束叙述 -> 校验/回退。 */
    /**
     * 对一个已经加载的快照执行固定研究报告流程。
     *
     * <ol>
     *   <li>固定规则计算方向、模块分数、冲突和缺失项。</li>
     *   <li>构造模型可见的有界证据包。</li>
     *   <li>模型不可用时直接返回确定性报告。</li>
     *   <li>模型可用时生成结构化叙述并校验。</li>
     *   <li>阻断问题最多修复一次；仍失败则回退并保留诊断。</li>
     * </ol>
     *
     * <p>模型只能写叙述字段，不能改变 Java 已确定的方向、证据状态和事实数据。</p>
     */
    public InstitutionalResearchReport analyzeInstitutional(StockResearchSnapshot snapshot) {
        DeterministicAssessment assessment = judgementEngine.assess(snapshot);
        ReportEvidencePackage evidence = reportComposer.compose(snapshot, assessment);
        InstitutionalResearchReport fallback = reportComposer.fallback(snapshot, assessment, null);
        // 模型配置是可选能力。没有模型时不抛错，保证行情和确定性研究仍然可用。
        if (narrativeGenerator == null || statusService == null || statusService.status() != AgentAvailability.READY) {
            return fallback;
        }
        long started = System.nanoTime();
        String traceId = "agent-" + java.util.UUID.randomUUID();
        try {
            // 第一次调用只接收有界证据包，不把任意 URL、文件或 Shell 能力暴露给模型。
            ReportNarrativeDraft draft = narrativeGenerator.generate(evidence);
            ReportValidator.ValidationResult validation = reportValidator.validate(draft, evidence);
            if (!validation.blockingIssues().isEmpty()) {
                try {
                    // 修复请求仍然使用同一份证据包，并明确传入校验问题，避免模型自由发挥。
                    ReportNarrativeDraft repaired = narrativeGenerator.repair(evidence, draft, validation.blockingIssues());
                    ReportValidator.ValidationResult repairedValidation = reportValidator.validate(repaired, evidence);
                    draft = repaired;
                    validation = repairedValidation;
                } catch (Exception repairFailure) {
                    LOGGER.warn("模型叙述修复失败 traceId={} issues={} type={}", traceId,
                            validation.blockingIssues(), repairFailure.getClass().getSimpleName());
                }
            }
            ModelDiagnostic diagnostic = null;
            if (!validation.blockingIssues().isEmpty()) {
                diagnostic = failureClassifier.validation(validation.blockingIssues(), narrativeGenerator.modelName(),
                        elapsedMillis(started), traceId);
            } else if (validation.hasWarnings()) {
                diagnostic = failureClassifier.validationWarning(validation.warnings(), narrativeGenerator.modelName(),
                        elapsedMillis(started), traceId);
            }
            // assembleValidated 会根据校验结果选择 MODEL_ASSISTED、PARTIAL 或 WITH_WARNINGS。
            return reportComposer.assembleValidated(snapshot, assessment, draft, validation,
                    narrativeGenerator.modelName(), diagnostic);
        } catch (Exception exception) {
            // 失败信息会经过分类和脱敏；报告本身回退到确定性内容，避免把异常堆栈返回给浏览器。
            ModelDiagnostic diagnostic = failureClassifier.classify(exception, narrativeGenerator.modelName(),
                    elapsedMillis(started), traceId);
            LOGGER.error("模型叙述失败 traceId={} stage={} code={} type={} stack={}", traceId,
                    diagnostic.failureStage(), diagnostic.errorCode(), diagnostic.exceptionType(), safeStack(exception));
            return reportComposer.fallbackWithDiagnostic(snapshot, assessment, diagnostic);
        }
    }

    private static long elapsedMillis(long started) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private String safeStack(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (!result.isEmpty()) result.append(" <- ");
            result.append(current.getClass().getName()).append(": ")
                    .append(failureClassifier.sanitize(current.getMessage()));
            for (StackTraceElement frame : current.getStackTrace()) result.append("\n at ").append(frame);
            current = current.getCause();
        }
        return result.toString();
    }

    /**
     * 旧版结构化 Agent 入口，用于学习工具调用和向后兼容。
     *
     * <p>它与机构研究报告流程不同：模型可以通过 {@code .tools(tools)} 使用受限工具，
     * 没有模型时返回 evidence-only 报告。新研究报告页面使用上面的固定流程。</p>
     */
    public AgentResearchReport analyze(String code) {
        return analyze(tools.getResearchSnapshot(code));
    }

    public AgentResearchReport analyze(StockResearchSnapshot snapshot) {
        AgentResearchReport fallback = evidenceOnlyReport(snapshot);
        if (chatClient == null || statusService.status() != AgentAvailability.READY) {
            return fallback;
        }
        try {
            String evidence = MAPPER.writeValueAsString(snapshot);
            AgentResearchReport report = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("请基于以下规范化快照生成结构化研究报告，并引用 sources：\n" + evidence)
                    .tools(tools)
                    .call()
                    .entity(AgentResearchReport.class);
            if (report == null) {
                throw new IllegalStateException("Model returned no structured report");
            }
            return report;
        } catch (Exception exception) {
            throw new AgentExecutionException("Structured agent analysis failed", exception);
        }
    }

    private static AgentResearchReport evidenceOnlyReport(StockResearchSnapshot snapshot) {
        Map<String, DataSection<?>> sections = sections(snapshot);
        List<String> missing = sections.entrySet().stream()
                .filter(entry -> entry.getValue().payload().isEmpty())
                .map(Map.Entry::getKey)
                .toList();
        List<SourceCitation> citations = new ArrayList<>();
        sections.forEach((name, section) -> section.provenance().ifPresent(source ->
                citations.add(citation(name, source, section.status().name()))));
        List<String> technical = snapshot.technical().payload()
                .map(value -> value.cards().stream().limit(8)
                        .map(card -> card.name() + "：" + card.trigger()).toList())
                .orElse(List.of());
        String summary = snapshot.quote().payload()
                .map(quote -> quote.name() + "（" + quote.security().code() + "）最新数据价 " + quote.price())
                .orElse("核心行情不可用，无法生成事实摘要");
        return new AgentResearchReport(
                summary,
                "模型未启用时仅展示确定性指标证据，不做主观市场判断。",
                technical,
                List.of("资本与基本面分区状态已保留在快照中。"),
                snapshot.crossSourceConsistent() ? List.of() : List.of("跨来源价格或日期未通过一致性检查。"),
                List.of("请结合公告与数据状态核对事件风险。"),
                missing,
                citations,
                "这是基于已获取数据的学习型汇总；缺失分区不会被替代或推断。",
                "仅供学习研究，不构成投资建议");
    }

    private static Map<String, DataSection<?>> sections(StockResearchSnapshot snapshot) {
        Map<String, DataSection<?>> sections = new LinkedHashMap<>();
        sections.put("quote", snapshot.quote());
        sections.put("bars", snapshot.bars());
        sections.put("valuation", snapshot.valuation());
        sections.put("technical", snapshot.technical());
        sections.put("sectors", snapshot.sectors());
        sections.put("fundFlow", snapshot.fundFlow());
        sections.put("capital", snapshot.capital());
        sections.put("fundamentals", snapshot.fundamentals());
        sections.put("research", snapshot.research());
        sections.put("news", snapshot.news());
        sections.put("announcements", snapshot.announcements());
        return sections;
    }

    private static SourceCitation citation(String section, Provenance source, String status) {
        return new SourceCitation(
                section, source.provider(), source.sourceUrl().toString(), source.fetchedAt(), status);
    }
}
