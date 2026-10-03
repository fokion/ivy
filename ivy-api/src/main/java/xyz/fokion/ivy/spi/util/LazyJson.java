package xyz.fokion.ivy.spi.util;

/**
 * JSON text that is parsed the first time its value is read, then kept. Connectors use it for
 * payloads that steps may or may not look into (an HTTP body, a Kafka message), so that a large
 * payload is held once, as text, until an expression reads a path inside it.
 */
public final class LazyJson {

    private static final Object UNPARSED = new Object();

    private final String text;
    private final boolean textWhenInvalid;
    private volatile Object value = UNPARSED;

    private LazyJson(String text, boolean textWhenInvalid) {
        this.text = text;
        this.textWhenInvalid = textWhenInvalid;
    }

    /** The parsed value of {@code text}, or {@code null} when it is not JSON. */
    public static LazyJson of(String text) {
        return new LazyJson(text == null ? "" : text, false);
    }

    /** The parsed value of {@code text}, or the text itself when it is not JSON. */
    public static LazyJson orText(String text) {
        return new LazyJson(text == null ? "" : text, true);
    }

    public String text() {
        return text;
    }

    /** Whether the text has been parsed already. */
    public boolean isParsed() {
        return value != UNPARSED;
    }

    /** The parsed value; parsing happens once. */
    public Object value() {
        Object v = value;
        if (v == UNPARSED) {
            synchronized (this) {
                v = value;
                if (v == UNPARSED) {
                    v = parse();
                    value = v;
                }
            }
        }
        return v;
    }

    private Object parse() {
        String trimmed = text.strip();
        // only documents are parsed: "42" or "true" in a body stay text
        boolean document = trimmed.startsWith("{") || trimmed.startsWith("[");
        if (document) {
            try {
                return Json.parse(trimmed);
            } catch (Json.JsonException e) {
                // not JSON after all
            }
        }
        return textWhenInvalid ? text : null;
    }

    @Override
    public String toString() {
        return text;
    }
}
