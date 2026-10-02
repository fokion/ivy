package xyz.fokion.ivy.core.connectors;

import java.util.List;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;
import xyz.fokion.ivy.spi.ConnectorException;

public final class ExecConfiguration implements Configuration {

    private List<String> command = List.of();
    private String stdin;
    private String script;

    public List<String> getCommand() {
        return command;
    }

    @ConfigurationProperty(help = "the program and its arguments")
    public void setCommand(List<String> command) {
        this.command = command;
    }

    public String getStdin() {
        return stdin;
    }

    @ConfigurationProperty(help = "text written to the standard input")
    public void setStdin(String stdin) {
        this.stdin = stdin;
    }

    public String getScript() {
        return script;
    }

    @ConfigurationProperty(help = "a shell script; a #! first line selects the interpreter")
    public void setScript(String script) {
        this.script = script;
    }

    @Override
    public void validate() {
        boolean hasScript = script != null && !script.isEmpty();
        if (!hasScript && command.isEmpty()) {
            throw new ConnectorException("invalid command");
        }
        if (hasScript && !command.isEmpty()) {
            throw new ConnectorException("cannot use both 'script' and 'command'");
        }
    }
}
