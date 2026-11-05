package com.iotbridge.access.netty.session;

import com.iotbridge.spi.session.SessionKey;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
     * SessionKey -> Channel。
     */
    private final ConcurrentMap<SessionKey, Channel> deviceChannels = new ConcurrentHashMap<>();

    /**
     * ChannelId -> SessionKey，用于断开时反查清理。
     */
    private final ConcurrentMap<String, SessionKey> channelKeys = new ConcurrentHashMap<>();

    /**
     * 绑定协议、设备与通道；同一 {@code (protocolType,deviceId)} 重复上线时覆盖旧通道。
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
        if (previous != null && !previous.id().equals(channel.id())) {
            channelKeys.remove(previous.id().asLongText());
        }
        channelKeys.put(channelKey, key);
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
        if (key != null) {
            // 仅当当前登记的通道就是断开通道时才移除，防止误删新连接
            deviceChannels.remove(key, channel);
            log.info("设备离线 protocol={} deviceId={} remote={}", key.getProtocolType(), key.getDeviceId(),
                    channel.remoteAddress());
        }
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
     * @param deviceId 设备标识
     * @return 任一协议在线返回 {@code true}
     */
    public boolean isDeviceOnline(String deviceId) {
        if (deviceId == null) {
            return false;
        }
        for (Map.Entry<SessionKey, Channel> entry : deviceChannels.entrySet()) {
            Channel channel = entry.getValue();
            if (deviceId.equals(entry.getKey().getDeviceId()) && channel != null && channel.isActive()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向指定协议下的目标设备写入下行字节帧。
     *
     * @param protocolType 协议类型
     * @param deviceId     设备标识
     * @param data         待写入字节
     * @return 成功投递返回 {@code true}；设备离线或写入失败返回 {@code false}
     */
    public boolean sendToDevice(String protocolType, String deviceId, byte[] data) {
        Channel channel = deviceChannels.get(SessionKey.of(protocolType, deviceId));
        if (channel == null || !channel.isActive()) {
            log.warn("设备离线，下行丢弃 protocol={} deviceId={}", SessionKey.of(protocolType, deviceId)
                    .getProtocolType(), deviceId);
            return false;
        }
        // 包装为 ByteBuf 直接进入出站流水线，writeAndFlush 成功后由 Netty 负责释放
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        channel.writeAndFlush(buf);
        return true;
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
