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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
