package com.jinelei.ducktype.sample;

import lombok.Data;

@Data
public class SampleClass {
    private String className;

    public void execute() {
        System.out.println("SampleClass is executing.");
    }

}
