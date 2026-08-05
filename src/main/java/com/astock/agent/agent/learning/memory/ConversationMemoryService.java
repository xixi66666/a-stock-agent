package com.astock.agent.agent.learning.memory;

import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * 对话消息的受控存储门面。
 *
 * <p>它统一校验 conversationId 和消息文本，并把记忆实现隐藏在 Spring AI ChatMemory 后面。
 * 记忆用于保持会话上下文，不是用来存放秘密配置或无限制历史数据的数据库。</p>
 */
public final class ConversationMemoryService {

    private final ChatMemory memory;

    public ConversationMemoryService(ChatMemory memory) {
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    public void add(String conversationId, Message message) {
        memory.add(requireId(conversationId), Objects.requireNonNull(message, "message"));
    }

    public void addUserMessage(String conversationId, String text) {
        add(conversationId, new UserMessage(requireText(text)));
    }

    public void addAssistantMessage(String conversationId, String text) {
        add(conversationId, new AssistantMessage(requireText(text)));
    }

    public List<Message> messages(String conversationId) {
        return List.copyOf(memory.get(requireId(conversationId)));
    }

    public void clear(String conversationId) {
        memory.clear(requireId(conversationId));
    }

    public ChatMemory chatMemory() {
        return memory;
    }

    private static String requireId(String value) {
        if (value == null || value.isBlank() || value.length() > 100) {
            throw new IllegalArgumentException("Conversation ID must contain 1 to 100 characters");
        }
        return value.trim();
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Message must not be blank");
        }
        return value;
    }
}
