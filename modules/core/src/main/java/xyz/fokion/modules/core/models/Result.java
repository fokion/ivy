package xyz.fokion.modules.core.models;

import java.util.Map;

public record Result(String output, State state, Map<String,Object> extractedData) implements IResult{

    public Result(String output){
        this(output,null,null);
    }

    @Override
    public State getState() {
        return state;
    }
}
