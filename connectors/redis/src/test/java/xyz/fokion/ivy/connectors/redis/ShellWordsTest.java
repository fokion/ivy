package xyz.fokion.ivy.connectors.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.spi.ConnectorException;

class ShellWordsTest {

    @Test
    void splitsCommands() {
        assertEquals(List.of("SET", "k", "a value"), ShellWords.split("SET k \"a value\""));
        assertEquals(List.of("SET", "k", "it's"), ShellWords.split("SET k it\\'s"));
        assertEquals(List.of("SET", "k", "{\"a\":1}"), ShellWords.split("SET k '{\"a\":1}'"));
        assertEquals(List.of("SET", "k", ""), ShellWords.split("SET k ''"));
        assertThrows(ConnectorException.class, () -> ShellWords.split("SET k \"open"));
    }
}
