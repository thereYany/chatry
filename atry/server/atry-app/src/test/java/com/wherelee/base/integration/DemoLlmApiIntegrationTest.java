package com.wherelee.base.integration;

import com.wherelee.base.modules.contracts.llm.LlmErrorCategory;
import com.wherelee.base.modules.contracts.llm.LlmException;
import com.wherelee.base.modules.contracts.llm.LlmOperations;
import com.wherelee.base.modules.contracts.llm.LlmRequest;
import com.wherelee.base.modules.contracts.llm.ChatMessage;
import com.wherelee.base.modules.llm.outbox.LlmOutboxDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LLM 全链路集成测试：HTTP → LlmService → mock provider → 预算预扣/结算 → 调用记录/发件箱 → 计量入账。
 *
 * <p>覆盖业务不变式：一次调用同时产出「调用记录 + 完成事件 + 结算后的余额」；
 * 请求 requestId 幂等；失败调用释放预扣且余额不变；SSE 流式以 done 事件汇总用量与余额；
 * LLM 发件箱投递后计量事件幂等入账（重复投递不重复计费）。</p>
 */
class DemoLlmApiIntegrationTest extends IntegrationTestSupport {

    @Autowired
    private LlmOperations llmOperations;
    @Autowired
    private LlmOutboxDispatcher llmOutboxDispatcher;

    private long tenant;
    private String token;

    @BeforeEach
    void prepareSubject() {
        tenant = newTenant();
        seedRole(tenant, "DEMO_USER");
        long userId = seedUser(tenant, unique("demo").toLowerCase(), "Pass1234", "DEMO_USER");
        token = tokenFor(tenant, userId, "demo_user", "DEMO_USER");
    }

    private static BigDecimal decimal(tools.jackson.databind.JsonNode node) {
        return new BigDecimal(node.toString());
    }

