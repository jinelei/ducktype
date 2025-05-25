package com.jinelei.ducktype.sample;

public class SampleMain {
    public static void main(String[] args) {
        System.out.println("SampleClass is instance of SampleInterface: %b".formatted(SampleInterface.class.isAssignableFrom(SampleClass.class)));
    }
}
