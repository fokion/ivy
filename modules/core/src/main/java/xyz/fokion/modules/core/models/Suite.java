package xyz.fokion.modules.core.models;

import java.util.List;

public record Suite(String name , String description , List<Case> testcases) {

}