    @Test
    void 同步对话_返回用量成本与结算后余额_并落调用记录与发件箱() throws Exception {
        String body = mockMvc.perform(post("/api/demo/llm/chat")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "你好，请介绍一下自己", "model", "mock-small",
                                "maxTokens", 32))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("mock"))
                .andExpect(jsonPath("$.content").isNotEmpty())
                .andExpect(jsonPath("$.usage.totalTokens").isNumber())
                .andReturn().getResponse().getContentAsString();
        var node = objectMapper.readTree(body);
        String requestId = node.get("requestId").asString();
        long totalTokens = node.get("usage").get("totalTokens").asLong();
        BigDecimal cost = decimal(node.get("cost").get("amount"));
        BigDecimal balance = decimal(node.get("balance").get("balance"));

        // 价格 0.000001/token：成本 = 总 token * 1e-6；演示初始余额 10
        assertThat(cost).isEqualByComparingTo(
                BigDecimal.valueOf(totalTokens).multiply(new BigDecimal("0.000001")));
        assertThat(balance).isEqualByComparingTo(new BigDecimal("10.000000").subtract(cost));

        // 调用记录（脱敏元数据）与发件箱事件同时落库
        assertThat(queryOne("SELECT status FROM t_llm_call_record WHERE request_id = ?",
                String.class, requestId)).isEqualTo("SUCCESS");
        assertThat(queryOne("SELECT COUNT(*) FROM t_llm_outbox WHERE aggregate_id = ?",
                Integer.class, requestId)).isEqualTo(1);
        // 预算预扣已结算
        assertThat(queryOne("SELECT status FROM t_metering_reservation WHERE reservation_id = ?",
                String.class, "llm:" + requestId)).isEqualTo("SETTLED");

        // 发件箱投递 → 计量事件幂等入账（重复投递不重复计价）
        llmOutboxDispatcher.dispatch();
        llmOutboxDispatcher.dispatch();
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_usage_event WHERE event_id = ?",
                "llm-usage:" + requestId + ":in")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM t_metering_usage_event WHERE event_id = ?",
                "llm-usage:" + requestId + ":out")).isEqualTo(1);

        // 用量查询接口可见本次调用事件
        mockMvc.perform(get("/api/demo/metering/usage?limit=20")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void 连续两次调用余额递减() throws Exception {
        BigDecimal first = callChat("第一次");
        BigDecimal second = callChat("第二次");
        assertThat(second).isLessThan(first);
        assertThat(first).isLessThan(new BigDecimal("10.000000"));
    }

    private BigDecimal callChat(String message) throws Exception {
        String body = mockMvc.perform(post("/api/demo/llm/chat")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", message, "model", "mock-small", "maxTokens", 32))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return decimal(objectMapper.readTree(body).get("balance").get("balance"));
    }

    @Test
    void SSE流式_输出delta并以done事件汇总用量与余额() throws Exception {
        MvcResult async = mockMvc.perform(post("/api/demo/llm/stream")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "流式你好", "model", "mock-small", "maxTokens", 32))))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult completed = mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();
        String sse = completed.getResponse().getContentAsString();

        assertThat(sse).contains("event:delta");
        assertThat(sse).contains("event:done");
        // 流式应输出 mock 的多个 chunk
        assertThat(sse.split("event:delta", -1)).hasSizeGreaterThanOrEqualTo(4);

        String doneJson = sse.substring(sse.lastIndexOf("event:done"));
        String dataLine = doneJson.lines().filter(line -> line.startsWith("data:"))
                .findFirst().orElseThrow().substring("data:".length()).trim();
        var done = objectMapper.readTree(dataLine);
        assertThat(done.get("provider").asString()).isEqualTo("mock/stream");
        assertThat(done.get("usage").get("totalTokens").asLong()).isPositive();
        BigDecimal balance = decimal(done.get("balance").get("balance"));
        assertThat(balance).isLessThan(new BigDecimal("10.000000"));

        // 流式调用记录标记 stream=true 且结算完成
        String requestId = done.get("requestId").asString();
        assertThat(queryOne("SELECT stream FROM t_llm_call_record WHERE request_id = ?",
                Boolean.class, requestId)).isTrue();
        assertThat(queryOne("SELECT status FROM t_llm_call_record WHERE request_id = ?",
                String.class, requestId)).isEqualTo("SUCCESS");
    }

    @Test
    void 失败调用映射502并释放预扣_余额不变() throws Exception {
        mockMvc.perform(post("/api/demo/llm/chat")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "失败调用", "model", "mock-fail", "maxTokens", 32))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.category").value("UPSTREAM_5XX"))
                .andExpect(jsonPath("$.retryable").value(true));

        // 演示账户首次访问会被 seed，失败调用不得扣费
        var balance = asSystem(() -> jdbc.queryForList(
                "SELECT CONCAT(balance, '|', frozen) FROM t_metering_account WHERE tenant_id = ?",
                String.class, tenant));
        assertThat(balance).hasSize(1);
        assertThat(balance.get(0)).isEqualTo("10.000000|0.000000");
    }

    @Test
    void requestId幂等_重复调用返回INVALID_REQUEST() {
        String requestId = newRequestId();
        LlmRequest request = new LlmRequest(requestId, tenant, 0, 1, "mock-small",
                List.of(new ChatMessage("user", "hi")), 16, false,
                Instant.now().plusSeconds(10), null);
        llmOperations.invoke(request);

        assertThatThrownBy(() -> llmOperations.invoke(request))
                .isInstanceOf(LlmException.class)
                .satisfies(e -> assertThat(((LlmException) e).error().category())
                        .isEqualTo(LlmErrorCategory.INVALID_REQUEST));
        assertThat(countRows("SELECT COUNT(*) FROM t_llm_call_record WHERE request_id = ?", requestId))
                .isEqualTo(1);
    }

    @Test
    void 未登录访问演示接口401_余额接口返回演示初始余额() throws Exception {
        mockMvc.perform(post("/api/demo/llm/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("message", "x"))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/demo/metering/balance")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(10.000000))
                .andExpect(jsonPath("$.currency").value("CNY"));
    }
}
