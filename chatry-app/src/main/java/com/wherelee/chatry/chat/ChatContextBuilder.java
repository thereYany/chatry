package com.wherelee.chatry.chat;

import com.wherelee.base.modules.contracts.llm.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;

/**
 * 上下文拼装：把持久化的历史消息压成一次 LLM 请求的 messages。
 *
 * <p>从最新往旧取，先按条数、再按字符预算截断；保留最近一轮用户消息是硬要求，
 * 否则模型会答非所问。只保留 user/assistant 两种角色（系统提示由内核/应用层决定，
 * 不属于用户历史）。</p>
 */
@Component
public class ChatContextBuilder {

    private final ChatProperties properties;

    public ChatContextBuilder(ChatProperties properties) {
        this.properties = properties;
    }

    public List<ChatMessage> build(List<ChatMessageEntity> history) {
        LinkedList<ChatMessage> context = new LinkedList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessageEntity message = history.get(i);
            if (!ChatMessageRepository.ROLE_USER.equals(message.role())
                    && !ChatMessageRepository.ROLE_ASSISTANT.equals(message.role())) {
                continue;
            }
            String content = message.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            boolean lastTurn = context.isEmpty();
            if (!lastTurn && chars + content.length() > properties.contextChars()) {
                break;
            }
            context.addFirst(new ChatMessage(message.role(), content));
            chars += content.length();
            if (context.size() >= properties.contextMessages()) {
                break;
            }
        }
        return List.copyOf(context);
    }
}
