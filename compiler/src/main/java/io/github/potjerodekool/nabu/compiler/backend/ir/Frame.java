package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.ArrayList;
import java.util.List;

public class Frame {

    // function result
    private static final Temp V0 = new Temp(Integer.MIN_VALUE);

    private final List<Local> locals = new ArrayList<>();

    public Frame(){
    }

    public int allocateLocal(final String name,
                             final IType type) {
        final var newLocal = new Local(name, type, locals.size());
        locals.add(newLocal);
        return newLocal.index();
    }

    public int indexOf(final String name) {
        return locals.stream()
                .filter(local -> local.name().equals(name))
                .mapToInt(Local::index)
                .findFirst()
                .orElse(-1);
    }

    public Local get(final int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException();
        }
        return locals.get(index);
    }

    public List<Local> getLocals() {
        return locals.stream()
                .toList();
    }

    public Temp rv() {
        return V0;
    }

}
