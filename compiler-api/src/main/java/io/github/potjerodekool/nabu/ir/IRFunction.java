package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IRFunction {

    public final String name;
    public final IRType returnType;
    public final List<IRValue> params;
    public final SourceLocation location;
    private final List<IRBasicBlock> blocks = new ArrayList<>();
    private boolean external = false;
    private final long flags;
    private final boolean isConstructor;

    public IRFunction(final String name,
                      final IRType returnType,
                      final List<IRValue> params,
                      final SourceLocation location,
                      final long flags) {
        this(name, returnType, params, location, flags, false);
    }

    public IRFunction(final String name,
                      final IRType returnType,
                      final List<IRValue> params,
                      final SourceLocation location,
                      final long flags,
                      final boolean isConstructor) {

        if (flags == 0) {
            //TODO Should have some flags set like access flags.
            //Add check and throw exeption here.
            System.err.println("Warning: Function " + name + " has no flags.");
        }

        this.name = name;
        this.returnType = returnType;
        this.params = List.copyOf(params);
        this.location = location;
        this.flags = flags;
        this.isConstructor = isConstructor;
    }

    public IRFunction withBlocks(final List<IRBasicBlock> blocks) {
        final var newFunction = new IRFunction(name, returnType, params, location, flags, isConstructor);
        blocks.forEach(newFunction::addBlock);
        return newFunction;
    }

    public boolean isConstructor() {
        return isConstructor;
    }

    public long getFlags() {
        return flags;
    }

    public List<IRBasicBlock> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    public void addBlock(IRBasicBlock block) {
        blocks.add(block);
    }

    public IRBasicBlock entryBlock() {
        if (blocks.isEmpty())
            throw new IllegalStateException("Functie '" + name + "' heeft geen blokken");
        return blocks.getFirst();
    }

    /**
     * Het functietype van deze functie (voor gebruik als FunctionRef).
     */
    public IRType.Function functionType() {
        List<IRType> paramTypes = params.stream()
                .map(IRValue::type)
                .toList();
        return new IRType.Function(returnType, paramTypes);
    }

    public void markExternal() {
        this.external = true;
    }

    public boolean isExternal() {
        return external;
    }

    @Override
    public String toString() {
        return "@" + name;
    }
}
