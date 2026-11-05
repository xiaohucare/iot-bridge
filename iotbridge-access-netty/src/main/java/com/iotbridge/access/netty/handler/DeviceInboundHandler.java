package com.iotbridge.access.netty.handler;

import com.iotbridge.access.netty.api.UplinkFrameEvent;
import com.iotbridge.access.netty.api.UplinkFrameListener;
import com.iotbridge.access.netty.session.ChannelManager;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设备上行帧入站处理器。
 *
 * <p>接收上游帧解码器（行分隔或长度字段）切分后的整帧 {@link ByteBuf}，转为字节后封装为
 * {@link UplinkFrameEvent} 回调 {@link UplinkFrameListener}（解码由 bridge-core 依据协议注册表完成）；
 * 连接断开时清理会话。该 Handler 非共享，服务端为每个通道创建独立实例。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public class DeviceInboundHandler extends SimpleChannelInboundHandler<ByteBuf> {

    private static final Logger log = LoggerFactory.getLogger(DeviceInboundHandler.class);

    private final ChannelManager channelManager;
    private final UplinkFrameListener uplinkFrameListener;

    public DeviceInboundHandler(ChannelManager channelManager, UplinkFrameListener uplinkFrameListener) {
        this.channelManager = channelManager;
        this.uplinkFrameListener = uplinkFrameListener;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        if (msg.readableBytes() == 0) {
            return;
        }
        // SimpleChannelInboundHandler 会自动释放 msg，这里复制为独立字节数组供下游异步使用
        byte[] frame = ByteBufUtil.getBytes(msg);
        String remote = String.valueOf(ctx.channel().remoteAddress());
        UplinkFrameEvent event = new UplinkFrameEvent(ctx.channel(), frame, remote);
        try {
            uplinkFrameListener.onUplinkFrame(event);
        } catch (RuntimeException e) {
            // 单帧处理异常不应中断连接
            log.error("上行帧处理异常 remote={} frameLen={}", remote, frame.length, e);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // 设备建立 TCP 连接（尚未解码上行帧、未绑定 deviceId），与 deviceId 级“设备上线”区分
        log.info("设备连上服务 remote={}", ctx.channel().remoteAddress());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        channelManager.unbindByChannel(ctx.channel());
        log.info("设备连接断开 remote={}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("设备通道异常 remote={} 关闭连接", ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
