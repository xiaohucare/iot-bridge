package com.iotbridge.bridge.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * bridge-core 装配配置。
 *
 * <p>与 access-netty（{@code NettyAccessConfig}）、mq（{@code RabbitConfig}）保持一致的约定：
 * 属性类仅标注 {@code @ConfigurationProperties}，统一在此经 {@code @EnableConfigurationProperties} 注册。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Configuration
@EnableConfigurationProperties(DownlinkBufferProperties.class)
public class BridgeCoreConfig {
}
