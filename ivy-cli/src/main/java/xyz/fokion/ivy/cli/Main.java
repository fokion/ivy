package xyz.fokion.ivy.cli;

import java.nio.file.Path;

/**
 * Plain entry point, to run ivy on the JVM without Quarkus: {@code java -cp ... xyz.fokion.ivy.cli.Main run}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int code = new Cli(System.getenv(), System.out, System.err, Path.of("").toAbsolutePath(),
                Path.of(System.getProperty("user.home"))).run(args);
        System.out.flush();
        System.exit(code);
    }
}
