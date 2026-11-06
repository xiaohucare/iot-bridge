package com.iotbridge.demo;

import com.iotbridge.common.util.JsonUtil;
import com.iotbridge.spi.model.DownlinkCommand;
import com.iotbridge.spi.model.UplinkMessage;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟业务平台完整上下行闭环（可运行示例）。
 *
 * <p>链路：订阅 Kafka 上行 {@code iotbridge.uplink} -> 解析 {@link UplinkMessage} 并打印 ->
 * 按设备节流生成 {@link DownlinkCommand} -> 经 RabbitMQ 下行交换机投递 -> 网关消费并下发设备。</p>
 *
 * <p>下发规则：同一设备默认每 5 秒最多下发一次 {@code setInterval}（参数 {@code value=30}），
 * 避免设备上行频繁时反向刷屏。下行交换机的路由方式与网关一致：直连模式用固定 routing key，
 * {@code consistent-hash} 模式用 {@code deviceId} 作为 routing key。</p>
 *
 * <p>直接以 {@code main} 方法在 IDE 中运行；Kafka 参数见 {@code args}，其余通过环境变量配置。</p>
 *
 * @author iotbridge
 * @date 2026-11-06
 */
public final class BusinessLoopSimulator {

    private static final String DEFAULT_KAFKA_SERVERS = "localhost:9092";
    private static final String DEFAULT_TOPIC = "iotbridge.uplink";
    private static final String DEFAULT_GROUP_ID = "iotbridge-demo-business";
    private static final String DEFAULT_EXCHANGE = "iotbridge.downlink.exchange";
    private static final String DEFAULT_ROUTING_KEY = "downlink";
    private static final String DEFAULT_DOWNLINK_COMMAND = "setInterval";
    private static final long DEFAULT_THROTTLE_SECONDS = 5L;

    private BusinessLoopSimulator() {
        // 工具类不允许实例化
    }

