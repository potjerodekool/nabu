package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.debug.SourceLocation;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;

import java.util.*;

public class IRFunction {

    public final String name;
    public final IRType returnType;
    public final List<IRValue> params;
    public final SourceLocation location;
    private final List<IRBasicBlock> blocks = new ArrayList<>();
    private boolean external = false;
    private final long flags;
    private final boolean isConstructor;
    private final List<CompoundAttribute> annotations = new ArrayList<>();
    private List<List<CompoundAttribute>> parameterAnnotations = List.of();
    private String genericSignature;
    private final List<LocalVar> localVariables = new ArrayList<>();
    private Map<String, String> allocaVersions = Map.of();

    public record LocalVar(String sourceName, IRType type, IRValue.Temp allocaTemp) {}

    public void addLocalVariable(LocalVar localVar) {
        localVariables.add(localVar);
    }

    public List<LocalVar> localVariables() {
        return Collections.unmodifiableList(localVariables);
    }

    public void setAllocaVersions(Map<String, String> versions) {
        this.allocaVersions = Map.copyOf(versions);
    }

    public Map<String, String> allocaVersions() {
        return allocaVersions;
    }

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
        newFunction.setAnnotations(this.annotations);
        newFunction.setParameterAnnotations(this.parameterAnnotations);
        newFunction.genericSignature = this.genericSignature;
        newFunction.localVariables.addAll(this.localVariables);
        newFunction.allocaVersions = this.allocaVersions;
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

    public void setAnnotations(final List<CompoundAttribute> annotations) {
        this.annotations.addAll(annotations);
    }

    public List<CompoundAttribute> annotations() {
        return Collections.unmodifiableList(annotations);
    }

    public void setParameterAnnotations(final List<List<CompoundAttribute>> parameterAnnotations) {
        this.parameterAnnotations = List.copyOf(parameterAnnotations);
    }

    public List<List<CompoundAttribute>> parameterAnnotations() {
        return parameterAnnotations;
    }

    public void setGenericSignature(String genericSignature) {
        this.genericSignature = genericSignature;
    }

    public String genericSignature() {
        return genericSignature;
    }

    @Override
    public String toString() {
        return "@" + name;
    }
}
