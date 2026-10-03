package xyz.fokion.ivy.connectors.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.spi.ConnectorException;

class JsonDocumentsTest {

    @Test
    void splitsConcatenatedObjects() {
        assertEquals(List.of("{\"a\":\"}\"}", "{\n\"b\": {\"c\": 1}\n}"),
                JsonDocuments.split("{\"a\":\"}\"}\n{\n\"b\": {\"c\": 1}\n}\n"));
        assertThrows(ConnectorException.class, () -> JsonDocuments.split("[1]"));
        assertThrows(ConnectorException.class, () -> JsonDocuments.split("{\"a\": 1"));
    }
}