    /**
     * 模拟业务入口。
     *
     * @param args 可选参数：{@code args[0]}=Kafka bootstrap servers、{@code args[1]}=上行主题、
     *             {@code args[2]}=消费组；其余配置取环境变量（见 README 5.1）
     * @throws InterruptedException 等待消息期间线程被中断
     */
    public static void main(String[] args) throws InterruptedException {
        String kafkaServers = args.length >= 1 && !args[0].isBlank()
                ? args[0] : envOrDefault("KAFKA_SERVERS", DEFAULT_KAFKA_SERVERS);
        String topic = args.length >= 2 && !args[1].isBlank() ? args[1] : DEFAULT_TOPIC;
        String groupId = args.length >= 3 && !args[2].isBlank() ? args[2] : DEFAULT_GROUP_ID;

        String rabbitHost = envOrDefault("RABBIT_HOST", "localhost");
        int rabbitPort = Integer.parseInt(envOrDefault("RABBIT_PORT", "5672"));
        String rabbitUser = envOrDefault("RABBIT_USER", "guest");
        String rabbitPassword = envOrDefault("RABBIT_PASS", "guest");
        String exchange = envOrDefault("DOWNLINK_EXCHANGE", DEFAULT_EXCHANGE);
        String routingKey = envOrDefault("DOWNLINK_ROUTING_KEY", DEFAULT_ROUTING_KEY);
        boolean consistentHash = "consistent-hash"
                .equalsIgnoreCase(envOrDefault("DOWNLINK_EXCHANGE_TYPE", "direct"));
        long throttleSeconds = Long.parseLong(
                envOrDefault("BUSINESS_DOWNLINK_INTERVAL_SECONDS", String.valueOf(DEFAULT_THROTTLE_SECONDS)));

        CachingConnectionFactory connectionFactory = new CachingConnectionFactory(rabbitHost, rabbitPort);
        connectionFactory.setUsername(rabbitUser);
        connectionFactory.setPassword(rabbitPassword);
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        // 与网关下行消费者使用相同的 JSON 转换器，确保能反序列化为 DownlinkCommand
        rabbitTemplate.setMessageConverter(new Jackson2JsonMessageConverter());

        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        // 业务处理成功后再提交位点，模拟「处理-提交」语义
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        AtomicLong received = new AtomicLong();
        AtomicLong sent = new AtomicLong();
        Map<String, Long> lastSentAt = new ConcurrentHashMap<>();

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[demo-business] 收到=" + received.get() + " 下发=" + sent.get());
            consumer.wakeup();
        }, "demo-business-shutdown"));

        try {
            consumer.subscribe(Collections.singletonList(topic));
            System.out.println("[demo-business] 已订阅 " + topic + " servers=" + kafkaServers + " group=" + groupId);
            System.out.println("[demo-business] 下行 exchange=" + exchange
                    + (consistentHash ? " routingKey=deviceId（一致哈希）" : " routingKey=" + routingKey)
                    + " 节流=" + throttleSeconds + "s，等待上行……（Ctrl-C 退出）");
            while (true) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofSeconds(1));
                } catch (WakeupException e) {
                    return;
                }
                for (ConsumerRecord<String, String> record : records) {
                    handle(record, exchange, routingKey, consistentHash, throttleSeconds,
                            lastSentAt, rabbitTemplate, received, sent);
                }
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } finally {
            consumer.close();
            connectionFactory.destroy();
        }
    }

    /**
     * 单条上行：解析 -> 打印 -> 节流下发。
     */
    private static void handle(ConsumerRecord<String, String> record, String exchange, String routingKey,
                               boolean consistentHash, long throttleSeconds, Map<String, Long> lastSentAt,
                               RabbitTemplate rabbitTemplate, AtomicLong received, AtomicLong sent) {
        try {
            Map<String, Object> body = JsonUtil.parseMap(record.value());
            Object deviceValue = body.get("deviceId");
            String deviceId = deviceValue == null ? "" : deviceValue.toString();
            if (deviceId.isEmpty()) {
                System.err.println("[demo-business] 缺少 deviceId，跳过 offset=" + record.offset());
                return;
            }
            Object protocolValue = body.get("protocolType");
            String protocolType = protocolValue == null ? "" : protocolValue.toString();
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = body.get("payload") instanceof Map
                    ? (Map<String, Object>) body.get("payload") : new HashMap<>();
            long timestamp = body.get("timestamp") instanceof Number number
                    ? number.longValue() : System.currentTimeMillis();
            UplinkMessage message = new UplinkMessage(deviceId, protocolType, payload, timestamp);
            System.out.printf("[demo-business] #%d device=%s protocol=%s payload=%s%n",
                    received.incrementAndGet(), message.getDeviceId(), message.getProtocolType(),
                    message.getPayload());

            long now = System.currentTimeMillis();
            long previous = lastSentAt.getOrDefault(deviceId, 0L);
            if (now - previous < Duration.ofSeconds(throttleSeconds).toMillis()) {
                return;
            }
            lastSentAt.put(deviceId, now);

            Map<String, Object> params = new HashMap<>();
            params.put("value", 30);
            String requestId = UUID.randomUUID().toString();
            DownlinkCommand command = new DownlinkCommand(deviceId, protocolType,
                    DEFAULT_DOWNLINK_COMMAND, params, requestId);
            String effectiveRoutingKey = consistentHash ? deviceId : routingKey;
            rabbitTemplate.convertAndSend(exchange, effectiveRoutingKey, command);
            System.out.printf("[demo-business] -> 下发 %s device=%s routingKey=%s requestId=%s%n",
                    DEFAULT_DOWNLINK_COMMAND, deviceId, effectiveRoutingKey, requestId);
            sent.incrementAndGet();
        } catch (RuntimeException e) {
            // 单条消息处理/投递失败不退出模拟进程，交由位点提交后的运维排查
            System.err.println("[demo-business] 处理失败 offset=" + record.offset() + " value=" + record.value()
                    + " cause=" + e.getMessage());
        }
    }

    private static String envOrDefault(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
