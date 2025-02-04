package xyz.fokion.modules.core.models;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface Step{
    String name();
    String type();
    CompletableFuture<Result> execute(Map<String,String> context);
    default Duration getTimeoutLimit(){
        return Duration.ofMinutes(5);
    }
}
