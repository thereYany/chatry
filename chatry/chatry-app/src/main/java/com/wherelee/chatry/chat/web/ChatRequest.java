package com.wherelee.chatry.chat.web;

import jakarta.validation.constraints.NotBlank;

/** 发送消息请求。 */
public record ChatRequest(@NotBlank String content, String model) {
}
