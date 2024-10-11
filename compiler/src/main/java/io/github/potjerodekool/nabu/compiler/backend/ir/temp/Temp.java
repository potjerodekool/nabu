package io.github.potjerodekool.nabu.compiler.backend.ir.temp;

public class Temp {

    private final int index;

    public Temp() {
        this(-1);
    }

    public Temp(final int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

}
