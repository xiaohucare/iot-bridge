package com.iotbridge.mq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
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
 * <p>声明持久化下行队列、直连交换机与绑定关系，并统一使用 JSON 消息转换器，
 * 使 {@code DownlinkCommand} 与线上报文可互相序列化。</p>
 *
 * <p><b>可靠性拓扑</b>：下行队列绑定死信参数（{@code x-dead-letter-exchange} /
 * {@code x-dead-letter-routing-key}），消费端 nack 不重回队列或反序列化失败的毒消息均转入
 * {@code <下行队列>.dlq} 死信队列，杜绝无限重投与静默丢失。</p>
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
     * 下行指令队列（持久化，绑定死信参数：nack 不重回队列的消息转入死信队列）。
     *
     * @param mqProperties MQ 配置
     * @return 队列声明
     */
    @Bean
    public Queue downlinkQueue(MqProperties mqProperties) {
        return QueueBuilder.durable(mqProperties.getDownlinkQueue())
                .deadLetterExchange(downlinkDlxName(mqProperties))
                .deadLetterRoutingKey(downlinkDlqRoutingKeyName(mqProperties))
                .build();
    }

    /**
     * 下行指令交换机（直连，持久化）。
     *
     * @param mqProperties MQ 配置
     * @return 交换机声明
     */
    @Bean
    public DirectExchange downlinkExchange(MqProperties mqProperties) {
        return new DirectExchange(mqProperties.getDownlinkExchange(), true, false);
    }

    /**
     * 队列与交换机绑定。
     *
     * @param downlinkQueue    下行队列
     * @param downlinkExchange 下行交换机
     * @param mqProperties     MQ 配置
     * @return 绑定关系
     */
    @Bean
    public Binding downlinkBinding(Queue downlinkQueue, DirectExchange downlinkExchange,
                                   MqProperties mqProperties) {
        return BindingBuilder.bind(downlinkQueue).to(downlinkExchange)
                .with(mqProperties.getDownlinkRoutingKey());
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
        return BindingBuilder.bind(downlinkDeadLetterQueue).to(downlinkDeadLetterExchange)
                .with(downlinkDlqRoutingKeyName(mqProperties));
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
        return mqProperties.getDownlinkQueue() + DLX_SUFFIX;
    }

    private static String downlinkDlqName(MqProperties mqProperties) {
        return mqProperties.getDownlinkQueue() + DLQ_SUFFIX;
    }

    private static String downlinkDlqRoutingKeyName(MqProperties mqProperties) {
        return mqProperties.getDownlinkQueue() + DLQ_ROUTING_KEY_SUFFIX;
    }
}
