package xyz.fokion.ivy.core.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

class ConfigurationBinderTest {

    public static class Waiting implements Configuration {
        int timeout;
        long limit;

        @ConfigurationProperty
        public void setTimeout(int timeout) {
            this.timeout = timeout;
        }

        @ConfigurationProperty
        public void setLimit(long limit) {
            this.limit = limit;
        }
    }

    @Test
    void bindsWholeNumbersWrittenInAnyForm() {
        Waiting w = ConfigurationBinder.bind(Waiting.class, Map.of("timeout", 3.0, "limit", "7"));
        assertEquals(3, w.timeout);
        assertEquals(7, w.limit);
        assertEquals(2, ConfigurationBinder.bind(Waiting.class, Map.of("timeout", "2.0")).timeout);
    }

    @Test
    void refusesAFractionInsteadOfCuttingIt() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> ConfigurationBinder.bind(Waiting.class, Map.of("timeout", 0.5)));
        assertEquals("'timeout': 0.5 is not a whole number", e.getMessage());
        assertThrows(ConnectorException.class, () -> ConfigurationBinder.bind(Waiting.class, Map.of("limit", "1.5")));
    }
}
