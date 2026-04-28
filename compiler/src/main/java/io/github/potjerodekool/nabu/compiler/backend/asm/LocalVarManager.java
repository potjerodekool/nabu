package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

import java.util.*;

public class LocalVarManager {
    private final Map<String, LocalVariable> varSlots = new HashMap<>();
    private final Set<String> stackValues = new HashSet<>();
    private final List<IRValue> stackItems = new ArrayList<>();

    private int nextSlot = 0;

    public LocalVarManager() {
    }

    public LocalVarManager(int nextSlot) {
        this.nextSlot = nextSlot;
    }

    public int allocateSlot(String name) {
        return allocateSlot(name, null);
    }

    public int allocateSlot(final String name,
                            final IRType type) {
        int slot = nextSlot++;
        varSlots.put(name, new LocalVariable(slot, type));
        return slot;
    }

    public int getSlot(String name) {
        final int index;

        if (hasSlot(name)) {
            index = varSlots.get(name).index();
        } else {
            index = -1;
        }

        if (index == -1) {
            throw new IllegalStateException("");
        }

        return index;
    }

    public int getOrCreateSlot(String name) {
        final var variable = getVar(name);

        if (variable != null) {
            return variable.index();
        }

        return allocateSlot(name);
    }

    public LocalVariable getOrCreateVar(String name) {
        getOrCreateSlot(name);
        return getVar(name);
    }

    public LocalVariable getVar(String name) {
        return varSlots.get(name);
    }

    public boolean hasSlot(String name) {
        return varSlots.containsKey(name);
    }

    public void setStackValue(final IRValue value) {
        if (value instanceof IRValue.Temp temp) {
            setStackValue(temp.name());
        } else {
            throw new IllegalArgumentException();
        }
        this.stackItems.add(value);
    }

    private void setStackValue(String value) {
        stackValues.add(value);
    }

    public boolean isOnStack(String value) {
        return stackValues.contains(value);
    }

}

