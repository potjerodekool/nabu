package io.github.potjerodekool.nabu.compiler.backend.ir.temp;

public class Temp {

    private final int index;

    public Temp() {
        this.index = -1;
    }

    public Temp(final int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return "Temp(" + index + ")";
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof Temp other) {
            return index == other.index;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return index;
    }
}
