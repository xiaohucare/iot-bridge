package com.iotbridge.mq.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 生产者配置。
 *
 * <p>显式提供 {@code KafkaTemplate<String,String>}，key/value 均使用字符串序列化，
 * 上行负载以 JSON 字符串投递，保证同一设备（deviceId 作 key）分区有序。</p>
 *
 * <p><b>配置来源唯一</b>：以 Spring Boot 的 {@code spring.kafka.*}（含 {@code spring.kafka.producer.properties.*}
 * 透传项）为唯一配置来源，本类只覆盖序列化器；可靠性参数（{@code acks=all}、幂等、重试）统一在
 * {@code application.yml} 中声明，避免代码与配置双轨不一致。</p>
 *
 * @author iotbridge
 * @date 2026-11-05
 */
@Configuration
public class KafkaProducerConfig {

    private final KafkaProperties kafkaProperties;

    public KafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    /**
     * 构建字符串序列化的生产者工厂。基础属性全部继承 {@code spring.kafka.producer.*} 配置。
     *
     * @return {@link ProducerFactory}
     */
    @Bean
    @SuppressWarnings("deprecation")
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    /**
     * 供上行生产者使用的 {@link KafkaTemplate}。
     *
     * @param producerFactory 生产者工厂
     * @return {@link KafkaTemplate}
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
