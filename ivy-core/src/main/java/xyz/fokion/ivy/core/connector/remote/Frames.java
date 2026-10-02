package xyz.fokion.ivy.core.connector.remote;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import xyz.fokion.ivy.spi.util.Json;

/**
 * Messages of the connector protocol: a 4-byte big-endian length followed by a UTF-8 JSON
 * object.
 */
public final class Frames {

    /** Larger messages are refused, to bound memory on bad input. */
    public static final int MAX_FRAME = 64 * 1024 * 1024;

    private Frames() {
    }

    public static void write(DataOutputStream out, Map<String, Object> message) throws IOException {
        byte[] bytes = Json.write(message, Json.COMPACT).getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /** Reads one message, or returns {@code null} at the end of the stream. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> read(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length < 0 || length > MAX_FRAME) {
            throw new IOException("invalid frame length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        Object message = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        if (!(message instanceof Map<?, ?> m)) {
            throw new IOException("a frame must hold a JSON object");
        }
        return (Map<String, Object>) m;
    }
}
