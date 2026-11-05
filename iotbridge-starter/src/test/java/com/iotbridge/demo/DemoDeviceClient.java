package com.iotbridge.demo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

/**
 * 模拟设备客户端（可运行示例）。
 *
 * <p>连接网关 demo-text 监听端口（默认 9001，行分隔切帧）后发送一行上行报文，并持续打印网关回写的下行指令，用于端到端联调。
 * 直接以 {@code main} 方法在 IDE 中运行，或：
 * {@code mvn -q -pl iotbridge-starter test-compile exec:java}（需 exec 插件）。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class DemoDeviceClient {

    private static final String HOST = "127.0.0.1";
    // demo-text 文本协议监听端口（多协议默认下 9000 为 SOS，文本设备走 9001）
    private static final int PORT = 9001;
    private static final String UPLINK_SAMPLE = "dev001,temperature=26.5,humidity=60";

    private DemoDeviceClient() {
        // 工具类不允许实例化
    }

    /**
     * 客户端入口。
     *
     * @param args 启动参数，未使用
     * @throws InterruptedException 连接或等待被关闭
     */
    public static void main(String[] args) throws InterruptedException {
        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new DelimiterBasedFrameDecoder(65536, Delimiters.lineDelimiter()))
                                    .addLast(new StringDecoder(CharsetUtil.UTF_8))
                                    .addLast(new StringEncoder(CharsetUtil.UTF_8))
                                    .addLast(new PrintInboundHandler());
                        }
                    });

            Channel channel = bootstrap.connect(HOST, PORT).sync().channel();
            System.out.println("[demo-device] 已连接网关 " + HOST + ":" + PORT);

            // 发送一行上行报文（末尾 \n 与网关行分隔一致）
            channel.writeAndFlush(UPLINK_SAMPLE + "\n");
            System.out.println("[demo-device] 已发送上行: " + UPLINK_SAMPLE);
            System.out.println("[demo-device] 等待下行指令……（对网关发起 curl 下发即可看到回写）");

            // 保持连接直到通道关闭
            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    /**
     * 打印网关下发的行报文。
     */
    private static final class PrintInboundHandler extends SimpleChannelInboundHandler<String> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String msg) {
            System.out.println("[demo-device] 收到下行: " + msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[demo-device] 通道异常: " + cause.getMessage());
            ctx.close();
        }
    }
}
