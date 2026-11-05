package com.iotbridge.admin.config;

import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.common.result.Result;
import com.iotbridge.common.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * REST 接口 API Key 鉴权过滤器。
 *
 * <p>校验 {@code X-Api-Key} 请求头（兼容 {@code Authorization: Bearer <key>}），缺失或不匹配返回 401。
 * 比较使用 {@link MessageDigest#isEqual(byte[], byte[])} 做常量时间比较，避免按字节比较带来的时序侧信道。
 * 未配置 {@code iotbridge.admin.api-key} 时直接放行（仅限本地开发）。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    private static final String API_KEY_HEADER = "X-Api-Key";

    private static final String BEARER_PREFIX = "Bearer ";

    private final AdminSecurityProperties properties;

    public ApiKeyAuthFilter(AdminSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 预检请求与鉴权关闭时不拦截
        return !properties.isEnabled() || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = extractKey(request);
        if (provided != null && constantTimeEquals(properties.getApiKey(), provided)) {
            filterChain.doFilter(request, response);
            return;
        }
        log.warn("REST 鉴权失败 uri={} remote={}", request.getRequestURI(), request.getRemoteAddr());
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                JsonUtil.toJson(Result.fail(ErrorCodeEnum.UNAUTHORIZED, "缺少或错误的 X-Api-Key")));
    }

    /**
     * 提取请求中的 API Key（优先 {@code X-Api-Key}，其次 {@code Authorization: Bearer}）。
     *
     * @param request HTTP 请求
     * @return API Key，未提供返回 {@code null}
     */
    private String extractKey(HttpServletRequest request) {
        String header = request.getHeader(API_KEY_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
