package xyz.fokion.ivy.connectors.redis;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.commands.ProtocolCommand;
import redis.clients.jedis.exceptions.JedisException;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.ConnectorException;
import xyz.fokion.ivy.spi.StepContext;
import xyz.fokion.ivy.spi.Struct;

/**
 * Runs redis commands. Result: {@code commands[]} with {@code name}, {@code args} and
 * {@code response}; responses are strings, or lists for multi-bulk replies, and "" for nil.
 */
@ConnectorClass(type = "redis", configurationClass = RedisConfiguration.class)
public final class RedisConnector implements Connector<RedisConfiguration> {

    @Override
    public java.util.Map<String, String> resultFields() {
        return Connector.fields(
                "commands", "one entry per command: name, args, response");
    }

    @Override
    public Object run(RedisConfiguration config, StepContext context) throws Exception {
        String url = config.dialUrl();
        if (url.isEmpty()) {
            url = context.var("redis.dialURL") == null ? "" : context.var("redis.dialURL");
        }
        if (url.isEmpty()) {
            throw new ConnectorException("missing dialURL");
        }
        List<String> lines = config.commands();
        if (!config.path().isEmpty()) {
            lines = Files.readAllLines(context.workdir().resolve(config.path()));
        }
        List<Object> results = new ArrayList<>();
        try (Jedis jedis = new Jedis(URI.create(url))) {
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                List<String> words = ShellWords.split(line);
                String name = words.getFirst();
                List<String> args = words.subList(1, words.size());
                Object response;
                try {
                    ProtocolCommand command = () -> name.getBytes(StandardCharsets.UTF_8);
                    response = jedis.sendCommand(command, args.toArray(String[]::new));
                } catch (JedisException e) {
                    throw new ConnectorException("redis command " + name + " " + args + " failed: " + e.getMessage(), e);
                }
                results.add(Struct.builder("Command").put("name", name).put("args", new ArrayList<>(args))
                        .put("response", convert(response)).build());
            }
        }
        return Struct.of("Result", Map.of("commands", results));
    }

    private static Object convert(Object response) {
        return switch (response) {
            case null -> "";
            case byte[] b -> new String(b, StandardCharsets.UTF_8);
            case List<?> l -> {
                List<Object> out = new ArrayList<>();
                l.forEach(e -> out.add(convert(e)));
                yield out;
            }
            default -> String.valueOf(response);
        };
    }
}
