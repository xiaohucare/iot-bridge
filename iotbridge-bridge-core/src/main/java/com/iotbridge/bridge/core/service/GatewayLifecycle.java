package com.iotbridge.bridge.core.service;

import com.iotbridge.access.netty.api.AccessServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 网关生命周期管理。
 *
 * <p>随 Spring 容器启动依次开启全部接入服务端（TCP、MQTT 等），容器关闭时逆序优雅停机并释放资源。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Component
public class GatewayLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GatewayLifecycle.class);

    private final List<AccessServer> accessServers;

    /**
     * 运行状态标志，需保证容器回调线程可见。
     */
    private volatile boolean running = false;

    public GatewayLifecycle(List<AccessServer> accessServers) {
        this.accessServers = accessServers;
    }

    @Override
    public void start() {
        try {
            for (AccessServer server : accessServers) {
                server.start();
                log.info("接入服务已启动 server={}", server.getClass().getSimpleName());
            }
            running = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("接入服务启动被中断", e);
            stop();
        } catch (RuntimeException e) {
            // 任一接入端口绑定失败（如端口占用）：回退已启动的服务端，避免半启动状态
            log.error("接入服务启动失败，回退已启动服务", e);
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        for (int i = accessServers.size() - 1; i >= 0; i--) {
            try {
                accessServers.get(i).stop();
            } catch (RuntimeException e) {
                // 单个服务端关闭失败不应阻断其他服务端的资源释放
                log.warn("接入服务关闭失败 server={}", accessServers.get(i).getClass().getSimpleName(), e);
            }
        }
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
