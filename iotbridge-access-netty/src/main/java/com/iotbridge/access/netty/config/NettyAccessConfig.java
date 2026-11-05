package com.iotbridge.access.netty.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 接入层配置装配，启用 {@link NettyProperties} 绑定。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Configuration
@EnableConfigurationProperties(NettyProperties.class)
public class NettyAccessConfig {
}
