package com.jinelei.ducktype.sample;

import java.util.List;

public class SampleClass implements SampleInterface {
    @Override()
    public void methodVoid() {
    }

    @Override()
    public int methodInt() {
        return 0;
    }

    @Override()
    public String methodString() {
        return "";
    }

    @Override()
    public List<String> methodList() {
        return List.of();
    }

    @Override()
    public String[] methodArray() {
        return new String[0];
    }
}
