package com.jinelei.ducktype.sample;

import com.jinelei.ducktype.sample.intf.SampleInterface;

public class Main {

    public static void main(String[] args) {
        System.out.printf("SampleClass is instance of SampleInterface: %b\n", SampleInterface.class.isAssignableFrom(SampleClass.class));
        System.out.printf("SampleClass is instance of SampleInterface: %b\n", SampleClass.class.isAssignableFrom(SampleInterface.class));
    }
}
