package io.github.potjerodekool.nabu.ir;

import io.github.potjerodekool.nabu.ir.types.IRType;

import java.util.*;

public class IRModule {

    public final long flags;
    public final String name;
    private IRType superType;
    private List<IRType> interfaces;

    private final List<IRField> fields = new ArrayList<>();
    private final List<IRFunction>       functions = new ArrayList<>();
    private final Map<String, IRGlobal>  globals   = new LinkedHashMap<>();
    private String sourceFile = "<onbekend>";
    private String sourceDir  = ".";

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
            throw new IllegalStateException(
                "Globale variabele al gedefinieerd: " + global.name());
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

    public void setSourceFile(String file, String dir) {
        this.sourceFile = file;
        this.sourceDir  = dir;
    }

    public String sourceFile() { return sourceFile; }
    public String sourceDir()  { return sourceDir;  }


    public IRModule withFunctions(final List<IRFunction> functions) {
        final var newModule = new IRModule(this.name);
        newModule.fields.addAll(this.fields);
        newModule.functions.addAll(List.copyOf(functions));
        newModule.globals.putAll(this.globals);
        newModule.sourceFile = this.sourceFile;
        newModule.sourceDir = this.sourceDir;
        return newModule;
    }

    @Override
    public String toString() { return "module " + name; }

}
