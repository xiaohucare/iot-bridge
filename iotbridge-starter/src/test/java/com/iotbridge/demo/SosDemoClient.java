package com.iotbridge.demo;

import com.iotbridge.plugin.sos.model.SosFrame;
import com.iotbridge.plugin.sos.protocol.SosProtocolConstants;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * SOS-C02 V3.0 协议 TCP 演示客户端（可运行示例）。
 *
 * <p>需先以 sos profile 启动网关：{@code --spring.profiles.active=sos}（TCP 端口 9000，长度字段切帧）。</p>
 *
 * <p>流程：连接网关 -> 发送注册包(0x01) -> 发送事件上报包(0x02, 报警) -> 保持连接并打印网关回写的下行帧。
 * 随后可通过 admin REST 对设备 IMEI 下发指令（如时间校准），在本客户端观察下行帧。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
public final class SosDemoClient {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9000;
    private static final String IMEI = "869975034441082";
    private static final byte[] VENDOR = {0x00, 0x01};

    private SosDemoClient() {
        // 工具类不允许实例化
    }

    /**
     * 客户端入口。
     *
     * @param args 启动参数，未使用
     * @throws InterruptedException 连接或等待被关闭
     */
    public static void main(String[] args) throws InterruptedException {
        byte[] registerFrame = buildRegisterFrame();
        byte[] eventFrame = buildEventFrame();

        EventLoopGroup group = new NioEventLoopGroup();
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new LengthFieldBasedFrameDecoder(1024, 9, 2, 5, 0))
                                    .addLast(new PrintInboundHandler());
                        }
                    });

            Channel channel = bootstrap.connect(HOST, PORT).sync().channel();
            System.out.println("[sos-device] 已连接网关 " + HOST + ":" + PORT);

            channel.writeAndFlush(wrap(registerFrame));
            System.out.println("[sos-device] 已发送注册帧: " + ByteBufUtil.hexDump(registerFrame));
            Thread.sleep(500);

            channel.writeAndFlush(wrap(eventFrame));
            System.out.println("[sos-device] 已发送事件帧: " + ByteBufUtil.hexDump(eventFrame));
            System.out.println("[sos-device] 等待下行…… (对网关下发: "
                    + "curl -XPOST localhost:8080/api/v1/devices/" + IMEI
                    + "/commands -H 'Content-Type: application/json' "
                    + "-d '{\"protocolType\":\"sos-c02\",\"command\":\"timeCalib\",\"params\":{\"vendor\":\"0001\"}}')");

            channel.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }

    private static ByteBuf wrap(byte[] frame) {
        return io.netty.buffer.Unpooled.wrappedBuffer(frame);
    }

    /**
     * 构造注册包(0x01)：设备类型 + IMEI + IMSI + ICCID + 电压 + CSQ + 电量 + 程序版本。
     */
    private static byte[] buildRegisterFrame() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x04); // 设备类型：手报(紧急按钮)
        writeAscii(data, IMEI, 15);
        writeAscii(data, "460113118743732", 15);   // IMSI
        writeAscii(data, "89861120224014398762", 20); // ICCID
        data.write(0x25); // 电池电压 3.7V(0.1V 单位)
        data.write(0x15); // CSQ 信号强度
        data.write(0x64); // 电量 100%
        data.write(0x10); // 程序版本 1.0
        SosFrame frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0001,
                SosProtocolConstants.CMD_REGISTER, data.toByteArray());
        return frame.toBytes();
    }

    /**
     * 构造事件上报包(0x02)：设备类型 + IMEI + 事件类型 + 电压 + CSQ + 电量 + 程序版本。
     */
    private static byte[] buildEventFrame() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0x04); // 设备类型
        writeAscii(data, IMEI, 15);
        data.write(0x02); // 事件类型：报警
        data.write(0x25);
        data.write(0x15);
        data.write(0x64);
        data.write(0x10);
        SosFrame frame = new SosFrame(SosProtocolConstants.PROTOCOL_VERSION, VENDOR, 0x0002,
                SosProtocolConstants.CMD_EVENT, data.toByteArray());
        return frame.toBytes();
    }

    private static void writeAscii(ByteArrayOutputStream out, String text, int fixedLen) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < fixedLen; i++) {
            out.write(i < bytes.length ? bytes[i] : 0x30);
        }
    }

    /**
     * 打印网关下发的二进制帧。
     */
    private static final class PrintInboundHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            byte[] frame = ByteBufUtil.getBytes(msg);
            System.out.println("[sos-device] 收到下行帧: " + ByteBufUtil.hexDump(frame)
                    + " (ascii=" + new String(frame, StandardCharsets.US_ASCII).replaceAll("\\p{Cntrl}", ".") + ")");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.err.println("[sos-device] 通道异常: " + cause.getMessage());
            ctx.close();
        }
    }
}
