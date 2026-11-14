package com.iotbridge.admin.controller;

import com.iotbridge.admin.config.AdminSecurityProperties;
import com.iotbridge.admin.support.CommandIdempotencyStore;
import com.iotbridge.mq.api.DownlinkPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * {@link CommandController} 的 REST 契约测试：正常投放、幂等键去重、幂等键格式校验。
 *
 * @author iotbridge
 * @date 2026-11-14
 */
class CommandControllerTest {

    private static final String BODY = "{\"command\":\"restart\"}";

    private DownlinkPublisher publisher;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        publisher = mock(DownlinkPublisher.class);
        CommandIdempotencyStore store = new CommandIdempotencyStore(new AdminSecurityProperties());
        mockMvc = standaloneSetup(new CommandController(publisher, store))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void sendsCommandAndReturnsRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/devices/dev1/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isNotEmpty());

        verify(publisher, times(1)).publish(any());
    }

    @Test
    void sameIdempotencyKeyIsPublishedOnlyOnce() throws Exception {
        String first = mockMvc.perform(post("/api/v1/devices/dev1/commands")
                        .header("Idempotency-Key", "order-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/v1/devices/dev1/commands")
                        .header("Idempotency-Key", "order-12345")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 重复提交返回同一 requestId，且不会再次投放（避免设备重复执行）
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        verify(publisher, times(1)).publish(any());
    }

    @Test
    void invalidIdempotencyKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/devices/dev1/commands")
                        .header("Idempotency-Key", "bad key!")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0400"));

        verify(publisher, times(0)).publish(any());
    }
}
