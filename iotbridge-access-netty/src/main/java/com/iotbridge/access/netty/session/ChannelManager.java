package com.iotbridge.access.netty.session;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 设备通道管理器。
 *
 * <p>维护 {@code deviceId <-> Channel} 双向映射，供下行按设备寻址写入、连接断开时清理会话。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class ChannelManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelManager.class);

    /**
     * deviceId -> Channel。
     */
    private final ConcurrentMap<String, Channel> deviceChannels = new ConcurrentHashMap<>();

    /**
     * ChannelId -> deviceId，用于断开时反查清理。
     */
    private final ConcurrentMap<String, String> channelDevices = new ConcurrentHashMap<>();

    /**
     * 绑定设备与通道；同一 deviceId 重复上线时覆盖旧通道。
     *
     * @param deviceId 设备标识
     * @param channel  设备通道
     */
    public void register(String deviceId, Channel channel) {
        if (deviceId == null || channel == null) {
            return;
        }
        String channelKey = channel.id().asLongText();
        // 覆盖前清理旧的 deviceId 映射，避免通道泄漏
        Channel previous = deviceChannels.put(deviceId, channel);
        if (previous != null && !previous.id().equals(channel.id())) {
            channelDevices.remove(previous.id().asLongText());
        }
        channelDevices.put(channelKey, deviceId);
        log.info("设备上线 deviceId={} remote={}", deviceId, channel.remoteAddress());
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
        String deviceId = channelDevices.remove(channelKey);
        if (deviceId != null) {
            // 仅当当前登记的通道就是断开通道时才移除，防止误删新连接
            deviceChannels.remove(deviceId, channel);
            log.info("设备离线 deviceId={} remote={}", deviceId, channel.remoteAddress());
        }
    }

    /**
     * 判断设备是否在线。
     *
     * @param deviceId 设备标识
     * @return 在线返回 {@code true}
     */
    public boolean isOnline(String deviceId) {
        Channel channel = deviceChannels.get(deviceId);
        return channel != null && channel.isActive();
    }

    /**
     * 向指定设备写入下行字节帧。
     *
     * @param deviceId 设备标识
     * @param data     待写入字节
     * @return 成功投递返回 {@code true}；设备离线或写入失败返回 {@code false}
     */
    public boolean sendToDevice(String deviceId, byte[] data) {
        Channel channel = deviceChannels.get(deviceId);
        if (channel == null || !channel.isActive()) {
            log.warn("设备离线，下行丢弃 deviceId={}", deviceId);
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
     * @return {@code deviceId -> 远端地址} 的有序（deviceId 升序）不可变映射副本，无在线设备时为空
     */
    public Map<String, String> snapshotOnlineDevices() {
        Map<String, String> snapshot = new TreeMap<>();
        for (Map.Entry<String, Channel> entry : deviceChannels.entrySet()) {
            Channel channel = entry.getValue();
            if (channel != null && channel.isActive()) {
                snapshot.put(entry.getKey(), String.valueOf(channel.remoteAddress()));
            }
        }
        return Collections.unmodifiableMap(snapshot);
    }
}
