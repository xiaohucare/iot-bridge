package com.iotbridge.admin.controller;

import com.iotbridge.admin.dto.CommandRequest;
import com.iotbridge.admin.support.CommandIdempotencyStore;
import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.common.result.Result;
import com.iotbridge.mq.api.DownlinkPublisher;
import com.iotbridge.spi.model.DownlinkCommand;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 下行指令接口。
 *
 * <p>业务平台调用本接口，将指令投递至 RabbitMQ 下行队列，由网关异步消费并下发设备。</p>
 *
 * <p><b>幂等</b>：可携带 {@code Idempotency-Key} 请求头；相同 {@code (deviceId, Idempotency-Key)}
 * 在记忆窗口内重复提交时，直接返回首次分配的 {@code requestId} 且不再重复投放，避免超时重试导致设备重复执行。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@RestController
@RequestMapping("/api/v1/devices")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    /**
     * 幂等键合法字符与长度约束（避免异常长/含控制字符的键污染缓存）。
     */
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{8,128}");

    private final DownlinkPublisher downlinkPublisher;
    private final CommandIdempotencyStore idempotencyStore;

    public CommandController(DownlinkPublisher downlinkPublisher, CommandIdempotencyStore idempotencyStore) {
        this.downlinkPublisher = downlinkPublisher;
        this.idempotencyStore = idempotencyStore;
    }

    /**
     * 向指定设备下发一条指令。
     *
     * @param deviceId       目标设备标识（路径变量）
     * @param idempotencyKey 可选幂等键，建议由调用方为「同一业务动作」生成并复用（8–128 位字母数字与 {@code _.:-}）
     * @param request        指令请求体
     * @return 包含 requestId 的统一响应；重复幂等键返回首次的 requestId
     */
    @PostMapping("/{deviceId}/commands")
    public Result<String> sendCommand(@PathVariable String deviceId,
                                      @RequestHeader(name = "Idempotency-Key", required = false)
                                      String idempotencyKey,
                                      @Valid @RequestBody CommandRequest request) {
        if (idempotencyKey != null && !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BridgeException(ErrorCodeEnum.BAD_REQUEST,
                    "Idempotency-Key 需为 8-128 位字母、数字或 _ . : -");
        }
        String existing = idempotencyStore.find(deviceId, idempotencyKey);
        if (existing != null) {
            log.info("幂等命中，重复指令未再次投放 deviceId={} requestId={}", deviceId, existing);
            return Result.ok(existing);
        }
        String requestId = UUID.randomUUID().toString();
        DownlinkCommand command = new DownlinkCommand(deviceId, request.getProtocolType(),
                request.getCommand(), request.getParams(), requestId);
        log.info("接收下行指令 deviceId={} command={} requestId={}", deviceId, request.getCommand(), requestId);
        // 投放失败会抛异常，异常路径不记忆幂等键，客户端可安全重试
        downlinkPublisher.publish(command);
        idempotencyStore.remember(deviceId, idempotencyKey, requestId);
        return Result.ok(requestId);
    }
}

