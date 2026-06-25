package com.example.shop.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class Test {
    public static void main(String[] args) {
        CopyOnWriteArrayList<String> list = new CopyOnWriteArrayList<>(List.of("a", "b", "c"));
        for (String s : list) {
            list.remove(s); // no exception — iterating over a snapshot
        }
        System.out.println(list); // prints [b, c]

        List<String> arrayList = new ArrayList<>(List.of("a", "b", "c"));
        for (String s : arrayList) {
            arrayList.remove(s); // throws ConcurrentModificationException
        }
    }
}
