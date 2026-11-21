package com.wherelee.base.modules.platform.logging;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wherelee.base.modules.platform.config.SecurityProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter(
            new LoggingProperties(true, false, 200, 1000),
            new SecurityProperties(List.of(), false, false, 8, 40, 10));

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.ACCESS_LOGGER);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.ACCESS_LOGGER);
        logger.detachAppender(appender);
        org.slf4j.MDC.clear();
    }

    @Test
    void logsApiRequestWithUserAndTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/credits");
        request.setRemoteAddr("10.0.0.8");
        request.addHeader("User-Agent", "JUnit");
        request.setAttribute("userId", 7L);
        request.setAttribute("tenantId", 0L);
        org.slf4j.MDC.put("traceId", "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, (req, res) -> {
        });

        assertThat(appender.list).hasSize(1);
        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).contains("method=GET", "path=/api/chat/credits", "status=200",
                "userId=7", "tenantId=0", "traceId=trace-123", "ua=\"JUnit\"");
    }

    @Test
    void skipsHealthActuatorAndStaticAssets() throws Exception {
        List<String> paths = List.of("/api/health", "/actuator/health", "/assets/app.js",
                "/favicon.ico", "/app.css");
        for (String path : paths) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            });
        }
        assertThat(appender.list).isEmpty();
    }

    @Test
    void masksSensitiveUserAgent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("User-Agent", "token=supersecret 13812345678");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
        });

        String message = appender.list.get(0).getFormattedMessage();
        assertThat(message).doesNotContain("supersecret", "13812345678");
        assertThat(message).contains("******");
    }
}
