package com.iotbridge.spi.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 上行消息模型：设备上报数据经协议插件解码后的统一结构。
 *
 * <p>字段全部使用包装类型；{@code payload} 永不为 {@code null}，缺省为空 Map。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class UplinkMessage {

    /**
     * 设备唯一标识。
     */
    private String deviceId;

    /**
     * 协议类型。
     */
    private String protocolType;

    /**
     * 上报业务负载（键值对）。
     */
    private Map<String, Object> payload = new HashMap<>();

    /**
     * 上报时间戳（毫秒）。
     */
    private Long timestamp;

    public UplinkMessage() {
    }

    public UplinkMessage(String deviceId, String protocolType, Map<String, Object> payload, Long timestamp) {
        this.deviceId = deviceId;
        this.protocolType = protocolType;
        if (payload != null) {
            this.payload = payload;
        }
        this.timestamp = timestamp;
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

    public Map<String, Object> getPayload() {
        return payload;
    }

    public void setPayload(Map<String, Object> payload) {
        this.payload = payload == null ? new HashMap<>() : payload;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "UplinkMessage{deviceId='" + deviceId + "', protocolType='" + protocolType
                + "', payload=" + payload + ", timestamp=" + timestamp + '}';
    }
}
