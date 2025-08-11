package io.github.potjerodekool.employee;

import java.util.List;

public abstract class Loopers {

    public int forLoop(int times) {
        int result = 0;

        for (int i = 0; i < times; i++) {
            result += 2;
        }
        return result;
    }

    public int forLoop2(int times,
                 final int add) {
        int result = 0;

        for (int i = 0; i < times; i++) {
            result += add;
        }
        return result;
    }

    public int forLoop3(int times) {
        int result = 0;

        for (int i = 0; i < times; i++)
            result += 2;
        return result;
    }

    public void empty(final int a) {
    }

    public int test() {
        int result = 0;
        return result;
    }

    abstract void hello(final int a);

    void blocks(final int a) {
        int z = 10;

        {
            int b = 10;
            b += 2;
        }
        {
            int d = 10;
            d += 2;

            {
                int e = 10;
                e = 2;
            }

            int x = 5;
            x += 2;
        }

        int c = 10;
        c++;
    }

    int forLoop(final List<Integer> list) {
        var result = 0;

        for(var number : list) {
            result += number;
        }

        return result;
    }
}
