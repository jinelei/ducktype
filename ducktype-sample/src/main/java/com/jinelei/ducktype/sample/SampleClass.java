package com.jinelei.ducktype.sample;

import java.util.List;

public class SampleClass {
    public void methodVoid() {
    }

    public int methodInt() {
        return 0;
    }

    public String methodString() {
        return "";
    }

    public List<String> methodList() {
        return List.of();
    }

    public String[] methodArray() {
        return new String[0];
    }
}
