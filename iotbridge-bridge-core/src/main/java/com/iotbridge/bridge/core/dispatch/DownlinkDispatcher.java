package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.bridge.core.reflow.DownlinkReflowService;
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
 * （为空回退默认），编码后写入目标设备通道；设备离线时：开启缓冲则按设备暂存<b>原始指令</b>待重投（并双检
 * 若设备此刻已在线则立即补发），否则记录告警丢弃；均不抛异常以免影响消费确认。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkDispatcher implements DownlinkDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(DownlinkDispatcher.class);

    private final ProtocolCodecRegistry codecRegistry;
    private final ChannelManager channelManager;
    private final DownlinkBuffer downlinkBuffer;
    private final DownlinkReflowService downlinkReflowService;

    public DownlinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                             DownlinkBuffer downlinkBuffer, DownlinkReflowService downlinkReflowService) {
        this.codecRegistry = codecRegistry;
        this.channelManager = channelManager;
        this.downlinkBuffer = downlinkBuffer;
        this.downlinkReflowService = downlinkReflowService;
    }

    @Override
    public void handle(DownlinkCommand command) {
        ProtocolCodec codec = codecRegistry.resolve(command.getProtocolType());
        byte[] frame = codec.encode(command);
        boolean delivered = channelManager.sendToDevice(command.getDeviceId(), frame);
        if (delivered) {
            if (log.isDebugEnabled()) {
                log.debug("下行已送达 deviceId={} command={}", command.getDeviceId(), command.getCommand());
            }
            return;
        }
        if (!downlinkBuffer.isEnabled()) {
            log.warn("下行未送达，设备离线 deviceId={} command={} requestId={}", command.getDeviceId(),
                    command.getCommand(), command.getRequestId());
            return;
        }
        // 离线暂存原始指令（补发时再编码，规避 timeCalib 等含时间戳指令失效）
        downlinkBuffer.offer(command.getDeviceId(), new PendingFrame(command, System.currentTimeMillis()));
        log.warn("下行未送达，设备离线已暂存待重投 deviceId={} command={} requestId={}",
                command.getDeviceId(), command.getCommand(), command.getRequestId());
        // 双检：暂存与“设备刚上线、上一轮补发已排空”交错时，若此刻已在线则立即补发，关闭滞留窗口
        if (channelManager.isOnline(command.getDeviceId())) {
            downlinkReflowService.reflow(command.getDeviceId());
        }
    }
}
