package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.Flags;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMBasicBlockRef;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTargetDataRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.lang.ref.Reference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Berekent en beheert het object-model van de IRModules die in één
 * compilatie worden ge-emitteerd.
 *
 * Elke klasse (IRModule) krijgt een LLVM-struct met:
 *   [0] nabu_object header  ({ ptr type, ptr vtable, ptr itable, i32 ref_count, i32 monitor, i32 thread })
 *   [1..] de instantie-velden — eerst die van de super-klasse (platte
 *         layout, dezelfde struct-indices als de super-klasse), daarna de
 *         eigen instantie-velden van de module.
 *
 * {@code IRValue.Named.fieldIndex()} (van IrGeneratingVisitor.computeFieldIndex) is een
 * teller over ALLE velden (statisch + instantie) van de declarerende klasse. Omdat een
 * sub-klasse de velden van haar super-klasse erft, moet een sub-class-struct de velden
 * van de super op dezelfde indices bevatten (velden worden altijd via de layout van de
 * DECLARERENDE klasse benaderd; zie InstructionEmitter.resolveObjectFieldPointer).
 *
 * Registratie doet twee passen:
 *   Fase A (registerStruct): struct-layout + nabu_type_info, ouders vóór kinderen
 *     (topologische volgorde via IRModule.superType()).
 *   Fase B (buildVtable/buildItable): vtable-slots — een override deelt het slot
 *     van de super-implementatie (cross-class override-slot-deling), zodat virtuele
 *     dispatch via een supertype-referentie de subclass-implementatie vindt.
 *
 * Classes zonder (geregistreerde) super-klasse blijven zelfstandig: geen super-velden,
 * super_type = null. Dat behoudt de oude single-module-compilatie.
 *
 * Wanneer een klasse een super-klasse heeft die NIET in deze compilatie zit (een
 * library-klasse zoals java/lang/Exception), wordt er een synthetische nabu_type_info
 * voor die super (én diens keten tot en met java/lang/Object) geëmitteerd, zodat
 * nabu_instanceof / nabu_can_catch over de super_type-keten kan matchen (bv. een zelf
 * gegooid subtype van Exception wordt gevangen door `catch (e : Exception)`).
 */
public class ClassLayouts {

    /**
     * Standaard library-hiërarchie voor type-info-synthese (bovenkant van andere
     * klassen). Elke hier NIET genoemde library-klasse viel vroeger direct terug op
     * "java/lang/Object" als super — ook als de ECHTE Java-hiërarchie via
     * RuntimeException/Exception/Throwable loopt. Voor een Nabu-gecompileerde klasse
     * die een library-exceptionklasse extend (bv. picocli's eigen
     * PicocliException/ParameterException extends RuntimeException, vendored en hier
     * dus als gewone Nabu-source meegecompileerd) betekende dat: de gesynthetiseerde
     * super-keten sprong van RuntimeException direct naar Object, zonder Exception/
     * Throwable ertussen. Een `catch (Exception e)`/`catch (Throwable t)` op zo'n
     * exception faalde daardoor stilzwijgend via nabu_instanceof (geen bezochte naam
     * matchte), en de exception liep tot een onbehandelde exception (abort). Deze
     * tabel vult de ontbrekende tussenschakels voor de gangbare java.lang/java.io/
     * java.util-exceptionhiërarchie aan; alles wat hier niet in staat valt — zoals
     * voorheen — terug op "java/lang/Object".
     */
    private static String librarySuperOf(String internalName) {
        if (internalName == null) return null;
        return switch (internalName) {
            case "java/lang/Throwable" -> "java/lang/Object";
            case "java/lang/Exception" -> "java/lang/Throwable";
            case "java/lang/Error" -> "java/lang/Throwable";
            case "java/lang/RuntimeException" -> "java/lang/Exception";
            case "java/lang/IndexOutOfBoundsException" -> "java/lang/RuntimeException";
            case "java/lang/ArrayIndexOutOfBoundsException" -> "java/lang/IndexOutOfBoundsException";
            case "java/lang/StringIndexOutOfBoundsException" -> "java/lang/IndexOutOfBoundsException";
            case "java/lang/IllegalArgumentException" -> "java/lang/RuntimeException";
            case "java/lang/NumberFormatException" -> "java/lang/IllegalArgumentException";
            case "java/lang/IllegalStateException" -> "java/lang/RuntimeException";
            case "java/lang/NullPointerException" -> "java/lang/RuntimeException";
            case "java/lang/ClassCastException" -> "java/lang/RuntimeException";
            case "java/lang/UnsupportedOperationException" -> "java/lang/RuntimeException";
            case "java/lang/ArithmeticException" -> "java/lang/RuntimeException";
            case "java/lang/NegativeArraySizeException" -> "java/lang/RuntimeException";
            case "java/lang/ArrayStoreException" -> "java/lang/RuntimeException";
            case "java/lang/ReflectiveOperationException" -> "java/lang/Exception";
            case "java/lang/ClassNotFoundException" -> "java/lang/ReflectiveOperationException";
            case "java/lang/NoSuchMethodException" -> "java/lang/ReflectiveOperationException";
            case "java/lang/NoSuchFieldException" -> "java/lang/ReflectiveOperationException";
            case "java/lang/InstantiationException" -> "java/lang/ReflectiveOperationException";
            case "java/lang/IllegalAccessException" -> "java/lang/ReflectiveOperationException";
            case "java/util/NoSuchElementException" -> "java/lang/RuntimeException";
            case "java/util/ConcurrentModificationException" -> "java/lang/RuntimeException";
            case "java/io/IOException" -> "java/lang/Exception";
            case "java/io/FileNotFoundException" -> "java/io/IOException";
            case "java/io/UncheckedIOException" -> "java/lang/RuntimeException";
            case "java/lang/Object" -> null;
            default -> "java/lang/Object"; // onbekende library-klasse: Object is de veilige bovenkant
        };
    }

    // Cache: interne naam -> gesynthetiseerde type-info global voor library-supers.
    private final Map<String, LLVMValueRef> librarySuperTypes = new java.util.HashMap<>();

    public static class ClassLayout {
        public final String internalName;
        public final LLVMTypeRef structType;
        public final Map<Integer, Integer> fieldIndexToStructIndex = new LinkedHashMap<>();
        public final List<LLVMTypeRef> instanceFieldTypes = new ArrayList<>();
        public final LLVMValueRef typeInfoGlobal;
        public LLVMValueRef vtableGlobal;
        /**
         * Of dit layout bij een interface hoort.
         *
         * <p>Een interface heeft geen klassen-vtable. De vtable van een interface
         * is leeg, maar erfde nog de slots van haar super, waardoor een
         * vtable-dispatch op een interface-receiver een willekeurige functie uit
         * een lege array laadde. Dispatch hoort daar via de itable te lopen.
         */
        public boolean isInterface;
        // full-method-naam (Owner_method) -> vtable-slot
        private final Map<String, Integer> virtualSlots = new LinkedHashMap<>();
        /**
         * Canonieke slotvolgorde: index i is de simple functienaam in slot i.
         *
         * <p>Niet uit {@link #virtualSlots} afleiden: die map bevat naast de
         * simple namen ook gekwalificeerde aliassen en overload-sleutels, waardoor
         * een super-klasse-slot per methode meer dan één keer in de vtable
         * terechtkwam en de slot-index van een eigen methode verschoof.
         */
        private final List<IRFunction> vtableOrder = new ArrayList<>();
        public LLVMValueRef itableGlobal;
        // interface-methode full-naam (Interface_m) -> itable-slot
        private final Map<String, Integer> itableSlots = new LinkedHashMap<>();

        ClassLayout(String internalName,
                    LLVMTypeRef structType,
                    LLVMValueRef typeInfoGlobal) {
            this.internalName = internalName;
            this.structType = structType;
            this.typeInfoGlobal = typeInfoGlobal;
        }

        /** Retourneert de struct-index voor een veld-index (inclusief header op 0), of -1. */
        public int structIndex(int fieldIndex) {
            Integer v = fieldIndexToStructIndex.get(fieldIndex);
            return v != null ? v : -1;
        }

        /** De functienaam die in {@code slot} staat volgens de canonieke volgorde. */
        public String vtableSlotFn(final int slot) {
            return slot >= 0 && slot < vtableOrder.size()
                    ? vtableOrder.get(slot).name : "?";
        }

        /** Retourneert het vtable-slot voor een methode (full name), of -1. */
        /** Slot van de parameterloze toString() (receiver-only), of -1. */
        public int toStringSlot() {
            for (int i = 0; i < vtableOrder.size(); i++) {
                final IRFunction f = vtableOrder.get(i);
                if (f.name.endsWith("_toString") && f.params.size() == 1) {
                    return i;
                }
            }
            return -1;
        }

        public int vtableSlot(String methodFullName) {
            Integer v = virtualSlots.get(methodFullName);
            return v != null ? v : -1;
        }

        /**
         * Vtable-slot voor een methode, met onderscheid tussen overloads.
         *
         * <p>Nodig omdat overloads dezelfde IR-functienaam delen: drie
         * {@code addSubcommand}-varianten krijgen ieder een eigen vtable-slot,
         * maar de slot-index bestond alleen per naam en bevatte daardoor alleen
         * de laatste. Een call naar de eerste variant belandde dan in de derde.
         */
        public int vtableSlot(String methodFullName, List<IRType> paramTypes) {
            if (paramTypes != null) {
                Integer v = virtualSlots.get(slotKey(methodFullName, paramTypes));
                if (v != null) return v;
                // Geen exacte sleutel: een call-site zonder descriptor (bv. een
                // `load i8*`) past op een overload met hetzelfde soort-profiel,
                // maar alleen als dat er precies één is.
                final int compatible = uniqueCompatibleSlot(methodFullName, paramTypes);
                if (compatible >= 0) return compatible;
                // Staan er onder deze naam wél overloads met een profiel in de
                // vtable, maar niet dit profiel, dan is dit een methode zonder
                // slot (bv. een private overload, die bytecode sinds Java 11
                // ook met invokevirtual aanroept). Het slot van de kale naam
                // is dan dat van een ANDERE overload: `String.split(String,
                // int, boolean)` riep zo via het slot van `split(String, int)`
                // zichzelf eindeloos aan. Geen slot = directe call.
                if (overloadedNames().contains(methodFullName)) return -1;
            }
            return vtableSlot(methodFullName);
        }

        /** Names with a profile key ({@code name#...}) in {@link #virtualSlots}. */
        private Set<String> overloadedNames = Set.of();
        private int overloadedNamesSize = -1;

        private Set<String> overloadedNames() {
            // virtualSlots only grows; rebuild when it did.
            if (overloadedNamesSize != virtualSlots.size()) {
                final Set<String> names = new HashSet<>();
                for (final String key : virtualSlots.keySet()) {
                    final int hash = key.indexOf('#');
                    if (hash >= 0) {
                        names.add(key.substring(0, hash));
                    }
                }
                overloadedNames = names;
                overloadedNamesSize = virtualSlots.size();
            }
            return overloadedNames;
        }

        /** Sleutel die een overload onderscheidt: naam + parameterprofiel. */
        private String slotKey(String methodFullName, List<IRType> paramTypes) {
            return virtualSlotKey(methodFullName, paramTypes);
        }

        /** Per naam: de parameterlijst en het slot van elke geregistreerde overload. */
        private final Map<String, List<Map.Entry<List<IRType>, Integer>>> overloadSlots = new HashMap<>();

        private void addOverload(String name, List<IRType> paramTypes, int slot) {
            overloadSlots.computeIfAbsent(name, k -> new ArrayList<>()).add(Map.entry(paramTypes, slot));
        }

        /**
         * Het slot van de enige overload onder {@code name} waarmee
         * {@code paramTypes} verenigbaar is (zelfde soort per parameter; waar
         * beide een descriptor hebben, dezelfde), of -1 bij geen of meerdere.
         */
        private int uniqueCompatibleSlot(String name, List<IRType> paramTypes) {
            final var overloads = overloadSlots.get(name);
            if (overloads == null) return -1;
            int found = -1;
            for (final var overload : overloads) {
                if (!compatibleParams(overload.getKey(), paramTypes)) continue;
                if (found >= 0 && found != overload.getValue()) return -1;
                found = overload.getValue();
            }
            return found;
        }

        private static boolean compatibleParams(List<IRType> declared, List<IRType> call) {
            if (declared.size() != call.size()) return false;
            for (int i = 0; i < declared.size(); i++) {
                final IRType d = declared.get(i);
                final IRType c = call.get(i);
                if (!typeProfile(d, false).equals(typeProfile(c, false))) return false;
                if (i > 0 && d instanceof IRType.Ptr dp && c instanceof IRType.Ptr cp
                        && dp.jvmDescriptor() != null && cp.jvmDescriptor() != null
                        && !dp.jvmDescriptor().equals(cp.jvmDescriptor())) {
                    return false;
                }
            }
            return true;
        }

        /** Retourneert het itable-slot voor een interface-methode (full name), of -1. */
        public int itableSlot(String interfaceMethodFullName, List<IRType> paramTypes) {
            Integer v = itableSlots.get(interfaceSlotKey(interfaceMethodFullName, paramTypes));
            return v != null ? v : -1;
        }
    }

    private final LLVMContextRef ctx;
    private final Map<String, LLVMValueRef> globalValueMap;
    // per normalized internal name
    private final Map<String, ClassLayout> byInternalName = new LinkedHashMap<>();
    // normalized internal name -> module (voor super-resolutie)
    private final Map<String, IRModule> modulesByInternalName = new LinkedHashMap<>();
    /** Call-target -> het target op de declarerende eigenaar (zie {@link #canonicalize}). */
    private final Map<String, String> declaringTargets = new HashMap<>();
    /** Interne klassenaam -> de lid-namen van de functies die de module declareert. */
    private final Map<String, Set<String>> memberNames = new HashMap<>();

