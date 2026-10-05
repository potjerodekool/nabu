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
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.bytedeco.llvm.LLVM.LLVMTargetDataRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;

import java.lang.ref.Reference;
import java.util.ArrayList;
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

    /** Standaard library-hiërarchie voor type-info-synthese (bovenkant van andere klassen). */
    private static String librarySuperOf(String internalName) {
        if (internalName == null) return null;
        return switch (internalName) {
            case "java/lang/Exception" -> "java/lang/Throwable";
            case "java/lang/Throwable" -> "java/lang/Object";
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
            }
            return vtableSlot(methodFullName);
        }

        /** Sleutel die een overload onderscheidt: naam + parameterprofiel. */
        private String slotKey(String methodFullName, List<IRType> paramTypes) {
            return virtualSlotKey(methodFullName, paramTypes);
        }

        /** Retourneert het itable-slot voor een interface-methode (full name), of -1. */
        public int itableSlot(String interfaceMethodFullName) {
            Integer v = itableSlots.get(interfaceMethodFullName);
            return v != null ? v : -1;
        }
    }

    private final LLVMContextRef ctx;
    private final Map<String, LLVMValueRef> globalValueMap;
    // per normalized internal name
    private final Map<String, ClassLayout> byInternalName = new LinkedHashMap<>();
    // normalized internal name -> module (voor super-resolutie)
    private final Map<String, IRModule> modulesByInternalName = new LinkedHashMap<>();

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
    private final Map<String, Integer> interfaceSlots = new LinkedHashMap<>();
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
        functionsByQualifiedName.clear();
        overloadsByName.clear();
        qualifiedNames.clear();
        constructorSymbols.clear();
        interfaceSlots.clear();

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
        layout.virtualSlots.putIfAbsent(virtualSlotKey(name, paramTypes), slot);
    }

    /**
     * Sleutel voor een overload in de vtable: naam + parameterprofiel.
     *
     * <p>Overloads delen de IR-functienaam, dus alleen de naam onderscheidt ze
     * niet. Zonder deze sleutel delen drie {@code addSubcommand}-varianten één
     * slot-index en belandt een call in de verkeerde functiepointer.
     */
    static String virtualSlotKey(final String name, final List<IRType> paramTypes) {
        final var sb = new StringBuilder(name).append('#');
        for (final var t : paramTypes) {
            sb.append(typeProfile(t));
        }
        return sb.toString();
    }

    private static String typeProfile(final IRType t) {
        if (t == null) return "O";
        if (t instanceof IRType.Ptr) return "P";
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
                        if (instr instanceof IRInstruction.Call call
                                && call.callKind() == CallKind.INTERFACE
                                && call.function() != null
                                && resolveFunction(call.function()) != null) {
                            callTargets.add(call.function());
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
    public IRFunction resolveFunction(String callTarget) {
        if (callTarget == null) return null;
        IRFunction direct = functionsByQualifiedName.get(callTarget);
        if (direct != null) return direct;
        for (IRModule module : modulesByInternalName.values()) {
            for (IRFunction fn : module.functions()) {
                if (callTarget.equals(fn.name)) return fn;
            }
        }
        return null;
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
            if (expectedReturnType != null) {
                for (final var candidate : candidates) {
                    if (sameParamProfile(candidate, paramTypes)
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
                if (candidate.params.size() == paramTypes.size()) return candidate;
            }
        }
        return candidates.getFirst();
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
    public int interfaceSlotIndex(String interfaceMethod) {
        Integer slot = interfaceSlots.get(interfaceMethod);
        return slot != null ? slot : -1;
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
    private String typeSymbol(String internalName) {
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
        if (m == null || m.superType() == null || !(m.superType() instanceof IRType.Ptr p)
                || p.jvmDescriptor() == null) {
            return null;
        }
        String sup = fromDescriptor(p.jvmDescriptor());
        return modulesByInternalName.containsKey(sup) ? sup : null;
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
        LLVMValueRef typeInfo = emitTypeInfo(mod, internalName, structType, counter, module, superTypeInfo);

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

        List<IRFunction> virtuals =
                module.functions().stream()
                        .filter(f -> !Flags.hasFlag(f.getFlags(), Flags.STATIC))
                        .filter(f -> !Flags.hasFlag(f.getFlags(), Flags.PRIVATE))
                        .filter(f -> !Flags.hasFlag(f.getFlags(), Flags.FINAL))
                        .filter(f -> !f.isConstructor())
                        .filter(f -> !f.name.endsWith("_super"))
                        .filter(f -> !f.isExternal())
                        .sorted(java.util.Comparator.comparing(f -> f.name))
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
            String superModuleName = superLayout.internalName;
            for (int i = 0; i < superLayout.vtableOrder.size(); i++) {
                IRFunction superFnRef = superLayout.vtableOrder.get(i);
                String superFn = superFnRef.name;
                IRFunction override = findOverride(virtuals, superFn, superModuleName);
                IRFunction placed = override != null ? override : superFnRef;
                slotFns.add(placed);
                layout.vtableOrder.add(placed);
                layout.virtualSlots.put(placed.name, i);
                layout.virtualSlots.putIfAbsent(superFn, i);
                if (override != null) {
                    registerSlot(layout, override.name, i,
                            override.params.stream().map(IRValue::type).toList());
                }
            }
            // Daarna eigen nieuwe virtuele methoden (die de super niet heeft)
            Set<String> placed = new java.util.HashSet<>();
            for (IRFunction f : layout.vtableOrder) {
                placed.add(f.name);
            }
            for (var v : virtuals) {
                if (!placed.contains(v.name)) {
                    slotFns.add(v);
                    layout.vtableOrder.add(v);
                    layout.virtualSlots.put(v.name, slotFns.size() - 1);
                    registerSlot(layout, v.name, slotFns.size() - 1,
                            v.params.stream().map(IRValue::type).toList());
                    placed.add(v.name);
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
                Integer slot = layout.virtualSlots.get(v.name);
                if (slot != null) {
                    layout.virtualSlots.putIfAbsent(qualified, slot);
                    registerSlot(layout, qualified, slot,
                            v.params.stream().map(IRValue::type).toList());
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
                LLVMValueRef fnRef = globalValueMap.get("@" + FnKeys.fnKey(
                        fn.name, fn.params.stream().map(IRValue::type).toList()));
                if (fnRef == null || fnRef.isNull()) {
                    fnRef = functionValueByName(fn.name);
                }
                if (fnRef == null || fnRef.isNull())
                    throw new IllegalStateException(
                            "Vtable-functie niet gedeclareerd: " + fn.name);
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
     * Zoekt in {@code ownVirtuals} de implementatie die de super-methode
     * {@code superFnFullName} overridet (zelfde methodenaam + parameterlijst).
     * De reguliere bovengrens wordt via {@code superModuleName} opgezocht (de
     * interne naam van de super-klasse, met '/'), niet via naam-parsing van de
     * functie-full-name.
     */
    private io.github.potjerodekool.nabu.backend.ir.IRFunction findOverride(
            List<io.github.potjerodekool.nabu.backend.ir.IRFunction> ownVirtuals,
            String superFnFullName,
            String superModuleName) {
        IRModule superMod = modulesByInternalName.get(superModuleName);
        io.github.potjerodekool.nabu.backend.ir.IRFunction superFn = null;
        if (superMod != null) {
            for (var f : superMod.functions()) {
                if (f.name.equals(superFnFullName)) {
                    superFn = f;
                    break;
                }
            }
        }
        if (superFn == null) return null;

        for (var own : ownVirtuals) {
            if (methodTail(own.name).equals(methodTail(superFnFullName))
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

        LLVMValueRef[] entries = new LLVMValueRef[interfaceSlots.size()];
        for (Map.Entry<String, Integer> slot : interfaceSlots.entrySet()) {
            String interfaceMethod = slot.getKey();
            if (isInterface && declaresInterfaceMethod(layout.internalName, interfaceMethod)) {
                // Slot-index vastleggen; de vulling hoort bij de implementerende klasse.
                layout.itableSlots.put(interfaceMethod, slot.getValue());
                continue;
            }
            layout.itableSlots.put(interfaceMethod, slot.getValue());
            LLVMValueRef implementation = findInterfaceImplementation(layout.internalName, interfaceMethod);
            entries[slot.getValue()] = implementation != null && !implementation.isNull()
                    ? implementation
                    : LLVMConstNull(ptrType);
        }

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

/** Of deze interface de gegeven interface-methode zelf declareert (of erft). */
    private boolean declaresInterfaceMethod(String internalName, String interfaceMethod) {
        final String method = methodTail(interfaceMethod);
        String current = internalName;
        while (current != null) {
            IRModule module = modulesByInternalName.get(current);
            if (module == null) return false;
            for (IRFunction fn : module.functions()) {
                if (fn.name != null && !fn.isConstructor() && methodTail(fn.name).equals(method)) {
                    return true;
                }
            }
            current = superInternalName(current);
        }
        return false;
    }

/**
     * Zoekt de implementatie van een interface-methode voor een klasse: eerst in
     * de klasse zelf, dan in zijn super-klassen (de meest afgeleide wint). Matcht
     * op methodenaam + expliciete parameterlijst (Java/JVM H1). Valt terug op de
     * eigen functie van de interface (default-method), en anders op null — een
     * klasse die de interface helemaal niet implementeert krijgt géén itable-slot.
     */
    private LLVMValueRef findInterfaceImplementation(String classInternalName, String interfaceMethod) {
        IRFunction interfaceFn = resolveFunction(interfaceMethod);
        if (interfaceFn == null) return null;

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
        if (implementsInterface(classInternalName, ownerOf(interfaceMethod))) {
            return functionValue(interfaceFn);
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
        String current = classInternalName;
        while (current != null) {
            IRModule module = modulesByInternalName.get(current);
            if (module != null) {
                for (IRType implemented : module.interfaces()) {
                    if (target.equals(normalizeInternalName(
                            implemented instanceof IRType.Ptr p ? p.jvmDescriptor() : null))) {
                        return true;
                    }
                }
            }
            current = superInternalName(current);
        }
        return false;
    }

    /**
     * De LLVMValueRef van een IRFunction: eerst de overload-sleutel (dan is het
     * zeker de juiste variant), dan de gewone naam. Let op dat een constructor
     * die met een methode botst onder een eigen symbool staat.
     */
    private LLVMValueRef functionValue(IRFunction fn) {
        String symbol = firstNonNull(constructorSymbol(fn), fn.name);
        List<IRType> paramTypes = fn.params.stream().map(p -> p.type()).toList();
        LLVMValueRef value = globalValueMap.get("@" + FnKeys.fnKey(symbol, paramTypes));
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
                                      LLVMValueRef superTypeInfo) {
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

        // struct nabu_type_info { i8* name, i64 size, i8* super_type, i32 field_count }
        PointerPointer<Pointer> info = new PointerPointer<>(4);
        info.put(0, i8Ptr);
        info.put(1, i64);
        info.put(2, i8Ptr);
        info.put(3, i32);
        LLVMTypeRef infoType = LLVMStructTypeInContext(ctx, info, 4, 0);
        Reference.reachabilityFence(info);

        String infoName = "_ZNabu" + typeSymbol(internalName) + "Etype_info";
        LLVMValueRef infoGlobal = LLVMAddGlobal(mod, infoType, new BytePointer(infoName));
        LLVMSetLinkage(infoGlobal, LLVMExternalLinkage);
        LLVMSetGlobalConstant(infoGlobal, 1);

        // init
        PointerPointer<Pointer> initFields = new PointerPointer<>(4);
        initFields.put(0, nameGlobalVal);
        initFields.put(1, LLVMConstInt(i64, size, 0));
        initFields.put(2, superPtr);
        initFields.put(3, LLVMConstInt(i32, fieldCount, 0));
        LLVMValueRef init = LLVMConstNamedStruct(infoType, initFields, 4);
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

        // struct nabu_type_info { i8* name, i64 size, i8* super_type, i32 field_count }
        PointerPointer<Pointer> info = new PointerPointer<>(4);
        info.put(0, i8Ptr);
        info.put(1, i64);
        info.put(2, i8Ptr);
        info.put(3, i32);
        LLVMTypeRef infoType = LLVMStructTypeInContext(ctx, info, 4, 0);
        Reference.reachabilityFence(info);

        String infoName = "_ZNabu" + typeSymbol(internalName) + "Etype_info";
        LLVMValueRef infoGlobal = LLVMAddGlobal(mod, infoType, new BytePointer(infoName));
        LLVMSetLinkage(infoGlobal, LLVMInternalLinkage);
        LLVMSetGlobalConstant(infoGlobal, 1);

        LLVMTargetDataRef dl = LLVMCreateTargetData(new BytePointer(""));
        long size = LLVMABISizeOfType(dl, objectHeaderType());
        LLVMDisposeTargetData(dl);

        PointerPointer<Pointer> initFields = new PointerPointer<>(4);
        initFields.put(0, nameGlobalVal);
        initFields.put(1, LLVMConstInt(i64, size, 0));
        initFields.put(2, parentInfo != null ? parentInfo : LLVMConstNull(i8Ptr));
        initFields.put(3, LLVMConstInt(i32, 0, 0));
        LLVMValueRef init = LLVMConstNamedStruct(infoType, initFields, 4);
        LLVMSetInitializer(infoGlobal, init);
        Reference.reachabilityFence(initFields);
        Reference.reachabilityFence(init);

        globalValueMap.put("@" + infoName, infoGlobal);
        librarySuperTypes.put(internalName, infoGlobal);
        return infoGlobal;
    }

public ClassLayout get(String internalName) {
        return byInternalName.get(normalizeInternalName(internalName));
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
            return "(module onbekend: " + internalName + ")";
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
        return "(geen descriptor: " + type + ")";
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
        if (nested >= 0) return name.substring(nested + 1);
        return name.isEmpty() ? null : name;
    }

    /** Lcompany/Pet; --> company/Pet ; example.Pet --> example/Pet */
    static String fromDescriptor(String desc) {
        if (desc == null) return null;
        String s = desc;
        if (s.startsWith("L")) s = s.substring(1);
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        // De frontend noemt modules met puntjes ('mini.Outer$Impl'), terwijl
        // jvmDescriptoren slashes gebruiken ('Lmini/Outer$Impl;'). Zonder deze
        // omzetting vond een lookup op het type van een parameter/local
        // (bv. een interface-receiver) de layout van die klasse nooit.
        return s.indexOf('.') >= 0 ? s.replace('.', '/') : s;
    }
}