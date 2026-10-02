package xyz.fokion.ivy.core.engine;

import java.util.Collections;
import java.util.Map;

import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.spi.StepContext;

/** The {@link StepContext} given to connectors. */
final class DefaultStepContext implements StepContext {

    private final RunContext ctx;
    private final Map<String, Object> step;
    private final IvyLog log;

    DefaultStepContext(RunContext ctx, Map<String, Object> step, IvyLog log) {
        this.ctx = ctx;
        this.step = Collections.unmodifiableMap(step);
        this.log = log;
    }

    @Override
    public Map<String, String> vars() {
        return Collections.unmodifiableMap(ctx.vars());
    }

    @Override
    public Map<String, Object> step() {
        return step;
    }

    @Override
    public void log(Level level, String message) {
        log.log(IvyLog.Level.valueOf(level.name()), ctx.fields(), message);
    }
}
