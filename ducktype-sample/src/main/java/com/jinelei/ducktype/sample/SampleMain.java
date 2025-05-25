package com.jinelei.ducktype.sample;

import com.jinelei.ducktype.sample.intf.SampleInterface;

public class SampleMain {
    public static void main(String[] args) {
        System.out.println("SampleClass is implements SampleInterface: %b".formatted(SampleInterface.class.isAssignableFrom(SampleClass.class)));
        System.out.println("SampleClass1 is implements SampleInterface: %b".formatted(SampleInterface.class.isAssignableFrom(SampleClass1.class)));
    }
}
