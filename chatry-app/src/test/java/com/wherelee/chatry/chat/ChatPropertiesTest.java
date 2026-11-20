package com.wherelee.chatry.chat;

import com.wherelee.chatry.credits.CreditProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPropertiesTest {

    @Test
    void fillsDefaultsWhenUnset() {
        ChatProperties properties = new ChatProperties(null, null, null, null, null, null, null, null);

        assertThat(properties.defaultModel()).isEqualTo("mock-small");
        assertThat(properties.defaultMaxTokens()).isEqualTo(512);
        assertThat(properties.contextMessages()).isEqualTo(20);
        assertThat(properties.contextChars()).isEqualTo(12_000);
        assertThat(properties.streamTimeoutMs()).isEqualTo(300_000L);
        assertThat(properties.titleChars()).isEqualTo(20);
    }

    @Test
    void creditDefaultsAndScale() {
        CreditProperties properties = new CreditProperties(null, null, null, null);

        assertThat(properties.initialGrant()).isEqualByComparingTo(new BigDecimal("5.000000"));
        assertThat(properties.initialGrant().scale()).isEqualTo(6);
        assertThat(properties.currency()).isEqualTo("CNY");
        assertThat(properties.estimateInputTokens()).isEqualTo(400);
        assertThat(properties.estimateOutputTokens()).isEqualTo(600);
    }
}
