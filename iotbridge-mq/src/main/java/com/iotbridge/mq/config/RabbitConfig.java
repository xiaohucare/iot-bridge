package com.iotbridge.mq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
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
 * @author iotbridge
 * @date 2026-11-05
 */
@Configuration
@EnableConfigurationProperties(MqProperties.class)
public class RabbitConfig {

    /**
     * 下行指令队列（持久化）。
     *
     * @param mqProperties MQ 配置
     * @return 队列声明
     */
    @Bean
    public Queue downlinkQueue(MqProperties mqProperties) {
        return new Queue(mqProperties.getDownlinkQueue(), true);
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
     * JSON 消息转换器。
     *
     * @return {@link MessageConverter}
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 配置好 JSON 转换器的 {@link RabbitTemplate}。
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
        return template;
    }
}
