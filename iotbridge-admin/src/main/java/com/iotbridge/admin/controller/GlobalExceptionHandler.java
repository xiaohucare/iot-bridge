package com.iotbridge.admin.controller;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.common.result.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局 REST 异常处理，统一转换为 {@link Result}。
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 处理业务异常。
     *
     * @param e 业务异常
     * @return 失败响应
     */
    @ExceptionHandler(BridgeException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBridge(BridgeException e) {
        log.warn("业务异常 code={} message={}", e.getCode(), e.getMessage());
        return Result.fail(ErrorCodeEnum.SYSTEM_ERROR, e.getMessage());
    }

    /**
     * 处理参数校验异常。
     *
     * @param e 校验异常
     * @return 失败响应
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ":" + messageOf(err))
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败 {}", detail);
        return Result.fail(ErrorCodeEnum.BAD_REQUEST, detail);
    }

    /**
     * 兜底异常处理。
     *
     * @param e 未知异常
     * @return 失败响应
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleOthers(Exception e) {
        log.error("未处理异常", e);
        return Result.fail(ErrorCodeEnum.SYSTEM_ERROR);
    }

    private String messageOf(FieldError err) {
        return err.getDefaultMessage() == null ? "" : err.getDefaultMessage();
    }
}
