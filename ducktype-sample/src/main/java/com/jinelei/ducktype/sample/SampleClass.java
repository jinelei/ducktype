package com.jinelei.ducktype.sample;

import com.jinelei.ducktype.sample.intf.SampleInterface;


public class SampleClass {
    public String fetchName(String name) {
        return "fetch name: " + name;
    }

    public static void main(String[] args) {
        SampleClass instance = new SampleClass();
        System.out.printf("instance is implements SampleInterface: %b\n", instance instanceof SampleInterface);
        // instance.setName("hello name");
        // System.out.printf("get name: %s\n", instance.getName());
        System.out.printf("fetch name: %s\n", instance.fetchName("sss"));
    }
}
