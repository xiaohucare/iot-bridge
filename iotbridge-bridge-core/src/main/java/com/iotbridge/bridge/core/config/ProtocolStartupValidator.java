package com.iotbridge.bridge.core.config;

import com.iotbridge.access.netty.config.NettyProperties;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 启动期协议配置校验：把「配置写错协议名」从运行时静默丢帧提前到启动失败。
 *
 * <p>校验 {@code iotbridge.protocol.default} 与各监听端口的 {@code protocol-type} 均已在注册表中注册。
 * 若缺失，原实现会在每个上行帧解码时报错并被入站处理器捕获，表现为「设备能连、数据全丢」且监控无感知。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
@Component
public class ProtocolStartupValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProtocolStartupValidator.class);

    private final ProtocolCodecRegistry codecRegistry;
    private final NettyProperties nettyProperties;

    public ProtocolStartupValidator(ProtocolCodecRegistry codecRegistry, NettyProperties nettyProperties) {
        this.codecRegistry = codecRegistry;
        this.nettyProperties = nettyProperties;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> supported = codecRegistry.supportedTypes();
        String defaultType = codecRegistry.getDefaultProtocolType();
        if (!supported.contains(defaultType)) {
            throw new IllegalStateException("默认协议未注册：iotbridge.protocol.default=" + defaultType
                    + "，已注册协议=" + supported);
        }
        List<NettyProperties.Listener> listeners = nettyProperties.getListeners();
        if (listeners != null) {
            for (NettyProperties.Listener listener : listeners) {
                String protocolType = listener.getProtocolType();
                if (protocolType != null && !protocolType.isEmpty() && !supported.contains(protocolType)) {
                    throw new IllegalStateException("监听绑定的协议未注册：name=" + listener.getName()
                            + " protocol-type=" + protocolType + "，已注册协议=" + supported);
                }
            }
        }
        log.info("协议启动校验通过，默认协议={} 已注册={}", defaultType, supported);
    }
}
