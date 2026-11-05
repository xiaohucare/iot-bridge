package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.mq.api.UplinkProducer;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.UplinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上行分发器：设备帧 -> 协议解码 -> 会话绑定 -> Kafka 投递。
 *
 * <p>实现接入层 {@link UplinkFrameListener}，由 Netty 入站处理器回调。使用默认协议解码，
 * 解码得到 deviceId 后登记 {@code deviceId<->Channel}，随后投递上行消息。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class UplinkDispatcher implements UplinkFrameListener {

    private static final Logger log = LoggerFactory.getLogger(UplinkDispatcher.class);

    private final ProtocolCodecRegistry codecRegistry;
    private final ChannelManager channelManager;
    private final UplinkProducer uplinkProducer;

    public UplinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                            UplinkProducer uplinkProducer) {
        this.codecRegistry = codecRegistry;
        this.channelManager = channelManager;
        this.uplinkProducer = uplinkProducer;
    }

    @Override
    public void onUplinkFrame(UplinkFrameEvent event) {
        ProtocolCodec codec = codecRegistry.getDefault();
        UplinkMessage message = codec.decode(event.getFrame());
        // 绑定设备与通道，供下行按 deviceId 寻址
        channelManager.register(message.getDeviceId(), event.getChannel());
        if (log.isDebugEnabled()) {
            log.debug("上行分发 deviceId={} protocol={} payload={}", message.getDeviceId(),
                    message.getProtocolType(), message.getPayload());
        }
        uplinkProducer.send(message);
    }
}
