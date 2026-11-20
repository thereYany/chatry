package com.wherelee.chatry;

import com.wherelee.base.modules.llm.config.LlmProperties;
import com.wherelee.base.modules.llm.provider.deepseek.DeepSeekProperties;
import com.wherelee.chatry.bootstrap.ChatrySeedProperties;
import com.wherelee.chatry.chat.ChatProperties;
import com.wherelee.chatry.credits.CreditProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 对话 AI 应用启动类。
 *
 * <p>业务（会话、消息、额度展示）全在本仓库；LLM 调用治理、计量计费、身份登录
 * 走 atry 内核的 contracts，重逻辑不在垂直域复制。</p>
 */
@SpringBootApplication(scanBasePackages = {
        "com.wherelee.chatry",
        "com.wherelee.base.modules.identity",
        "com.wherelee.base.modules.llm",
        "com.wherelee.base.modules.metering"
})
@EnableConfigurationProperties({LlmProperties.class, DeepSeekProperties.class,
        ChatProperties.class, CreditProperties.class, ChatrySeedProperties.class})
@EnableScheduling
public class ChatryApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatryApplication.class, args);
    }
}
