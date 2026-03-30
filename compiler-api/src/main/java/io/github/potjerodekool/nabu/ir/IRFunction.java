package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class IRFunction {

    public final String         name;
    public final IRType returnType;
    public final List<IRValue>  params;
    public final SourceLocation location;
    private final List<IRBasicBlock> blocks = new ArrayList<>();
    private boolean external = false;
    private final boolean isStatic;

    public IRFunction(String name,
                      IRType returnType,
                      List<IRValue> params,
                      SourceLocation location,
                      final boolean isStatic) {
        this.name       = name;
        this.returnType = returnType;
        this.params     = List.copyOf(params);
        this.location   = location;
        this.isStatic = isStatic;
    }

    public boolean isStatic() {
        return isStatic;
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

    /** Het functietype van deze functie (voor gebruik als FunctionRef). */
    public IRType.Function functionType() {
        List<IRType> paramTypes = params.stream()
                .map(IRValue::type)
                .toList();
        return new IRType.Function(returnType, paramTypes);
    }

    public void markExternal()   { this.external = true; }
    public boolean isExternal()  { return external; }

    @Override
    public String toString() { return "@" + name; }
}
