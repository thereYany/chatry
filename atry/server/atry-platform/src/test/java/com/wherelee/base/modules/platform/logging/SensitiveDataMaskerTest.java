package com.wherelee.base.modules.platform.logging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataMaskerTest {

    @Test
    void masksPasswordAndTokenPairs() {
        String masked = SensitiveDataMasker.mask(
                "login username=lrs password=Passw0rd! token=abc.def.ghi Authorization: Bearer xyz.123");
        assertThat(masked).doesNotContain("Passw0rd", "abc.def.ghi", "xyz.123");
        assertThat(masked).contains("password=******", "token=******", "Authorization: ******");
    }

    @Test
    void masksJsonFieldSecrets() {
        String masked = SensitiveDataMasker.mask(
                "{\"username\":\"lrs\",\"password\":\"secret123\",\"access_token\":\"t-123\"}");
        assertThat(masked).doesNotContain("secret123", "t-123");
        assertThat(masked).contains("\"password\":\"******\"", "\"access_token\":\"******\"");
    }

    @Test
    void masksPhoneIdCardAndEmail() {
        String masked = SensitiveDataMasker.mask(
                "user 13812345678 id 110101199001011234 email zhangsan@example.com");
        assertThat(masked).doesNotContain("13812345678", "110101199001011234", "zhangsan@example.com");
        assertThat(masked).contains("1**********", "******************", "z***@example.com");
    }

    @Test
    void keepsHarmlessTextUntouched() {
        String text = "order created id=42 amount=12.00 CNY";
        assertThat(SensitiveDataMasker.mask(text)).isEqualTo(text);
    }

    @Test
    void nullSafe() {
        assertThat(SensitiveDataMasker.mask(null)).isNull();
        assertThat(SensitiveDataMasker.mask("")).isEmpty();
    }
}
