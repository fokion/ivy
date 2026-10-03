package xyz.fokion.ivy.core.log;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import xyz.fokion.ivy.core.expr.Values;
import xyz.fokion.ivy.spi.util.Json;

/**
 * The secret values of a run, hidden wherever ivy writes text: the log, the console, every
 * report and the step dumps.
 * <p>
 * A value is secret when it is given with {@code --secret}, or when it is the value of a
 * variable whose name a suite lists under {@code secrets} (suite and test case variables, and
 * values captured by {@code set} or passed as executor inputs). Besides the value itself, its
 * base64, URL, JSON and XML encodings are hidden, so that it does not show through an
 * {@code Authorization} header or an escaped report.
 */
public final class Secrets {

    /** What a secret is replaced with. */
    public static final String MASK = "__hidden__";

    /** Secrets shorter than this hide too much text; they are hidden all the same, with a warning. */
    public static final int SHORT = 4;

    private final Set<String> names = ConcurrentHashMap.newKeySet();
    private final Set<String> raw = ConcurrentHashMap.newKeySet();
    /** every hidden form, longest first: a longer secret containing a shorter one is hidden whole */
    private volatile List<String> forms = List.of();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();

    /** Marks a variable name as secret: its values are hidden wherever they come from. */
    public void addName(String name) {
        if (name != null && !name.isBlank()) {
            names.add(name.strip());
        }
    }

    public void addNames(Collection<String> list) {
        list.forEach(this::addName);
    }

    public boolean isName(String name) {
        return names.contains(name);
    }

    public Set<String> names() {
        return Set.copyOf(names);
    }

    /** Whether a value is a known secret. */
    public boolean isSecret(Object value) {
        Object v = Values.unwrap(value);
        return v != null && raw.contains(Values.display(v));
    }

    /** Adds a secret value: a string, or every string and number inside a list or a map. */
    public void add(Object value) {
        Object v = Values.unwrap(value);
        switch (v) {
            case null -> {
            }
            case Map<?, ?> m -> m.values().forEach(this::add);
            case List<?> l -> l.forEach(this::add);
            default -> addText(Values.display(v));
        }
    }

    private synchronized void addText(String secret) {
        if (secret.isEmpty() || !raw.add(secret)) {
            return;
        }
        if (secret.length() < SHORT) {
            warnings.add("a secret is shorter than " + SHORT + " characters: every occurrence of it is hidden");
        }
        Set<String> all = new LinkedHashSet<>(forms);
        all.addAll(encodings(secret));
        List<String> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        forms = List.copyOf(sorted);
    }

    /** A secret and the forms it takes once encoded. */
    static Set<String> encodings(String secret) {
        Set<String> out = new LinkedHashSet<>();
        out.add(secret);
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        out.add(Base64.getEncoder().encodeToString(bytes));
        out.add(Base64.getEncoder().withoutPadding().encodeToString(bytes));
        out.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        out.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
        out.add(URLEncoder.encode(secret, StandardCharsets.UTF_8).replace("+", "%20"));
        String json = Json.quote(secret, true);
        out.add(json.substring(1, json.length() - 1));
        out.add(secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;"));
        out.add(secret.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&#34;")
                .replace("'", "&#39;"));
        out.removeIf(String::isEmpty);
        return out;
    }

    /** Hides HTTP basic authentication credentials: {@code base64(user:password)}. */
    public void addBasicAuth(String user, String password) {
        if (password != null && !password.isEmpty()) {
            addText(Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8)));
        }
    }

    /** The text with every secret replaced by {@link #MASK}. */
    public String hide(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        for (String form : forms) {
            if (s.contains(form)) {
                s = s.replace(form, MASK);
            }
        }
        return s;
    }

    public UnaryOperator<String> filter() {
        return this::hide;
    }

    /** Warnings about the secrets, such as very short values. */
    public Set<String> warnings() {
        return Set.copyOf(warnings);
    }

    public boolean isEmpty() {
        return forms.isEmpty();
    }
}
