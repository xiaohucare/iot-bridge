package com.iotbridge.mq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 下行拓扑与消息转换配置。
 *
 * <p>声明持久化下行队列、交换机与绑定关系，并统一使用 JSON 消息转换器，
 * 使 {@code DownlinkCommand} 与线上报文可互相序列化。</p>
 *
 * <p><b>可靠性拓扑</b>：下行队列绑定死信参数（{@code x-dead-letter-exchange} /
 * {@code x-dead-letter-routing-key}），消费端 nack 不重回队列或反序列化失败的毒消息均转入
 * {@code <下行队列>.dlq} 死信队列，杜绝无限重投与静默丢失。</p>
 *
 * <p><b>多实例路由</b>：{@code iotbridge.mq.downlink-exchange-type=consistent-hash} 时改用
 * {@code x-consistent-hash} 交换机，每实例声明独立队列并以 weight=1 绑定；发布端以 deviceId 作路由键，
 * 同一设备恒定落到同一实例队列。直连交换机在「同一 routing key 绑多个队列」时是广播语义，
 * 会造成重复下发与随机错投，因此多实例必须用一致哈希。</p>
 *
 * <p><b>发布确认</b>：{@code RabbitTemplate} 启用 publisher confirm 与 mandatory 退回回调；
 * 具体的同步等待确认见 {@code RabbitDownlinkPublisher}。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class RabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

    /**
     * 死信交换机名后缀。
     */
    private static final String DLX_SUFFIX = ".dlx";

    /**
     * 死信队列名后缀。
     */
    private static final String DLQ_SUFFIX = ".dlq";

    /**
     * 死信路由键后缀。
     */
    private static final String DLQ_ROUTING_KEY_SUFFIX = ".dead";

    /**
     * 一致哈希交换机类型（由 rabbitmq_consistent_hash_exchange 插件提供）。
     */
    private static final String CONSISTENT_HASH_EXCHANGE_TYPE = "x-consistent-hash";

    /**
     * 一致哈希绑定权重：每实例队列权重相同，设备按键哈希后均匀分布到各实例。
     */
    private static final String CONSISTENT_HASH_BINDING_WEIGHT = "1";

    /**
     * 下行指令队列（持久化，绑定死信参数：nack 不重回队列的消息转入死信队列）。
     *
     * <p>一致哈希模式下队列名按实例隔离（{@link MqProperties#effectiveQueueName()}），
     * 使每个实例只消费路由到自己的设备指令。</p>
     *
     * @param mqProperties MQ 配置
     * @return 队列声明
     */
    @Bean
    public Queue downlinkQueue(MqProperties mqProperties) {
        return QueueBuilder.durable(mqProperties.effectiveQueueName())
                .deadLetterExchange(downlinkDlxName(mqProperties))
                .deadLetterRoutingKey(downlinkDlqRoutingKeyName(mqProperties))
                .build();
    }

    /**
     * 下行指令交换机（持久化）：默认直连，多实例时改用一致哈希。
     *
     * @param mqProperties MQ 配置
     * @return 交换机声明
     */
    @Bean
    public Exchange downlinkExchange(MqProperties mqProperties) {
        if (mqProperties.isConsistentHash()) {
            log.info("下行交换机使用一致哈希 exchange={} 实例={} 队列={}", mqProperties.getDownlinkExchange(),
                    mqProperties.effectiveInstanceId(), mqProperties.effectiveQueueName());
            // 不用 hash-header：发布端以 deviceId 作 routing key，交换机按 routing key 哈希分发
            return new CustomExchange(mqProperties.getDownlinkExchange(), CONSISTENT_HASH_EXCHANGE_TYPE, true,
                    false);
        }
        return new DirectExchange(mqProperties.getDownlinkExchange(), true, false);
    }

    /**
     * 队列与交换机绑定。
     *
     * <p>直连模式用配置的固定路由键；一致哈希模式绑定权重，发布端以 deviceId 作路由键由 broker 哈希分发。</p>
     *
     * @param downlinkQueue    下行队列
     * @param downlinkExchange 下行交换机
     * @param mqProperties     MQ 配置
     * @return 绑定关系
     */
    @Bean
    public Binding downlinkBinding(Queue downlinkQueue, Exchange downlinkExchange, MqProperties mqProperties) {
        String routingKey = mqProperties.isConsistentHash()
                ? CONSISTENT_HASH_BINDING_WEIGHT : mqProperties.getDownlinkRoutingKey();
        return new Binding(downlinkQueue.getName(), Binding.DestinationType.QUEUE,
                downlinkExchange.getName(), routingKey, null);
    }

    /**
     * 死信交换机（直连，持久化），接收下行队列拒绝的消息。
     *
     * @param mqProperties MQ 配置
     * @return 交换机声明
     */
    @Bean
    public DirectExchange downlinkDeadLetterExchange(MqProperties mqProperties) {
        return new DirectExchange(downlinkDlxName(mqProperties), true, false);
    }

    /**
     * 死信队列（持久化），保存消费失败/毒消息供人工排查或重放。
     *
     * @param mqProperties MQ 配置
     * @return 队列声明
     */
    @Bean
    public Queue downlinkDeadLetterQueue(MqProperties mqProperties) {
        return QueueBuilder.durable(downlinkDlqName(mqProperties)).build();
    }

    /**
     * 死信队列与死信交换机绑定。
     *
     * @param downlinkDeadLetterQueue 死信队列
     * @param downlinkDeadLetterExchange 死信交换机
     * @param mqProperties MQ 配置
     * @return 绑定关系
     */
    @Bean
    public Binding downlinkDeadLetterBinding(Queue downlinkDeadLetterQueue,
                                             DirectExchange downlinkDeadLetterExchange,
                                             MqProperties mqProperties) {
        return new Binding(downlinkDeadLetterQueue.getName(), Binding.DestinationType.QUEUE,
                downlinkDeadLetterExchange.getName(), downlinkDlqRoutingKeyName(mqProperties), null);
    }

    /**
     * JSON 消息转换器。
     *
     * @return {@link MessageConverter}
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置好 JSON 转换器与发布确认回调的 {@link RabbitTemplate}。
     *
     * <p>需配合 {@code spring.rabbitmq.publisher-confirm-type: correlated} 与
     * {@code publisher-returns: true}（application.yml 已声明）生效。</p>
     *
     * @param connectionFactory Rabbit 连接工厂
     * @param messageConverter  消息转换器
     * @return {@link RabbitTemplate}
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        // mandatory：消息不可路由时经 returns 回调退回（而不是被 broker 静默丢弃）
        template.setMandatory(true);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("RabbitMQ broker 拒绝确认 correlation={} cause={}", correlationData, cause);
            }
        });
        template.setReturnsCallback(returned -> log.error("下行消息不可路由被退回 exchange={} routingKey={} replyText={} message={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText(),
                new String(returned.getMessage().getBody())));
        return template;
    }

    private static String downlinkDlxName(MqProperties mqProperties) {
        return mqProperties.effectiveQueueName() + DLX_SUFFIX;
    }

    private static String downlinkDlqName(MqProperties mqProperties) {
        return mqProperties.effectiveQueueName() + DLQ_SUFFIX;
    }

    private static String downlinkDlqRoutingKeyName(MqProperties mqProperties) {
        return mqProperties.effectiveQueueName() + DLQ_ROUTING_KEY_SUFFIX;
    }
}
