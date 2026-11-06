package com.iotbridge.access.netty.api;

/**
 * 接入服务端契约。
 *
 * <p>TCP（{@code NettyTcpServer}）与 MQTT（{@code MqttServer}）等接入实现均遵循该接口，
 * 由 bridge-core 的生命周期组件统一随容器启停，避免 core 逐个感知具体接入模块。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public interface AccessServer {

    /**
     * 启动服务端并绑定端口。
     *
     * @throws InterruptedException 绑定过程被中断
     */
    void start() throws InterruptedException;

    /**
     * 关闭服务端与线程组，释放资源。需可重复调用。
     */
    void stop();
}