    /**
     * Functie-resolutie-index: de IR gebruikt voor een gedeclareerde functie de
     * SIMPLE eigenaar als naam ({@code Owner_method}), terwijl elke call-site de
     * GEKWALIFICEerde eigenaar gebruikt ({@code package.Owner_method}). Zonder
     * deze index mist elke lookup van een klasse in een package — dat bracht
     * interface-dispatch (bv. picocli) op een {@code Interface-functie niet
     * gedeclareerd}-fout. Key = gekwalificeerde naam, value = de IRFunction zoals
     * die in de module gedeclareerd is (dus onder de simple-owner naam).
     */
    private final Map<String, IRFunction> functionsByQualifiedName = new LinkedHashMap<>();
    /**
     * Elke functie onder haar eigen IR-naam, de eerste in modulevolgorde: de
     * terugval van {@link #resolveFunction(String)}. Functies uit bytecode
     * ({@code picocli.CommandLine$Model$CommandSpec_resemblesOption}) staan
     * niet in {@link #functionsByQualifiedName}; zonder deze index doorzocht
     * elke lookup alle functies van alle modules, en buildItable deed dat per
     * slot per klasse (de picocli-build bleef daar een half uur in hangen).
     * Lui opgebouwd, na {@link #indexModules} leeggemaakt.
     */
    private Map<String, IRFunction> functionsByName;
    /**
     * Zelfde sleutels als {@link #functionsByQualifiedName}, maar dan alle
     * overloads per naam. Overloads delen de IR-functienaam (bv. drie
     * {@code CommandLine_init}-constructors), dus voor call-resolutie is de
     * parameterlijst nodig om de juiste variant te kiezen.
     */
    private final Map<String, List<IRFunction>> overloadsByName = new LinkedHashMap<>();
    /**Omgekeerd: gedeclareerde IRFunction -> gekwalificeerde naam. */
    private final Map<IRFunction, String> qualifiedNames = new java.util.IdentityHashMap<>();
    /**
     * Constructoren die dezelfde naam hebben als een methode in hun klasse
     * (bv. klasse {@code Impl} met methode {@code init} én constructor
     * {@code <init>} — beide heten {@code Impl_init}). Zonder onderscheid raakt
     * een vtable-slot naar de constructor. Key = de gedeclareerde naam, value =
     * het unieke LLVM-symbool voor de constructor.
     */
    private final Map<String, String> constructorSymbols = new LinkedHashMap<>();
    /**
     * Interface-methode (gekwalificeerde naam) -> itable-slot. De index is
     * GLOBAAL voor de hele compilatie: élke klasse bouwt haar itable in exact
     * deze volgorde, zodat een call via interface I (die alleen I's eigen
     * slot-index kent) altijd de implementatie van de dynamische klasse treft.
     */
    /**
     * Globale itable-slots, per interface-methode ÉN parameterlijst
     * ({@link FnKeys#fnKey}): overloads (Appendable.append(char) /
     * append(CharSequence) / append(CharSequence, int, int)) krijgen elk een
     * eigen slot. Op naam alleen deelden ze één slot, gevuld met een
     * willekeurige overload, en riep Formatter append(char) aan met een
     * CharSequence (onzin-tekst).
     */
    private final Map<String, Integer> interfaceSlots = new LinkedHashMap<>();
    /** Per slot-sleutel: de functienaam van de call-site en diens parameters. */
    private final Map<String, InterfaceSlotSig> interfaceSlotSigs = new HashMap<>();

    private record InterfaceSlotSig(String function, List<IRType> paramTypes) {
    }

    /** De slot-sleutel van een interface-aanroep: naam + parameterlijst. */
    static String interfaceSlotKey(final String function, final List<IRType> paramTypes) {
        return FnKeys.fnKey(function, paramTypes);
    }

    private String slotFunction(final String slotKey) {
        final InterfaceSlotSig sig = interfaceSlotSigs.get(slotKey);
        return sig != null ? sig.function() : slotKey;
    }

    private IRFunction resolveSlotFunction(final String slotKey) {
        final InterfaceSlotSig sig = interfaceSlotSigs.get(slotKey);
        return sig != null ? resolveFunction(sig.function(), sig.paramTypes()) : resolveFunction(slotKey);
    }
    /**
     * Interne namen van interfaces waarvan minstens één klasse in deze
     * compilatie (direct of via super-interface) de implementer is. Zonder
     * implementerende klasse is de itable van elke runtime-instantie leeg en
     * kan interface-dispatch alleen slagen als de call direct naar de
     * interface-functie valt (bv. annotatie-instanties uit de C-runtime).
     */
    private final java.util.Set<String> interfacesWithImplementers = new java.util.HashSet<>();
    /** Unieke, symbool-veilige naam voor de type-info/vtable/itable-globals. */
    private final Map<String, String> typeSymbols = new LinkedHashMap<>();

    private LLVMTypeRef headerType;

    /** Header-struct: type-pointer-slot. */
    public static final int HEADER_SLOT_TYPE   = 0;
    /** Header-struct: vtable-pointer-slot. */
    public static final int HEADER_SLOT_VTABLE = 1;
    /** Header-struct: itable-pointer-slot. */
    public static final int HEADER_SLOT_ITABLE = 2;
    /** Header-struct: referentieteller. */
    public static final int HEADER_SLOT_REF_COUNT = 3;

    public ClassLayouts(LLVMContextRef ctx, Map<String, LLVMValueRef> globalValueMap) {
        this.ctx = ctx;
        this.globalValueMap = globalValueMap;
    }

    public LLVMTypeRef objectHeaderType() {
        if (headerType == null) {
            // { ptr type, ptr vtable, ptr itable, i32 ref_count, i32 monitor, i32 thread }
            LLVMTypeRef[] header = {
                    LLVMPointerTypeInContext(ctx, 0),
                    LLVMPointerTypeInContext(ctx, 0),
                    LLVMPointerTypeInContext(ctx, 0),
                    LLVMInt32TypeInContext(ctx),
                    LLVMInt32TypeInContext(ctx),
                    LLVMInt32TypeInContext(ctx)
            };
            PointerPointer<Pointer> pp = new PointerPointer<>(header.length);
            for (int i = 0; i < header.length; i++) pp.put(i, header[i]);
            headerType = LLVMStructTypeInContext(ctx, pp, header.length, 0);
            Reference.reachabilityFence(pp);
        }
        return headerType;
    }

    /**
     * Indexeert de modules: naam-normalisatie, de functie-resolutie-index, de
     * ctor-symbool-ontscheiding en de globale itable-slotvolgorde.
     *
     * MOET vóór de functie-signaturen en vóór de late-externe declaraties
     * draaien: die stappen moeten weten of een call-target naar een functie
     * verwijst die in deze compilatie gedeclareerd is.
     */
    public void indexModules(List<IRModule> modules) {
        modulesByInternalName.clear();
        declaringTargets.clear();
        memberNames.clear();
        functionsByQualifiedName.clear();
        functionsByName = null;
        overloadsByName.clear();
        qualifiedNames.clear();
        constructorSymbols.clear();
        interfaceSlots.clear();
        interfaceSlotSigs.clear();

for (IRModule m : modules) {
            String internal = normalizeInternalName(m.name);
            if (internal == null) continue;
            modulesByInternalName.put(internal, m);

            // De frontend declareert met de SIMPLE eigenaar ('Outer$IHelp' ->
            // 'IHelp_value'), terwijl call-sites de gekwalificeerde naam gebruiken
            // — en die is in de puntjes-notatie van de module ('pkg.Outer$IHelp'),
            // terwijl descriptors slashes gebruiken. Daarom beide vormen.
            for (String owner : List.of(m.name, internal)) {
                String simpleOwner = simpleOwnerOf(owner);
                if (simpleOwner == null) continue;
                for (IRFunction fn : m.functions()) {
                    if (fn.name == null || !fn.name.startsWith(simpleOwner + "_")) continue;
                    String qualified = owner + "_" + fn.name.substring(simpleOwner.length() + 1);
                    functionsByQualifiedName.putIfAbsent(qualified, fn);
                    qualifiedNames.putIfAbsent(fn, qualified);
                    // De frontend schrijft call-sites met puntjes als underscores
                    // ('picocli.CommandLine' -> 'picocli_CommandLine_init', zie
                    // IrGeneratingVisitor: classMirror.getClassName().replace('.', '_')).
                    // Zonder deze gemanglede alias mist de lookup en valt een
                    // constructor-call terug op declareExternalConstructor, dat
                    // een 1-parameterige signatuur verzint — LLVM weigert de
                    // module dan met "Incorrect number of arguments".
                    functionsByQualifiedName.putIfAbsent(mangleName(qualified), fn);
                    indexOverload(qualified, fn);
                    indexOverload(mangleName(qualified), fn);
                    // Ook onder de basisnaam: de frontend geeft elke JVM-overload
                    // een eigen numerieke sleutel (`CommandLine_usage.656`), maar
                    // call-sites schrijven `CommandLine_usage`. Zonder deze
                    // extra indexering vond een call alleen de niet-gesuffixeerde
                    // variant en werden de echte overloads nooit overwogen.
                    indexOverload(stripOverloadKey(qualified), fn);
                    indexOverload(stripOverloadKey(mangleName(qualified)), fn);
                    // En in de puntjes-notatie, want dat is hoe de frontend de
                    // owner schrijft ('picocli.CommandLine_usage'). De
                    // intern-vorm geeft 'picocli/CommandLine_usage'; zonder deze
                    // alias mist elke gepakkte-klasse call zijn overloads en
                    // bindt de eerste (void) kandidaat.
                    String dotted = qualified.replace('/', '.');
                    functionsByQualifiedName.putIfAbsent(dotted, fn);
                    indexOverload(dotted, fn);
                    indexOverload(stripOverloadKey(dotted), fn);
                }
            }
            for (IRFunction fn : m.functions()) {
                if (fn.name == null) continue;
                if (!fn.name.contains(".")) {
                    // Handmatig gebouwde IR (tests) gebruikt de volledige naam
                    // zelf: 'test/Main' declareert 'test_Printable_print'.
                    functionsByQualifiedName.putIfAbsent(fn.name, fn);
                    qualifiedNames.putIfAbsent(fn, fn.name);
                    indexOverload(fn.name, fn);
                    indexOverload(stripOverloadKey(fn.name), fn);
                }
            }
        }

        indexConstructorSymbols();
        indexInterfaceSlots(modules);
    }

    /**
     * Registreert het vtable-slot van een overload ook onder een sleutel die het
     * parameterprofiel meeneemt, zodat calls naar een specifieke overload het
     * juiste slot vinden.
     */
    private static void registerSlot(final ClassLayout layout, final String name,
                                     final int slot, final List<IRType> paramTypes) {
        if (name == null || paramTypes == null) return;
        if (layout.virtualSlots.putIfAbsent(virtualSlotKey(name, paramTypes), slot) == null) {
            layout.addOverload(name, paramTypes, slot);
        }
    }

    /**
     * Sleutel voor een overload in de vtable: naam + parameterprofiel.
     *
     * <p>Overloads delen de IR-functienaam, dus alleen de naam onderscheidt ze
     * niet. Zonder deze sleutel delen drie {@code addSubcommand}-varianten één
     * slot-index en belandt een call in de verkeerde functiepointer.
     *
     * <p>Referentieparameters tellen met hun descriptor mee: picocli's
     * {@code arity(String)} en {@code arity(Range)} kregen anders allebei
     * {@code #PP}, en {@code arity(String)} riep via dat gedeelde slot
     * zichzelf aan met een Range. De receiver (positie 0) telt als kale
     * {@code P}: de declaratie beschrijft {@code this} met de eigen klasse,
     * een call-site met het statische type van de ontvanger.
     */
    static String virtualSlotKey(final String name, final List<IRType> paramTypes) {
        final var sb = new StringBuilder(name).append('#');
        for (int i = 0; i < paramTypes.size(); i++) {
            sb.append(typeProfile(paramTypes.get(i), i > 0));
        }
        return sb.toString();
    }

    private static String typeProfile(final IRType t, final boolean withDescriptor) {
        if (t == null) return "O";
        if (t instanceof IRType.Ptr p) {
            return withDescriptor && p.jvmDescriptor() != null ? "P" + p.jvmDescriptor() : "P";
        }
        if (t instanceof IRType.Int i) return "I" + i.bits();
        if (t instanceof IRType.Float f) return "F" + f.bits();
        if (t instanceof IRType.Bool) return "Z";
        if (t instanceof IRType.Void) return "V";
        return "O";
    }

    /**
     * Haalt de numerieke overload-sleutel van een functienaam af: de frontend
     * geeft elke JVM-overload een eigen teller (`CommandLine_usage.656`), terwijl
     * call-sites de kale naam gebruiken. Alleen een afsluitend `.` + cijfers
     * wordt afgehaald — de puntjes in `java.io.PrintStream_print` zijn
     * packagescheidingstekens en blijven staan.
     */
    static String stripOverloadKey(final String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return name;
        for (int i = dot + 1; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return name;
        }
        return name.substring(0, dot);
    }

    /**
     * Registreert een overload onder zijn naam, zonder duplicaten.
     */
    private void indexOverload(final String name, final IRFunction fn) {
        final var list = overloadsByName.computeIfAbsent(name,
                k -> new ArrayList<>());
        if (!list.contains(fn)) list.add(fn);
    }

