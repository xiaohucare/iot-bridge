package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.handler.DownlinkDispatchHandler;
import com.iotbridge.spi.model.DownlinkCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 下行分发器：RabbitMQ 指令 -> 协议编码 -> 写入设备通道。
 *
 * <p>实现 {@link DownlinkDispatchHandler}，由 mq 下行消费者回调。按指令携带的协议类型选择编解码器
 * （为空回退默认），编码后写入目标设备通道；设备离线时记录告警，不抛异常以免影响消费确认。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkDispatcher implements DownlinkDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(DownlinkDispatcher.class);

    private final ProtocolCodecRegistry codecRegistry;
    private final ChannelManager channelManager;

    public DownlinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager) {
        this.codecRegistry = codecRegistry;
        this.channelManager = channelManager;
    }

    @Override
    public void handle(DownlinkCommand command) {
        ProtocolCodec codec = codecRegistry.resolve(command.getProtocolType());
        byte[] frame = codec.encode(command);
        boolean delivered = channelManager.sendToDevice(command.getDeviceId(), frame);
        if (!delivered) {
            log.warn("下行未送达，设备离线 deviceId={} command={} requestId={}", command.getDeviceId(),
                    command.getCommand(), command.getRequestId());
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("下行已送达 deviceId={} command={}", command.getDeviceId(), command.getCommand());
        }
    }
}
