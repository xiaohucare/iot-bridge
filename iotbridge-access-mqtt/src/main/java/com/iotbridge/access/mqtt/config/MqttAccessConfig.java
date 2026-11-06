package com.iotbridge.access.mqtt.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT 接入层配置装配，启用 {@link MqttProperties} 绑定。
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@Configuration
@EnableConfigurationProperties(MqttProperties.class)
public class MqttAccessConfig {
}
