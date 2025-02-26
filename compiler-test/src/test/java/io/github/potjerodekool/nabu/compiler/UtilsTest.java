package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.example.Utils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UtilsTest {

    @Test
    void isTrue() {
        //final var utils = new Utils;
        assertTrue(Utils.isTrue(true));
        assertFalse(Utils.isTrue(false));
    }

    @Test
    void isBiggerThenTenInt() {
        assertTrue(Utils.isBiggerThenTen(11));
        assertFalse(Utils.isBiggerThenTen(10));
        assertFalse(Utils.isBiggerThenTen(2));
        assertFalse(Utils.isBiggerThenTen(-10));
    }

    @Test
    void isBiggerThenTenByte() {
        assertTrue(Utils.isBiggerThenTen((byte) 11));
        assertFalse(Utils.isBiggerThenTen((byte) 10));
        assertFalse(Utils.isBiggerThenTen((byte) 2));
        assertFalse(Utils.isBiggerThenTen((byte) -10));
    }

    @Test
    public void isBiggerThen180() {
        assertTrue(Utils.isBiggerThen180(181));
        assertFalse(Utils.isBiggerThen180(180));
        assertFalse(Utils.isBiggerThen180(150));
        assertFalse(Utils.isBiggerThen180(-20));
    }

    @Test
    public void someMethods() {
        Utils.someMethod();
    }

    static int compareToTenInt(int value) {
        if (value < 10) {
            return -1;
        } else if (value > 10) {
            return 1;
        } else {
            return 0;
        }
    }
}
