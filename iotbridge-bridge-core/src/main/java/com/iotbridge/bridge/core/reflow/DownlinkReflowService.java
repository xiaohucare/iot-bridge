package com.iotbridge.bridge.core.reflow;

import com.iotbridge.access.netty.session.ChannelManager;
import com.iotbridge.bridge.core.buffer.DownlinkBuffer;
import com.iotbridge.bridge.core.buffer.PendingFrame;
import com.iotbridge.bridge.core.registry.ProtocolCodecRegistry;
import com.iotbridge.spi.codec.ProtocolCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 下行补发服务：把某设备暂存的待重投指令按序重新编码并写入通道。
 *
 * <p>按 {@code deviceId} 串行化（每设备独立锁），保证「排空 -> 逐条发送」原子执行，消除补发与
 * 实时下发、以及多次补发交错的乱序与滞留。补发时以<b>当前</b>指令重新编码（而非缓存字节帧），规避
 * {@code timeCalib} 等含时间戳指令在离线期间失效；发送失败立即中断并把剩余帧按原序退回缓冲，等待
 * 下一次上线补发，杜绝 drain 出的帧静默丢失。异常在内部隔离，不影响调用方主链路。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkReflowService {

    private static final Logger log = LoggerFactory.getLogger(DownlinkReflowService.class);

    private final DownlinkBuffer downlinkBuffer;
    private final ChannelManager channelManager;
    private final ProtocolCodecRegistry codecRegistry;

    /**
     * deviceId -> 补发串行锁。条目数与「在线或曾有暂存」的设备量同阶，锁对象开销可忽略，不做回收。
     */
    private final ConcurrentMap<String, Object> reflowLocks = new ConcurrentHashMap<>();

    public DownlinkReflowService(DownlinkBuffer downlinkBuffer, ChannelManager channelManager,
                                 ProtocolCodecRegistry codecRegistry) {
        this.downlinkBuffer = downlinkBuffer;
        this.channelManager = channelManager;
        this.codecRegistry = codecRegistry;
    }

    /**
     * 排空并补发指定设备暂存的下行指令。缓冲未开启或设备标识为空时直接返回。
     *
     * <p>整体在 per-device 锁内执行，保证同设备补发互斥；异常被捕获隔离，不外泄到上行投递等主链路。</p>
     *
     * @param deviceId 设备标识
     */
    public void reflow(String deviceId) {
        if (!downlinkBuffer.isEnabled() || deviceId == null) {
            return;
        }
        Object lock = reflowLocks.computeIfAbsent(deviceId, key -> new Object());
        synchronized (lock) {
            try {
                drainAndSend(deviceId);
            } catch (RuntimeException ex) {
                log.error("下行补发异常 deviceId={}", deviceId, ex);
            }
        }
    }

    /**
     * 取出全部暂存指令并按序重新编码发送；发送失败时把剩余帧（含当前帧）退回缓冲，编码失败则跳过该帧。
     *
     * @param deviceId 设备标识
     */
    private void drainAndSend(String deviceId) {
        List<PendingFrame> pending = downlinkBuffer.drain(deviceId);
        if (pending.isEmpty()) {
            return;
        }
        log.info("设备上线补发暂存下行 deviceId={} 数量={}", deviceId, pending.size());
        for (int i = 0; i < pending.size(); i++) {
            PendingFrame frame = pending.get(i);
            byte[] encoded;
            try {
                ProtocolCodec codec = codecRegistry.resolve(frame.getProtocolType());
                encoded = codec.encode(frame.getDownlinkCommand());
            } catch (RuntimeException ex) {
                // 编码失败为确定性错误，重排无意义且可能死循环，记录后跳过该帧
                log.error("补发编码失败，丢弃该帧 deviceId={} command={} requestId={}", deviceId,
                        frame.getCommand(), frame.getRequestId(), ex);
                continue;
            }
            if (!channelManager.sendToDevice(deviceId, encoded)) {
                // 通道中途失效：把尚未发送成功的剩余帧按原序退回缓冲，等待下次上线
                reEnqueue(deviceId, pending, i);
                log.warn("补发中断，剩余 {} 帧已退回缓冲 deviceId={}", pending.size() - i, deviceId);
                return;
            }
        }
    }

    /**
     * 将 {@code fromIndex}（含）之后的剩余帧按原序退回缓冲。仅退回未成功发送的帧，避免重复下发。
     *
     * @param deviceId  设备标识
     * @param pending   本轮排空出的帧列表
     * @param fromIndex 首个未成功发送帧的下标
     */
    private void reEnqueue(String deviceId, List<PendingFrame> pending, int fromIndex) {
        for (int j = fromIndex; j < pending.size(); j++) {
            downlinkBuffer.offer(deviceId, pending.get(j));
        }
    }
}
