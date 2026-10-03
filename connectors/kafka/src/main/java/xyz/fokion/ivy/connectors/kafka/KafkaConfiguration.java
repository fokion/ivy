package xyz.fokion.ivy.connectors.kafka;

import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

/** A kafka step; keys may be written in camelCase or snake_case. */
public final class KafkaConfiguration implements Configuration {

    private List<String> addrs = List.of();
    private String clientType = "";
    private boolean withTls;
    private boolean insecureTls;
    private boolean withSasl;
    private String saslMechanism = "PLAIN";
    private String user = "";
    private String password = "";
    private Map<String, String> properties = Map.of();

    private List<Map<String, Object>> messages = List.of();
    private String messagesFile = "";
    private boolean withAvro;
    private String schemaRegistryAddr = "";

    private String groupId = "";
    private List<String> topics = List.of();
    private int timeout = 5;
    private int waitFor;
    private int messageLimit;
    private String initialOffset = "newest";
    private String keyFilter = "";

    @ConfigurationProperty(required = true, help = "bootstrap servers, host:port")
    public void setAddrs(List<String> addrs) {
        this.addrs = addrs;
    }

    @ConfigurationProperty(required = true, help = "producer or consumer")
    public void setClientType(String clientType) {
        this.clientType = clientType;
    }

    @ConfigurationProperty
    public void setWithTls(boolean withTls) {
        this.withTls = withTls;
    }

    @ConfigurationProperty(help = "skip the broker host name verification")
    public void setInsecureTls(boolean insecureTls) {
        this.insecureTls = insecureTls;
    }

    @ConfigurationProperty
    public void setWithSasl(boolean withSasl) {
        this.withSasl = withSasl;
    }

    @ConfigurationProperty(help = "PLAIN (default), SCRAM-SHA-256 or SCRAM-SHA-512")
    public void setSaslMechanism(String saslMechanism) {
        this.saslMechanism = saslMechanism;
    }

    @ConfigurationProperty
    public void setUser(String user) {
        this.user = user;
    }

    @ConfigurationProperty(secret = true)
    public void setPassword(String password) {
        this.password = password;
    }

    @ConfigurationProperty(help = "extra Kafka client properties, e.g. ssl.truststore.location")
    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    @ConfigurationProperty(help = "producer: topic, key, headers, value or valueFile, avroSchemaFile")
    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages;
    }

    @ConfigurationProperty(help = "producer: a JSON file holding the messages")
    public void setMessagesFile(String messagesFile) {
        this.messagesFile = messagesFile;
    }

    @ConfigurationProperty(help = "encode and decode values with Avro and the schema registry")
    public void setWithAvro(boolean withAvro) {
        this.withAvro = withAvro;
    }

    @ConfigurationProperty
    public void setSchemaRegistryAddr(String schemaRegistryAddr) {
        this.schemaRegistryAddr = schemaRegistryAddr;
    }

    @ConfigurationProperty(help = "consumer group, ivy by default")
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    @ConfigurationProperty
    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    @ConfigurationProperty(help = "consumer: seconds to reach messageLimit, 5 by default")
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @ConfigurationProperty(help = "consumer: seconds to collect messages, without failing")
    public void setWaitFor(int waitFor) {
        this.waitFor = waitFor;
    }

    @ConfigurationProperty(help = "consumer: stop after this many messages")
    public void setMessageLimit(int messageLimit) {
        this.messageLimit = messageLimit;
    }

    @ConfigurationProperty(help = "consumer: newest (default) or oldest")
    public void setInitialOffset(String initialOffset) {
        this.initialOffset = initialOffset;
    }

    @ConfigurationProperty(help = "consumer: only keep messages with this key")
    public void setKeyFilter(String keyFilter) {
        this.keyFilter = keyFilter;
    }

    @Override
    public void validate() {
        if (!clientType.equals("producer") && !clientType.equals("consumer")) {
            throw new ConnectorException("type must be a consumer or a producer");
        }
        if (timeout <= 0) {
            timeout = 5;
        }
        if (waitFor > 0 && timeout < waitFor) {
            throw new ConnectorException("can't wait for messages " + waitFor + "s longer than the timeout " + timeout + "s");
        }
        if (withAvro && schemaRegistryAddr.isEmpty()) {
            throw new ConnectorException("withAvro needs a schemaRegistryAddr");
        }
    }

    List<String> addrs() {
        return addrs;
    }

    String clientType() {
        return clientType;
    }

    boolean withTls() {
        return withTls;
    }

    boolean insecureTls() {
        return insecureTls;
    }

    boolean withSasl() {
        return withSasl;
    }

    String saslMechanism() {
        return saslMechanism;
    }

    String user() {
        return user;
    }

    String password() {
        return password;
    }

    Map<String, String> properties() {
        return properties;
    }

    List<Map<String, Object>> messages() {
        return messages;
    }

    String messagesFile() {
        return messagesFile;
    }

    boolean withAvro() {
        return withAvro;
    }

    String schemaRegistryAddr() {
        return schemaRegistryAddr;
    }

    String groupId() {
        return groupId.isEmpty() ? "ivy" : groupId;
    }

    List<String> topics() {
        return topics;
    }

    int timeout() {
        return timeout;
    }

    int waitFor() {
        return waitFor;
    }

    int messageLimit() {
        return messageLimit;
    }

    String initialOffset() {
        return initialOffset;
    }

    String keyFilter() {
        return keyFilter;
    }
}
