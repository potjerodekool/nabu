package io.github.potjerodekool.nabu.backend.ir;

import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.lang.Flags;
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
    private final List<IRAnnotation> bytecodeAnnotations = new ArrayList<>();
    private String sourceFile = "<onbekend>";
    private String sourceDir  = ".";
    private String genericSignature;
    /** De top-level klasse van de nest (JVMS 4.7.28), of null als dit de host is of er geen nest is. */
    private String nestHost;
    /** Voor een nest-host: de geneste klassen uit dezelfde compilatie-eenheid (JVMS 4.7.29). */
    private final List<String> nestMembers = new ArrayList<>();

    /** Het InnerClasses-attribuut (JVMS 4.7.6): de geneste klassen die deze klasse zelf, haar buitenklassen of haar ledenklassen betreffen. */
    private final List<InnerClass> innerClasses = new ArrayList<>();

    /**
     * Een regel van het InnerClasses-attribuut.
     *
     * @param name   Naam van de geneste klasse (met punten).
     * @param outer  Naam van de omsluitende klasse, of null voor een lokale of anonieme klasse.
     * @param inner  Eenvoudige naam, of null voor een anonieme klasse.
     * @param access De toegangsvlaggen van de geneste klasse (ACC_STATIC, ACC_PRIVATE enz.).
     */
    public record InnerClass(String name, String outer, String inner, int access) {
    }

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

    public void removeFunction(IRFunction fn) { functions.remove(fn); }

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

    /** Runtime-zichtbare annotaties van een klasse uit bytecode. */
    public void setBytecodeAnnotations(final List<IRAnnotation> annotations) {
        this.bytecodeAnnotations.addAll(annotations);
    }

    public List<IRAnnotation> bytecodeAnnotations() {
        return Collections.unmodifiableList(bytecodeAnnotations);
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
        newModule.bytecodeAnnotations.addAll(this.bytecodeAnnotations);
        newModule.superType = this.superType;
        newModule.interfaces = this.interfaces != null ? List.copyOf(this.interfaces) : null;
        newModule.genericSignature = this.genericSignature;
        newModule.sourceFile = this.sourceFile;
        newModule.sourceDir = this.sourceDir;
        newModule.sealedClass = this.sealedClass;
        newModule.permittedSubclasses = this.permittedSubclasses != null ? List.copyOf(this.permittedSubclasses) : null;
        newModule.nestHost = this.nestHost;
        newModule.nestMembers.addAll(this.nestMembers);
        newModule.innerClasses.addAll(this.innerClasses);
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
        linkInnerClasses(modules, byName);
    }

    /**
     * Vult het InnerClasses-attribuut van elke module: de eigen regel van een geneste klasse, die van haar
     * buitenklassen en die van haar directe ledenklassen. Zonder dat attribuut kent reflectie en Spring's
     * classpath-scanner (ASM-metadata, {@code innerAccessFlags}) een geneste klasse niet als zodanig.
     */
    private static void linkInnerClasses(final List<IRModule> modules,
                                         final Map<String, IRModule> byName) {
        final Map<String, List<IRModule>> children = new LinkedHashMap<>();
        for (final var module : modules) {
            if (module.nestHost != null) {
                children.computeIfAbsent(parentName(module), k -> new ArrayList<>()).add(module);
            }
        }

        for (final var module : modules) {
            final Map<String, InnerClass> entries = new LinkedHashMap<>();
            var current = module;
            while (current != null && current.nestHost != null) {
                entries.putIfAbsent(current.name, innerClassOf(current, byName));
                current = byName.get(parentName(current));
            }
            for (final var child : children.getOrDefault(module.name, List.of())) {
                entries.putIfAbsent(child.name, innerClassOf(child, byName));
            }
            module.innerClasses.clear();
            module.innerClasses.addAll(entries.values());
        }
    }

    private static String parentName(final IRModule module) {
        return module.name.substring(0, module.name.lastIndexOf('$'));
    }

    private static InnerClass innerClassOf(final IRModule module,
                                           final Map<String, IRModule> byName) {
        final var parentName = parentName(module);
        final var parent = byName.get(parentName);
        final var simpleName = module.name.substring(module.name.lastIndexOf('$') + 1);

        // `Outer$1` is anonymous and `Outer$1Local` is local: both have no outer class (JVMS 4.7.6).
        final boolean startsWithDigit = !simpleName.isEmpty() && Character.isDigit(simpleName.charAt(0));
        final String outer = startsWithDigit ? null : parentName;
        String inner = simpleName;
        if (startsWithDigit) {
            int i = 0;
            while (i < simpleName.length() && Character.isDigit(simpleName.charAt(i))) {
                i++;
            }
            inner = i < simpleName.length() ? simpleName.substring(i) : null;
        }

        return new InnerClass(module.name, outer, inner, innerAccess(module, parent, startsWithDigit));
    }

    private static final int ACC_PUBLIC = 0x0001;
    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_FINAL = 0x0010;
    private static final int ACC_INTERFACE = 0x0200;
    private static final int ACC_ABSTRACT = 0x0400;
    private static final int ACC_ANNOTATION = 0x2000;
    private static final int ACC_ENUM = 0x4000;

    /**
     * De inner_class_access_flags: de modifiers uit de broncode, plus wat de taal impliciet maakt (JLS 8.9, 8.10,
     * 9.5): geneste enums, records en interfaces zijn static en leden van een interface zijn public static.
     */
    private static int innerAccess(final IRModule module,
                                   final IRModule parent,
                                   final boolean localOrAnonymous) {
        final long flags = module.flags;
        int access = 0;
        if (Flags.hasFlag(flags, Flags.PUBLIC)) {
            access |= ACC_PUBLIC;
        }
        if (Flags.hasFlag(flags, Flags.PRIVATE)) {
            access |= ACC_PRIVATE;
        }
        if (Flags.hasFlag(flags, Flags.PROTECTED)) {
            access |= ACC_PROTECTED;
        }
        if (Flags.hasFlag(flags, Flags.STATIC)) {
            access |= ACC_STATIC;
        }
        if (Flags.hasFlag(flags, Flags.FINAL)) {
            access |= ACC_FINAL;
        }
        if (Flags.hasFlag(flags, Flags.ABSTRACT)) {
            access |= ACC_ABSTRACT;
        }
        if (Flags.hasFlag(flags, Flags.INTERFACE)) {
            access |= ACC_INTERFACE | ACC_ABSTRACT | ACC_STATIC;
            if (Flags.hasFlag(flags, Flags.ANNOTATION)) {
                access |= ACC_ANNOTATION;
            }
        }
        if (Flags.hasFlag(flags, Flags.ENUM)) {
            access |= ACC_ENUM | ACC_STATIC;
        }
        if (Flags.hasFlag(flags, Flags.RECORD)) {
            access |= ACC_STATIC | ACC_FINAL;
        }
        if (!localOrAnonymous && parent != null && Flags.hasFlag(parent.flags, Flags.INTERFACE)) {
            access |= ACC_PUBLIC | ACC_STATIC;
        }
        return access;
    }

    public List<InnerClass> innerClasses() {
        return Collections.unmodifiableList(innerClasses);
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
