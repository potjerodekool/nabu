package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRField;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
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
 * {@link IRValue.Named#fieldIndex()} (van IrGeneratingVisitor.computeFieldIndex) is een
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
        // full-method-naam (Owner_method) -> vtable-slot
        private final Map<String, Integer> virtualSlots = new LinkedHashMap<>();
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

        /** Retourneert het vtable-slot voor een methode (full name), of -1. */
        public int vtableSlot(String methodFullName) {
            Integer v = virtualSlots.get(methodFullName);
            return v != null ? v : -1;
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

    private LLVMTypeRef headerType;

    /** Header-struct: type-pointer-slot. */
    public static final int HEADER_SLOT_TYPE   = 0;
    /** Header-struct: vtable-pointer-slot. */
    public static final int HEADER_SLOT_VTABLE = 1;
    /** Header-struct: itable-pointer-slot. */
    public static final int HEADER_SLOT_ITABLE = 2;

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
     * Registreert de object-layouts voor alle modules en emitteert de bijbehorende
     * nabu_type_info global en vtable/itable. Ouders worden vóór kinderen
     * geregistreerd, zodat kinder-layouts de struct- en vtable-indices van de
     * super-klasse kunnen overnemen.
     */
    public void registerAll(LLVMModuleRef mod, List<IRModule> modules) {
        modulesByInternalName.clear();
        for (IRModule m : modules) {
            String n = normalizeInternalName(m.name);
            if (n != null) modulesByInternalName.put(n, m);
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
        List<io.github.potjerodekool.nabu.backend.ir.IRFunction> virtuals =
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

        // slot-order + bijbehorende functiepointer
        List<String> slotFns = new ArrayList<>();
        if (superLayout != null && !superLayout.virtualSlots.isEmpty()) {
            // Eerst de slots van de super-klasse (in volgorde), overrides vervangen
            String superModuleName = superLayout.internalName;
            for (Map.Entry<String, Integer> e : superLayout.virtualSlots.entrySet()) {
                String superFn = e.getKey();
                io.github.potjerodekool.nabu.backend.ir.IRFunction override =
                        findOverride(virtuals, superFn, superModuleName);
                slotFns.add(override != null ? override.name : superFn);
                layout.virtualSlots.put(superFn, e.getValue());
                if (override != null) layout.virtualSlots.put(override.name, e.getValue());
            }
            // Daarna eigen nieuwe virtuele methoden (die de super niet heeft)
            Set<String> placed = new java.util.HashSet<>(layout.virtualSlots.keySet());
            for (var v : virtuals) {
                if (!placed.contains(v.name)) {
                    slotFns.add(v.name);
                    layout.virtualSlots.put(v.name, slotFns.size() - 1);
                    placed.add(v.name);
                }
            }
        } else {
            // Geen (geregistreerde) super: eigen gesorteerde virtuele methoden
            for (var v : virtuals) {
                slotFns.add(v.name);
                layout.virtualSlots.put(v.name, slotFns.size() - 1);
            }
        }

        LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef vtableType = LLVMArrayType(ptrType, slotFns.size());

        String vtableName = "_ZNabu" + layout.internalName + "Evtable";
        LLVMValueRef vtableGlobal = LLVMAddGlobal(mod, vtableType, new BytePointer(vtableName));
        LLVMSetLinkage(vtableGlobal, LLVMInternalLinkage);
        LLVMSetGlobalConstant(vtableGlobal, 1);

        LLVMValueRef vtableInit;
        if (slotFns.isEmpty()) {
            vtableInit = LLVMConstNull(vtableType);
        } else {
            PointerPointer<Pointer> initArr = new PointerPointer<>(slotFns.size());
            for (int i = 0; i < slotFns.size(); i++) {
                String fnName = slotFns.get(i);
                LLVMValueRef fnRef = globalValueMap.get("@" + fnName);
                if (fnRef == null || fnRef.isNull())
                    throw new IllegalStateException(
                            "Vtable-functie niet gedeclareerd: " + fnName);
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
     * Bouwt de itable (array van functiepointers) voor de interface-methoden die
     * in deze module via een interface-call (CallKind.INTERFACE) worden aangeroepen.
     *
     * Veronderstelt dat elke interface-methode als (externe) functie is gedeclareerd,
     * zodat de functiepointer opvraagbaar is uit {@code globalValueMap}. In een
     * echte multi-module opzet zou dit de implementatie van de klasse zelf zijn;
     * cross-module koppeling hoort bij Fase 5/wiring.
     */
    private void buildItable(LLVMModuleRef mod, IRModule module, ClassLayout layout) {
        List<String> ifaceMethods = new java.util.ArrayList<>();
        for (io.github.potjerodekool.nabu.backend.ir.IRFunction fn : module.functions()) {
            for (var block : fn.blocks()) {
                for (IRInstruction instr : block.instructions()) {
                    if (instr instanceof IRInstruction.Call call
                            && call.callKind() == CallKind.INTERFACE
                            && call.function() != null
                            && !ifaceMethods.contains(call.function())) {
                        ifaceMethods.add(call.function());
                    }
                }
            }
        }
        java.util.Collections.sort(ifaceMethods);

        LLVMTypeRef ptrType = LLVMPointerTypeInContext(ctx, 0);
        LLVMTypeRef itableType = LLVMArrayType(ptrType, ifaceMethods.size());

        String itableName = "_ZNabu" + layout.internalName + "Eitable";
        LLVMValueRef itableGlobal = LLVMAddGlobal(mod, itableType, new BytePointer(itableName));
        LLVMSetLinkage(itableGlobal, LLVMInternalLinkage);
        LLVMSetGlobalConstant(itableGlobal, 1);

        LLVMValueRef itableInit;
        if (ifaceMethods.isEmpty()) {
            itableInit = LLVMConstNull(itableType);
        } else {
            PointerPointer<Pointer> initArr = new PointerPointer<>(ifaceMethods.size());
            for (int i = 0; i < ifaceMethods.size(); i++) {
                String m = ifaceMethods.get(i);
                LLVMValueRef fnRef = globalValueMap.get("@" + m);
                if (fnRef == null || fnRef.isNull())
                    throw new IllegalStateException(
                            "Interface-functie niet gedeclareerd: " + m);
                initArr.put(i, fnRef);
                layout.itableSlots.put(m, i);
            }
            itableInit = LLVMConstArray(ptrType, initArr, ifaceMethods.size());
            Reference.reachabilityFence(initArr);
        }
        LLVMSetInitializer(itableGlobal, itableInit);
        Reference.reachabilityFence(itableInit);

        layout.itableGlobal = itableGlobal;
        globalValueMap.put("@" + itableName, itableGlobal);
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
        String nameGlobal = ".type.name." + internalName.replace('/', '_');
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

        String infoName = "_ZNabu" + internalName + "Etype_info";
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
        String nameGlobal = ".type.name." + internalName.replace('/', '_');
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

        String infoName = "_ZNabu" + internalName + "Etype_info";
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
        for (final ClassLayout layout : byInternalName.values()) {
            if (layout.vtableSlot(methodFullName) >= 0) {
                return layout;
            }
        }
        return null;
    }

    public static String normalizeInternalName(String name) {
        if (name == null) return null;
        return fromDescriptor(name);
    }

    /** Lcompany/Pet; --> company/Pet ; example.Pet --> example/Pet */
    static String fromDescriptor(String desc) {
        if (desc == null) return null;
        String s = desc;
        if (s.startsWith("L")) s = s.substring(1);
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        return s;
    }
}