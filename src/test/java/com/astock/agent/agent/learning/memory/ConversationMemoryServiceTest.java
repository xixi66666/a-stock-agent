package com.astock.agent.agent.learning.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

class ConversationMemoryServiceTest {

    @Test
    void isolatesConversationsAndTrimsToConfiguredWindow() {
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(2)
                .build();
        var service = new ConversationMemoryService(memory);

        service.addUserMessage("a", "first");
        service.addAssistantMessage("a", "second");
        service.addUserMessage("a", "third");
        service.addUserMessage("b", "other");

        assertThat(service.messages("a")).extracting(message -> message.getText())
                .containsExactly("second", "third");
        assertThat(service.messages("b")).extracting(message -> message.getText())
                .containsExactly("other");
    }

    @Test
    void clearsConversationAndRejectsBlankId() {
        var memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(4)
                .build();
        var service = new ConversationMemoryService(memory);
        service.add("demo", new UserMessage("hello"));
        service.clear("demo");

        assertThat(service.messages("demo")).isEmpty();
        assertThatThrownBy(() -> service.messages(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
