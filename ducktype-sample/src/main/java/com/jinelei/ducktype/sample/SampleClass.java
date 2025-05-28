package com.jinelei.ducktype.sample;

import com.jinelei.ducktype.annotation.AddField;
import lombok.Data;

@AddField
@Data
public class SampleClass {
    private String code;

    public void methodVoid() {
        System.out.println("SampleClass methodVoid");
    }
}
