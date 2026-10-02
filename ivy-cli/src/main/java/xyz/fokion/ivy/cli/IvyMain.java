package xyz.fokion.ivy.cli;

import java.nio.file.Path;

import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Quarkus entry point, only used to build the binary: the command line itself is {@link Cli}.
 */
@QuarkusMain
public class IvyMain implements QuarkusApplication {

    @Override
    public int run(String... args) {
        return new Cli(System.getenv(), System.out, System.err, Path.of("").toAbsolutePath(),
                Path.of(System.getProperty("user.home"))).run(args);
    }
}
