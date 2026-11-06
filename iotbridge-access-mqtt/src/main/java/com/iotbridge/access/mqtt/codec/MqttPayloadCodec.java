package com.iotbridge.access.mqtt.codec;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.common.util.JsonUtil;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * MQTT 载荷编解码：把 PUBLISH 的载荷映射为统一上行消息，把下行指令映射为发布载荷。
 *
 * <p>MQTT 的设备标识来自 clientId（由接入层通过 {@code UplinkFrameEvent#getDeviceId()} 传入），
 * 载荷本身约定为 JSON 对象；非 JSON 载荷保留为 {@code raw} 字段，不做丢弃。
 * 下行载荷结构为 {@code {"command":..., "params":{...}, "requestId":...}}。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@Component
public class MqttPayloadCodec implements ProtocolCodec {

    private final String protocolType;

    public MqttPayloadCodec(MqttProperties properties) {
        this.protocolType = properties.getProtocolType();
    }

    @Override
    public String protocolType() {
        return protocolType;
    }

    @Override
    public UplinkMessage decode(byte[] frame) {
        String text = frame == null ? "" : new String(frame, StandardCharsets.UTF_8);
        Map<String, Object> payload = new HashMap<>();
        String deviceId = null;
        if (!text.isEmpty()) {
            try {
                payload.putAll(JsonUtil.parseMap(text));
                Object embeddedDeviceId = payload.get("deviceId");
                if (embeddedDeviceId instanceof String value && !value.isEmpty()) {
                    deviceId = value;
                }
            } catch (RuntimeException ex) {
                // 非 JSON 载荷（如厂商私有格式）：保留原文，避免吞掉设备数据
                payload.put("raw", text);
            }
        }
        return new UplinkMessage(deviceId, protocolType, payload, System.currentTimeMillis());
    }

    @Override
    public byte[] encode(DownlinkCommand command) {
        Map<String, Object> body = new HashMap<>();
        body.put("command", command.getCommand());
        body.put("params", command.getParams() == null ? Map.of() : command.getParams());
        if (command.getRequestId() != null) {
            body.put("requestId", command.getRequestId());
        }
        return JsonUtil.toJson(body).getBytes(StandardCharsets.UTF_8);
    }
}
