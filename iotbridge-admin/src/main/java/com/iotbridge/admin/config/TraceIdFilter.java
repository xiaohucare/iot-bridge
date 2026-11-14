package com.iotbridge.admin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 请求链路标识过滤器。
 *
 * <p>为每个 REST 请求生成/透传 {@code traceId} 写入 MDC，日志模板可通过 {@code %X{traceId}}
 * 输出；同时回写 {@code X-Trace-Id} 响应头，便于业务侧与网关日志对账。</p>
 *
 * <p>优先使用调用方传入的 {@code X-Trace-Id}（便于跨系统串联），但对长度与字符集做校验，
 * 避免把任意内容注入日志。</p>
 *
 * @author iotbridge
 * @date 2026-11-14
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /**
     * 链路标识请求头 / 响应头名称。
     */
    public static final String HEADER = "X-Trace-Id";

    /**
     * MDC 键。
     */
    public static final String MDC_KEY = "traceId";

    /**
     * 允许透传的链路标识格式：8–64 位字母、数字与 {@code -_.}。
     */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && VALID_TRACE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
