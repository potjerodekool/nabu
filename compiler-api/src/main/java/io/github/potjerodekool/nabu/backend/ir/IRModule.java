package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.lang.model.element.CompoundAttribute;

import java.util.*;

public class IRModule {

    public final long flags;
    public final String name;
    private IRType superType;
    private List<IRType> interfaces;
    private boolean sealedClass = false;
    private List<IRType> permittedSubclasses = new ArrayList<>();

    private final List<IRField> fields = new ArrayList<>();
    private final List<IRFunction>       functions = new ArrayList<>();
    private final Map<String, IRGlobal>  globals   = new LinkedHashMap<>();
    private final List<CompoundAttribute> annotations = new ArrayList<>();
    private String sourceFile = "<onbekend>";
    private String sourceDir  = ".";
    private String genericSignature;
    /** De top-level klasse van de nest (JVMS 4.7.28), of null als dit de host is of er geen nest is. */
    private String nestHost;
    /** Voor een nest-host: de geneste klassen uit dezelfde compilatie-eenheid (JVMS 4.7.29). */
    private final List<String> nestMembers = new ArrayList<>();

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
        newModule.sealedClass = this.sealedClass;
        newModule.permittedSubclasses = this.permittedSubclasses != null ? List.copyOf(this.permittedSubclasses) : null;
        newModule.nestHost = this.nestHost;
        newModule.nestMembers.addAll(this.nestMembers);
        return newModule;
    }

    public String nestHost() {
        return nestHost;
    }

    public void nestHost(final String nestHost) {
        this.nestHost = nestHost;
    }

    public List<String> nestMembers() {
        return Collections.unmodifiableList(nestMembers);
    }

    public void addNestMember(final String member) {
        this.nestMembers.add(member);
    }

    /**
     * Legt de nest-relaties vast tussen modules uit één compilatie-eenheid:
     * {@code a.Outer$Inner} en {@code a.Outer$1} horen bij host {@code a.Outer}.
     * Zonder NestHost/NestMembers gaf private toegang tussen geneste klassen
     * (een private methode of veld van de andere klasse) een
     * IllegalAccessError, sinds javac's nestmates (JEP 181) zonder synthetische
     * accessors.
     */
    public static void linkNests(final List<IRModule> modules) {
        final Map<String, IRModule> byName = new LinkedHashMap<>();
        for (final var module : modules) {
            byName.put(module.name, module);
        }
        for (final var module : modules) {
            final int lastDot = module.name.lastIndexOf('.');
            final int dollar = module.name.indexOf('$', lastDot + 1);
            if (dollar <= lastDot + 1) {
                continue;
            }
            final var host = byName.get(module.name.substring(0, dollar));
            if (host != null) {
                module.nestHost = host.name;
                host.nestMembers.add(module.name);
            }
        }
    }

    public boolean sealedClass() {
        return this.sealedClass;
    }

    public void sealedClass(final boolean sealedClass) {
        this.sealedClass = sealedClass;
    }

    public List<IRType> permittedSubclasses() {
        return Collections.unmodifiableList(permittedSubclasses);
    }

    public void addPermittedSubclass(final IRType type) {
        this.permittedSubclasses.add(type);
    }

}
