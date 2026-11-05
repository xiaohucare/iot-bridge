package com.iotbridge.admin.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.HashMap;
import java.util.Map;

/**
 * 下行指令请求体。
 *
 * <p>{@code deviceId} 由路径变量提供，此处承载协议、指令名与参数。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class CommandRequest {

    /**
     * 协议类型，可为空则回退网关默认协议。
     */
    private String protocolType;

    /**
     * 指令名称。
     */
    @NotBlank(message = "command 不能为空")
    private String command;

    /**
     * 指令参数。
     */
    private Map<String, Object> params = new HashMap<>();

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

    @Override
    public String toString() {
        return "CommandRequest{protocolType='" + protocolType + "', command='" + command
                + "', params=" + params + '}';
    }
}
