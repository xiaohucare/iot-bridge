package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.bridge.core.reflow.DownlinkReflowService;
import com.iotbridge.mq.api.UplinkProducer;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.model.UplinkMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 上行分发器：设备帧 -> 协议解码 -> 会话绑定 -> Kafka 投递。
 *
 * <p>实现接入层 {@link UplinkFrameListener}，由 Netty 入站处理器回调。按接入端口绑定的
 * 协议类型（{@link UplinkFrameEvent#getProtocolType()}，为空则回退注册表默认协议）解码，
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
    private final DownlinkReflowService downlinkReflowService;

    public UplinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                            UplinkProducer uplinkProducer, DownlinkReflowService downlinkReflowService) {
        this.codecRegistry = codecRegistry;
        this.channelManager = channelManager;
        this.uplinkProducer = uplinkProducer;
        this.downlinkReflowService = downlinkReflowService;
    }

    @Override
    public void onUplinkFrame(UplinkFrameEvent event) {
        ProtocolCodec codec = codecRegistry.resolve(event.getProtocolType());
        UplinkMessage message = codec.decode(event.getFrame());
        // 绑定协议+设备与通道，供下行按 (protocol,deviceId) 寻址（不同协议相同设备号不互覆）
        channelManager.register(message.getProtocolType(), message.getDeviceId(), event.getChannel());
        if (log.isDebugEnabled()) {
            log.debug("上行分发 deviceId={} protocol={} payload={}", message.getDeviceId(),
                    message.getProtocolType(), message.getPayload());
        }
        uplinkProducer.send(message);
        // 设备（重新）上线，补发此前因离线暂存的下行指令；置于上行投递之后且内部隔离异常，不影响主链路
        downlinkReflowService.reflow(message.getProtocolType(), message.getDeviceId());
    }
}
