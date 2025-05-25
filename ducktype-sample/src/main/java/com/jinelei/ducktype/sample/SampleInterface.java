package com.jinelei.ducktype.sample;

import com.jinelei.ducktype.annotation.DuckType;

@DuckType
public interface SampleInterface {
    void execute();
    String getName();
}
