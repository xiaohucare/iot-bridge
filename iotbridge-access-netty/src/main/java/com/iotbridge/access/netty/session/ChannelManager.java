package com.iotbridge.access.netty.session;

import com.iotbridge.spi.session.SessionKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 设备通道管理器。
 *
 * <p>维护 {@code (protocolType,deviceId) <-> Channel} 双向映射，供下行按设备寻址写入、连接断开时清理会话。</p>
 *
 * <p>多协议并行接入下 {@code deviceId} 仅在单一协议域内唯一，跨协议可能重号，故会话以
 * {@link SessionKey}（协议 + 设备号）为键，避免不同协议相同设备号互相覆盖。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    /**
     * 写通道等待完成的超时时间（毫秒）。超时即视为发送失败，交由上层暂存重投。
     */
    private static final long WRITE_TIMEOUT_MS = 5_000L;

    /**
     * SessionKey -> Channel。
     */
    private final ConcurrentMap<SessionKey, Channel> deviceChannels = new ConcurrentHashMap<>();

    /**
     * ChannelId -> SessionKey，用于断开时反查清理。
     */
    private final ConcurrentMap<String, SessionKey> channelKeys = new ConcurrentHashMap<>();

    /**
     * deviceId -> 该设备登记过的会话键集合，供跨协议在线判定免全表扫描。
     */
    private final ConcurrentMap<String, Set<SessionKey>> deviceIndex = new ConcurrentHashMap<>();

    /**
     * 绑定协议、设备与通道；同一 {@code (protocolType,deviceId)} 重复上线时覆盖旧通道并<b>主动关闭旧连接</b>，
     * 防止僵尸 TCP 连接占用资源。
     *
     * @param protocolType 协议类型（为空归一化为默认协议键）
     * @param deviceId     设备标识
     * @param channel      设备通道
     */
    public void register(String protocolType, String deviceId, Channel channel) {
        if (deviceId == null || channel == null) {
            return;
        }
        SessionKey key = SessionKey.of(protocolType, deviceId);
        String channelKey = channel.id().asLongText();
        // 覆盖前清理旧的 deviceId 映射，避免通道泄漏
        Channel previous = deviceChannels.put(key, channel);
        if (previous != null && previous != channel) {
            channelKeys.remove(previous.id().asLongText());
            // 旧连接关闭会触发其 channelInactive -> unbindByChannel；此时 key 已指向新通道，
            // 条件删除不会误清新会话
            previous.close();
            log.info("设备重复上线，已关闭旧连接 protocol={} deviceId={} remote={}", key.getProtocolType(), deviceId,
                    previous.remoteAddress());
        }
        channelKeys.put(channelKey, key);
        deviceIndex.computeIfAbsent(deviceId, k -> ConcurrentHashMap.newKeySet()).add(key);
        log.info("设备上线 protocol={} deviceId={} remote={}", key.getProtocolType(), deviceId,
                channel.remoteAddress());
    }

    /**
     * 依据通道断开清理会话映射。
     *
     * @param channel 断开通道
     */
    public void unbindByChannel(Channel channel) {
        if (channel == null) {
            return;
        }
        String channelKey = channel.id().asLongText();
        SessionKey key = channelKeys.remove(channelKey);
        if (key != null && deviceChannels.remove(key, channel)) {
            indexRemove(key);
            log.info("设备离线 protocol={} deviceId={} remote={}", key.getProtocolType(), key.getDeviceId(),
                    channel.remoteAddress());
        }
    }

    /**
     * 会话移除后同步维护设备索引；集合为空时移除设备条目。
     */
    private void indexRemove(SessionKey key) {
        deviceIndex.computeIfPresent(key.getDeviceId(), (deviceId, keys) -> {
            keys.remove(key);
            return keys.isEmpty() ? null : keys;
        });
    }

    /**
     * 判断指定协议下的设备是否在线。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @return 在线返回 {@code true}
     */
    public boolean isOnline(String protocolType, String deviceId) {
        Channel channel = deviceChannels.get(SessionKey.of(protocolType, deviceId));
        return channel != null && channel.isActive();
    }

    /**
     * 判断设备在任意协议下是否在线（跨协议聚合，供运维便捷查询）。
     *
     * <p>基于 {@code deviceId -> 会话键集合} 索引，无需全表扫描。</p>
     *
     * @param deviceId 设备标识
     * @return 任一协议在线返回 {@code true}
     */
    public boolean isDeviceOnline(String deviceId) {
        if (deviceId == null) {
            return false;
        }
        Set<SessionKey> keys = deviceIndex.get(deviceId);
        if (keys == null) {
            return false;
        }
        for (SessionKey key : keys) {
            Channel channel = deviceChannels.get(key);
            if (channel != null && channel.isActive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向指定协议下的目标设备写入下行字节帧。
     *
     * <p>同步等待写操作完成（有界超时），返回真实发送结果，杜绝「写入尚未完成即报成功」的假成功；
     * 写失败/超时返回 {@code false}，交由上层按设备离线策略处理（暂存重投等）。
     * 若恰在通道所属 EventLoop 线程上调用则退化为异步等待（仅记录失败日志），避免 Netty 死锁。</p>
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @param data         待写入字节
     * @return 成功投递返回 {@code true}；设备离线、写入失败或超时返回 {@code false}
     */
    public boolean sendToDevice(String protocolType, String deviceId, byte[] data) {
        Channel channel = deviceChannels.get(SessionKey.of(protocolType, deviceId));
        if (channel == null || !channel.isActive()) {
            log.warn("设备离线，下行丢弃 protocol={} deviceId={}", SessionKey.of(protocolType, deviceId)
                    .getProtocolType(), deviceId);
            return false;
        }
        // 包装为 ByteBuf 直接进入出站流水线，writeAndFlush 完成后由 Netty 负责释放
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        ChannelFuture future = channel.writeAndFlush(buf);
        if (channel.eventLoop().inEventLoop()) {
            listenQuietly(future, protocolType, deviceId);
            return true;
        }
        try {
            boolean done = future.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                log.warn("下行写入等待超时(可能发送缓慢) protocol={} deviceId={} timeoutMs={}", protocolType,
                        deviceId, WRITE_TIMEOUT_MS);
                return false;
            }
            return future.isSuccess();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("下行写入等待被中断 protocol={} deviceId={}", protocolType, deviceId);
            return false;
        }
    }

    private void listenQuietly(ChannelFuture future, String protocolType, String deviceId) {
        future.addListener(f -> {
            if (!f.isSuccess()) {
                log.error("下行写入失败(异步) protocol={} deviceId={}", protocolType, deviceId, f.cause());
            }
        });
    }

    /**
     * 当前登记的设备会话数量（含 {@code isActive()} 为假、尚未清理的死连接）。
     *
     * @return 登记数量
     */
    public int onlineCount() {
        return deviceChannels.size();
    }

    /**
     * 当前真正在线（通道 {@code isActive()} 为真）的设备数量。遍历计数，不构造快照。
     *
     * @return 在线数量
     */
    public int countActive() {
        int count = 0;
        for (Channel channel : deviceChannels.values()) {
            if (channel != null && channel.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 快照当前在线设备（通道 {@code isActive} 为真）。
     *
     * @return {@code SessionKey -> 远端地址} 的有序（protocolType、deviceId 升序）不可变映射副本，无在线设备时为空
     */
    public Map<SessionKey, String> snapshotOnlineDevices() {
        // SessionKey 自然序（protocolType、deviceId 升序）保证快照输出稳定
        Map<SessionKey, String> snapshot = new TreeMap<>();
        for (Map.Entry<SessionKey, Channel> entry : deviceChannels.entrySet()) {
            Channel channel = entry.getValue();
            if (channel != null && channel.isActive()) {
                snapshot.put(entry.getKey(), String.valueOf(channel.remoteAddress()));
            }
        }
        return snapshot;
    }
}
