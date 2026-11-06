package com.iotbridge.access.mqtt.codec;

import com.iotbridge.access.mqtt.config.MqttProperties;
import com.iotbridge.common.util.JsonUtil;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MqttPayloadCodec} 载荷映射测试。
 *
 * @author iotbridge
 * @date 2026-11-06
 */
class MqttPayloadCodecTest {

    private MqttPayloadCodec codec() {
        return new MqttPayloadCodec(new MqttProperties());
    }

    @Test
    void decodeJsonPayloadKeepsFieldsAndEmbeddedDeviceId() {
        byte[] frame = "{\"deviceId\":\"dev-json\",\"temperature\":26.5}"
                .getBytes(StandardCharsets.UTF_8);

        UplinkMessage message = codec().decode(frame);

        assertEquals("mqtt", message.getProtocolType());
        assertEquals("dev-json", message.getDeviceId());
        assertEquals(26.5, message.getPayload().get("temperature"));
        assertNotNull(message.getTimestamp());
    }

    @Test
    void decodeNonJsonPayloadKeepsRawTextWithoutLosingData() {
        byte[] frame = "temp=26.5;hum=60".getBytes(StandardCharsets.UTF_8);

        UplinkMessage message = codec().decode(frame);

        assertEquals("mqtt", message.getProtocolType());
        assertEquals("temp=26.5;hum=60", message.getPayload().get("raw"));
    }

    @Test
    void decodeEmptyPayloadYieldsEmptyMap() {
        UplinkMessage message = codec().decode(new byte[0]);

        assertTrue(message.getPayload().isEmpty());
        assertEquals("mqtt", message.getProtocolType());
    }

    @Test
    void encodeProducesCommandJsonPayload() {
        Map<String, Object> params = new HashMap<>();
        params.put("value", 30);
        DownlinkCommand command = new DownlinkCommand("dev1", "mqtt", "setInterval", params, "req-1");

        String payload = new String(codec().encode(command), StandardCharsets.UTF_8);
        Map<String, Object> body = JsonUtil.parseMap(payload);

        assertEquals("setInterval", body.get("command"));
        assertEquals("req-1", body.get("requestId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> encodedParams = (Map<String, Object>) body.get("params");
        assertEquals(30, encodedParams.get("value"));
    }
}
