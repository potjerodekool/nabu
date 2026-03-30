package io.github.potjerodekool.nabu.ir;

import java.util.*;

public class IRModule {

    public final String name;

    private final List<IRFunction>       functions = new ArrayList<>();
    private final Map<String, IRGlobal>  globals   = new LinkedHashMap<>();
    private String sourceFile = "<onbekend>";
    private String sourceDir  = ".";

    public IRModule(String name) { this.name = name; }

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

    // -------------------------------------------------------
    // Bronbestand (voor debuginfo)
    // -------------------------------------------------------

    public void setSourceFile(String file, String dir) {
        this.sourceFile = file;
        this.sourceDir  = dir;
    }

    public String sourceFile() { return sourceFile; }
    public String sourceDir()  { return sourceDir;  }

    @Override
    public String toString() { return "module " + name; }
}
