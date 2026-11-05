package com.iotbridge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

/**
 * iotbridge 网关启动类。
 *
 * <p>组件扫描 {@code com.iotbridge} 下各模块 Bean；{@code @EnableRabbit} 开启下行队列监听。
 * Netty 接入由 bridge-core 的 {@code GatewayLifecycle} 随容器自动启停。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@EnableRabbit
@SpringBootApplication
public class IotbridgeApplication {

    /**
     * 应用入口。
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(IotbridgeApplication.class, args);
    }
}
