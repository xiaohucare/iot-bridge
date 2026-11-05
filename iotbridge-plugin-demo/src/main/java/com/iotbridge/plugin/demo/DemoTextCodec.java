package com.iotbridge.plugin.demo;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 示例协议编解码器：行文本协议。
 *
 * <p>上行格式：{@code deviceId,key=value,key=value}；下行格式：{@code deviceId:command:key=value&key2=value2}。
 * 该实现通过 {@code META-INF/services} 使用 {@link java.util.ServiceLoader} 注册，供网关自动发现。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class DemoTextCodec implements ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(DemoTextCodec.class);

    /**
     * 协议标识。
     */
    public static final String PROTOCOL_TYPE = "demo-text";

    /**
     * 上行字段分隔符。
     */
    private static final String UPLINK_SEPARATOR = ",";

    /**
     * 下行字段分隔符。
     */
    private static final String DOWNLINK_SEPARATOR = ":";

    /**
     * 下行多参数连接符。
     */
    private static final String PARAM_JOINER = "&";

    /**
     * 键值对分隔符。
     */
    private static final String KV_SEPARATOR = "=";

    /**
     * 行结束符。
     */
    private static final String LINE_END = "\n";

    @Override
    public String protocolType() {
        return PROTOCOL_TYPE;
    }

    @Override
    public UplinkMessage decode(byte[] frame) {
        if (frame == null || frame.length == 0) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR, "上行帧为空");
        }
        String text = new String(frame, StandardCharsets.UTF_8).trim();
        String[] tokens = text.split(UPLINK_SEPARATOR);
        if (tokens.length < 1 || tokens[0].isEmpty()) {
            throw new BridgeException(ErrorCodeEnum.DECODE_ERROR, "上行帧缺少 deviceId: " + text);
        }
        String deviceId = tokens[0].trim();
        Map<String, Object> payload = new HashMap<>();
        for (int i = 1; i < tokens.length; i++) {
            String kv = tokens[i].trim();
            if (kv.isEmpty()) {
                continue;
            }
            int idx = kv.indexOf(KV_SEPARATOR);
            if (idx > 0) {
                String key = kv.substring(0, idx).trim();
                String value = kv.substring(idx + 1).trim();
                payload.put(key, value);
            } else {
                // 无值键，置为空串，保留键存在性
                payload.put(kv, "");
            }
        }
        UplinkMessage message = new UplinkMessage(deviceId, PROTOCOL_TYPE, payload,
                System.currentTimeMillis());
        if (log.isDebugEnabled()) {
            log.debug("decode deviceId={} payload={}", deviceId, payload);
        }
        return message;
    }

    @Override
    public byte[] encode(DownlinkCommand command) {
        if (command == null || command.getDeviceId() == null || command.getCommand() == null) {
            throw new BridgeException(ErrorCodeEnum.ENCODE_ERROR, "下行指令缺少 deviceId 或 command");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(command.getDeviceId())
                .append(DOWNLINK_SEPARATOR)
                .append(command.getCommand());
        Map<String, Object> params = command.getParams();
        if (params != null && !params.isEmpty()) {
            sb.append(DOWNLINK_SEPARATOR);
            boolean first = true;
            for (Map.Entry<String, Object> entry : params.entrySet()) {
                if (!first) {
                    sb.append(PARAM_JOINER);
                }
                sb.append(entry.getKey()).append(KV_SEPARATOR)
                        .append(entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                first = false;
            }
        }
        sb.append(LINE_END);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
