package com.iotbridge.config;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.admin.config.AdminSecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * 生产环境安全基线校验：在 {@code prod} profile 下，缺失关键安全配置时<b>启动失败</b>，
 * 而不是仅打一条 WARN 后继续以「无鉴权」状态对外提供服务。
 *
 * <p>校验项：</p>
 * <ol>
 *   <li>{@code iotbridge.admin.api-key} 为空 → 拒绝启动（REST 下行指令不可无鉴权暴露）；</li>
 *   <li>MQTT 接入启用但 {@code iotbridge.mqtt.username} 为空 → 拒绝启动（匿名接入等同开放下行入口）。</li>
 * </ol>
 *
 * <p>非 prod profile 下只记录告警，保持本地开发与联调便利。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
@Component
public class ProductionSecurityGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecurityGuard.class);

    /**
     * 生产环境 profile 名称。
     */
    private static final String PROD_PROFILE = "prod";

    private final Environment environment;
    private final AdminSecurityProperties adminProperties;
    private final MqttProperties mqttProperties;

    public ProductionSecurityGuard(Environment environment, AdminSecurityProperties adminProperties,
                                   MqttProperties mqttProperties) {
        this.environment = environment;
        this.adminProperties = adminProperties;
        this.mqttProperties = mqttProperties;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.stream(environment.getActiveProfiles()).anyMatch(PROD_PROFILE::equals);
        boolean apiKeyMissing = !adminProperties.isEnabled();
        boolean mqttAnonymous = Boolean.TRUE.equals(mqttProperties.getEnabled()) && !mqttProperties.isAuthEnabled();

        if (!apiKeyMissing && !mqttAnonymous) {
            log.info("安全基线校验通过 apiKeyEnabled={} mqttEnabled={}", adminProperties.isEnabled(),
                    mqttProperties.getEnabled());
            return;
        }
        if (!prod) {
            if (apiKeyMissing) {
                log.warn("REST 未启用 API Key（iotbridge.admin.api-key 为空），仅限本地开发；prod profile 下将拒绝启动");
            }
            if (mqttAnonymous) {
                log.warn("MQTT 接入已启用但未配置 username，允许匿名接入，仅限本地开发；prod profile 下将拒绝启动");
            }
            return;
        }
        StringBuilder problems = new StringBuilder();
        if (apiKeyMissing) {
            problems.append("iotbridge.admin.api-key 未配置（REST 下行指令将无鉴权）; ");
        }
        if (mqttAnonymous) {
            problems.append("iotbridge.mqtt 已启用但 username 为空（允许匿名接入）; ");
        }
        throw new IllegalStateException("生产环境安全基线未满足，拒绝启动：" + problems
                + "请配置相应环境变量（IOTBRIDGE_API_KEY / IOTBRIDGE_MQTT_USERNAME 等）后重启");
    }
}
