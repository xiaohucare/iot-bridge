package com.iotbridge.access.mqtt.codec;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.common.util.JsonUtil;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * MQTT 载荷编解码：把 PUBLISH 的载荷映射为统一上行消息，把下行指令映射为发布载荷。
 *
 * <p>MQTT 的设备标识<b>一律以 CONNECT 的 clientId 为准</b>（由接入层通过
 * {@code UplinkFrameEvent#getDeviceId()} 传入）；载荷中内嵌的 {@code deviceId} 字段会被剥离，
 * 避免设备伪造他人身份或产生双重身份歧义。载荷约定为 JSON 对象；非 JSON 载荷保留为
 * {@code raw} 字段，不做丢弃。下行载荷结构为 {@code {"command":..., "params":{...}, "requestId":...}}。</p>
 *
 * <p>仅在 {@code iotbridge.mqtt.enabled=true}（缺省视为开启）时注册：MQTT 关闭时不向协议注册表
 * 暴露 {@code mqtt} 协议，保持「禁用即彻底不可达」的语义。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@Component
@ConditionalOnProperty(prefix = "iotbridge.mqtt", name = "enabled", havingValue = "true", matchIfMissing = true)
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
        if (!text.isEmpty()) {
            try {
                payload.putAll(JsonUtil.parseMap(text));
                // 身份以 clientId 为准：剥离载荷内嵌 deviceId，防止设备伪造他人身份上报
                payload.remove("deviceId");
            } catch (RuntimeException ex) {
                // 非 JSON 载荷（如厂商私有格式）：保留原文，避免吞掉设备数据
                payload.put("raw", text);
            }
        }
        return new UplinkMessage(null, protocolType, payload, System.currentTimeMillis());
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
