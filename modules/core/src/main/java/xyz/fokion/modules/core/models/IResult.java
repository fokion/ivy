package xyz.fokion.modules.core.models;


import java.util.Map;

public interface IResult {

    State getState();

    String output();

    Map<String,Object> extractedData();
}
