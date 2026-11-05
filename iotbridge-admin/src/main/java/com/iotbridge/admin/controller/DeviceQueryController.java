package com.iotbridge.admin.controller;

import com.iotbridge.common.result.Result;
import com.iotbridge.spi.session.DeviceSessionQuery;
import com.iotbridge.spi.session.DeviceSessionView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备在线状态查询接口。
 *
 * <p>供运维/业务平台排查“设备是否在线、当前有哪些设备在线”，数据来自网关会话快照。
 * 依赖 spi 层 {@link DeviceSessionQuery} 而非接入层，保持模块解耦。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@RestController
@RequestMapping("/api/v1/devices")
public class DeviceQueryController {

    private final DeviceSessionQuery deviceSessionQuery;

    public DeviceQueryController(DeviceSessionQuery deviceSessionQuery) {
        this.deviceSessionQuery = deviceSessionQuery;
    }

    /**
     * 查询当前所有在线设备。
     *
     * @param protocol 协议类型（可选查询参数）；传入则仅列出该协议下的在线会话，省略则列出全部协议
     * @return 在线设备会话列表（无在线设备时为空列表）
     */
    @GetMapping("/online")
    public Result<List<DeviceSessionView>> listOnline(
            @RequestParam(name = "protocol", required = false) String protocol) {
        return Result.ok(deviceSessionQuery.onlineDevices(protocol));
    }

    /**
     * 查询指定设备是否在线。
     *
     * @param deviceId 设备标识（路径变量）
     * @param protocol 协议类型（可选查询参数）；传入则按协议精确判定，省略则跨协议任一在线即算在线
     * @return 在线返回 {@code true}
     */
    @GetMapping("/{deviceId}/online")
    public Result<Boolean> isOnline(@PathVariable String deviceId,
                                    @RequestParam(name = "protocol", required = false) String protocol) {
        boolean online = (protocol == null || protocol.isEmpty())
                ? deviceSessionQuery.isOnline(deviceId)
                : deviceSessionQuery.isOnline(protocol, deviceId);
        return Result.ok(online);
    }
}
