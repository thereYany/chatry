package com.wherelee.base.modules.identity.auth;

import com.wherelee.base.modules.platform.audit.AuditRetentionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthEventRetentionJobTest {

    @Test
    void deletesUntilBelowBatchSize() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AuditRetentionProperties properties = new AuditRetentionProperties(true, 180, 3, 10, "0 20 3 * * *");
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(3, 3, 2);
        AuthEventRetentionJob job = new AuthEventRetentionJob(jdbc, properties);

        assertThat(job.deleteExpired(properties)).isEqualTo(8);
        verify(jdbc, times(3)).update(anyString(), any(Object[].class));
    }
}
