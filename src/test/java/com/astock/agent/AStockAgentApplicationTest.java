package com.astock.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiAudioSpeechModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.ai.model.chat=none",
        "spring.ai.model.audio.speech=openai",
        "spring.ai.model.audio.transcription=openai",
        "spring.ai.openai.api-key=",
        "spring.autoconfigure.exclude="
})
class AStockAgentApplicationTest {

    @Test
    void contextLoadsWithoutAudioModelCredentials(@Autowired ApplicationContext context) {
        ListableBeanFactory beanFactory = context;
        assertThat(beanFactory.getBeansOfType(OpenAiAudioSpeechModel.class)).isEmpty();
        assertThat(beanFactory.getBeansOfType(OpenAiAudioTranscriptionModel.class)).isEmpty();
    }
}
