package io.github.potjerodekool.nabu.example;

import org.junit.jupiter.api.Test;
import io.github.potjerodekool.nabu.example.Loops;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LoopsTest {


    private void print(final List<Object> list) {
        for (var item : list) {
            if (item instanceof String s) {
                System.out.print(s);
            } else if (item instanceof List<?> subList) {
                print((List<Object>) subList);
            } else {
                throw new IllegalArgumentException();
            }
        }
    }

    @Test
    void forLoop() throws NoSuchMethodException {
        final var loops = new Loops();
        final var result = loops.forLoop(10);
        assertEquals(20, result);
        System.out.println("for loop result " + result);
    }

    @Test
    void forEachLoop() {
        final var loops = new Loops();
        final var result = loops.forEachLoop(List.of(10, 20, 30));
        assertEquals(60, result);
    }

    @Test
    void whileLoop() {
        final var loops = new Loops();
        final var result = loops.whileLoop(List.of(1,2,3,4));
        assertEquals(10, result);
    }

    @Test
    void doWhileLoop() {
        final var loops = new Loops();
        final var result = loops.doWhileLoop(3);
        assertEquals(15, result);
    }
}
