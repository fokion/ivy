package xyz.fokion.ivy.core.engine;

import java.util.Collections;
import java.util.Map;

import xyz.fokion.ivy.core.expr.Scope;
import xyz.fokion.ivy.core.expr.Template;
import xyz.fokion.ivy.core.log.IvyLog;
import xyz.fokion.ivy.spi.StepContext;

/** The {@link StepContext} given to connectors: a view over the step's scope. */
final class DefaultStepContext implements StepContext {

    private final RunContext ctx;
    private final Scope scope;
    private final Map<String, Object> step;
    private final IvyLog log;

    DefaultStepContext(RunContext ctx, Scope scope, Map<String, Object> step, IvyLog log) {
        this.ctx = ctx;
        this.scope = scope;
        this.step = Collections.unmodifiableMap(step);
        this.log = log;
    }

    @Override
    public Map<String, Object> vars() {
        return scope.asMap();
    }

    @Override
    public String interpolate(String text) {
        return Template.has(text) ? Template.compile(text).renderString(scope) : text;
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
