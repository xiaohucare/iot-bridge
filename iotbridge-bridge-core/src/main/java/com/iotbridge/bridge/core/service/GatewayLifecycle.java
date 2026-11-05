package com.iotbridge.bridge.core.service;

import com.iotbridge.access.netty.server.NettyTcpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 网关生命周期管理。
 *
 * <p>随 Spring 容器启动开启 Netty TCP 服务端，容器关闭时优雅停机并释放线程组。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class GatewayLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GatewayLifecycle.class);

    private final NettyTcpServer nettyTcpServer;

    /**
     * 运行状态标志，需保证容器回调线程可见。
     */
    private volatile boolean running = false;

    public GatewayLifecycle(NettyTcpServer nettyTcpServer) {
        this.nettyTcpServer = nettyTcpServer;
    }

    @Override
    public void start() {
        try {
            nettyTcpServer.start();
            running = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Netty 服务端启动被中断", e);
        }
    }

    @Override
    public void stop() {
        nettyTcpServer.stop();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 在容器刷新完成后自动启动。
     *
     * @return 恒为 {@code true}
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 设定较晚的启动阶段，确保 MQ、协议注册表等依赖已就绪。
     *
     * @return 生命周期阶段
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
