package com.iotbridge.admin.controller;

import com.iotbridge.admin.dto.CommandRequest;
import com.iotbridge.common.result.Result;
import com.iotbridge.mq.api.DownlinkPublisher;
import com.iotbridge.spi.model.DownlinkCommand;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 下行指令接口。
 *
 * <p>业务平台调用本接口，将指令投递至 RabbitMQ 下行队列，由网关异步消费并下发设备。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@RestController
@RequestMapping("/api/v1/devices")
public class CommandController {

    private static final Logger log = LoggerFactory.getLogger(CommandController.class);

    private final DownlinkPublisher downlinkPublisher;

    public CommandController(DownlinkPublisher downlinkPublisher) {
        this.downlinkPublisher = downlinkPublisher;
    }

    /**
     * 向指定设备下发一条指令。
     *
     * @param deviceId 目标设备标识（路径变量）
     * @param request  指令请求体
     * @return 包含 requestId 的统一响应
     */
    @PostMapping("/{deviceId}/commands")
    public Result<String> sendCommand(@PathVariable String deviceId, @Valid @RequestBody CommandRequest request) {
        String requestId = UUID.randomUUID().toString();
        DownlinkCommand command = new DownlinkCommand(deviceId, request.getProtocolType(),
                request.getCommand(), request.getParams(), requestId);
        log.info("接收下行指令 deviceId={} command={} requestId={}", deviceId, request.getCommand(), requestId);
        downlinkPublisher.publish(command);
        return Result.ok(requestId);
    }
}
