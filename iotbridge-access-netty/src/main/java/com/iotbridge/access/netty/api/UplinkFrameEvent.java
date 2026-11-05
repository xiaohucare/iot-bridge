package com.iotbridge.access.netty.api;

import io.netty.channel.Channel;

/**
 * 上行帧事件载体。
 *
 * <p>由接入层构造，携带原始帧字节与对应的 Netty {@link Channel}，交由 bridge-core 解码、
 * 绑定设备会话并投递上行消息。置于 access-netty 模块以持有 Netty 类型，避免污染 spi。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class UplinkFrameEvent {

    private final Channel channel;
    private final byte[] frame;
    private final String remoteAddress;
    private final String protocolType;

    /**
     * 构造上行帧事件（不指定协议，回退注册表默认协议）。
     *
     * @param channel       来源设备通道
     * @param frame         原始帧字节（不含分隔符）
     * @param remoteAddress 远端地址描述
     */
    public UplinkFrameEvent(Channel channel, byte[] frame, String remoteAddress) {
        this(channel, frame, remoteAddress, null);
    }

    /**
     * 构造上行帧事件。
     *
     * @param channel       来源设备通道
     * @param frame         原始帧字节（不含分隔符）
     * @param remoteAddress 远端地址描述
     * @param protocolType  该接入端口绑定的协议类型；为空时下游回退默认协议
     */
    public UplinkFrameEvent(Channel channel, byte[] frame, String remoteAddress, String protocolType) {
        this.channel = channel;
        this.frame = frame;
        this.remoteAddress = remoteAddress;
        this.protocolType = protocolType;
    }

    public Channel getChannel() {
        return channel;
    }

    public byte[] getFrame() {
        return frame;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    /**
     * 接入端口绑定的协议类型（可能为空）。
     *
     * @return 协议类型，空表示未绑定（由下游回退默认协议）
     */
    public String getProtocolType() {
        return protocolType;
    }
}