    /**
     * Zet de puntjes-/slashnotatie om naar de underscore-vorm waarmee de
     * frontend call-sites schrijft: {@code pkg.Outer$Inner -> pkg_Outer$Inner}.
     */
    private static String mangleName(final String name) {
        final var sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            sb.append(c == '.' || c == '/' ? '_' : c);
        }
        return sb.toString();
    }

    /**
     * Een constructor die dezelfde IR-naam heeft als een methode van dezelfde
     * klasse krijgt een eigen symbool (`Impl_init$ctor`), zodat de gewone naam
     * altijd de methode blijft — anders zou een vtable-slot naar de constructor
     * wijzen. Overloads van methoden hebben elk hun eigen overload-sleutel en
     * raken elkaar niet.
     */
    private void indexConstructorSymbols() {
        for (IRModule module : modulesByInternalName.values()) {
            var nonConstructors = new java.util.HashSet<String>();
            for (IRFunction fn : module.functions()) {
                if (fn.name != null && !fn.isConstructor()) {
                    nonConstructors.add(fn.name);
                }
            }
            for (IRFunction fn : module.functions()) {
                if (fn.name != null && fn.isConstructor() && nonConstructors.contains(fn.name)) {
                    constructorSymbols.put(fn.name, fn.name + "$ctor");
                }
            }
        }
    }

    /**
     * Bepaalt de itable-slotvolgorde: elke interface-methode die in deze
     * compilatie via een interface-call wordt aangeroepen EN waarvan de
     * interface (dus de functie-declaratie) ook in deze compilatie zit.
     * Aanroepen van een niet-gecompileerde interface (bv. {@code
     * java.util.Iterator}) krijgen géén slot: die vallen terug op een directe
     * call, net als voorheen.
     */
    private void indexInterfaceSlots(List<IRModule> modules) {
        var callTargets = new java.util.TreeSet<String>();
        for (IRModule module : modules) {
            for (IRFunction fn : module.functions()) {
                if (fn.isExternal()) continue;
                for (var block : fn.blocks()) {
                    for (IRInstruction instr : block.instructions()) {
                        // The call as the emitter sees it: a method the interface only inherits
                        // (Stream.onClose, declared by BaseStream) is named after its declaring interface.
                        if (instr instanceof IRInstruction.Call raw
                                && raw.callKind() == CallKind.INTERFACE
                                && raw.function() != null) {
                            // Only a call that does not name a declared function is rewritten: the others keep their slot.
                            final IRInstruction.Call call = resolveFunction(raw.function()) != null
                                    ? raw : canonicalize(CallTargets.normalize(raw));
                            if (call.function() == null || resolveFunction(call.function()) == null) {
                                continue;
                            }
                            // A method the interface inherits from Object has a vtable slot, not an itable slot.
                            if (!call.function().equals(raw.function())) {
                                final IRModule declaring = modulesByInternalName.get(ownerInternalName(call.function()));
                                if (declaring == null || !Flags.hasFlag(declaring.flags, Flags.INTERFACE)) {
                                    continue;
                                }
                            }
                            final String key = interfaceSlotKey(call.function(), call.paramTypes());
                            callTargets.add(key);
                            interfaceSlotSigs.putIfAbsent(key,
                                    new InterfaceSlotSig(call.function(), call.paramTypes()));
                        }
                    }
                }
            }
        }
        int slot = 0;
        for (String target : callTargets) {
            interfaceSlots.put(target, slot++);
        }
    }

    /**
     * De IRFunction die bij een call-target hoort: de functie zoals die in de
     * module gedeclareerd is. accepteert zowel de gekwalificeerde naam (wat
     * call-sites gebruiken) als de gedeclareerde naam zelf.
     */
    /** Of er een IR-functie met deze naam, precies deze ene parameter en een body is. */
    public boolean hasDefinedFunction(final String name, final IRType param) {
        for (IRModule module : modulesByInternalName.values()) {
            for (IRFunction fn : module.functions()) {
                if (name.equals(fn.name) && fn.params.size() == 1
                        && fn.params.getFirst().type().equals(param) && !fn.blocks().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    public IRFunction resolveFunction(String callTarget) {
        if (callTarget == null) return null;
        IRFunction direct = functionsByQualifiedName.get(callTarget);
        if (direct != null) return direct;
        return functionsByName().get(callTarget);
    }

    private Map<String, IRFunction> functionsByName() {
        if (functionsByName == null) {
            functionsByName = new HashMap<>();
            for (IRModule module : modulesByInternalName.values()) {
                for (IRFunction fn : module.functions()) {
                    if (fn.name != null) functionsByName.putIfAbsent(fn.name, fn);
                }
            }
        }
        return functionsByName;
    }

    /**
     * De IRFunction die bij een call-target mét parameterlijst hoort.
     *
     * <p>Nodig omdat overloads dezelfde IR-functienaam delen: drie
     * {@code CommandLine_init}-constructors (2, 3 en 4 params) hebben alle drie
     * de naam {@code CommandLine_init}. {@link #resolveFunction(String)} geeft
     * dan de eerste, en een call met twee argumenten wordt geëmitteerd tegen
     * de vier-parameterige variant — LLVM weigert dat met "Incorrect number of
     * arguments passed to called function".
     *
     * <p>Er wordt eerst exact op het (parameter-)profiel gematcht; bij geen
     * unieke match valt de keuze terug op het aantal parameters en daarna op
     * de eerste kandidaat, zodat bestaand gedrag behouden blijft.
     */
    public IRFunction resolveFunction(final String callTarget,
                                      final List<IRType> paramTypes) {
        return resolveFunction(callTarget, paramTypes, null);
    }

    /**
     * Zelfde keuze, maar de call-site heeft ook een verwacht returntype. De IR
     * schrijft call-sites zonder overload-sleutel (het ASM-backend disambigeert
     * daar met de descriptor, LLVM niet), dus meerdere overloads kunnen dezelfde
     * pointer-parameterlijst hebben. Omdat `profile` alle pointers gelijkstelt,
     * was de eerste kandidaat winnend — bij `CommandLine.usage(StringBuilder,
     * Help)` (ret String) vs `CommandLine.usage(PrintWriter, ColorScheme)`
     * (ret void) levert dat een void-functie op, en dan een benoemde void-call,
     * parameter-typefouten en `ret void` in een ptr-functie. Daarom eerst
     * filteren op returntype; valt er niets weg, dan het oude gedrag.
     */
    public IRFunction resolveFunction(final String callTarget,
                                      final List<IRType> paramTypes,
                                      final IRType expectedReturnType) {
        final var candidates = resolveCandidates(callTarget);
        if (candidates.isEmpty()) return null;
        if (candidates.size() == 1) return candidates.getFirst();

        if (paramTypes != null) {
            final IRFunction mostSpecific = mostSpecificOverload(
                    candidates, paramTypes, expectedReturnType);
            if (mostSpecific != null) return mostSpecific;
            if (expectedReturnType != null) {
                for (final var candidate : candidates) {
                    if (sameParamProfile(candidate, paramTypes)
                            && sameReturnType(candidate, expectedReturnType)) {
                        return candidate;
                    }
                }
                for (final var candidate : candidates) {
                    if (sameParamKinds(candidate, paramTypes)
                            && sameReturnType(candidate, expectedReturnType)) {
                        return candidate;
                    }
                }
                for (final var candidate : candidates) {
                    if (candidate.params.size() == paramTypes.size()
                            && sameReturnType(candidate, expectedReturnType)) {
                        return candidate;
                    }
                }
            }
            for (final var candidate : candidates) {
                if (sameParamProfile(candidate, paramTypes)) return candidate;
            }
            for (final var candidate : candidates) {
                if (sameParamKinds(candidate, paramTypes)) return candidate;
            }
            for (final var candidate : candidates) {
                if (candidate.params.size() == paramTypes.size()) return candidate;
            }
        }
        return candidates.getFirst();
    }

    /**
     * De overload die qua parameter-descriptors het best bij de call-site
     * past, of null als geen enkele kandidaat op het pointer/primitief-profiel
     * past.
     *
     * <p>Het profiel stelt alle referenties gelijk, zodat de eerste kandidaat
     * won: {@code isNonDefault(String[], String[])} bond aan
     * {@code isNonDefault(Object, Object)} in plaats van aan
     * {@code isNonDefault(Object[], Object[])}, en riep daarna
     * {@code equals} aan op een array. Per parameter telt: exact dezelfde
     * descriptor het zwaarst, daarna array-op-array, daarna
     * niet-array-op-niet-array; een array-parameter tegenover een
     * niet-array-argument valt af.
     */
    private static IRFunction mostSpecificOverload(final List<IRFunction> candidates,
                                                   final List<IRType> paramTypes,
                                                   final IRType expectedReturnType) {
        IRFunction best = null;
        int bestScore = -1;
        boolean informative = false;
        for (final var candidate : candidates) {
            if (!sameParamProfile(candidate, paramTypes)) continue;
            if (expectedReturnType != null && !sameReturnType(candidate, expectedReturnType)) continue;
            int score = 0;
            boolean rejected = false;
            for (int i = 0; i < paramTypes.size() && !rejected; i++) {
                final String declared = descriptorOf(candidate.params.get(i).type());
                final String actual = descriptorOf(paramTypes.get(i));
                if (declared == null || actual == null) continue;
                informative = true;
                final boolean declaredArray = declared.startsWith("[");
                final boolean actualArray = actual.startsWith("[");
                if (declared.equals(actual)) score += 3;
                else if (declaredArray && actualArray) score += 2;
                else if (declaredArray) rejected = true;
                else if (!actualArray) score += 1;
            }
            if (!rejected && score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        // Zonder descriptors valt er niets te wegen: het oude gedrag kiest dan.
        return informative ? best : null;
    }

    private static String descriptorOf(final IRType type) {
        return type instanceof IRType.Ptr p ? p.jvmDescriptor() : null;
    }

    /** Of de returntype van {@code fn} past bij wat de call-site verwacht. */
    private static boolean sameReturnType(final IRFunction fn,
                                          final IRType expectedReturnType) {
        if (fn == null || expectedReturnType == null) return false;
        return profile(fn.returnType, expectedReturnType);
    }

    /** Alle IRFunctions die bij een call-target horen, in declaratievolgorde. */
    public List<IRFunction> resolveCandidates(final String callTarget) {
        if (callTarget == null) return List.of();
        final var found = overloadsByName.get(callTarget);
        if (found != null && !found.isEmpty()) return found;
        final var single = resolveFunction(callTarget);
        return single != null ? List.of(single) : List.of();
    }

    /**
     * Zwakker dan {@link #sameParamProfile}: referenties zijn onderling
     * uitwisselbaar, ongeacht hun descriptor. Een argument van een SUBTYPE
     * ({@code new LinkedHashSet(List)} tegen {@code LinkedHashSet(Collection)})
     * heeft een andere descriptor dan de parameter en miste zo het exacte
     * profiel; de arity-fallback koos dan de eerste overload met evenveel
     * parameters - hier {@code LinkedHashSet(int)}.
     */
    private static boolean sameParamKinds(final IRFunction fn,
                                          final List<IRType> paramTypes) {
        if (fn.params.size() != paramTypes.size()) return false;
        for (int i = 0; i < paramTypes.size(); i++) {
            final IRType declared = fn.params.get(i).type();
            final IRType actual = paramTypes.get(i);
            if (declared instanceof IRType.Ptr && actual instanceof IRType.Ptr) continue;
            if (!profile(declared, actual)) return false;
        }
        return true;
    }

    /** Vergelijkt de IR-parameters met de call-site-parameter-types. */
    private static boolean sameParamProfile(final IRFunction fn,
                                            final List<IRType> paramTypes) {
        if (fn.params.size() != paramTypes.size()) return false;
        for (int i = 0; i < paramTypes.size(); i++) {
            if (!profile(fn.params.get(i).type(), paramTypes.get(i))) return false;
        }
        return true;
    }

    /**
     * Vergelijkt twee IRTypes op het niveau dat voor overload-resolutie telt:
     * pointers zijn onderling gelijkwaardig (alle objecten zijn opaque i8*),
     * primitives worden op bitbreedte/kind vergeleken.
     */
    private static boolean profile(final IRType declared, final IRType actual) {
        if (declared == null || actual == null) return declared == actual;
        if (declared instanceof IRType.Ptr a && actual instanceof IRType.Ptr b) {
            // Referentietypes onderscheiden als de descriptor bekend is: anders
            // zijn `usage(StringBuilder, Help)` en `usage(PrintWriter,
            // ColorScheme)` ononderscheidbaar en wint de eerste kandidaat.
            if (a.jvmDescriptor() != null && b.jvmDescriptor() != null) {
                return a.jvmDescriptor().equals(b.jvmDescriptor());
            }
            return true;
        }
        if (declared instanceof IRType.Ptr || actual instanceof IRType.Ptr) {
            return declared instanceof IRType.Ptr && actual instanceof IRType.Ptr;
        }
        if (declared instanceof IRType.Int a && actual instanceof IRType.Int b) {
            return a.bits() == b.bits();
        }
        if (declared instanceof IRType.Float a && actual instanceof IRType.Float b) {
            return a.bits() == b.bits();
        }
        if (declared instanceof IRType.Bool) return actual instanceof IRType.Bool;
        return declared.equals(actual);
    }

    /** De gekwalificeerde naam van een functie, of null als die niet bekend is. */
    public String qualifiedName(IRFunction fn) {
        return fn == null ? null : qualifiedNames.get(fn);
    }

    /** De globale itable-slot van een interface-methode, of -1. */
    public int interfaceSlotIndex(String interfaceMethod, List<IRType> paramTypes) {
        Integer slot = interfaceSlots.get(interfaceSlotKey(interfaceMethod, paramTypes));
        if (slot == null) {
            slot = uniqueSlotByName(interfaceSlots, interfaceMethod);
        }
        return slot != null ? slot : -1;
    }

    /** Het enige slot van een methodenaam (één overload in gebruik), anders null. */
    private Integer uniqueSlotByName(final Map<String, Integer> slots, final String function) {
        Integer found = null;
        for (final Map.Entry<String, Integer> e : slots.entrySet()) {
            if (function.equals(slotFunction(e.getKey()))) {
                if (found != null) {
                    return null;
                }
                found = e.getValue();
            }
        }
        return found;
    }

    /** Het unieke symbool voor een constructor die met een methode botst, of null. */
    public String constructorSymbol(IRFunction fn) {
        if (fn == null || !fn.isConstructor() || fn.name == null) return null;
        return constructorSymbols.get(fn.name);
    }

    /** Het unieke symbool voor de constructor onder de (gebotste) naam. */
    public String constructorSymbolName(String declaredName) {
        return constructorSymbols.get(declaredName);
    }

    /** Een symbool dat veilig in een LLVM-globalnaam gebruikt kan worden. */
    String typeSymbol(String internalName) {
        return typeSymbols.computeIfAbsent(internalName, name -> {
            StringBuilder sb = new StringBuilder(name.length());
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                        || (c >= '0' && c <= '9') || c == '_' || c == '$';
                sb.append(ok ? c : '_');
            }
            return sb.toString();
        });
    }

    /**
     * Registreert de object-layouts voor alle modules en emitteert de bijbehorende
     * nabu_type_info global en vtable/itable. Ouders worden vóór kinderen
     * geregistreerd, zodat kinder-layouts de struct- en vtable-indices van de
     * super-klasse kunnen overnemen.
     */
    public void registerAll(LLVMModuleRef mod, List<IRModule> modules) {
        if (modulesByInternalName.isEmpty()) {
            indexModules(modules);
        }

        // Topologische volgorde: super eerst (alleen als de super in dezelfde
        // compilatie zit); niet-battene supers worden genegeerd.
        List<IRModule> order = topologicalOrder(modules);

        // Fase A: structs + type-info (ouders eerst)
        for (IRModule m : order) {
            registerStruct(mod, m);
        }
        // Fase B: vtables/itables (heeft alle structs nodig)
        for (IRModule m : order) {
            ClassLayout layout = byInternalName.get(normalizeInternalName(m.name));
            if (layout != null) {
                buildVtable(mod, m, layout);
                buildItable(mod, m, layout);
            }
        }
        // Fase C: welke interfaces hebben implementerende klassen in deze
        // compilatie? Doorslaggevend voor de dispatch-keuze in
        // InstructionEmitter.tryEmitInterfaceCall.
        collectInterfacesWithImplementers(order);
        buildLibraryItable(mod);
    }

    /** De itable voor library-objecten (zie {@link #buildLibraryItable}), of null. */
    private LLVMValueRef libraryItable;

    public LLVMValueRef libraryItable() {
        return libraryItable;
    }

    /**
     * Een tabel met de type-info van elke gecompileerde klasse (geen
     * interfaces), zodat de runtime een klasse op naam kan vinden — o.a. voor
     * {@code Class.getSuperclass()}, dat de super-keten van de klasse met díe
     * naam nodig heeft. Retourneert {@code {tabel, aantal}} of null als er geen
     * klassen zijn.
     */
    public LLVMValueRef[] buildTypeInfoTable(LLVMModuleRef mod) {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final List<LLVMValueRef> infos = new ArrayList<>();
        for (ClassLayout layout : byInternalName.values()) {
            if (!layout.isInterface && layout.typeInfoGlobal != null) {
                infos.add(layout.typeInfoGlobal);
            }
        }
        if (infos.isEmpty()) {
            return null;
        }
        final PointerPointer<Pointer> entries = new PointerPointer<>(infos.size());
        for (int i = 0; i < infos.size(); i++) {
            entries.put(i, infos.get(i));
        }
        final LLVMTypeRef type = LLVMArrayType(ptrType, infos.size());
        final LLVMValueRef table = LLVMAddGlobal(mod, type, new BytePointer("_ZNabuTypeInfoTable"));
        LLVMSetLinkage(table, LLVMInternalLinkage);
        LLVMSetGlobalConstant(table, 1);
        LLVMSetInitializer(table, LLVMConstArray(ptrType, entries, infos.size()));
        Reference.reachabilityFence(entries);
        return new LLVMValueRef[]{table,
                LLVMConstInt(LLVMInt32TypeInContext(ctx), infos.size(), 0)};
    }

    /**
     * De itable voor objecten die de C-runtime maakt (jcl-lite: Field, Method,
     * collecties, ...). Die hebben geen eigen itable, maar belanden wél achter
     * een interface-referentie: {@code AnnotatedElement.isAnnotationPresent} op
     * een runtime-Field laadde {@code itable[slot]} uit een NULL-itable. Elke slot
     * wijst hier naar de functie van de interface zelf — de C-implementatie uit
     * de runtime, de default-body of de abstracte stub — precies wat een directe
     * aanroep zou doen.
     */
    private void buildLibraryItable(LLVMModuleRef mod) {
        final LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        final int n = interfaceSlots.size();
        if (n == 0) {
            return;
        }
        final PointerPointer<Pointer> entries = new PointerPointer<>(n);
        for (Map.Entry<String, Integer> slot : interfaceSlots.entrySet()) {
            final IRFunction interfaceFn = resolveSlotFunction(slot.getKey());
            final LLVMValueRef value = interfaceFn != null ? functionValue(interfaceFn) : null;
            // Een abstracte interface-methode zonder body heeft alleen een
            // definitie als het een JDK-lid is (`pkg.Owner_member`): dat levert
            // de runtime, of het krijgt een nabu_unresolved-stub. Een abstracte
            // methode uit de eigen sources (`Greeter_greet`) niet — die slot
            // blijft leeg, anders faalt de link op een undefined reference.
            final boolean linkable = value != null && !value.isNull()
                    && (interfaceFn.isExternal() || !interfaceFn.blocks().isEmpty()
                        || LLVMGetValueName(value).getString().indexOf('.') >= 0);
            final LLVMValueRef fn = linkable ? value : null;
            entries.put(slot.getValue(), fn != null && !fn.isNull() ? fn : LLVMConstNull(ptrType));
        }
        final LLVMTypeRef type = LLVMArrayType(ptrType, n);
        final LLVMValueRef global = LLVMAddGlobal(mod, type, new BytePointer("_ZNabuLibraryEitable"));
        LLVMSetLinkage(global, LLVMInternalLinkage);
        LLVMSetGlobalConstant(global, 1);
        LLVMSetInitializer(global, LLVMConstArray(ptrType, entries, n));
        Reference.reachabilityFence(entries);
        libraryItable = global;
    }

    /**
     * Vult {@link #interfacesWithImplementers}: interfaces waarvan minstens
     * één niet-interface-module in de batch (of diens super-klasse) de
     * implementeert. Superinterfaces van zulke interfaces worden meegenomen
     * (een implementer van een subinterface implementeert de hele keten).
     */
    private void collectInterfacesWithImplementers(final List<IRModule> order) {
        final var owners = new java.util.LinkedHashSet<String>();
        for (final String interfaceMethod : interfaceSlots.keySet()) {
            owners.add(ownerOf(slotFunction(interfaceMethod)));
        }
        // Ook interfaces zonder eigen methoden (IScope extends IGetter, ISetter):
        // een call via zo'n statisch type gebruikt de itable alleen als de
        // interface implementers heeft.
        for (final IRModule m : order) {
            if (Flags.hasFlag(m.flags, Flags.INTERFACE)) {
                owners.add(m.name);
            }
        }
        for (final IRModule m : order) {
            if (Flags.hasFlag(m.flags, Flags.INTERFACE)) {
                continue;
            }
            final String name = normalizeInternalName(m.name);
            for (final String owner : owners) {
                if (implementsInterface(name, owner)) {
                    interfacesWithImplementers.add(normalizeInternalName(owner));
                }
            }
        }
        // Propageer naar superinterfaces (fixpoint; de interface-hiërarchie is
        // acyclisch, maar het aantal niveaus is niet vooraf bekend).
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final IRModule m : order) {
                if (!Flags.hasFlag(m.flags, Flags.INTERFACE)
                        || !interfacesWithImplementers.contains(normalizeInternalName(m.name))) {
                    continue;
                }
                for (final IRType ext : m.interfaces()) {
                    if (ext instanceof IRType.Ptr p && p.jvmDescriptor() != null) {
                        final String sup = normalizeInternalName(fromDescriptor(p.jvmDescriptor()));
                        if (modulesByInternalName.containsKey(sup)
                                && interfacesWithImplementers.add(sup)) {
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * Of er in deze compilatie een klasse bestaat die de interface implementeert
     * (direct of via super-interface). {@code false} betekent: itable-dispatch
     * kan op geen enkel batch-object slagen.
     */
    public boolean hasImplementers(final String interfaceInternalName) {
        return interfaceInternalName != null
                && interfacesWithImplementers.contains(normalizeInternalName(interfaceInternalName));
    }

    private List<IRModule> topologicalOrder(List<IRModule> modules) {
        List<IRModule> order = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        boolean progress;
        do {
            progress = false;
            for (IRModule m : modules) {
                String n = normalizeInternalName(m.name);
                if (placed.contains(n)) continue;
                String sup = superInternalName(n);
                if (sup == null
                        || !modulesByInternalName.containsKey(sup)
                        || placed.contains(sup)) {
                    order.add(m);
                    placed.add(n);
                    progress = true;
                }
            }
        } while (progress);
        // Cyclus-restant (mag niet voorkomen): resterende modules alsnog toevoegen
        for (IRModule m : modules) {
            if (!placed.contains(normalizeInternalName(m.name))) {
                order.add(m);
                placed.add(normalizeInternalName(m.name));
            }
        }
        return order;
    }

    /** Retourneert de normalized internal name van de directe super-klasse, of null. */
    public String superInternalName(String internalName) {
        IRModule m = modulesByInternalName.get(internalName);
        if (m != null && m.superType() == null
                && !Flags.hasFlag(m.flags, Flags.INTERFACE)
                && !"java/lang/Object".equals(internalName)
                && modulesByInternalName.containsKey("java/lang/Object")) {
            // De frontend laat een impliciete `extends Object` weg. Is Object
            // (uit bytecode) gecompileerd, dan erft de klasse diens vtable-
            // prefix: JDK-code roept equals/hashCode/toString aan via de
            // slots van Object. Zonder die prefix stond op slot 0 (equals) van
            // picocli's ArgSpec diens applyInitialValue, en riep HashMap die aan.
            return "java/lang/Object";
        }
        if (m == null || m.superType() == null || !(m.superType() instanceof IRType.Ptr p)
                || p.jvmDescriptor() == null) {
            return null;
        }
        String sup = fromDescriptor(p.jvmDescriptor());
        return modulesByInternalName.containsKey(sup) ? sup : null;
    }

    /**
     * De naam van hetzelfde lid (methode of statisch veld) in de directe
     * super-klasse: {@code pkg.Sub_member} -> {@code pkg.Super_member}, of
     * null als de eigenaar of zijn super-klasse niet in deze compilatie zit.
     *
     * <p>De eigenaar is de langste prefix vóór een {@code '_'} die een bekende
     * module is: lidnamen bevatten zelf ook underscores ({@code MAX_VALUE}).
     */
    public String superCallTarget(String callTarget) {
        int idx = ownerSplit(callTarget);
        if (idx < 0) return null;
        String sup = superInternalName(normalizeInternalName(callTarget.substring(0, idx)));
        return sup != null ? sup.replace('/', '.') + callTarget.substring(idx) : null;
    }

    /**
     * The same member on each interface the owner of {@code target} implements (also through its
     * superclasses): a static field can be inherited from an interface constant
     * ({@code AESCrypt.AES_KEYSIZES} comes from {@code AESConstants}).
     */
    public List<String> interfaceMemberTargets(final String target) {
        final int idx = ownerSplit(target);
        if (idx < 0) return List.of();
        final String owner = normalizeInternalName(target.substring(0, idx));
        final List<String> out = new ArrayList<>();
        for (final String itf : implementedInterfacesWithSupers(owner)) {
            out.add(itf.replace('/', '.') + target.substring(idx));
        }
        return out;
    }

    /**
     * Herschrijft een call naar een GEËRFD lid op de klasse of interface die
     * het lid daadwerkelijk declareert: bytecode (en de source-frontend) mag
     * als eigenaar het statische type van de receiver noemen
     * ({@code LinkedHashSet.add}, {@code List.stream}, {@code Map.getClass}),
     * terwijl de functie, het vtable-slot en het itable-slot onder de
     * declarerende eigenaar staan ({@code HashSet_add},
     * {@code Collection_stream}, {@code Object_getClass}). Zonder deze stap
     * vindt de dispatch geen slot en wordt het een directe call naar een
     * symbool dat nergens gedefinieerd is.
     */
    public IRInstruction.Call canonicalize(final IRInstruction.Call call) {
        final String fn = call.function();
        if (fn == null || fn.endsWith("_init") || fn.endsWith("_clinit")
                || fn.endsWith("_super")) {
            return call;
        }
        final String profile = profileOf(call.paramTypes());
        final String declaring = declaringTargets.computeIfAbsent(fn + "#" + profile,
                k -> findDeclaringTarget(fn, profile));
        if (declaring.equals(fn)) return call;
        return new IRInstruction.Call(
                call.callKind(), call.returnType(), call.paramTypes(),
                call.result(), declaring, call.args(), call.location());
    }

    /**
     * Het exacte parameterprofiel (met descriptors, zoals de overload-sleutel):
     * alleen het soort type is te grof, want {@code update(byte[])} en
     * {@code update(ByteBuffer)} zijn dan dezelfde overload.
     */
    private static String profileOf(final List<IRType> paramTypes) {
        return FnKeys.fnKey("", paramTypes);
    }

    private String findDeclaringTarget(final String callTarget, final String profile) {
        final int idx = ownerSplit(callTarget);
        if (idx < 0) return callTarget;
        final String owner = normalizeInternalName(callTarget.substring(0, idx));
        final String member = callTarget.substring(idx + 1);
        if (declaresMember(owner, member, profile)) return callTarget;

        // Eerst de super-klasse-keten (een concrete implementatie wint van
        // een interface-declaratie), daarna de interfaces breadth-first.
        final List<String> classes = new ArrayList<>();
        classes.add(owner);
        for (String sup = superInternalName(owner); sup != null && !classes.contains(sup);
             sup = superInternalName(sup)) {
            if (declaresMember(sup, member, profile)) return sup.replace('/', '.') + "_" + member;
            classes.add(sup);
        }
        final java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(classes);
        final Set<String> seen = new HashSet<>(classes);
        while (!pending.isEmpty()) {
            final IRModule module = modulesByInternalName.get(pending.poll());
            if (module == null) continue;
            for (final IRType implemented : module.interfaces()) {
                if (!(implemented instanceof IRType.Ptr p) || p.jvmDescriptor() == null) continue;
                final String itf = normalizeInternalName(fromDescriptor(p.jvmDescriptor()));
                if (!modulesByInternalName.containsKey(itf) || !seen.add(itf)) continue;
                if (declaresMember(itf, member, profile)) return itf.replace('/', '.') + "_" + member;
                pending.add(itf);
            }
        }
        // Een interface erft de publieke methoden van Object (JLS §9.2).
        if (declaresMember("java/lang/Object", member, profile)) {
            return "java.lang.Object_" + member;
        }
        return callTarget;
    }

    /**
     * Of de module {@code internalName} het lid {@code member} declareert.
     *
     * <p>Voor een module uit bytecode (functienamen beginnen met de
     * gekwalificeerde klassenaam) telt ook het parameterprofiel: declareert
     * de klasse wel {@code write(int)} maar niet {@code write(byte[])}, dan
     * hoort een aanroep van die laatste bij de voorouder. Voor source-modules
     * verschilt de naam- en parameterconventie per frontend-pad; daar is elke
     * suffix na een '_' een mogelijke lid-naam en telt alleen de naam. Een
     * vals "declareert zelf" laat de call ongemoeid; een vals "declareert
     * niet" zou hem naar een voorouder omleiden.
     */
    private boolean declaresMember(final String internalName, final String member,
                                   final String profile) {
        final IRModule module = modulesByInternalName.get(internalName);
        if (module == null) return false;
        final Set<String> names = memberNames.computeIfAbsent(internalName, k -> {
            final String qualified = internalName.replace('/', '.') + "_";
            final Set<String> found = new HashSet<>();
            for (final IRFunction fn : module.functions()) {
                if (fn.name == null) continue;
                if (fn.name.startsWith(qualified)) {
                    final String name = fn.name.substring(qualified.length());
                    found.add(name);
                    found.add(name + "#" + profileOf(FnKeys.declaredParamsOf(fn)));
                    if (fn.isExternal() && !fn.params.isEmpty()) {
                        // Een native methode is extern gedeclareerd met de
                        // receiver als gewone eerste parameter (`%arg0`), dus
                        // het profiel zonder die receiver telt ook.
                        found.add(name + "#" + profileOf(fn.params.stream()
                                .skip(1).map(IRValue::type).toList()));
                    }
                    found.add("#bytecode");
                    continue;
                }
                for (int i = fn.name.indexOf('_'); i >= 0; i = fn.name.indexOf('_', i + 1)) {
                    found.add(fn.name.substring(i + 1));
                }
            }
            return found;
        });
        if (!names.contains(member)) return false;
        return !names.contains("#bytecode") || names.contains(member + "#" + profile);
    }

    /**
     * De interne naam van de eigenaar van een lid-naam
     * ({@code pkg.Owner_member} -> {@code pkg/Owner}), of null als die
     * eigenaar niet in deze compilatie zit.
     */
    public String ownerInternalName(String callTarget) {
        int idx = ownerSplit(callTarget);
        return idx < 0 ? null : normalizeInternalName(callTarget.substring(0, idx));
    }

    /** Index van de {@code '_'} tussen eigenaar en lid, of -1. */
    private int ownerSplit(String callTarget) {
        if (callTarget == null) return -1;
        for (int idx = callTarget.lastIndexOf('_'); idx > 0;
             idx = callTarget.lastIndexOf('_', idx - 1)) {
            if (modulesByInternalName.containsKey(
                    normalizeInternalName(callTarget.substring(0, idx)))) {
                return idx;
            }
        }
        return -1;
    }

    /**
     * Fase A: registreert de struct-layout voor een module (super-velden eerst
     * op dezelfde indices als de super-klasse) en emitteert het nabu_type_info global.
     */
    private void registerStruct(LLVMModuleRef mod, IRModule module) {
        String internalName = normalizeInternalName(module.name);

        String superName = superInternalName(internalName);
        ClassLayout superLayout = superName != null ? byInternalName.get(superName) : null;

        // Instantie-velden (statisch overgeslagen in de struct)
        List<LLVMTypeRef> fields = new ArrayList<>();
        fields.add(objectHeaderType());

        int superInstanceCount = 0;
        if (superLayout != null) {
            superLayout.instanceFieldTypes.forEach(fields::add);
            superInstanceCount = superLayout.instanceFieldTypes.size();
        }

        int counter = 0;        // telt ALLE (eigen) velden (mimic computeFieldIndex)
        int structIdx = 1 + superInstanceCount; // struct-index na header + super-velden
        var indexMap = new LinkedHashMap<Integer, Integer>();

        for (IRField field : module.fields()) {
            boolean isStatic = Flags.hasFlag(field.flags(), Flags.STATIC);
            if (!isStatic) {
                indexMap.put(counter, structIdx);
                fields.add(mapFieldType(field.type()));
                structIdx++;
            }
            counter++;
        }

        LLVMTypeRef[] fieldArr = fields.toArray(new LLVMTypeRef[0]);
        PointerPointer<Pointer> pp = new PointerPointer<>(fieldArr.length);
        for (int i = 0; i < fieldArr.length; i++) pp.put(i, fieldArr[i]);
        LLVMTypeRef structType = LLVMStructTypeInContext(ctx, pp, fieldArr.length, 0);
        Reference.reachabilityFence(pp);

        // nabu_type_info global — super_type wijst naar de super-klasse in deze compilatie;
        // anders synthetiseren we type-info voor de library-super-keten (Exception → … → Object)
        // zó dat instanceof/can_catch over de super-keten matchen.
        LLVMValueRef superTypeInfo = superLayout != null ? superLayout.typeInfoGlobal : null;
        if (superTypeInfo == null && module.superType() instanceof IRType.Ptr superPtr
                && superPtr.jvmDescriptor() != null) {
            String superInternal = fromDescriptor(superPtr.jvmDescriptor());
            if (superInternal != null) {
                superTypeInfo = librarySuperTypeInfo(mod, superInternal);
            }
        }
        // Eigen instantievelden met hun byte-offset, voor
        // Unsafe.objectFieldOffset (bv. ConcurrentHashMap.SIZECTL) op elke
        // gecompileerde klasse — niet alleen de reflect-config-klassen.
        final List<Map.Entry<String, Long>> instanceFields = new ArrayList<>();
        {
            final LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
            int fieldCounter = 0;
            for (IRField field : module.fields()) {
                final Integer idx = indexMap.get(fieldCounter++);
                if (idx != null) {
                    instanceFields.add(Map.entry(field.name(),
                            LLVMOffsetOfElement(dl, structType, idx)));
                }
            }
            LLVMDisposeTargetData(dl);
        }
        LLVMValueRef typeInfo = emitTypeInfo(mod, internalName, structType, counter, module, superTypeInfo,
                instanceFields);

        ClassLayout layout = new ClassLayout(internalName, structType, typeInfo);
        // Een interface heeft geen klassen-vtable; dispatch loopt via de itable.
        layout.isInterface = Flags.hasFlag(module.flags, Flags.INTERFACE);
        layout.fieldIndexToStructIndex.putAll(indexMap);
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) layout.instanceFieldTypes.add(fields.get(i));
        }

        byInternalName.put(internalName, layout);
        if (module.name != null && !module.name.equals(internalName)) {
            byInternalName.put(module.name, layout);
        }
    }

    /**
     * Fase B: bouwt de vtable voor een klasse en registreert de slot-indices.
     *
     * Als de super-klasse (die in deze compilatie zit en al geregistreerd is)
     * virtuele methoden heeft, neemt de sub-klasse diens slots over:
     * <ul>
     *   <li>een OVERRIDE (zelfde methodenaam + parameterlijst) plaatst de eigen
     *       functie op het slot van de super-implementatie;</li>
     *   <li>eigen nieuwe virtuele methoden worden achter de super-slots
     *       toegevoegd.</li>
     * </ul>
     * Zo blijft een dispatch op de supertype-referentie werken voor elementen met
     * een subtype-tabel (cross-class override-slot-deling).
     */
private void buildVtable(LLVMModuleRef mod, IRModule module, ClassLayout layout) {
        if (Flags.hasFlag(module.flags, Flags.INTERFACE)) {
            // Een interface heeft geen instanties, dus ook geen vtable. Vooral
            // geen verwijzing naar de eigen abstracte methoden: die hebben geen
            // body en zouden als undefined symbol de link laten falen.
            LLVMValueRef empty = LLVMConstNull(LLVMArrayType(LLVMPointerTypeInContext(ctx, 0), 0));
            LLVMValueRef vtableGlobal = LLVMAddGlobal(mod, LLVMTypeOf(empty),
                    new BytePointer("_ZNabu" + typeSymbol(layout.internalName) + "Evtable"));
            LLVMSetLinkage(vtableGlobal, LLVMInternalLinkage);
            LLVMSetGlobalConstant(vtableGlobal, 1);
            LLVMSetInitializer(vtableGlobal, empty);
            Reference.reachabilityFence(empty);
            layout.vtableGlobal = vtableGlobal;
            globalValueMap.put("@_ZNabu" + typeSymbol(layout.internalName) + "Evtable", vtableGlobal);
            return;
        }

        // Kandidaten voor een override van een super-slot: ook final methoden.
        // `final` verbiedt alleen verdere overrides; de methode overschrijft
        // zelf wel degelijk haar ouder. Zonder deze kandidaten bleef bv. het
        // abstracte CharsetProvider.charsetForName in de vtable van
        // StandardCharsets staan (diens override is `public final`) en sprong
        // Charset.defaultCharset() naar een unreachable-stub.
        List<IRFunction> overrideCandidates =
                module.functions().stream()
                        .filter(f -> !Flags.hasFlag(f.getFlags(), Flags.STATIC))
                        .filter(f -> !Flags.hasFlag(f.getFlags(), Flags.PRIVATE))
                        .filter(f -> !f.isConstructor())
                        .filter(f -> !f.name.endsWith("_super"))
                        // Een native instantiemethode (Object.hashCode) is ook
                        // virtueel: zonder slot konden overrides als
                        // ArgSpec.hashCode niet via Object gedispatcht worden.
                        // De runtime (of een stub) levert het symbool.
                        .filter(f -> !f.isExternal()
                                || (Flags.hasFlag(f.getFlags(), Flags.NATIVE)
                                    && layout.internalName.equals(ownerInternalName(f.name))))
                        .sorted(java.util.Comparator.comparing(f -> f.name))
                        .toList();
        // Nieuwe slots alleen voor niet-final methoden (zoals voorheen).
        List<IRFunction> virtuals = overrideCandidates.stream()
                .filter(f -> !Flags.hasFlag(f.getFlags(), Flags.FINAL))
                .toList();

        String superName = superInternalName(layout.internalName);
        ClassLayout superLayout = superName != null ? byInternalName.get(superName) : null;

        // slot-order + bijbehorende functiepointer. De functie, niet de naam:
        // overloads delen de IR-functienaam, dus een vtable die per slot alleen
        // een naam bewaart, zette twee verschillende overloads op dezelfde
        // functiepointer (bv. stackTraceText 1- en 2-args). De call met de
        // andere parameterlijst kreeg dan een functie met een ander profiel.
        List<IRFunction> slotFns = new ArrayList<>();
        if (superLayout != null && !superLayout.vtableOrder.isEmpty()) {
            // Eerst de slots van de super-klasse (in canonieke volgorde),
            // overrides vervangen de functiepointer maar houden de slot-index.
            for (int i = 0; i < superLayout.vtableOrder.size(); i++) {
                IRFunction superFnRef = superLayout.vtableOrder.get(i);
                String superFn = superFnRef.name;
                IRFunction override = findOverride(overrideCandidates, superFnRef);
                IRFunction placed = override != null ? override : superFnRef;
                slotFns.add(placed);
                layout.vtableOrder.add(placed);
                layout.virtualSlots.put(placed.name, i);
                layout.virtualSlots.putIfAbsent(superFn, i);
                // Ook onder naam + parameterprofiel: de kale naam onderscheidt
                // de overloads van een geërfde methode niet (write(int) en
                // write(byte[]) delen 'OutputStream_write').
                registerSlot(layout, superFn, i,
                        superFnRef.params.stream().map(IRValue::type).toList());
                if (override != null) {
                    registerSlot(layout, override.name, i,
                            override.params.stream().map(IRValue::type).toList());
                }
            }
            // Daarna eigen nieuwe virtuele methoden (die de super niet heeft)
            // Sleutel = naam + parameterprofiel (virtualSlotKey), niet de kale
            // naam: anders deelt een tweede nieuwe overload met dezelfde naam
            // (bv. twee builder-overloads die geen van beide de super hebben)
            // stilzwijgend geen slot en valt een call terug op het slot van de
            // eerste overload -- een ABI-mismatch die als een willekeurige
            // functiepointer (of een vtable/constructor-adres) oplevert.
            Set<String> placed = new java.util.HashSet<>();
            for (IRFunction f : layout.vtableOrder) {
                placed.add(virtualSlotKey(f.name, f.params.stream().map(IRValue::type).toList()));
            }
            for (var v : virtuals) {
                String key = virtualSlotKey(v.name, v.params.stream().map(IRValue::type).toList());
                if (!placed.contains(key)) {
                    slotFns.add(v);
                    layout.vtableOrder.add(v);
                    layout.virtualSlots.put(v.name, slotFns.size() - 1);
                    registerSlot(layout, v.name, slotFns.size() - 1,
                            v.params.stream().map(IRValue::type).toList());
                    placed.add(key);
                }
            }
        } else {
            // Geen (geregistreerde) super: eigen gesorteerde virtuele methoden
            for (var v : virtuals) {
                slotFns.add(v);
                layout.vtableOrder.add(v);
                layout.virtualSlots.put(v.name, slotFns.size() - 1);
                registerSlot(layout, v.name, slotFns.size() - 1,
                        v.params.stream().map(IRValue::type).toList());
            }
        }

        // Elke virtuele methode ook onder zijn GEKWALIFICEERDE naam registreren:
        // call-sites (bv. 'package.Animal_getAge') zoeken het vtable-slot op die
        // naam, terwijl de declaratie met de simple owner is geschreven. Zonder
        // deze alias zou dispatch voor elke klasse in een package stilletjes
        // vervallen tot een directe call.
        for (var v : virtuals) {
            String qualified = qualifiedName(v);
            if (qualified != null) {
                // v's EIGEN slot via de overload-sleutel opzoeken, niet via de
                // kale naam (layout.virtualSlots.get(v.name)): die kale-naam-
                // entry is 'last write wins' over alle overloads (zie de
                // hoofdlus hierboven) en wijst dus voor een 0-arg overload
                // zomaar naar het slot van een andere overload met dezelfde
                // IR-naam. Dat registreerde de gekwalificeerde alias van de
                // getter onder het slot van de setter (en vice versa), en een
                // call via de gekwalificeerde naam -- wat elke cross-package
                // dispatch gebruikt -- trof dan de verkeerde functie.
                final List<IRType> vParams = v.params.stream().map(IRValue::type).toList();
                Integer slot = layout.virtualSlots.get(virtualSlotKey(v.name, vParams));
                if (slot == null) {
                    slot = layout.virtualSlots.get(v.name);
                }
                if (slot != null) {
                    layout.virtualSlots.putIfAbsent(qualified, slot);
                    registerSlot(layout, qualified, slot, vParams);
                }
            }
        }

        LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef vtableType = LLVMArrayType(ptrType, slotFns.size());

        String vtableName = "_ZNabu" + typeSymbol(layout.internalName) + "Evtable";
        LLVMValueRef vtableGlobal = LLVMAddGlobal(mod, vtableType, new BytePointer(vtableName));
        LLVMSetLinkage(vtableGlobal, LLVMInternalLinkage);
        LLVMSetGlobalConstant(vtableGlobal, 1);

        LLVMValueRef vtableInit;
        if (slotFns.isEmpty()) {
            vtableInit = LLVMConstNull(vtableType);
        } else {
            PointerPointer<Pointer> initArr = new PointerPointer<>(slotFns.size());
            for (int i = 0; i < slotFns.size(); i++) {
                IRFunction fn = slotFns.get(i);
                // Eerst de overload-sleutel: die is uniek per parameterlijst.
                // De kale naam dekt maar één van de overloads.
                // FnKeys.declaredParamsOf (zonder de impliciete receiver),
                // niet fn.params.stream()...type() (mét receiver): de
                // registratie in FunctionEmitter.declareSignature strippt
                // 'this' uit de overload-sleutel. Met receiver erin matchte
                // het slot van een 0-arg overload (bv. Builder.ansi())
                // toevallig de sleutel van een 1-arg overload (Builder.ansi
                // (Ansi)) met dezelfde IR-naam, en kregen beide vtable-slots
                // dezelfde (verkeerde) functiepointer.
                // Klasse-gekwalificeerde sleutel eerst: de kale overload-
                // sleutel ("Builder_build#") is GEEN garantie tegen botsingen
                // tussen klassen die de simple owner-naam delen (elke nested
                // Builder-klasse heet in de IR gewoon "Builder"). Zonder dit
                // kon de vtable-opbouw van klasse A de nog-ongedefinieerde
                // LLVM-functie van klasse B's gelijknamige slot vinden
                // (last-match via de kale sleutel) en daar, als B's eigen fn
                // toevallig GEEN blocks had, een unreachable-stub in planten
                // (emitAbstractSlotStub) — waarna B's ECHTE body in fase 4
                // als een los, onbereikbaar tweede blok bovenop kwam.
                final String qualified = qualifiedName(fn);
                LLVMValueRef fnRef = qualified != null
                        ? globalValueMap.get("@" + FnKeys.fnKey(qualified, FnKeys.declaredParamsOf(fn)))
                        : null;
                if (fnRef == null || fnRef.isNull()) {
                    fnRef = globalValueMap.get("@" + FnKeys.fnKey(
                            fn.name, FnKeys.declaredParamsOf(fn)));
                }
                if (fnRef == null || fnRef.isNull()) {
                    fnRef = functionValueByName(fn.name);
                }
                if (fnRef == null || fnRef.isNull())
                    throw new IllegalStateException(
                            "Vtable function not declared: " + fn.name);
                emitAbstractSlotStub(fn, fnRef);
                initArr.put(i, fnRef);
            }
            vtableInit = LLVMConstArray(ptrType, initArr, slotFns.size());
            Reference.reachabilityFence(initArr);
        }
        LLVMSetInitializer(vtableGlobal, vtableInit);
        Reference.reachabilityFence(vtableInit);

        layout.vtableGlobal = vtableGlobal;
        globalValueMap.put("@" + vtableName, vtableGlobal);
    }

    /**
     * Abstracte methode zonder body die op een vtable-slot staat: geef het
     * symbool een unreachable-stub, zodat de vtable-referentie daadwerkelijk
     * linkt.
     *
     * <p>Een abstracte methode krijgt in de IR geen blokken
     * ({@code IRBuilder.beginFunction} maakt voor {@code Flags.ABSTRACT} geen
     * entry-block) en dus ook geen {@code define} in fase 4 — alleen een
     * declaration. Toch bevat de vtable van de abstracte klasse (en die van
     * tussenklassen die de methode niet overriden) een pointer naar dat
     * symbool. Zo'n globale constante gaat als live referentie de
     * object-file in, met een undefined reference tot gevolg bij het linken.
     *
     * <p>Slot-functies van concrete methoden hebben wél IR-blokken en worden
     * in fase 4 gedefinieerd; die raken we hier niet. Externe (native)
     * functies moeten extern blijven en worden eveneens overgeslagen. De
     * check op {@code LLVMIsDeclaration} maakt de stub idempotent: een
     * abstracte methode die door meerdere vtables wordt gedeeld, krijgt er
     * precies één.
     */
    private void emitAbstractSlotStub(IRFunction fn, LLVMValueRef fnRef) {
        if (!fn.blocks().isEmpty() || fn.isExternal()) return;
        if (LLVMIsDeclaration(fnRef) == 0) return;
        // A method the C runtime implements stays extern: the slot points to
        // that implementation (a JDK method lowered only as a declaration,
        // see BytecodeReachabilityCompiler.dispatchOnly).
        if (runtimeSymbols.contains(LLVMGetValueName(fnRef).getString())) return;

        LLVMBasicBlockRef entry = LLVMAppendBasicBlockInContext(ctx, fnRef,
                new BytePointer("entry"));
        LLVMBuilderRef builder = LLVMCreateBuilderInContext(ctx);
        LLVMPositionBuilderAtEnd(builder, entry);
        LLVMBuildUnreachable(builder);
        LLVMDisposeBuilder(builder);
    }

    /**
     * Zoekt in {@code ownVirtuals} de implementatie die {@code superFn} overridet
     * (zelfde methodenaam + parameterlijst).
     *
     * <p>De vergelijking gaat rechtstreeks op het {@code superFn}-object uit de
     * super-vtable. Een eerdere variant zocht de super-functie nogmaals op in
     * het module van de <i>directe</i> super-klasse; een slot dat hoger in de
     * ancestor-keten thuishoort (bv. {@code self()} uit {@code AbstractHandler},
     * geërfd via {@code AbstractParseResultHandler}) staat in dat module niet en
     * de lookup faalde stilletjes. Het gevolg: de override van de subklasse
     * werd níet op het bestaande slot geplaatst maar als extra slot
     * toegevoegd, terwijl het abstracte ancestor-symbool (zonder body) in de
     * vtable bleef staan — een undefined reference bij het linken.
     */
    private io.github.potjerodekool.nabu.backend.ir.IRFunction findOverride(
            List<io.github.potjerodekool.nabu.backend.ir.IRFunction> ownVirtuals,
            io.github.potjerodekool.nabu.backend.ir.IRFunction superFn) {
        for (var own : ownVirtuals) {
            if (methodTail(own.name).equals(methodTail(superFn.name))
                    && sameParameters(own, superFn)) {
                return own;
            }
        }
        return null;
    }

    private static boolean sameParameters(io.github.potjerodekool.nabu.backend.ir.IRFunction a,
                                          io.github.potjerodekool.nabu.backend.ir.IRFunction b) {
        // Parameter 0 is de receiver ('this') — die verschilt per klasse en
        // telt niet mee bij override-detectie (Java/JVM H1: alleen de
        // expliciete parameterlijst bepaalt de override).
        if (a.params.size() != b.params.size()) return false;
        for (int i = 1; i < a.params.size(); i++) {
            if (!typeKey(a.params.get(i).type()).equals(typeKey(b.params.get(i).type()))) {
                return false;
            }
        }
        return true;
    }

    private static String typeKey(IRType t) {
        if (t instanceof IRType.Ptr p) {
            return p.jvmDescriptor() != null ? p.jvmDescriptor() : "ptr:" + typeKey(p.pointee());
        }
        return t.getClass().getSimpleName();
    }

    /** Name-deel na de laatste '_' (Owner_method -> method). */
    private static String methodTail(String fullName) {
        int idx = fullName.lastIndexOf('_');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    /**
     * Bouwt de itable (array van functiepointers) van een module.
     *
     * <b>Interface</b>: er bestaan geen interface-instanties, dus de itable is
     * leeg. Wat wél nodig is, is de slot-index van elke eigen en geërfde
     * interface-methode: een call via dit statische type laadt
     * {@code obj.itable[slot]} en die index moet overeenkomen met de positie in
     * de itable van de implementerende klasse.
     *
     * <b>Klasse</b>: voor élke interface-methode van de compilatie (de globale
     * slotvolgorde, {@link #interfaceSlots}) wordt de implementatie gezocht in
     * de klasse zelf en zijn super-klassen; als die er niet is, valt het terug
     * op de functie van de interface (default-method / abstracte stub). Zo
     * wijst de itable van elke klasse naar de eigen implementatie, terwijl de
     * call-site dezelfde index gebruikt — echte dynamische dispatch.
     */
    private void buildItable(LLVMModuleRef mod, IRModule module, ClassLayout layout) {
        LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        boolean isInterface = Flags.hasFlag(module.flags, Flags.INTERFACE);

        // Only an object whose class implements the slot's interface is ever
        // dispatched through that slot, so a class fills just those slots and
        // its itable ends at the last one. An interface has no instances
        // (a lambda object has its own synthetic class): it only needs the
        // slot indices, not entries.
        final Set<String> implemented = isInterface ? Set.of() : implementedInterfacesWithSupers(layout.internalName);
        LLVMValueRef[] entries = new LLVMValueRef[interfaceSlots.size()];
        int length = 0;
        for (Map.Entry<String, Integer> slot : interfaceSlots.entrySet()) {
            String interfaceMethod = slot.getKey();
            layout.itableSlots.put(interfaceMethod, slot.getValue());
            if (isInterface) {
                continue;
            }
            final String owner = interfaceSlotOwner(interfaceMethod);
            if (owner != null && !implemented.contains(owner)) {
                continue;
            }
            LLVMValueRef implementation = findInterfaceImplementation(layout.internalName, interfaceMethod);
            entries[slot.getValue()] = implementation != null && !implementation.isNull()
                    ? implementation
                    : LLVMConstNull(ptrType);
            length = slot.getValue() + 1;
        }
        entries = java.util.Arrays.copyOf(entries, length);

        String itableName = "_ZNabu" + typeSymbol(layout.internalName) + "Eitable";
        LLVMTypeRef itableType = LLVMArrayType(ptrType, entries.length);
        LLVMValueRef itableGlobal = LLVMAddGlobal(mod, itableType, new BytePointer(itableName));
        LLVMSetLinkage(itableGlobal, LLVMInternalLinkage);
        LLVMSetGlobalConstant(itableGlobal, 1);

        LLVMValueRef itableInit;
        if (entries.length == 0) {
            itableInit = LLVMConstNull(itableType);
        } else {
            PointerPointer<Pointer> initArr = new PointerPointer<>(entries.length);
            for (int i = 0; i < entries.length; i++) {
                initArr.put(i, entries[i] != null ? entries[i] : LLVMConstNull(ptrType));
            }
            itableInit = LLVMConstArray(ptrType, initArr, entries.length);
            Reference.reachabilityFence(initArr);
        }
        LLVMSetInitializer(itableGlobal, itableInit);
        Reference.reachabilityFence(itableInit);

        layout.itableGlobal = itableGlobal;
        globalValueMap.put("@" + itableName, itableGlobal);
    }

    private final Set<String> runtimeSymbols = RuntimeSymbols.load();

    /** Slot key -> internal name of the interface its call names, or null if unknown. */
    private final Map<String, java.util.Optional<String>> interfaceSlotOwners = new HashMap<>();

    private String interfaceSlotOwner(final String interfaceMethod) {
        return interfaceSlotOwners.computeIfAbsent(interfaceMethod, key -> {
            final InterfaceSlotSig sig = interfaceSlotSigs.get(key);
            return java.util.Optional.ofNullable(sig != null ? ownerInternalName(sig.function()) : null);
        }).orElse(null);
    }

    /** The interfaces a class implements, also through its superclasses and superinterfaces. */
    private Set<String> implementedInterfacesWithSupers(final String internalName) {
        final Set<String> out = new HashSet<>();
        for (String c = internalName; c != null; c = superInternalName(c)) {
            for (final String itf : implementedInterfaceNames(modulesByInternalName.get(c))) {
                out.add(normalizeInternalName(itf));
            }
        }
        return out;
    }

/**
     * Zoekt de implementatie van een interface-methode voor een klasse: eerst in
     * de klasse zelf, dan in zijn super-klassen (de meest afgeleide wint). Matcht
     * op methodenaam + expliciete parameterlijst (Java/JVM H1). Valt terug op de
     * eigen functie van de interface (default-method), en anders op null — een
     * klasse die de interface helemaal niet implementeert krijgt géén itable-slot.
     */
    /** De expliciete parameters van een functie: bij een instantiemethode zonder de receiver. */
    private static List<IRType> explicitParams(final IRFunction fn) {
        final List<IRType> all = fn.params.stream().map(IRValue::type).toList();
        if (Flags.hasFlag(fn.getFlags(), Flags.STATIC) || all.isEmpty()) {
            return all;
        }
        return all.subList(1, all.size());
    }

    /** The function of the interface that declares the slot's method with exactly these parameters, or null. */
    private IRFunction findOverloadInInterface(final InterfaceSlotSig sig, final String tail, final String wanted) {
        final IRModule module = modulesByInternalName.get(ownerInternalName(sig.function()));
        if (module == null) {
            return null;
        }
        IRFunction found = null;
        for (final IRFunction fn : module.functions()) {
            if (fn.isConstructor() || Flags.hasFlag(fn.getFlags(), Flags.STATIC)) continue;
            if (!methodTail(fn.name).equals(tail)
                    || !FnKeys.fnKey("", explicitParams(fn)).equals(wanted)) continue;
            if (found == null || found.blocks().isEmpty()) {
                found = fn;
            }
        }
        return found;
    }

    private LLVMValueRef findInterfaceImplementation(String classInternalName, String interfaceMethod) {
        IRFunction interfaceFn = resolveSlotFunction(interfaceMethod);
        if (interfaceFn == null) return null;

        // Eerst exact op de parameterlijst van de call-site: de interface-
        // module declareert niet elke overload (alleen wat gelowered is), dus
        // resolveFunction kan een andere overload teruggeven dan het slot
        // bedoelt (Appendable.append(char) voor append(CharSequence, int, int)).
        final InterfaceSlotSig sig = interfaceSlotSigs.get(interfaceMethod);
        if (sig != null) {
            final String wanted = FnKeys.fnKey("", sig.paramTypes());
            final String tail = methodTail(sig.function());
            String c = classInternalName;
            while (c != null) {
                IRModule module = modulesByInternalName.get(c);
                if (module != null) {
                    for (IRFunction fn : module.functions()) {
                        if (fn.isConstructor() || Flags.hasFlag(fn.getFlags(), Flags.STATIC)) continue;
                        if (fn.name.endsWith("_super")) continue;
                        if (methodTail(fn.name).equals(tail)
                                && FnKeys.fnKey("", explicitParams(fn)).equals(wanted)) {
                            LLVMValueRef value = functionValue(fn);
                            if (value != null) return value;
                        }
                    }
                }
                c = superInternalName(c);
            }
            if (!FnKeys.fnKey("", explicitParams(interfaceFn)).equals(wanted)) {
                // The function found by name is another overload (Path.resolve(Path) for resolve(String)). The
                // overload of the slot has a name with a suffix; look for it in the interface itself.
                final IRFunction exact = findOverloadInInterface(sig, tail, wanted);
                if (exact == null) {
                    // Not that (wrong) implementation: this overload has no default or stub, the slot stays empty.
                    return null;
                }
                interfaceFn = exact;
            }
        }

        String current = classInternalName;
        while (current != null) {
            IRModule module = modulesByInternalName.get(current);
            if (module != null) {
                for (IRFunction fn : module.functions()) {
                    if (fn.isConstructor() || Flags.hasFlag(fn.getFlags(), Flags.STATIC)) continue;
                    if (fn.name.endsWith("_super")) continue;
                    if (methodTail(fn.name).equals(methodTail(interfaceFn.name))
                            && sameParameters(fn, interfaceFn)) {
                        LLVMValueRef value = functionValue(fn);
                        if (value != null) return value;
                    }
                }
            }
            current = superInternalName(current);
        }

        // Geen implementatie in de klassen-keten. Dan pas de interface-functie
        // zelf gebruiken (default-method met body, of abstracte stub) — en alleen
        // als de klasse de interface daadwerkelijk implementeert.
        if (implementsInterface(classInternalName, ownerOf(slotFunction(interfaceMethod)))) {
            // A default method of a more specific interface wins over the method the slot names
            // (Spliterator.OfInt.tryAdvance(Consumer) over the abstract Spliterator.tryAdvance).
            final LLVMValueRef specific = findMoreSpecificDefault(classInternalName, interfaceFn, sig);
            return specific != null ? specific : functionValue(interfaceFn);
        }
        return null;
    }

    /**
     * The default method (with a body) that overrides the slot's method in the most specific
     * interface the class implements, or null when there is none.
     */
    private LLVMValueRef findMoreSpecificDefault(final String classInternalName, final IRFunction interfaceFn,
                                                 final InterfaceSlotSig sig) {
        final String slotOwner = normalizeInternalName(ownerOf(interfaceFn.name));
        final String wanted = sig != null ? FnKeys.fnKey("", sig.paramTypes()) : null;
        final String tail = methodTail(interfaceFn.name);
        final List<String> owners = new ArrayList<>();
        final Map<String, IRFunction> candidates = new LinkedHashMap<>();
        for (final String itf : implementedInterfacesWithSupers(classInternalName)) {
            if (itf.equals(slotOwner)) continue;
            final IRModule module = modulesByInternalName.get(itf);
            if (module == null) continue;
            for (final IRFunction fn : module.functions()) {
                if (fn.isConstructor() || Flags.hasFlag(fn.getFlags(), Flags.STATIC)) continue;
                if (fn.blocks().isEmpty() || fn.name.endsWith("_super")) continue;
                if (!methodTail(fn.name).equals(tail)) continue;
                final boolean sameParams = wanted != null
                        ? FnKeys.fnKey("", explicitParams(fn)).equals(wanted)
                        : sameParameters(fn, interfaceFn);
                if (!sameParams) continue;
                // Only an interface below the slot's interface overrides it.
                if (!implementsInterface(itf, slotOwner)) continue;
                owners.add(itf);
                candidates.put(itf, fn);
            }
        }
        // The most specific one: no other candidate interface extends it.
        for (final String owner : owners) {
            boolean overridden = false;
            for (final String other : owners) {
                if (!other.equals(owner) && implementsInterface(other, owner)) {
                    overridden = true;
                    break;
                }
            }
            if (!overridden) {
                return functionValue(candidates.get(owner));
            }
        }
        return null;
    }

    /** Het eigenaarsdeel van een call-target: {@code pkg.Owner$Helper_method} -> {@code pkg.Owner$Helper}. */
    private static String ownerOf(String qualifiedName) {
        int idx = qualifiedName.lastIndexOf('_');
        return idx >= 0 ? qualifiedName.substring(0, idx) : qualifiedName;
    }

    /** Of de klasse (of een super-klasse) de gegeven interface implementeert. */
    private boolean implementsInterface(String classInternalName, String interfaceOwner) {
        final String target = normalizeInternalName(interfaceOwner);
        if (target == null) return false;
        // Ook via superinterfaces: picocli's CommandUserObject implementeert
        // IScope, en IScope (zonder eigen methoden) breidt IGetter uit. Alleen
        // de directe interfaces bekijken liet IGetter zonder implementers, en
        // `scope.get()` werd een directe call naar de body-loze IGetter_get.
        final Deque<String> pending = new ArrayDeque<>();
        final Set<String> seen = new HashSet<>();
        String current = classInternalName;
        while (current != null) {
            IRModule module = modulesByInternalName.get(current);
            if (module != null) {
                for (IRType implemented : module.interfaces()) {
                    final String name = normalizeInternalName(
                            implemented instanceof IRType.Ptr p ? p.jvmDescriptor() : null);
                    if (name != null) pending.add(name);
                }
            }
            current = superInternalName(current);
        }
        while (!pending.isEmpty()) {
            final String itf = pending.poll();
            if (!seen.add(itf)) continue;
            if (target.equals(itf)) return true;
            final IRModule itfModule = modulesByInternalName.get(itf);
            if (itfModule != null) {
                for (IRType ext : itfModule.interfaces()) {
                    final String name = normalizeInternalName(
                            ext instanceof IRType.Ptr p ? p.jvmDescriptor() : null);
                    if (name != null) pending.add(name);
                }
            }
        }
        return false;
    }

    /**
     * De LLVMValueRef van een IRFunction: eerst de overload-sleutel (dan is het
     * zeker de juiste variant), dan de gewone naam. Let op dat een constructor
     * die met een methode botst onder een eigen symbool staat.
     */
    LLVMValueRef functionValue(IRFunction fn) {
        String symbol = firstNonNull(constructorSymbol(fn), fn.name);
        // Zie buildVtable: declaredParamsOf (zonder receiver) om consistent
        // te blijven met hoe FunctionEmitter.declareSignature de
        // overload-sleutel registreert.
        List<IRType> paramTypes = FnKeys.declaredParamsOf(fn);
        // Klasse-gekwalificeerde sleutel eerst (zie buildVtable's zelfde
        // aanpassing): de kale sleutel alleen is geen garantie tegen
        // botsingen tussen klassen die de simple owner-naam delen.
        final String qualified = qualifiedName(fn);
        LLVMValueRef value = qualified != null
                ? globalValueMap.get("@" + FnKeys.fnKey(qualified, paramTypes))
                : null;
        if (value == null || value.isNull()) {
            value = globalValueMap.get("@" + FnKeys.fnKey(symbol, paramTypes));
        }
        if (value == null || value.isNull()) {
            value = globalValueMap.get("@" + symbol);
        }
        return value;
    }

    /**
     * De LLVMValueRef van een functie die onder een naam bekend is die niet
     * per definitie het LLVM-symbool is: de vtable/itable bevat gekwalificeerde
     * namen ({@code pkg.Outer$IHelp_value}) terwijl de declaratie onder de
     * simple-owner naam staat ({@code IHelp_value}). De IRFunction-resolutie
     * levert de declaratie, waarvan het werkelijke symbool weer uit
     * {@code globalValueMap} komt.
     */
    private LLVMValueRef functionValueByName(String name) {
        LLVMValueRef value = globalValueMap.get("@" + name);
        if (value != null && !value.isNull()) {
            return value;
        }
        IRFunction fn = resolveFunction(name);
        return fn != null ? functionValue(fn) : null;
    }

    private static String firstNonNull(String first, String fallback) {
        return first != null ? first : fallback;
    }

    private LLVMTypeRef mapFieldType(io.github.potjerodekool.nabu.backend.ir.types.IRType type) {
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr) {
            // referentie-veld: opaque pointer
            return LLVMPointerTypeInContext(ctx, 0);
        }
        return new TypeMapper(ctx).map(type);
    }

    private LLVMValueRef emitTypeInfo(LLVMModuleRef mod,
                                      String internalName,
                                      LLVMTypeRef structType,
                                      int fieldCount,
                                      IRModule module,
                                      LLVMValueRef superTypeInfo,
                                      List<Map.Entry<String, Long>> instanceFields) {
        LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef i64   = LLVMInt64TypeInContext(ctx);
        LLVMTypeRef i32   = LLVMInt32TypeInContext(ctx);

        // name string global
        String nameGlobal = ".type.name." + typeSymbol(internalName);
        LLVMValueRef nameStr = LLVMConstStringInContext(ctx,
                new BytePointer(internalName), internalName.length(), 0);
        LLVMTypeRef nameStrType = LLVMTypeOf(nameStr);
        LLVMValueRef nameGlobalVal = LLVMAddGlobal(mod, nameStrType, new BytePointer(nameGlobal));
        LLVMSetLinkage(nameGlobalVal, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(nameGlobalVal, 1);
        LLVMSetInitializer(nameGlobalVal, nameStr);
        globalValueMap.put("@" + nameGlobal, nameGlobalVal);

        // object size
        LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
        long size = LLVMABISizeOfType(dl, structType);
        LLVMDisposeTargetData(dl);

        // super_type: verwijst naar de super-klasse in deze compilatie; anders naar de
        // gesynthetiseerde library-type-info (Exception-keten) of null.
        LLVMValueRef superPtr = superTypeInfo != null ? superTypeInfo : LLVMConstNull(i8Ptr);

        LLVMTypeRef infoType = typeInfoStructType();

        String infoName = "_ZNabu" + typeSymbol(internalName) + "Etype_info";
        LLVMValueRef infoGlobal = LLVMAddGlobal(mod, infoType, new BytePointer(infoName));
        LLVMSetLinkage(infoGlobal, LLVMExternalLinkage);
        LLVMSetGlobalConstant(infoGlobal, 1);

        final List<String> interfaces = implementedInterfaceNames(module);

        // init
        PointerPointer<Pointer> initFields = new PointerPointer<>(8);
        initFields.put(0, nameGlobalVal);
        initFields.put(1, LLVMConstInt(i64, size, 0));
        initFields.put(2, superPtr);
        initFields.put(3, LLVMConstInt(i32, fieldCount, 0));
        initFields.put(4, interfaceNameArray(mod, internalName, interfaces));
        initFields.put(5, LLVMConstInt(i32, interfaces.size(), 0));
        initFields.put(6, instanceFieldArray(mod, internalName, instanceFields));
        initFields.put(7, LLVMConstInt(i32, instanceFields.size(), 0));
        LLVMValueRef init = LLVMConstNamedStruct(infoType, initFields, 8);
        LLVMSetInitializer(infoGlobal, init);
        Reference.reachabilityFence(initFields);
        Reference.reachabilityFence(init);

        globalValueMap.put("@" + infoName, infoGlobal);
        return infoGlobal;
    }

    /**
     * Synthetiseert een nabu_type_info-global voor een library-klasse (die NIET
     * in deze compilatie zit, bv. java/lang/Exception) inclusief diens bovenliggende
     * keten tot en met java/lang/Object. Daarmee kan {@code nabu_instanceof}/{@code
     * nabu_can_catch} over de super_type-keten matchen: een batch-ge&ccedil;mpileerd
     * subtype van Exception wordt gevangen door {@code catch (e : Exception)}.
     *
     * De entry heeft alleen de object-header (grootte = grootte van de header), geen
     * velden: de struct-layout van de library-klasse is hier geen onderdeel van.
     */
    private LLVMValueRef librarySuperTypeInfo(LLVMModuleRef mod, String internalName) {
        LLVMValueRef cached = librarySuperTypes.get(internalName);
        if (cached != null) return cached;

        String parent = librarySuperOf(internalName);
        LLVMValueRef parentInfo = parent != null ? librarySuperTypeInfo(mod, parent) : null;

        LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef i64   = LLVMInt64TypeInContext(ctx);
        LLVMTypeRef i32   = LLVMInt32TypeInContext(ctx);

        // name string global (zelfde schema als emitTypeInfo)
        String nameGlobal = ".type.name." + typeSymbol(internalName);
        LLVMValueRef nameStr = LLVMConstStringInContext(ctx,
                new BytePointer(internalName), internalName.length(), 0);
        LLVMTypeRef nameStrType = LLVMTypeOf(nameStr);
        LLVMValueRef nameGlobalVal = LLVMAddGlobal(mod, nameStrType, new BytePointer(nameGlobal));
        LLVMSetLinkage(nameGlobalVal, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(nameGlobalVal, 1);
        LLVMSetInitializer(nameGlobalVal, nameStr);
        globalValueMap.put("@" + nameGlobal, nameGlobalVal);

        LLVMTypeRef infoType = typeInfoStructType();

        String infoName = "_ZNabu" + typeSymbol(internalName) + "Etype_info";
        LLVMValueRef infoGlobal = LLVMAddGlobal(mod, infoType, new BytePointer(infoName));
        LLVMSetLinkage(infoGlobal, LLVMInternalLinkage);
        LLVMSetGlobalConstant(infoGlobal, 1);

        LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
        long size = LLVMABISizeOfType(dl, objectHeaderType());
        LLVMDisposeTargetData(dl);

        PointerPointer<Pointer> initFields = new PointerPointer<>(8);
        initFields.put(0, nameGlobalVal);
        initFields.put(1, LLVMConstInt(i64, size, 0));
        initFields.put(2, parentInfo != null ? parentInfo : LLVMConstNull(i8Ptr));
        initFields.put(3, LLVMConstInt(i32, 0, 0));
        initFields.put(4, LLVMConstNull(i8Ptr));
        initFields.put(5, LLVMConstInt(i32, 0, 0));
        initFields.put(6, LLVMConstNull(i8Ptr));
        initFields.put(7, LLVMConstInt(i32, 0, 0));
        LLVMValueRef init = LLVMConstNamedStruct(infoType, initFields, 8);
        LLVMSetInitializer(infoGlobal, init);
        Reference.reachabilityFence(initFields);
        Reference.reachabilityFence(init);

        globalValueMap.put("@" + infoName, infoGlobal);
        librarySuperTypes.put(internalName, infoGlobal);
        return infoGlobal;
    }

    /**
     * {@code struct nabu_type_info { i8* name, i64 size, i8* super_type,
     * i32 field_count, i8** interfaces, i32 interface_count,
     * nabu_type_field* instance_fields, i32 instance_field_count }} — moet
     * overeenkomen met runtime/nabu_runtime.h.
     */
    LLVMTypeRef typeInfoStructType() {
        LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef i32 = LLVMInt32TypeInContext(ctx);
        PointerPointer<Pointer> info = new PointerPointer<>(8);
        info.put(0, i8Ptr);
        info.put(1, LLVMInt64TypeInContext(ctx));
        info.put(2, i8Ptr);
        info.put(3, i32);
        info.put(4, i8Ptr);
        info.put(5, i32);
        info.put(6, i8Ptr);
        info.put(7, i32);
        LLVMTypeRef infoType = LLVMStructTypeInContext(ctx, info, 8, 0);
        Reference.reachabilityFence(info);
        return infoType;
    }

    /**
     * Een {@code [n x { i8*, i64 }]}-global ({@code nabu_type_field}: naam en
     * byte-offset) met de eigen instantievelden, of null bij geen.
     */
    private LLVMValueRef instanceFieldArray(LLVMModuleRef mod, String internalName,
                                            List<Map.Entry<String, Long>> instanceFields) {
        LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        if (instanceFields.isEmpty()) {
            return LLVMConstNull(i8Ptr);
        }
        LLVMTypeRef i64 = LLVMInt64TypeInContext(ctx);
        PointerPointer<Pointer> memberTypes = new PointerPointer<>(2);
        memberTypes.put(0, i8Ptr);
        memberTypes.put(1, i64);
        LLVMTypeRef entryType = LLVMStructTypeInContext(ctx, memberTypes, 2, 0);
        Reference.reachabilityFence(memberTypes);

        PointerPointer<Pointer> entries = new PointerPointer<>(instanceFields.size());
        for (int i = 0; i < instanceFields.size(); i++) {
            final String name = instanceFields.get(i).getKey();
            LLVMValueRef str = LLVMConstStringInContext(ctx, new BytePointer(name), name.length(), 0);
            LLVMValueRef strGlobal = LLVMAddGlobal(mod, LLVMTypeOf(str),
                    new BytePointer(".type.fld." + typeSymbol(internalName) + "." + i));
            LLVMSetLinkage(strGlobal, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(strGlobal, 1);
            LLVMSetInitializer(strGlobal, str);
            PointerPointer<Pointer> members = new PointerPointer<>(2);
            members.put(0, strGlobal);
            members.put(1, LLVMConstInt(i64, instanceFields.get(i).getValue(), 0));
            entries.put(i, LLVMConstStructInContext(ctx, members, 2, 0));
            Reference.reachabilityFence(members);
        }
        LLVMTypeRef arrayType = LLVMArrayType(entryType, instanceFields.size());
        LLVMValueRef array = LLVMAddGlobal(mod, arrayType,
                new BytePointer(".type.flds." + typeSymbol(internalName)));
        LLVMSetLinkage(array, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(array, 1);
        LLVMSetInitializer(array, LLVMConstArray(entryType, entries, instanceFields.size()));
        Reference.reachabilityFence(entries);
        return array;
    }

    /**
     * De interne namen van alle interfaces die {@code module} implementeert:
     * de directe plus, voor zover in deze compilatie bekend, hun
     * superinterfaces. Interfaces van super-klassen staan in de type-info van
     * die super-klassen; {@code nabu_instanceof} loopt de keten af.
     */
    private List<String> implementedInterfaceNames(IRModule module) {
        final Set<String> out = new LinkedHashSet<>();
        if (module == null) {
            return List.of();
        }
        final Deque<IRType> work = new ArrayDeque<>(module.interfaces());
        while (!work.isEmpty()) {
            if (!(work.poll() instanceof IRType.Ptr p) || p.jvmDescriptor() == null) {
                continue;
            }
            final String name = fromDescriptor(p.jvmDescriptor());
            if (!out.add(name)) {
                continue;
            }
            final IRModule itf = modulesByInternalName.get(normalizeInternalName(name));
            if (itf != null) {
                work.addAll(itf.interfaces());
            }
        }
        return new ArrayList<>(out);
    }

    /** Een {@code [n x i8*]}-global met de interfacenamen als C-strings, of null bij geen. */
    private LLVMValueRef interfaceNameArray(LLVMModuleRef mod, String internalName, List<String> names) {
        LLVMTypeRef i8Ptr = LLVMPointerTypeInContext(ctx, 0);
        if (names.isEmpty()) {
            return LLVMConstNull(i8Ptr);
        }
        PointerPointer<Pointer> entries = new PointerPointer<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            LLVMValueRef str = LLVMConstStringInContext(ctx, new BytePointer(name), name.length(), 0);
            LLVMValueRef strGlobal = LLVMAddGlobal(mod, LLVMTypeOf(str),
                    new BytePointer(".type.itf." + typeSymbol(internalName) + "." + i));
            LLVMSetLinkage(strGlobal, LLVMPrivateLinkage);
            LLVMSetGlobalConstant(strGlobal, 1);
            LLVMSetInitializer(strGlobal, str);
            entries.put(i, strGlobal);
        }
        LLVMTypeRef arrayType = LLVMArrayType(i8Ptr, names.size());
        LLVMValueRef array = LLVMAddGlobal(mod, arrayType,
                new BytePointer(".type.itfs." + typeSymbol(internalName)));
        LLVMSetLinkage(array, LLVMPrivateLinkage);
        LLVMSetGlobalConstant(array, 1);
        LLVMSetInitializer(array, LLVMConstArray(i8Ptr, entries, names.size()));
        Reference.reachabilityFence(entries);
        return array;
    }

public ClassLayout get(String internalName) {
        return byInternalName.get(normalizeInternalName(internalName));
    }

    /**
     * Whether the owner of a call target ({@code pkg.Owner_member}) is an
     * interface of this compilation, as after {@link #canonicalize} for a
     * method a class only inherits from an interface.
     */
    public boolean ownedByInterface(String callTarget) {
        if (callTarget == null) return false;
        final int idx = ownerSplit(callTarget);
        if (idx < 0) return false;
        final IRModule module = moduleOf(callTarget.substring(0, idx));
        return module != null && Flags.hasFlag(module.flags, Flags.INTERFACE);
    }

    /** The layout of the owner of a call target ({@code pkg.Owner_member}), or null. */
    public ClassLayout ownerLayout(String callTarget) {
        if (callTarget == null) return null;
        final int idx = ownerSplit(callTarget);
        return idx < 0 ? null : get(callTarget.substring(0, idx));
    }

    /** De IRModule bij een interne naam, of null. */
    public IRModule moduleOf(String internalName) {
        return modulesByInternalName.get(normalizeInternalName(internalName));
    }

    /**
     * Diagnostiek: de velden van de module met hun teller-index, zoals
     * {@code computeFieldIndex} die telt (alle velden, statisch en instantie).
     */
    public String fieldDiagnostics(String internalName) {
        IRModule module = moduleOf(internalName);
        if (module == null) {
            return "(module unknown: " + internalName + ")";
        }
        StringBuilder sb = new StringBuilder();
        int counter = 0;
        for (IRField field : module.fields()) {
            if (counter > 0) {
                sb.append(", ");
            }
            sb.append(counter).append('=').append(field.name());
            if (Flags.hasFlag(field.flags(), Flags.STATIC)) {
                sb.append("(static)");
            }
            counter++;
        }
        return sb.toString();
    }

    /** Zoals {@link #fieldDiagnostics(String)}, maar voor een IRType (bv. ownerType). */
    public String fieldDiagnosticsForType(io.github.potjerodekool.nabu.backend.ir.types.IRType type) {
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p
                && p.jvmDescriptor() != null) {
            return fieldDiagnostics(fromDescriptor(p.jvmDescriptor()));
        }
        return "(no descriptor: " + type + ")";
    }

    /**
     * De veld-index van een veldnaam binnen één module: dezelfde teller over
     * statische en instantie-velden die de frontend hanteert, zodat de index
     * rechtstreeks in {@link ClassLayout#structIndex(int)} past. -1 als de
     * module het veld niet declareert.
     */
    public int fieldIndexIn(IRModule module, String fieldName) {
        if (module == null || fieldName == null) return -1;
        int index = 0;
        for (IRField field : module.fields()) {
            if (fieldName.equals(field.name())) {
                return index;
            }
            index++;
        }
        return -1;
    }

    /** Haalt een layout op aan de hand van een IRType (bijv. Named.ownerType / object type). */
    public ClassLayout getFor(io.github.potjerodekool.nabu.backend.ir.types.IRType type) {
        if (type instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p
                && p.jvmDescriptor() != null) {
            return get(fromDescriptor(p.jvmDescriptor()));
        }
        return null;
    }

    /**
     * Zoekt de layout die een vtable-slot voor de gegeven methode-full-name
     * registreert. Gebruikt als fallback wanneer het statische type van de
     * receiver geen layout-owner oplevert (bv. obscured naar een opaque pointer
     * door SSA/optimalisatie): virtuele dispatch draait dan om de declarerende
     * klasse van de methode.
     */
    public ClassLayout findLayoutForVtableSlot(String methodFullName) {
        return findLayoutForVtableSlot(methodFullName, null);
    }

    /**
     * Zoekt de layout die een vtable-slot voor de gegeven methode-full-name
     * registreert, met onderscheid tussen overloads. Zonder parameterlijst valt
     * het terug op de kale naam; dat is voldoende wanneer de methode niet
     * overladen is, maar een overload zou dan de slot van de laatste variant
     * krijgen.
     */
    public ClassLayout findLayoutForVtableSlot(String methodFullName,
                                               List<IRType> paramTypes) {
        for (final ClassLayout layout : byInternalName.values()) {
            // Alleen layouts met een échte vtable: de slot-index is alleen
            // geldig ten opzichte van de array die in de header van het object
            // staat. Een abstracte klasse (geen vtable) zou anders een
            // betekenisloze index opleveren.
            if (layout.vtableGlobal == null) continue;
            if (paramTypes != null
                    && layout.vtableSlot(methodFullName, paramTypes) >= 0) {
                return layout;
            }
            if (layout.vtableSlot(methodFullName) >= 0) {
                return layout;
            }
        }
        return null;
    }

    /**
     * Vindt (bij benadering) de layout van de DECLARERENDE klasse van een
     * veld met de gegeven naam (+ fieldIndex wanneer >= 0), wanneer de
     * ownerType-pointer het layout niet direct vastlegt (bv. degeneratie
     * naar een opaque ptr bij Nabu-sources zonder geresolved klasse-type).
     * Bij fieldIndex {@code < 0} winnen velden met een exacte indexspecificatie de
     * match; anders bepaalt de fieldnaam de match.
     */
    public FieldOwner findField(String fieldName, int fieldIndex) {
        FieldOwner nameOnlyMatch = null;
        for (final Map.Entry<String, IRModule> entry : modulesByInternalName.entrySet()) {
            final IRModule module = entry.getValue();
            int index = 0;
            for (final IRField field : module.fields()) {
                if (fieldName.equals(field.name())
                        && (fieldIndex == index)) {
                    final ClassLayout layout = byInternalName.get(entry.getKey());
                    if (layout != null) {
                        return new FieldOwner(layout, index);
                    }
                }
                if (nameOnlyMatch == null
                        && fieldName.equals(field.name())
                        && byInternalName.get(entry.getKey()) != null) {
                    nameOnlyMatch = new FieldOwner(byInternalName.get(entry.getKey()), index);
                }
                index++;
            }
        }
        return nameOnlyMatch;
    }

    /** De intern-namen van alle geregistreerde layouts (diagnose). */
    public java.util.List<String> registeredNames() {
        return new java.util.ArrayList<>(byInternalName.keySet());
    }

    /** De reg-genormaliseerde module-namen (diagnose). */
    public java.util.List<String> registeredModules() {
        return new java.util.ArrayList<>(modulesByInternalName.keySet());
    }

    /** Layout + de geconcreteerde veld-index van een veld-match. */
    public record FieldOwner(ClassLayout layout, int fieldIndex) {
    }

    /**
     * Vindt (bij benadering) de layout van de DECLARERENDE klasse van een
     * veld met de gegeven naam + fieldIndex, wanneer de ownerType-pointer
     * het layout niet direct vastlegt (bv. degeneratie naar een opaque ptr
     * bij Nabu-sources zonder geresolved klasse-type). Volgorde is het
     * batch-register zal de beste match op dezelfde fieldIndex+naam vinden.
     */
    public ClassLayout findLayoutForField(String fieldName, int fieldIndex) {
        final FieldOwner owner = findField(fieldName, fieldIndex);
        return owner != null ? owner.layout() : null;
    }

    public static String normalizeInternalName(String name) {
        if (name == null) return null;
        return fromDescriptor(name);
    }

    /**
     * De simple eigenaar zoals de frontend die voor functienamen gebruikt:
     * {@code mini/Outer$IHelp} -> {@code IHelp}, {@code mini/Outer} -> {@code Outer}.
     * De IR declareert functies immers als {@code <simpleOwner>_<methode>}, terwijl
     * call-sites de volledige interne naam gebruiken.
     */
    static String simpleOwnerOf(String internalName) {
        if (internalName == null) return null;
        String name = internalName;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int nested = name.lastIndexOf('$');
        // Anonieme klasse (`Outer$Inner$1`): de frontend noemt haar functies
        // naar de volledige geneste naam; alleen "1" zou tussen alle anonieme
        // klassen botsen.
        if (nested >= 0 && nested + 1 < name.length()
                && name.substring(nested + 1).chars().allMatch(Character::isDigit)) {
            return name.substring(name.lastIndexOf('.') + 1);
        }
        if (nested >= 0) return name.substring(nested + 1);
        return name.isEmpty() ? null : name;
    }

    /** Lcompany/Pet; --> company/Pet ; example.Pet --> example/Pet */
    static String fromDescriptor(String desc) {
        if (desc == null) return null;
        String s = desc;
        // Alleen een échte descriptor uitpakken: een interne naam die met een
        // 'L' begint (klasse `Loud` in het default package) werd anders `oud`,
        // waardoor `Louder extends Loud` aan een gesynthetiseerde library-
        // type-info hing i.p.v. aan die van de gecompileerde klasse.
        if (s.length() > 2 && s.charAt(0) == 'L' && s.endsWith(";")) {
            s = s.substring(1, s.length() - 1);
        }
        // De frontend noemt modules met puntjes ('mini.Outer$Impl'), terwijl
        // jvmDescriptoren slashes gebruiken ('Lmini/Outer$Impl;'). Zonder deze
        // omzetting vond een lookup op het type van een parameter/local
        // (bv. een interface-receiver) de layout van die klasse nooit.
        return s.indexOf('.') >= 0 ? s.replace('.', '/') : s;
    }
}