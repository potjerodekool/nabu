package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import org.bytedeco.llvm.LLVM.LLVMValueRef;

import io.github.potjerodekool.nabu.backend.ir.IRFunction;

import java.util.*;

/**
 * The state of the module that is being emitted, shared by every emitter that works on it: what the bodies create
 * once for the whole module (class-initialiser functions and flags, class slots, caches, declarations) and what
 * they look up by name. The per-function state stays in {@link InstructionEmitter} and {@link FunctionEmitter}.
 * Today there is one emitter of each, so one thread uses this; it is the part that a worker with its own context
 * would have to share or replace.
 */
final class ModuleEmitState {

    /** Name -> global found by the suffix scan in {@link #resolveValue}. */
    Map<String, LLVMValueRef>   suffixMatches = new HashMap<>();

    // Per call-site-shape (naam, parameters, resultaattype) gedeclareerde
    // externen voor signature-polymorfe methoden.
    Map<String, LLVMValueRef> polymorphicDeclarations = new HashMap<>();

    // Velden waarvan al gemeld is dat ze niet benaderbaar zijn (eenmalig
    // waarschuwen per veld).
    java.util.Set<String> warnedUnsupportedFields = new java.util.LinkedHashSet<>();

    /**
     * JVM names of the array types this module allocates, by index (index 0
     * is "unknown"). The index goes into the upper 24 bits of the array's
     * element-size word, so the runtime knows the class of an array
     * ({@code nabu_array_class}); LLVMModuleEmitter emits the table.
     */
    private Map<String, Integer> arrayTypeIds = new java.util.LinkedHashMap<>();

    /** The id of an array type, new ones numbered in order. Synchronized: parallel workers share one table. */
    int arrayTypeId(final String arrayClass) {
        synchronized (arrayTypeIds) {
            return arrayTypeIds.computeIfAbsent(arrayClass, k -> arrayTypeIds.size() + 1);
        }
    }

    List<String> arrayTypeNames() {
        synchronized (arrayTypeIds) {
            return new ArrayList<>(arrayTypeIds.keySet());
        }
    }

    /**
     * A secondary worker numbers the array types in the table of the primary one after the prelude (which gave both
     * the same table, checked here), so that an id means the same in every object file.
     */
    void shareArrayTypesWith(final ModuleEmitState primary) {
        if (!primary.arrayTypeNames().equals(arrayTypeNames())) {
            throw new IllegalStateException("The preludes of two workers numbered the array types differently");
        }
        this.arrayTypeIds = primary.arrayTypeIds;
    }

    /** Array types the C runtime allocates itself; always in the table so it can tag them. */
    private static final List<String> RUNTIME_ARRAY_TYPES = List.of(
            "[B", "[J", "[Ljava/lang/Object;", "[Ljava/lang/String;", "[Ljava/lang/Class;",
            "[Ljava/lang/reflect/Field;", "[Ljava/lang/reflect/Method;",
            "[Ljava/lang/reflect/Constructor;", "[Ljava/lang/reflect/Type;",
            "[Ljava/lang/reflect/TypeVariable;", "[Ljava/lang/annotation/Annotation;",
            "[[Ljava/lang/annotation/Annotation;");

    ModuleEmitState() {
        RUNTIME_ARRAY_TYPES.forEach(this::arrayTypeId);
    }

    /**
     * Symbols whose body some worker has taken, when several workers emit at once: a second IR function with the symbol
     * of one that was emitted must not get a body in another object file (duplicate definition). Null with one worker.
     */
    Set<String> claimedSymbols;

    int castCaches = 0;

    int instanceOfCaches = 0;

    /** The global that keeps the Class object of a class literal, by class name (see emitClassLiteral). */
    Map<String, LLVMValueRef> classLiteralSlots = new java.util.HashMap<>();

    /** The globals by every suffix that follows a '$', '.' or '_' of their key; built when a scan is first needed. */
    Map<String, LLVMValueRef> suffixIndex;

    int suffixIndexSize;

    /** Interne klassenaam -> de eenmalige initialisatie-functie, of null als er niets te doen is. */
    Map<String, LLVMValueRef> classInitFns = new HashMap<>();

    /** Internal class name → the state flag of its once function (2 when the class is initialized). */
    Map<String, LLVMValueRef> classInitFlags = new HashMap<>();

    /**
     * Namen die uitsluitend als ad-hoc extern zijn gedeclareerd (call zonder
     * eigen IRFunction-declaratie). Voor deze namen mag stap 2.5 in
     * {@link LLVMModuleEmitter} later een tweede, correctere paramlijst
     * toevoegen; voor echte IRFunction-namen mag dat niet, omdat dat de
     * werkelijk geëmitteerde functie zou vervangen door een lege stub.
     */
    Set<String> adHocExternalNames = new HashSet<>();

    /**
     * LLVM-functies van {@code <clinit>}-functies (statische
     * initializers). Deze worden nooit door de JVM-semantiek expliciet
     * aangeroepen; de native backend registreert ze als constructors in
     * {@code @llvm.global_ctors} zodat ze vóór {@code main} draaien.
     */
    List<LLVMValueRef> clinitFunctions = new ArrayList<>();

    /** The function declared for each IRFunction instance, whatever its name is shared with. */
    Map<IRFunction, LLVMValueRef> declared = new java.util.IdentityHashMap<>();
}
