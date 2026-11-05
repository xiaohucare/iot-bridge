package com.iotbridge.spi.codec;

import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;

/**
 * 协议编解码插件 SPI。
 *
 * <p>每种硬件接入协议实现本接口，并通过 {@code META-INF/services/com.iotbridge.spi.codec.ProtocolCodec}
 * 使用 {@link java.util.ServiceLoader} 注册，网关启动时自动加载。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public interface ProtocolCodec {

    /**
     * 协议唯一标识，用于消息路由与插件选择。
     *
     * @return 协议类型，如 {@code "demo-text"}
     */
    String protocolType();

    /**
     * 将设备原始帧解码为统一上行消息。
     *
     * @param frame 设备上报的原始字节帧（不含分隔符）
     * @return 上行消息，不可为 {@code null}
     */
    UplinkMessage decode(byte[] frame);

    /**
     * 将下行指令编码为设备可识别的原始字节帧。
     *
     * @param command 下行指令
     * @return 待写入设备 TCP 通道的字节帧（不含分隔符）
     */
    byte[] encode(DownlinkCommand command);
}
