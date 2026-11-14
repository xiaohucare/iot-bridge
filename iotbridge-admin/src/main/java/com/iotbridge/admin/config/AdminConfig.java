package com.iotbridge.admin.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 管理端 REST 配置：注册 API Key 鉴权过滤器。
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@Configuration
@EnableConfigurationProperties(AdminSecurityProperties.class)
public class AdminConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminConfig.class);

    /**
     * 注册 API Key 鉴权过滤器，拦截全部 {@code /api/**} 请求。
     *
     * @param properties 鉴权配置
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<ApiKeyAuthFilter> apiKeyAuthFilter(AdminSecurityProperties properties) {
        if (!properties.isEnabled()) {
            log.warn("未配置 iotbridge.admin.api-key，REST 接口不校验鉴权，请勿用于生产环境");
        }
        FilterRegistrationBean<ApiKeyAuthFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyAuthFilter(properties));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        registration.setName("apiKeyAuthFilter");
        return registration;
    }

    /**
     * 注册链路标识过滤器：为每个请求生成 {@code traceId} 并写入 MDC，供日志模板输出。
     *
     * <p>顺序早于鉴权过滤器，保证「未通过鉴权」的请求日志也带链路标识。</p>
     *
     * @return 过滤器注册
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("traceIdFilter");
        return registration;
    }
}
