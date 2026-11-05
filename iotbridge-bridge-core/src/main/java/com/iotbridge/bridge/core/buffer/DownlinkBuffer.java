package com.iotbridge.bridge.core.buffer;

import com.iotbridge.bridge.core.config.DownlinkBufferProperties;
import com.iotbridge.spi.session.SessionKey;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * 按设备的下行待发缓冲区。
 *
 * <p>设备离线时把<b>原始下行指令</b>按 {@code deviceId} 入队（单设备有界，超限丢弃最旧；设备维度总量
 * 受 {@code maxPendingDevices} 约束，超限淘汰最陈旧设备队列，防止外部 deviceId 无界增长耗尽内存），
 * 设备重新上线时由 {@code DownlinkReflowService} 取出并按序重新编码补发（超过 TTL 的指令丢弃）。
 * 仅存在于当前网关实例内存，多实例不共享；进程重启即清空。</p>
 *
 * <p>队列采用 {@link ConcurrentLinkedDeque}：入队用 {@code compute} 保证「判容量+丢最旧+入队」原子，
 * 计数读取（{@link #pendingSize}/{@link #totalPending}）在锁外进行也无线程安全问题，仅为弱一致视图。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class DownlinkBuffer {

    private static final Logger log = LoggerFactory.getLogger(DownlinkBuffer.class);

    /**
     * 单设备队列与设备数下限，非法配置兜底为 1。
     */
    private static final int MIN_LIMIT = 1;

    /**
     * SessionKey（协议+设备号）-> 待发指令队列（先进先出，线程安全）。
     */
    private final ConcurrentMap<SessionKey, ConcurrentLinkedDeque<PendingFrame>> queues = new ConcurrentHashMap<>();

    private final DownlinkBufferProperties properties;

    public DownlinkBuffer(DownlinkBufferProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时打印生效配置并对非法边界告警，避免运行期静默兜底造成误解。
     */
    @PostConstruct
    public void logEffectiveConfig() {
        if (properties.getMaxPendingPerDevice() < MIN_LIMIT) {
            log.warn("iotbridge.downlink.max-pending-per-device={} 非法，已按最小值 {} 兜底",
                    properties.getMaxPendingPerDevice(), MIN_LIMIT);
        }
        if (properties.getMaxPendingDevices() < MIN_LIMIT) {
            log.warn("iotbridge.downlink.max-pending-devices={} 非法，已按最小值 {} 兜底",
                    properties.getMaxPendingDevices(), MIN_LIMIT);
        }
        log.info("下行待发缓冲配置：enabled={}, maxPendingPerDevice={}, maxPendingDevices={}, pendingTtlMs={}",
                properties.isBufferEnabled(), properties.getMaxPendingPerDevice(),
                properties.getMaxPendingDevices(), properties.getPendingTtlMs());
    }

    /**
     * 是否启用离线暂存重投。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return properties.isBufferEnabled();
    }

    /**
     * 暂存一条待发下行指令。未启用、参数非法时忽略。
     *
     * @param protocolType 协议类型（与设备上线时一致的具象协议）
     * @param deviceId     设备标识
     * @param frame        待发指令快照
     */
    public void offer(String protocolType, String deviceId, PendingFrame frame) {
        if (!isEnabled() || deviceId == null || frame == null) {
            return;
        }
        SessionKey key = SessionKey.of(protocolType, deviceId);
        int perDeviceMax = Math.max(MIN_LIMIT, properties.getMaxPendingPerDevice());
        // 新设备入队前先做全局设备数约束，避免在 compute 持锁内操作同一 map 造成递归更新
        if (!queues.containsKey(key)) {
            enforceDeviceCapacity();
        }
        // compute 持 bin 锁期间只做纯内存操作，被丢弃的指令带出来在锁外记录，避免日志 IO 放大锁竞争
        final PendingFrame[] droppedHolder = new PendingFrame[1];
        queues.compute(key, (k, deque) -> {
            ConcurrentLinkedDeque<PendingFrame> target = deque == null ? new ConcurrentLinkedDeque<>() : deque;
            while (target.size() >= perDeviceMax) {
                droppedHolder[0] = target.pollFirst();
            }
            target.offerLast(frame);
            return target;
        });
        if (droppedHolder[0] != null) {
            log.warn("待发队列超限丢弃最旧指令 protocol={} deviceId={} dropped={}", key.getProtocolType(),
                    deviceId, droppedHolder[0]);
        }
        if (log.isDebugEnabled()) {
            log.debug("下行已暂存待重投 protocol={} deviceId={} command={} requestId={}", key.getProtocolType(),
                    deviceId, frame.getCommand(), frame.getRequestId());
        }
    }

    /**
     * 原子取出并清空指定协议下某设备的待发指令（丢弃超过 TTL 的指令）。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @return 待补发指令列表（按入队顺序），无待发时返回空列表
     */
    public List<PendingFrame> drain(String protocolType, String deviceId) {
        if (deviceId == null) {
            return Collections.emptyList();
        }
        ConcurrentLinkedDeque<PendingFrame> taken = queues.remove(SessionKey.of(protocolType, deviceId));
        if (taken == null || taken.isEmpty()) {
            return Collections.emptyList();
        }
        long ttl = properties.getPendingTtlMs();
        long now = System.currentTimeMillis();
        List<PendingFrame> out = new ArrayList<>(taken.size());
        int expired = 0;
        for (PendingFrame pending : taken) {
            if (ttl > 0 && now - pending.getEnqueuedAt() > ttl) {
                expired++;
                continue;
            }
            out.add(pending);
        }
        if (expired > 0) {
            log.warn("补发时丢弃超期指令 deviceId={} 超期数={}", deviceId, expired);
        }
        return out;
    }

    /**
     * 指定协议下某设备当前待发指令数（弱一致视图）。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @return 待发数，不存在返回 0
     */
    public int pendingSize(String protocolType, String deviceId) {
        if (deviceId == null) {
            return 0;
        }
        ConcurrentLinkedDeque<PendingFrame> deque = queues.get(SessionKey.of(protocolType, deviceId));
        return deque == null ? 0 : deque.size();
    }

    /**
     * 当前所有设备的待发指令总数（弱一致视图）。
     *
     * @return 待发总数
     */
    public int totalPending() {
        int count = 0;
        for (ConcurrentLinkedDeque<PendingFrame> deque : queues.values()) {
            count += deque.size();
        }
        return count;
    }

    /**
     * 当前暂存设备数量（弱一致视图）。
     *
     * @return 设备数
     */
    public int pendingDeviceCount() {
        return queues.size();
    }

    /**
     * 清空全部待发指令（主要供测试与运维使用）。
     */
    public void clear() {
        queues.clear();
    }

    /**
     * 约束暂存设备总数不超上限：达到上限时淘汰「队首指令最陈旧」的设备队列。
     *
     * <p>在 {@code offer} 为新设备入队前调用（此路径为低频溢出路径，全表扫描可接受）。软上限：
     * 并发下可能瞬时多出一两个设备，可接受。</p>
     */
    private void enforceDeviceCapacity() {
        int deviceCap = Math.max(MIN_LIMIT, properties.getMaxPendingDevices());
        if (queues.size() < deviceCap) {
            return;
        }
        SessionKey victim = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<SessionKey, ConcurrentLinkedDeque<PendingFrame>> entry : queues.entrySet()) {
            PendingFrame head = entry.getValue().peekFirst();
            long headTime = head == null ? Long.MAX_VALUE : head.getEnqueuedAt();
            if (headTime < oldest) {
                oldest = headTime;
                victim = entry.getKey();
            }
        }
        if (victim != null) {
            ConcurrentLinkedDeque<PendingFrame> removed = queues.remove(victim);
            log.warn("待发设备数超上限 {}，淘汰最陈旧设备队列 protocol={} deviceId={} 丢弃指令数={}",
                    deviceCap, victim.getProtocolType(), victim.getDeviceId(), removed == null ? 0 : removed.size());
        }
    }
}
