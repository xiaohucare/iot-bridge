package com.iotbridge.admin.controller;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局 REST 异常处理，统一转换为 {@link Result}。
 *
 * <p>业务异常保留 {@link BridgeException#getCode()} 原始错误码，并按错误类别映射 HTTP 状态，
 * 不再一律返回 200 + 系统错误，调用方可直接依据状态码与 code 区分参数错误、设备离线与下游故障。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常：透传异常携带的错误码，HTTP 状态按错误码类别映射。
     *
     * @param e 业务异常
     * @return 失败响应
     */
    @ExceptionHandler(BridgeException.class)
    public ResponseEntity<Result<Void>> handleBridge(BridgeException e) {
        HttpStatus status = statusOf(e.getCode());
        if (status.is5xxServerError()) {
            log.error("业务异常 code={} message={}", e.getCode(), e.getMessage(), e);
        } else {
            log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        }
        return ResponseEntity.status(status).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 处理参数校验异常。
     *
     * @param e 校验异常
     * @return 失败响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ":" + messageOf(err))
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}", detail);
        return ResponseEntity.badRequest().body(Result.fail(ErrorCodeEnum.BAD_REQUEST, detail));
    }

    /**
     * 兜底异常处理。
     *
     * @param e 未知异常
     * @return 失败响应
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOthers(Exception e) {
        log.error("未处理异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Result.fail(ErrorCodeEnum.SYSTEM_ERROR));
    }

    /**
     * 按错误码类别映射 HTTP 状态。
     *
     * <p>{@code A0301} 未授权；{@code A04xx} 参数错误；{@code B011x} 协议/编码类为客户端请求问题；
     * {@code B0200} 设备离线为冲突；{@code B0300} 下游 MQ 不可用为 503；其余按服务端错误处理。</p>
     *
     * @param code 错误码
     * @return HTTP 状态
     */
    private HttpStatus statusOf(String code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (ErrorCodeEnum.UNAUTHORIZED.getCode().equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (code.startsWith("A04")) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ErrorCodeEnum.PROTOCOL_NOT_FOUND.getCode().equals(code)
                || ErrorCodeEnum.DECODE_ERROR.getCode().equals(code)
                || ErrorCodeEnum.ENCODE_ERROR.getCode().equals(code)) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ErrorCodeEnum.DEVICE_OFFLINE.getCode().equals(code)) {
            return HttpStatus.CONFLICT;
        }
        if (ErrorCodeEnum.DOWNLINK_PUBLISH_FAILED.getCode().equals(code)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String messageOf(FieldError err) {
        return err.getDefaultMessage() == null ? "" : err.getDefaultMessage();
    }
}
