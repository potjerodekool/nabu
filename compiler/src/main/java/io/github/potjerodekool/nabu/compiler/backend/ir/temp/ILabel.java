package io.github.potjerodekool.nabu.compiler.backend.ir.temp;

public class ILabel {

    private final String name;
    private static int cntr = 0;

    public ILabel() {
        this("L" + cntr++);
    }

    public ILabel(final String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    public static ILabel gen() {
        return generate("L");
    }

    private static ILabel generate(final String debugName) {
        return new ILabel(debugName + "_" + cntr++);
    }
}
