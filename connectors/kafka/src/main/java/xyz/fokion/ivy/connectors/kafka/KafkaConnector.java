package xyz.fokion.ivy.connectors.kafka;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;

import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.DefaultAssertionsProvider;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;
import xyz.fokion.ivy.spi.util.Json;
import xyz.fokion.ivy.spi.util.LazyJson;

/**
 * Produces messages to, or consumes messages from, Kafka topics.
 * <p>
 * Result: {@code messages[]} ({@code topic}, {@code partition}, {@code offset}, {@code key},
 * {@code headers}, {@code value}: parsed when it is JSON, else the text, and {@code raw}: the
 * text), {@code durationMs} and {@code error}. Failures to produce or consume are reported in
 * {@code error}, checked by the default assertion.
 */
@ConnectorClass(type = "kafka", configurationClass = KafkaConfiguration.class)
public final class KafkaConnector implements Connector<KafkaConfiguration>, DefaultAssertionsProvider {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "messages", "the messages consumed: topic, partition, offset, key, value (parsed), raw, headers",
                "durationMs", "the duration in milliseconds",
                "error", "why the step failed, empty otherwise");
    }

    @Override
    public List<Object> defaultAssertions() {
        return List.of("isEmpty(result.error)");
    }

    private static Struct result(long durationMs, List<Object> messages, String error) {
        return Struct.builder("Result")
                .put("messages", messages)
                .put("durationMs", durationMs)
                .put("error", error)
                .build();
    }

    @Override
    public Object run(KafkaConfiguration config, StepContext context) throws Exception {
        long start = System.nanoTime();
        SchemaRegistry registry = config.withAvro() ? new SchemaRegistry(config.schemaRegistryAddr()) : null;
        List<Object> messages = new ArrayList<>();
        String err = "";
        try {
            if (config.clientType().equals("producer")) {
                produce(config, registry, context);
            } else {
                consume(config, registry, context, messages);
            }
        } catch (ConnectorException e) {
            err = e.getMessage();
            messages.clear();
        } catch (org.apache.kafka.common.KafkaException e) {
            err = e.getMessage() == null ? e.toString() : e.getMessage();
            messages.clear();
        }
        return result((System.nanoTime() - start) / 1_000_000, messages, err);
    }

    private static Properties clientProperties(KafkaConfiguration c) {
        Properties p = new Properties();
        p.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, String.join(",", c.addrs()));
        String protocol = c.withSasl() ? (c.withTls() ? "SASL_SSL" : "SASL_PLAINTEXT") : (c.withTls() ? "SSL" : "PLAINTEXT");
        p.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, protocol);
        if (c.withSasl()) {
            String module = c.saslMechanism().startsWith("SCRAM")
                    ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                    : "org.apache.kafka.common.security.plain.PlainLoginModule";
            p.put(SaslConfigs.SASL_MECHANISM, c.saslMechanism());
            p.put(SaslConfigs.SASL_JAAS_CONFIG, module + " required username=\"" + escape(c.user())
                    + "\" password=\"" + escape(c.password()) + "\";");
        }
        if (c.withTls() && c.insecureTls()) {
            p.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, "");
        }
        p.put(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        p.put(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG, "10000");
        p.putAll(c.properties());
        return p;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ------------------------------------------------------------ producer

    private static void produce(KafkaConfiguration c, SchemaRegistry registry, StepContext context) throws Exception {
        List<Map<String, Object>> messages = c.messages();
        Path workdir = context.workdir();
        if (!c.messagesFile().isEmpty()) {
            Object parsed = Json.parse(Files.readString(workdir.resolve(c.messagesFile())));
            if (!(parsed instanceof List<?> l)) {
                throw new ConnectorException("messagesFile must hold a JSON array of messages");
            }
            messages = new ArrayList<>();
            for (Object o : l) {
                messages.add(asMap(o));
            }
        }
        if (messages.isEmpty()) {
            throw new ConnectorException("Either one of `messages` or `messagesFile` field must be set");
        }
        Properties p = clientProperties(c);
        p.put(ProducerConfig.ACKS_CONFIG, "1");
        p.put(ProducerConfig.RETRIES_CONFIG, "10");
        p.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000");
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(p, new ByteArraySerializer(), new ByteArraySerializer())) {
            List<Future<RecordMetadata>> sent = new ArrayList<>();
            for (Map<String, Object> m : messages) {
                String topic = string(m.get("topic"));
                if (topic.isEmpty()) {
                    throw new ConnectorException("a message has no topic");
                }
                byte[] value = value(m, registry, workdir);
                ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topic, null,
                        string(m.get("key")).getBytes(StandardCharsets.UTF_8), value);
                for (Map.Entry<String, Object> h : asMap(m.get("headers")).entrySet()) {
                    record.headers().add(h.getKey(), string(h.getValue()).getBytes(StandardCharsets.UTF_8));
                }
                sent.add(producer.send(record));
            }
            for (Future<RecordMetadata> f : sent) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new ConnectorException(e.getCause().getMessage(), e.getCause());
                }
            }
            context.log(StepContext.Level.DEBUG, "produced " + sent.size() + " message(s)");
        }
    }

    private static byte[] value(Map<String, Object> m, SchemaRegistry registry, Path workdir)
            throws Exception {
        String raw;
        Object v = m.get("value");
        if (v != null && !"".equals(v)) {
            raw = v instanceof String s ? s : Json.write(v, Json.COMPACT);
        } else {
            String file = string(firstOf(m, "valueFile", "value_file"));
            if (file.isEmpty()) {
                throw new ConnectorException("a message needs a value or a valueFile");
            }
            raw = Files.readString(workdir.resolve(file));
        }
        if (registry == null) {
            return raw.getBytes(StandardCharsets.UTF_8);
        }
        // topic name strategy
        String subject = string(m.get("topic")) + "-value";
        String schemaFile = string(firstOf(m, "avroSchemaFile", "avro_schema_file")).strip();
        SchemaRegistry.Schema schema;
        if (!schemaFile.isEmpty()) {
            String text = Files.readString(workdir.resolve(schemaFile));
            schema = new SchemaRegistry.Schema(registry.register(subject, text), text);
        } else {
            schema = registry.latest(subject);
        }
        return AvroCodec.encode(raw, schema.schema(), schema.id());
    }

    // ------------------------------------------------------------ consumer

    private static void consume(KafkaConfiguration c, SchemaRegistry registry, StepContext context, List<Object> messages) {
        if (c.topics().isEmpty()) {
            throw new ConnectorException("You must provide topics");
        }
        Properties p = clientProperties(c);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, c.groupId());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, c.initialOffset().strip().equals("oldest") ? "earliest" : "latest");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // the step "timeout" also limits the whole step: stop consuming a little before
        long budget = (c.waitFor() > 0 ? c.waitFor() : c.timeout()) * 1000L;
        long deadline = System.currentTimeMillis() + Math.max(500, budget - 500);
        Map<TopicPartition, OffsetAndMetadata> consumed = new HashMap<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(p, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(c.topics());
            boolean limitReached = false;
            while (!limitReached && System.currentTimeMillis() < deadline) {
                long remaining = Math.clamp(deadline - System.currentTimeMillis(), 1, 500);
                for (ConsumerRecord<byte[], byte[]> r : consumer.poll(Duration.ofMillis(remaining))) {
                    String key = r.key() == null ? "" : new String(r.key(), StandardCharsets.UTF_8);
                    if (!c.keyFilter().isEmpty() && !key.equals(c.keyFilter())) {
                        context.log(StepContext.Level.INFO, "ignore message with key: " + key);
                        consumed.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                        continue;
                    }
                    String value = decodeValue(r.value(), registry);
                    Map<String, String> headers = new LinkedHashMap<>();
                    for (Header h : r.headers()) {
                        headers.put(h.key(), h.value() == null ? "" : new String(h.value(), StandardCharsets.UTF_8));
                    }
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("topic", r.topic());
                    message.put("partition", (long) r.partition());
                    message.put("offset", r.offset());
                    message.put("key", key);
                    message.put("headers", headers);
                    // parsed the first time a step reads into it
                    message.put("value", LazyJson.orText(value));
                    message.put("raw", value);
                    messages.add(message);
                    consumed.put(new TopicPartition(r.topic(), r.partition()), new OffsetAndMetadata(r.offset() + 1));
                    if (c.messageLimit() > 0 && messages.size() >= c.messageLimit()) {
                        context.log(StepContext.Level.INFO, "message limit reached");
                        limitReached = true;
                        break;
                    }
                }
            }
            if (!consumed.isEmpty()) {
                // the next consumer of the group starts after these messages
                consumer.commitSync(consumed);
            }
            if (!limitReached && c.waitFor() == 0) {
                throw new ConnectorException("kafka consume failed: no " + (c.messageLimit() > 0
                        ? c.messageLimit() + " message(s)" : "end of messages") + " within " + c.timeout()
                        + "s, got " + messages.size() + (c.messageLimit() > 0 ? "" : "; set messageLimit or waitFor"));
            }
        }
    }

    private static String decodeValue(byte[] value, SchemaRegistry registry) {
        if (value == null) {
            return "";
        }
        if (registry == null) {
            return new String(value, StandardCharsets.UTF_8);
        }
        int id = AvroCodec.schemaId(value);
        return AvroCodec.decode(value, registry.byId(id));
    }

    private static Object firstOf(Map<String, Object> m, String... keys) {
        for (String k : keys) {
            if (m.get(k) != null) {
                return m.get(k);
            }
        }
        return null;
    }

    private static String string(Object o) {
        return o == null ? "" : o instanceof String s ? s : o instanceof Map || o instanceof List
                ? Json.write(o, Json.COMPACT) : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o == null) {
            return Map.of();
        }
        if (!(o instanceof Map<?, ?> m)) {
            throw new ConnectorException("expected a map, got " + o);
        }
        return (Map<String, Object>) m;
    }
}
