/**
 * iotbridge-mq：消息中间件适配层。
 *
 * <p>上行经 Kafka 投递（{@code KafkaUplinkProducer}），下行经 RabbitMQ 收发
 * （{@code RabbitDownlinkPublisher} 与 {@code DownlinkCommandConsumer}）。</p>
 */
package com.iotbridge.mq;
