package com.iotbridge.spi.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 下行指令模型：业务平台下发给设备的统一结构。
 *
 * <p>字段全部使用包装类型；{@code params} 永不为 {@code null}，缺省为空 Map。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class DownlinkCommand {

    /**
     * 目标设备唯一标识。
     */
    private String deviceId;

    /**
     * 协议类型；为空时使用网关默认协议。
     */
    private String protocolType;

    /**
     * 指令名称。
     */
    private String command;

    /**
     * 指令参数（键值对）。
     */
    private Map<String, Object> params = new HashMap<>();

    /**
     * 请求追踪 ID，用于下行链路幂等与日志关联。
     */
    private String requestId;

    public DownlinkCommand() {
    }

    public DownlinkCommand(String deviceId, String protocolType, String command,
                           Map<String, Object> params, String requestId) {
        this.deviceId = deviceId;
        this.protocolType = protocolType;
        this.command = command;
        if (params != null) {
            this.params = params;
        }
        this.requestId = requestId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getProtocolType() {
        return protocolType;
    }

    public void setProtocolType(String protocolType) {
        this.protocolType = protocolType;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params == null ? new HashMap<>() : params;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    @Override
    public String toString() {
        return "DownlinkCommand{deviceId='" + deviceId + "', protocolType='" + protocolType
                + "', command='" + command + "', params=" + params + ", requestId='" + requestId + "'}";
    }
}
