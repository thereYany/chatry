package com.wherelee.chatry.credits;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CreditServiceTest {

    @Test
    void convertsCostToMicrosWithRounding() {
        assertThat(CreditService.micros(null)).isZero();
        assertThat(CreditService.micros(BigDecimal.ZERO)).isZero();
        assertThat(CreditService.micros(new BigDecimal("0.000083"))).isEqualTo(83L);
        assertThat(CreditService.micros(new BigDecimal("0.0000544"))).isEqualTo(54L);
        assertThat(CreditService.micros(new BigDecimal("0.0000545"))).isEqualTo(55L);
        assertThat(CreditService.micros(new BigDecimal("1.234567"))).isEqualTo(1_234_567L);
    }
}
