package xyz.fokion.modules.core.models;

import java.util.Map;

public class StepContext<T extends Step> {
    private final String id;
    private final String name;
    private Map<String, String> context;
    private final T stepDefinition;
    private IResult result;
    public StepContext(String id, String name,  T stepDefinition) {
        this.id = id;
        this.name = name;
        this.stepDefinition = stepDefinition;
    }

    public T getStepDefinition() {
        return stepDefinition;
    }

    public void setResult(IResult result) {
        this.result = result;
    }

    public IResult getResult() {
        return result;
    }

    public Map<String, String> getContext() {
        return context;
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }
}
