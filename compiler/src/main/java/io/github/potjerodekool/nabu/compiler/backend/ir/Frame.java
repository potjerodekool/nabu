package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;

import java.util.ArrayList;
import java.util.List;

public class Frame {

    // function result
    public static final int RV = Integer.MIN_VALUE;

    public static final Temp V0 = new Temp(RV);

    private final List<Local> locals = new ArrayList<>();

    private final Frame parentFrame;

    private final List<Frame> childFrames = new ArrayList<>();

    public Frame() {
        this(null);
    }

    private Frame(final Frame parentFrame) {
        this.parentFrame = parentFrame;
    }

    public Frame getParentFrame() {
        return parentFrame;
    }

    public Frame subFrame() {
        final var subFrame = new Frame(this);
        childFrames.add(subFrame);
        return subFrame;
    }

    public int allocateLocal(final String name,
                             final IType type,
                             final boolean parameter) {
        final var newLocal = new Local(name, type, generateIndex(), parameter);
        locals.add(newLocal);
        return newLocal.index();
    }

    private int generateIndex() {
        return totalLocals();
    }

    private int totalLocals() {
        if (parentFrame == null) {
            return locals.size();
        } else {
            return parentFrame.totalLocals() + locals.size();
        }
    }

    public int indexOf(final String name) {
        final var indexOptional = locals.stream()
                .filter(local -> local.name().equals(name))
                .mapToInt(Local::index)
                .findFirst();

        if (indexOptional.isPresent()) {
            return indexOptional.getAsInt();
        } else if (parentFrame != null) {
            return parentFrame.indexOf(name);
        } else {
            return -1;
        }
    }

    public Local get(final String name) {
        return get(indexOf(name));
    }

    public Local get(final int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException();
        }

        final var localOptional = locals.stream()
                .filter(it -> it.index() == index)
                .findFirst();

        if (localOptional.isPresent()) {
            return localOptional.get();
        } else if (parentFrame != null) {
            return parentFrame.get(index);
        } else {
            return null;
        }
    }

    public List<Local> getLocals() {
        return locals.stream()
                .toList();
    }

    public List<Local> getAllLocals() {
        final List<Local> allLocals = new ArrayList<>();
        allLocals.addAll(locals);
        allLocals.addAll(childFrames.stream()
                .flatMap(childFrame -> childFrame.getAllLocals().stream())
                .toList());
        return allLocals;
    }

    public Temp rv() {
        return V0;
    }

}
