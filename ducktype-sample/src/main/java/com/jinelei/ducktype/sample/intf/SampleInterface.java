package com.jinelei.ducktype.sample.intf;

import com.jinelei.ducktype.annotation.DuckType;

import java.util.List;

@DuckType
public interface SampleInterface {

    String fetchName(String name);

}
