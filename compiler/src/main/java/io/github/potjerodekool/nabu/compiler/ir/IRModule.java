package io.github.potjerodekool.nabu.compiler.ir;

import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.lang.model.element.CompoundAttribute;

import java.util.*;

public class IRModule {

    public final long flags;
    public final String name;
    private IRType superType;
    private List<IRType> interfaces;

    private final List<IRField> fields = new ArrayList<>();
    private final List<IRFunction>       functions = new ArrayList<>();
    private final Map<String, IRGlobal>  globals   = new LinkedHashMap<>();
    private final List<CompoundAttribute> annotations = new ArrayList<>();
    private String sourceFile = "<onbekend>";
    private String sourceDir  = ".";
    private String genericSignature;

    public IRModule(final String name) {
        this(0, name);
    }

    public IRModule(final long flags,
                    final String name) {
        this.flags = flags;
        this.name = name;
    }

    public void superType(final IRType superType) {
        this.superType = superType;
    }

    public IRType superType() {
        return this.superType;
    }

    public void interfaces(final List<IRType> interfaces) {
        this.interfaces = interfaces;
    }

    public List<IRType> interfaces() {
        if (this.interfaces == null) {
            return Collections.emptyList();
        }
        return this.interfaces;
    }

    public void emitField(final IRField field) {
        fields.add(field);
    }

    // -------------------------------------------------------
    // Functies
    // -------------------------------------------------------

    public void addFunction(IRFunction fn) { functions.add(fn); }

    public List<IRFunction> functions() {
        return Collections.unmodifiableList(functions);
    }

    public Optional<IRFunction> findFunction(String name) {
        return functions.stream().filter(f -> f.name.equals(name)).findFirst();
    }

    // -------------------------------------------------------
    // Globals
    // -------------------------------------------------------

    public void addGlobal(IRGlobal global) {
        if (globals.containsKey(global.name()))
            return;
        globals.put(global.name(), global);
    }

    public Map<String, IRGlobal> globals() {
        return Collections.unmodifiableMap(globals);
    }

    public Optional<IRGlobal> findGlobal(String name) {
        return Optional.ofNullable(globals.get(name));
    }

    public List<IRField> fields() {
        return fields;
    }

    // -------------------------------------------------------
    // Bronbestand (voor debuginfo)
    // -------------------------------------------------------

    public void setAnnotations(final List<CompoundAttribute> annotations) {
        this.annotations.addAll(annotations);
    }

    public List<CompoundAttribute> annotations() {
        return Collections.unmodifiableList(annotations);
    }

    public void setGenericSignature(String genericSignature) {
        this.genericSignature = genericSignature;
    }

    public String genericSignature() {
        return genericSignature;
    }

    public void setSourceFile(String file, String dir) {
        this.sourceFile = file;
        this.sourceDir  = dir;
    }

    public String sourceFile() { return sourceFile; }
    public String sourceDir()  { return sourceDir;  }


    public IRModule withFunctions(final List<IRFunction> functions) {
        final var newModule = new IRModule(this.flags, this.name);
        newModule.fields.addAll(this.fields);
        newModule.functions.addAll(List.copyOf(functions));
        newModule.globals.putAll(this.globals);
        newModule.annotations.addAll(this.annotations);
        newModule.superType = this.superType;
        newModule.interfaces = this.interfaces != null ? List.copyOf(this.interfaces) : null;
        newModule.genericSignature = this.genericSignature;
        newModule.sourceFile = this.sourceFile;
        newModule.sourceDir = this.sourceDir;
        return newModule;
    }

    @Override
    public String toString() { return "module " + name; }

}
