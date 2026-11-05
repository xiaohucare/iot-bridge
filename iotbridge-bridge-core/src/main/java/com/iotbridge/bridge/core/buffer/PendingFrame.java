package com.iotbridge.bridge.core.buffer;

import com.iotbridge.spi.model.DownlinkCommand;

/**
 * 待重投的下行指令快照。
 *
 * <p>缓存<b>原始下行指令</b>而非已编码字节帧：设备重新上线补发时再以当时的协议编解码器编码，
 * 规避 {@code timeCalib} 等含时间戳指令在离线期间被固化导致的时效错误（送达即错误）。
 * {@code enqueuedAt} 用于 TTL 过期判定。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class PendingFrame {

    /**
     * 原始下行指令（补发时重新编码）。
     */
    private final DownlinkCommand downlinkCommand;

    /**
     * 入队时间戳（毫秒），用于 TTL 过期判定。
     */
    private final long enqueuedAt;

    /**
     * 构造待重投指令快照。
     *
     * @param downlinkCommand 原始下行指令
     * @param enqueuedAt      入队时间戳（毫秒）
     */
    public PendingFrame(DownlinkCommand downlinkCommand, long enqueuedAt) {
        this.downlinkCommand = downlinkCommand;
        this.enqueuedAt = enqueuedAt;
    }

    public DownlinkCommand getDownlinkCommand() {
        return downlinkCommand;
    }

    public long getEnqueuedAt() {
        return enqueuedAt;
    }

    /**
     * 目标设备标识（取自原始指令）。
     *
     * @return deviceId，指令为空时返回 {@code null}
     */
    public String getDeviceId() {
        return downlinkCommand == null ? null : downlinkCommand.getDeviceId();
    }

    /**
     * 协议类型（取自原始指令，可能为空回退默认）。
     *
     * @return protocolType，指令为空时返回 {@code null}
     */
    public String getProtocolType() {
        return downlinkCommand == null ? null : downlinkCommand.getProtocolType();
    }

    /**
     * 指令名称（取自原始指令，用于日志与链路追踪）。
     *
     * @return command 名称，指令为空时返回 {@code null}
     */
    public String getCommand() {
        return downlinkCommand == null ? null : downlinkCommand.getCommand();
    }

    /**
     * 请求追踪 ID（取自原始指令，用于日志与链路追踪）。
     *
     * @return requestId，指令为空时返回 {@code null}
     */
    public String getRequestId() {
        return downlinkCommand == null ? null : downlinkCommand.getRequestId();
    }

    @Override
    public String toString() {
        return "PendingFrame{command='" + getCommand() + "', requestId='" + getRequestId()
                + "', enqueuedAt=" + enqueuedAt + '}';
    }
}
