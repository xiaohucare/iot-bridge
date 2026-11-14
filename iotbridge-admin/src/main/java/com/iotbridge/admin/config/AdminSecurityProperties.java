package com.iotbridge.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 管理端 REST 安全配置，前缀 {@code iotbridge.admin}。
 *
 * <p>{@code api-key} 为空表示未启用鉴权（仅建议本地开发使用）；生产环境必须配置，
 * 并在网关前置 TLS（REST 侧可通过标准 {@code server.ssl.*} 开启）。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@ConfigurationProperties(prefix = "iotbridge.admin")
public class AdminSecurityProperties {

    /**
     * 调用 REST 接口所需的 API Key；为空则关闭鉴权。
     */
    private String apiKey = "";

    /**
     * 是否启用 API Key 鉴权。
     *
     * @return 配置了非空 key 返回 {@code true}
     */
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * REST 幂等键（{@code Idempotency-Key} 请求头）记忆时长（毫秒）；{@code <=0} 表示不过期。
     */
    private long idempotencyTtlMs = 600000L;

    /**
     * 幂等键记忆的最大条数；超限先清理过期项，仍超限则拒绝记忆（下次重试可能重复投放）。
     */
    private int idempotencyMaxEntries = 50000;

    public long getIdempotencyTtlMs() {
        return idempotencyTtlMs;
    }

    public void setIdempotencyTtlMs(long idempotencyTtlMs) {
        this.idempotencyTtlMs = idempotencyTtlMs;
    }

    public int getIdempotencyMaxEntries() {
        return idempotencyMaxEntries;
    }

    public void setIdempotencyMaxEntries(int idempotencyMaxEntries) {
        this.idempotencyMaxEntries = idempotencyMaxEntries;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
