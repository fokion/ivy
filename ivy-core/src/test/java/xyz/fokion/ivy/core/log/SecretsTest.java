package xyz.fokion.ivy.core.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SecretsTest {

    @Test
    void hidesValuesAndTheirEncodings() {
        Secrets s = new Secrets();
        s.add("p@ss \"word\"");
        assertEquals("pw: __hidden__", s.hide("pw: p@ss \"word\""));
        assertEquals("__hidden__", s.hide(Base64.getEncoder().encodeToString("p@ss \"word\"".getBytes(StandardCharsets.UTF_8))));
        assertEquals("q=__hidden__", s.hide("q=p%40ss+%22word%22"));
        assertEquals("{\"pw\":\"__hidden__\"}", s.hide("{\"pw\":\"p@ss \\\"word\\\"\"}"));
        assertEquals("<pw>__hidden__</pw>", s.hide("<pw>p@ss &quot;word&quot;</pw>"));
        assertEquals("nothing here", s.hide("nothing here"));
    }

    @Test
    void hidesTheLongestSecretFirst() {
        Secrets s = new Secrets();
        s.add("foo");
        s.add("foobar");
        assertEquals("__hidden__ and __hidden__", s.hide("foobar and foo"));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("shorter than 4")));
    }

    @Test
    void addsTheLeavesOfNestedValues() {
        Secrets s = new Secrets();
        s.add(Map.of("user", "ada-lovelace", "keys", List.of("k-1234", 98765L)));
        assertEquals("__hidden__ __hidden__ __hidden__", s.hide("ada-lovelace k-1234 98765"));
        assertTrue(s.isSecret("k-1234"));
        assertFalse(s.isSecret("other"));
    }

    @Test
    void hidesBasicAuthTokens() {
        Secrets s = new Secrets();
        s.addBasicAuth("ada", "s3cret");
        String token = Base64.getEncoder().encodeToString("ada:s3cret".getBytes(StandardCharsets.UTF_8));
        assertEquals("Authorization: Basic __hidden__", s.hide("Authorization: Basic " + token));
    }
}
