/**
 * iotbridge-access-netty：Netty TCP 长连接接入层。
 *
 * <p>包含服务端引导 {@code NettyTcpServer}、入站处理器 {@code DeviceInboundHandler}、
 * 设备会话管理 {@code ChannelManager}，以及供 bridge-core 实现的 {@code UplinkFrameListener} 回调契约。</p>
 */
package com.iotbridge.access.netty;
