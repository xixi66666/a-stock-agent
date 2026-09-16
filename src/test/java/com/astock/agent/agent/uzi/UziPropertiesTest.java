package com.astock.agent.agent.uzi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class UziPropertiesTest {

    @Test
    void defaultsMatchTheSetupScriptRuntimeAndLocalReportDirectory() {
        var properties = new UziProperties(null, null, null, null, 0);

        assertThat(properties.rootPath()).isEqualTo("tools/uzi/UZI-Skill");
        assertThat(properties.python()).isEqualTo("tools/uzi/.venv/Scripts/python.exe");
        assertThat(properties.reportPath()).isEqualTo("data/uzi-reports");
        assertThat(properties.taskTimeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.maxConcurrentTasks()).isEqualTo(1);
    }
}
