package com.iotbridge.bridge.core.dispatch;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.bridge.core.metrics.BridgeMetrics;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.bridge.core.reflow.DownlinkReflowService;
import com.iotbridge.spi.codec.ProtocolCodec;
import com.iotbridge.spi.handler.DownlinkDispatchHandler;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.session.DeliveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 下行分发器：RabbitMQ 指令 -> 协议编码 -> 写入设备通道。
 *
 * <p>实现 {@link DownlinkDispatchHandler}，由 mq 下行消费者回调。按指令携带的协议类型选择编解码器
 * （为空回退默认），编码后写入目标设备通道。</p>
 *
 * <p><b>三态投递语义</b>（见 {@link DeliveryResult}）：只有确认「未送达」才允许按设备暂存待重投；
 * 「状态未知」（写等待超时）<b>不进入暂存</b>——此时数据可能已写出，重投会导致设备重复执行同一条指令，
 * 仅计数告警。设备离线时：开启缓冲则暂存<b>原始指令</b>（并双检若此刻已在线则立即补发），否则记录告警丢弃。
 * 均不抛异常以免影响消费确认。</p>
 *
 * <p><b>幂等去重</b>：同一 {@code (protocolType, deviceId, requestId)} 若已确认送达过，后续重复指令
 * （MQ 重投、REST 重试）直接跳过并计数，避免设备重复执行。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkDispatcher implements DownlinkDispatchHandler {

    private static final Logger log = LoggerFactory.getLogger(DownlinkDispatcher.class);

    /**
     * MDC 键：指令请求号，便于把 REST/Rabbit 侧与设备写入侧的日志串起来。
     */
    private static final String MDC_REQUEST_ID = "requestId";

    /**
     * MDC 键：设备标识。
     */
    private static final String MDC_DEVICE_ID = "deviceId";

    /**
     * MDC 键：协议类型。
     */
    private static final String MDC_PROTOCOL_TYPE = "protocolType";

    private final ProtocolCodecRegistry codecRegistry;
    private final ChannelManager channelManager;
    private final DownlinkBuffer downlinkBuffer;
    private final DownlinkReflowService downlinkReflowService;
    private final DownlinkIdempotencyCache idempotencyCache;
    private final BridgeMetrics metrics;

    /**
     * Spring 注入用构造。
     *
     * @param codecRegistry         协议注册表
     * @param channelManager        会话管理
     * @param downlinkBuffer        离线暂存缓冲
     * @param downlinkReflowService 补发服务
     * @param idempotencyCache      幂等去重
     * @param metrics               指标
     */
    @Autowired
    public DownlinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                              DownlinkBuffer downlinkBuffer, DownlinkReflowService downlinkReflowService,
                              DownlinkIdempotencyCache idempotencyCache, BridgeMetrics metrics) {
        this.codecRegistry = codecRegistry;
        this.channelManager = channelManager;
        this.downlinkBuffer = downlinkBuffer;
        this.downlinkReflowService = downlinkReflowService;
        this.idempotencyCache = idempotencyCache;
        this.metrics = metrics;
    }

    /**
     * 兼容构造（幂等缓存取默认配置、指标为空操作），供既有调用方与测试使用。
     *
     * @param codecRegistry         协议注册表
     * @param channelManager        会话管理
     * @param downlinkBuffer        离线暂存缓冲
     * @param downlinkReflowService 补发服务
     */
    public DownlinkDispatcher(ProtocolCodecRegistry codecRegistry, ChannelManager channelManager,
                              DownlinkBuffer downlinkBuffer, DownlinkReflowService downlinkReflowService) {
        this(codecRegistry, channelManager, downlinkBuffer, downlinkReflowService,
                new DownlinkIdempotencyCache(new DownlinkBufferProperties()), BridgeMetrics.NOOP);
    }

    @Override
    public void handle(DownlinkCommand command) {
        MDC.put(MDC_REQUEST_ID, command.getRequestId() == null ? "" : command.getRequestId());
        MDC.put(MDC_DEVICE_ID, command.getDeviceId() == null ? "" : command.getDeviceId());
        try {
            dispatch(command);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_DEVICE_ID);
            MDC.remove(MDC_PROTOCOL_TYPE);
        }
    }

    /**
     * 执行一次下行投递（MDC 已设置）。
     *
     * @param command 下行指令
     */
    private void dispatch(DownlinkCommand command) {
        ProtocolCodec codec;
        String protocolType;
        byte[] frame;
        try {
            codec = codecRegistry.resolve(command.getProtocolType());
            // 会话/缓冲以与设备上线一致的具象协议为键：指令未指定协议时回退默认协议，与上行注册时的具象协议对齐
            protocolType = codec.protocolType();
            MDC.put(MDC_PROTOCOL_TYPE, protocolType == null ? "" : protocolType);
            frame = codec.encode(command);
        } catch (RuntimeException ex) {
            // 协议解析/编码失败多为确定性错误（非法协议、无法识别指令），重投无益且会引发毒消息循环；
            // 记录后丢弃，绝不外抛，以免回传消费者导致消费确认失败与无限重投（与本类既有约定一致）
            metrics.downlink(BridgeMetrics.DOWNLINK_ERROR);
            log.error("下行指令处理失败，已丢弃 deviceId={} command={} requestId={} protocolType={}",
                    command.getDeviceId(), command.getCommand(), command.getRequestId(), command.getProtocolType(), ex);
            return;
        }

        if (idempotencyCache.isDuplicate(protocolType, command.getDeviceId(), command.getRequestId())) {
            // 已确认送达过的同一请求号再次出现：跳过，避免设备重复执行
            metrics.downlink(BridgeMetrics.DOWNLINK_DUPLICATE);
            log.warn("重复下行指令已跳过（该请求号已确认送达过）protocol={} deviceId={} command={} requestId={}",
                    protocolType, command.getDeviceId(), command.getCommand(), command.getRequestId());
            return;
        }

        DeliveryResult result = channelManager.sendToDevice(protocolType, command.getDeviceId(), frame);
        if (result == DeliveryResult.DELIVERED) {
            idempotencyCache.markDelivered(protocolType, command.getDeviceId(), command.getRequestId());
            metrics.downlink(BridgeMetrics.DOWNLINK_DELIVERED);
            if (log.isDebugEnabled()) {
                log.debug("下行已送达 protocol={} deviceId={} command={}", protocolType, command.getDeviceId(),
                        command.getCommand());
            }
            return;
        }
        if (result == DeliveryResult.UNKNOWN) {
            // 写等待超时：数据可能已写出。重投会造成设备重复执行，因此不暂存、不重投，只计数留痕
            metrics.downlink(BridgeMetrics.DOWNLINK_UNKNOWN);
            log.warn("下行送达状态未知（写等待超时），未进入暂存以避免重复执行 protocol={} deviceId={} command={} "
                    + "requestId={}", protocolType, command.getDeviceId(), command.getCommand(), command.getRequestId());
            return;
        }
        if (!downlinkBuffer.isEnabled()) {
            metrics.downlink(BridgeMetrics.DOWNLINK_DROPPED);
            log.warn("下行未送达，设备离线 protocol={} deviceId={} command={} requestId={}", protocolType,
                    command.getDeviceId(), command.getCommand(), command.getRequestId());
            return;
        }
        // 离线暂存原始指令（补发时再编码，规避 timeCalib 等含时间戳指令失效）
        downlinkBuffer.offer(protocolType, command.getDeviceId(),
                new PendingFrame(command, System.currentTimeMillis()));
        metrics.downlink(BridgeMetrics.DOWNLINK_BUFFERED);
        log.warn("下行未送达，设备离线已暂存待重投 protocol={} deviceId={} command={} requestId={}", protocolType,
                command.getDeviceId(), command.getCommand(), command.getRequestId());
        // 双检：暂存与“设备刚上线、上一轮补发已排空”交错时，若此刻已在线则立即补发，关闭滞留窗口
        if (channelManager.isOnline(protocolType, command.getDeviceId())) {
            downlinkReflowService.reflow(protocolType, command.getDeviceId());
        }
    }
}
