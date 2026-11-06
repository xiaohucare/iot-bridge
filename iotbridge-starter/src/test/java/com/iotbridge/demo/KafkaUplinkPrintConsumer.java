package com.iotbridge.demo;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟业务平台的 Kafka 上行消费者（可运行示例）。
 *
 * <p>以独立进程连接 Kafka 订阅上行主题 {@code iotbridge.uplink}，把每一条上行消息打印出来，
 * 用于验证「设备/网关 -> Kafka -> 业务平台」这一段链路。与网关内置组件无耦合，不影响线上业务的消费组。</p>
 *
 * <p>直接以 {@code main} 方法在 IDE 中运行；可选参数为 bootstrap servers、主题、消费组。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public final class KafkaUplinkPrintConsumer {

    private static final String DEFAULT_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String DEFAULT_TOPIC = "iotbridge.uplink";
    private static final String DEFAULT_GROUP_ID = "iotbridge-demo-uplink-printer";

    private KafkaUplinkPrintConsumer() {
        // 工具类不允许实例化
    }

    /**
     * 消费者入口。
     *
     * @param args 启动参数（均可省略）：{@code args[0]}=bootstrap servers、{@code args[1]}=主题、
     *             {@code args[2]}=消费组；受限内存环境下可只按默认值运行
     * @throws InterruptedException 等待消息期间线程被中断
     */
    public static void main(String[] args) throws InterruptedException {
        String bootstrapServers = args.length >= 1 && !args[0].isBlank()
                ? args[0] : envOrDefault("KAFKA_SERVERS", DEFAULT_BOOTSTRAP_SERVERS);
        String topic = args.length >= 2 && !args[1].isBlank() ? args[1] : DEFAULT_TOPIC;
        String groupId = args.length >= 3 && !args[2].isBlank() ? args[2] : DEFAULT_GROUP_ID;

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // 演示进程只关注启动之后的新消息，不重复消费历史数据
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        AtomicLong received = new AtomicLong();
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        // Ctrl-C 时唤醒 poll，保证退出前关闭消费者、提交位点
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[demo-business] 收到消息数: " + received.get());
            consumer.wakeup();
        }, "demo-kafka-shutdown"));

        try (KafkaConsumer<String, String> closable = consumer) {
            closable.subscribe(Collections.singletonList(topic));
            System.out.println("[demo-business] 已订阅 Kafka 主题 " + topic + " servers=" + bootstrapServers
                    + " group=" + groupId + "，等待上行消息……（Ctrl-C 退出）");
            while (true) {
                ConsumerRecords<String, String> records;
                try {
                    records = closable.poll(Duration.ofSeconds(1));
                } catch (org.apache.kafka.common.errors.WakeupException e) {
                    return;
                }
                for (ConsumerRecord<String, String> record : records) {
                    long count = received.incrementAndGet();
                    System.out.printf("[demo-business] #%d topic=%s partition=%d offset=%d key=%s%n  value=%s%n",
                            count, record.topic(), record.partition(), record.offset(), record.key(), record.value());
                }
            }
        }
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
