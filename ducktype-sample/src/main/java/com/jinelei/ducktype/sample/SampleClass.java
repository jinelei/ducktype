package com.jinelei.ducktype.sample;

import lombok.Data;

@Data
public class SampleClass {

    private String name;

    public String fetchName(String name) {
        return name;
    }

}
