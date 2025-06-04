package com.jinelei.ducktype.sample.intf;

import com.jinelei.ducktype.annotation.DuckType;

@DuckType
public interface SampleInterface {

    String fetchName(String name);

}
