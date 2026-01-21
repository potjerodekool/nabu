package io.github.potjerodekool;

import java.util.HashMap;

public class Dog implements Animal {
    @Override
    public void talk() {
        System.out.println("Bark");

        final var map = new HashMap<String, Integer>();
        final var count = map.getOrDefault("test", 0) + 1;

    }
}
