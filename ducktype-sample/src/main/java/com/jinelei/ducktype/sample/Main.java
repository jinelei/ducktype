package com.jinelei.ducktype.sample;

public class Main {
    public static void main(String[] args) {
        System.out.println("Hello world!");
        System.out.println("SampleInterface is isAssignableFrom SampleClass: " + SampleInterface.class.isAssignableFrom(SampleClass.class));
    }
}
