package com.astock.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;

/**
 * Spring Boot 启动入口。
 *
 * <p>启动类只负责扫描组件和配置属性；具体的市场数据、分析、Agent、Web Bean 分别在各自
 * 配置类中组装。这里显式排除 OpenAI 语音自动配置，是因为本项目只学习文本聊天模型，
 * 不应因为没有语音 API Key 而阻止应用启动。</p>
 */
@SpringBootApplication(exclude = {
        OpenAiAudioSpeechAutoConfiguration.class,
        OpenAiAudioTranscriptionAutoConfiguration.class
})
@ConfigurationPropertiesScan
public class AStockAgentApplication {

    public static void main(String[] args) {
        // 应用启动后，页面、REST API 和离线确定性分析可以在没有模型凭据时继续运行。
        SpringApplication.run(AStockAgentApplication.class, args);
    }
}
