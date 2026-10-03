package xyz.fokion.ivy.cli.mcp;

import java.util.Map;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.Connector;
import xyz.fokion.ivy.spi.ConnectorClass;
import xyz.fokion.ivy.spi.StepContext;

/** A test connector printing to {@code System.out}, as some drivers do. */
@ConnectorClass(type = "shout", configurationClass = ShoutConnector.ShoutConfiguration.class)
public final class ShoutConnector implements Connector<ShoutConnector.ShoutConfiguration> {

    public static final class ShoutConfiguration implements Configuration {
        private String text = "";

        public String getText() {
            return text;
        }

        @ConfigurationProperty(help = "what to print")
        public void setText(String text) {
            this.text = text;
        }
    }

    @Override
    public Object run(ShoutConfiguration configuration, StepContext context) {
        System.out.println("SHOUT " + configuration.getText());
        return Map.of("text", configuration.getText());
    }

    @Override
    public Map<String, String> resultFields() {
        return Connector.fields("text", "what was printed");
    }
}
