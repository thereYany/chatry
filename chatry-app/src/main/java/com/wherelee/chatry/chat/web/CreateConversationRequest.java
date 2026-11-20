package com.wherelee.chatry.chat.web;

/** 创建会话请求（标题可省略，首条消息会自动生成）。 */
public record CreateConversationRequest(String title) {
}
