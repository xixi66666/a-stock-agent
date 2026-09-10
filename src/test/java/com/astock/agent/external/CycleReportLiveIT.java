package com.astock.agent.external;

import com.astock.agent.agent.cycle.*;
import com.astock.agent.agent.model.NamedChatClientRegistry;
import com.astock.agent.analysis.StockResearchSnapshot;
import com.astock.agent.marketdata.model.SecurityId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

/** 真实模型与本地原书烟测，故意不给市场数据；结果不可作为股票研究结论。 */
@Tag("external")
@EnabledIfSystemProperty(named = "cycle.live.enabled", matches = "true")
@SpringBootTest(properties = "spring.ai.model.chat=none")
class CycleReportLiveIT {
    @Autowired NamedChatClientRegistry registry;
    @Autowired CycleProperties properties;
    @Autowired ObjectMapper mapper;

    @Test @Timeout(300)
    void executesOriginalSkillWithExplicitlyUnavailableMarketEvidence() throws Exception {
        var model = registry.forRole("cycle-report").orElseThrow();
        var library = new CycleLibrary(Path.of(properties.skillPath()), Path.of(properties.bookPath()), properties.python());
        var session = new CycleSession(library, StockResearchSnapshot.empty(SecurityId.parse("600519")),
                stage -> System.out.println("Cycle smoke stage: " + stage));
        var report = new CycleAgent().run(model.client(), session);
        assertThat(report.dimensions()).hasSize(7).allMatch(d -> d.facts().isEmpty());
        assertThat(session.chapters().size()).isGreaterThanOrEqualTo(2);
        assertThat(session.evidence()).hasSize(12);
        Files.createDirectories(Path.of("target/data-verification"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/data-verification/cycle-skill-smoke.json").toFile(), Map.of(
                "purpose", "真实模型和原书执行烟测；市场证据全部不可用，不是股票投资报告", "model", model.modelName(),
                "report", report, "chapters", session.chapters(), "trace", session.trace()));
    }
}
