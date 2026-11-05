package com.iotbridge.bridge.core.registry;

import com.iotbridge.common.exception.BridgeException;
import com.iotbridge.common.exception.ErrorCodeEnum;
import com.iotbridge.spi.codec.ProtocolCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议编解码插件注册表。
 *
 * <p>启动时通过 {@link ServiceLoader} 发现 classpath 上以 {@code META-INF/services} 注册的插件，
 * 并合并 Spring 容器内的 {@link ProtocolCodec} Bean，按 {@code protocolType} 建索引。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class ProtocolCodecRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCodecRegistry.class);

    private final Map<String, ProtocolCodec> codecs = new ConcurrentHashMap<>();

    /**
     * 默认协议类型，用于未显式指定协议的上行帧与下行指令。
     */
    private final String defaultProtocolType;

    /**
     * 构造并初始化注册表。
     *
     * @param springCodecs        Spring 容器内的编解码 Bean（可为空）
     * @param defaultProtocolType 默认协议类型
     */
    public ProtocolCodecRegistry(List<ProtocolCodec> springCodecs,
                                 @Value("${iotbridge.protocol.default:demo-text}") String defaultProtocolType) {
        this.defaultProtocolType = defaultProtocolType;
        ServiceLoader.load(ProtocolCodec.class).forEach(codec -> codecs.put(codec.protocolType(), codec));
        if (springCodecs != null) {
            springCodecs.forEach(codec -> codecs.put(codec.protocolType(), codec));
        }
        log.info("协议插件已加载，共 {} 个：{}，默认协议={}", codecs.size(), codecs.keySet(), defaultProtocolType);
    }

    /**
     * 按协议类型获取编解码器。
     *
     * @param protocolType 协议类型
     * @return 编解码器
     */
    public ProtocolCodec get(String protocolType) {
        ProtocolCodec codec = codecs.get(protocolType);
        if (codec == null) {
            throw new BridgeException(ErrorCodeEnum.PROTOCOL_NOT_FOUND, "未找到协议插件: " + protocolType);
        }
        return codec;
    }

    /**
     * 获取默认协议编解码器。
     *
     * @return 默认编解码器
     */
    public ProtocolCodec getDefault() {
        return get(defaultProtocolType);
    }

    /**
     * 依据可能为空的协议类型解析编解码器：为空时回退默认协议。
     *
     * @param protocolType 协议类型，可为 {@code null} 或空串
     * @return 编解码器
     */
    public ProtocolCodec resolve(String protocolType) {
        if (protocolType == null || protocolType.isEmpty()) {
            return getDefault();
        }
        return get(protocolType);
    }

    /**
     * 返回已注册的协议类型集合。
     *
     * @return 协议类型集合
     */
    public Set<String> supportedTypes() {
        return Set.copyOf(codecs.keySet());
    }

    public String getDefaultProtocolType() {
        return defaultProtocolType;
    }
}
