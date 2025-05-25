package com.jinelei.ducktype.sample.intf;

import com.jinelei.ducktype.annotation.DuckType;

import java.util.List;

@DuckType
public interface SampleInterface {
    void methodVoid();

    int methodInt();

    String methodString();

    List<String> methodList();

    String[] methodArray();
}
