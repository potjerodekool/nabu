package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import java.lang.ref.Reference;
import java.util.*;
import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Emitteert IRFunction objecten naar LLVM functie-bodies.
 *
 * Verantwoordelijkheden:
 *   - Functie-signatuur declareren in de module
 *   - Parameters koppelen aan lokale waarden
 *   - Basisblokken aanmaken (voor vooruitverwijzingen)
 *   - Instructies emitteren via InstructionEmitter
 *
 * Externe functies (isExternal() == true) krijgen alleen een declaratie,
 * geen body.
 */
public class FunctionEmitter {

    private final LLVMContextRef              ctx;
    private final LLVMModuleRef               mod;
    private final LLVMBuilderRef              builder;
    private final TypeMapper types;
    private final ConstantResolver constants;
    private final InstructionEmitter instructions;
    private final Map<String, LLVMValueRef>   globalValueMap;
    private final Map<String, LLVMBasicBlockRef> blockMap;

    /**
     * Maps a block label to its exception handler label if the block
     * is inside a try region. Built during emitBody().
     */
    private final Map<String, String> tryHandlerMap = new HashMap<>();

    /**
     * Maps een IR-blok-label van een catch-handler naar het (nieuwe) LLVM
     * blok waarin de catch-body-instructies worden geëmit. De aanloop-code
     * (landingpad + type-check + rethrow) wordt in het oorspronkelijke
     * handler-blok geëmit, de catch-body in dit aparte blok.
     */
    private final Map<String, LLVMBasicBlockRef> handlerBodyBlocks = new HashMap<>();
    /** De JDK-symbolen die de C-runtime zelf definieert (zie {@link RuntimeSymbols}). */
    private final java.util.Set<String> runtimeSymbols = RuntimeSymbols.load();
    private static final boolean TRACE_OVERRIDES = System.getenv("NABU_TRACE_OVERRIDES") != null
            || Boolean.getBoolean("nabu.traceOverrides");
    /** Handler-label -> stackslot waarin het landingspad de gevangen exception zet. */
    private final Map<String, LLVMValueRef> handlerExceptionSlots = new HashMap<>();

    /**
     * Namen die uitsluitend als ad-hoc extern zijn gedeclareerd (call zonder
     * eigen IRFunction-declaratie). Voor deze namen mag stap 2.5 in
     * {@link LLVMModuleEmitter} later een tweede, correctere paramlijst
     * toevoegen; voor echte IRFunction-namen mag dat niet, omdat dat de
     * werkelijk geëmitteerde functie zou vervangen door een lege stub.
     */
    private final Set<String> adHocExternalNames = new HashSet<>();

    /**
     * LLVM-functies van {@code <clinit>}-functies (statische
     * initializers). Deze worden nooit door de JVM-semantiek expliciet
     * aangeroepen; de native backend registreert ze als constructors in
     * {@code @llvm.global_ctors} zodat ze vóór {@code main} draaien.
     */
    private final List<LLVMValueRef> clinitFunctions = new ArrayList<>();

    /**
     * Layouts van de klassen in de gecompileerde modules. Nodig om bij het
     * declareren van een ad-hoc extern te bepalen of de callee eigenlijk een
     * interface-methode is (die een itable-entry nodig heeft) of een gewone
     * vtable-call.
     */
    private final ClassLayouts classLayouts;

    /**
     * {@code true} als {@code fnName} alleen via
     * {@link #declareSignature(IRInstruction.Call)} is gedeclareerd.
     */
    public boolean isAdHocExternal(final String fnName) {
        return fnName != null && adHocExternalNames.contains(fnName);
    }

    public FunctionEmitter(LLVMContextRef ctx,
                           LLVMModuleRef mod,
                           LLVMBuilderRef builder,
                           TypeMapper types,
                           ConstantResolver constants,
                           InstructionEmitter instructions,
                           Map<String, LLVMValueRef> globalValueMap,
                           Map<String, LLVMBasicBlockRef> blockMap,
                           ClassLayouts classLayouts) {
        this.ctx            = ctx;
        this.mod            = mod;
        this.builder        = builder;
        this.types          = types;
        this.constants      = constants;
        this.instructions   = instructions;
        this.globalValueMap = globalValueMap;
        this.blockMap       = blockMap;
        this.classLayouts   = classLayouts;
    }

    private String currentFnNameForDiagnostics;

    public String currentFnNameForDiagnostics() {
        return currentFnNameForDiagnostics;
    }

    /**
     * Retourneert het LLVM-blok voor de catch-body van een handler (of null
     * als het blok geen handler is). Wordt gebruikt door emitBody om de
     * instructies van een catch-handler in het juiste blok te emitteren.
     */
    public LLVMBasicBlockRef getHandlerBodyBlock(String blockLabel) {
        return handlerBodyBlocks.get(blockLabel);
    }

    /**
     * Retourneert de handler-label voor een blok als het in een try-regio zit,
     * of {@code null} als het blok niet beschermd is.
     * Wordt aangeroepen door InstructionEmitter om te beslissen of een
     * {@code call} moet worden omgezet naar {@code invoke}.
     */
    public String getHandlerLabel(String blockLabel) {
        return tryHandlerMap.get(blockLabel);
    }

    // -------------------------------------------------------
    // Signatuur declareren (stap 1 — vóór bodies)
    // -------------------------------------------------------

    /**
     * Declareert de functie-signatuur in de LLVM-module.
     * Mag worden aangeroepen voor wederzijds-recursieve functies.
     */
    public void declareSignature(IRFunction fn) {
        final String baseSymbol = symbolOf(fn);
        final List<IRType> paramKey = FnKeys.declaredParamsOf(fn);
        // Botsingsvrije, deterministische naam vóór LLVMAddFunction: zonder
        // dit uniekt LLVM een naam die al bestaat zelf met een volgnummer uit
        // de symbolentabel (zie FnKeys.stableSymbol's javadoc), en dan wijst
        // de "@"+baseSymbol-registratie hieronder niet meer naar de ECHTE
        // (hernoemde) functie. Twee overloads die de IR-naam delen (bv.
        // `Builder_ansi` voor zowel `ansi()` als `ansi(Ansi)`) overschreven
        // elkaar zo stilletjes in globalValueMap, waardoor BEIDE vtable-slots
        // naar de laatst-gedeclareerde overload wezen (crash in
        // ColorScheme.Builder-dispatch: zie ColorScheme_init-SIGSEGV).
        final String symbol = FnKeys.stableSymbol(mod, baseSymbol, paramKey);
        LLVMTypeRef[] paramArray = types.mapParams(fn.params);
        LLVMTypeRef   retType    = types.map(fn.returnType);

        PointerPointer<Pointer> paramTypes = types.toPointerPointer(paramArray);
        LLVMTypeRef   fnType  = LLVMFunctionType(retType, paramTypes,
                                    fn.params.size(), 0);
        LLVMValueRef  llvmFn  = LLVMAddFunction(mod,
                                    new BytePointer(symbol), fnType);

        Reference.reachabilityFence(paramTypes);
        Reference.reachabilityFence(paramArray);

        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                "LLVMAddFunction failed for: " + symbol);

        // Externe functies: ExternalLinkage, geen body
        if (fn.isExternal())
            LLVMSetLinkage(llvmFn, LLVMExternalLinkage);

        // Een constructor die een methode met dezelfde IR-naam deelt, staat
        // onder een eigen symbool (`Impl_init$ctor`). Alleen díe sleutels
        // worden geschreven: de gewone naam en de gewone profiel-sleutel
        // horen bij de methode, anders wijst het vtable-slot naar de
        // constructor in plaats van naar de methode.
        globalValueMap.put("@" + symbol, llvmFn);
        // Overload-sleutel: dezelfde fn onder `<naam>#<params>` registreren
        // (Range.min()/Range.min(int) deelden voorheen één LLVM-signature,
        // waardoor GetParam(i) van de latere overload garbage opleverde).
        globalValueMap.put("@" + FnKeys.fnKey(baseSymbol, paramKey), llvmFn);
        // Kale naam blijft bij de EERSTE overload horen (zelfde conventie als
        // de ad-hoc call-declaratie hieronder): een latere overload met
        // dezelfde IR-naam overschrijft hem niet meer stilletjes.
        globalValueMap.putIfAbsent("@" + baseSymbol, llvmFn);
        // Extra, klasse-gekwalificeerde sleutel: de kale overload-sleutel
        // hierboven ("Builder_build#") is GEEN garantie tegen botsingen
        // tussen klassen — de IR gebruikt overal de SIMPLE eigenaar
        // ("Builder" voor élke nested Builder-klasse), dus zes verschillende
        // klassen met een no-arg build() deelden letterlijk dezelfde
        // overload-sleutel. emitBody() vond daardoor altijd de LAATST
        // geregistreerde llvmFn terug en plakte ALLE zes method-bodies als
        // losse, onbereikbare basic blocks aan ÉÉN LLVM-functie (het eerste,
        // bereikbare blok bleef een kale 'unreachable' -> SIGSEGV zodra iets
        // die functie aanriep, bv. ColorScheme.Builder.build()). De
        // gekwalificeerde naam (via classLayouts, IdentityHashMap per
        // IRFunction-instantie) IS wél uniek per klasse en voorkomt dat.
        final String qualified = classLayouts != null
                ? classLayouts.qualifiedName(fn) : null;
        if (qualified != null) {
            globalValueMap.put("@" + qualifiedKey(fn, qualified, paramKey), llvmFn);
        }

        if (!fn.isExternal() && baseSymbol.contains("<clinit>")) {
            clinitFunctions.add(llvmFn);
        }
    }

    /**
     * Registreert alle tot nu toe gedecleareerde {@code <clinit>}-functies als
     * constructors in {@code @llvm.global_ctors}, zodat statische velden
     * geïnitialiseerd zijn vóór {@code main} (JVM doet dit lazy; de native
     * backend kiest voor eager — dezelfde aanpak als deel-runtime's reflectie-
     * en GC-init).
     */
    public void registerClinitCtors(final ModuleCtors ctors) {
        for (LLVMValueRef clinit : clinitFunctions) {
            ctors.add(clinit);
        }
    }

    /**
     * Het LLVM-symbool waaronder {@code fn} gedeclareerd wordt.
     *
     * <p>Een constructor die dezelfde IR-naam heeft als een methode van dezelfde
     * klasse krijgt een eigen symbool, zodat de gewone naam altijd de methode
     * blijft. Overloads van methoden hebben elk hun eigen overload-sleutel en
     * raken elkaar niet.
     */
    private String symbolOf(final IRFunction fn) {
        final String ctorSymbol = classLayouts == null
                ? null
                : classLayouts.constructorSymbol(fn);
        return ctorSymbol != null ? ctorSymbol : fn.name;
    }

    /**
     * Klasse-gekwalificeerde overload-sleutel voor {@code fn}, uniek per
     * IRFunction-instantie. Een constructor die de IR-naam met een methode
     * deelt, levert dezelfde gekwalificeerde naam op (beide
     * {@code Owner_init}); zonder de extra symbool-suffix zou de
     * constructor-registratie de sleutel van de methode overschrijven en wees
     * het vtable-slot van de methode naar de constructor.
     */
    private String qualifiedKey(final IRFunction fn, final String qualified,
                                final List<IRType> paramKey) {
        final String symbol = symbolOf(fn);
        final String q = symbol.equals(fn.name)
                ? qualified
                : qualified + symbol.substring(fn.name.length());
        return FnKeys.fnKey(q, paramKey);
    }

    /**
     * Declareert een externe callee die als (ad-hoc) call in een IR-module
     * voorkomt zonder eigen IRFunction-declaratie (bv. interface-calls
     * java.util.Iterator_hasNext). De signatuur volgt de call-instructie.
     */
    public void declareSignature(IRInstruction.Call call) {
        final String fnName = call.function();

        // Een niet-statische call draagt de JVM-receiver als eerste argument;
        // de LLVM-declaratie moet die ook hebben, anders wijzen zowel de
        // functietype als de overload-sleutel naar een lijst die één argument
        // korter is dan wat er later daadwerkelijk wordt meegegeven.
        final List<IRType> callParams = FnKeys.callParams(call);
        LLVMTypeRef[] paramArray = new LLVMTypeRef[callParams.size()];
        for (int i = 0; i < paramArray.length; i++) {
            paramArray[i] = types.map(callParams.get(i));
        }
        LLVMTypeRef retType = types.map(call.returnType());

        PointerPointer<Pointer> paramTypes = types.toPointerPointer(paramArray);
        LLVMTypeRef fnType = LLVMFunctionType(retType, paramTypes,
                paramArray.length, 0);
        // Bezette naam (overload)? Dan een deterministische suffix i.p.v.
        // LLVM's volgnummer-unieking — zie FnKeys.stableSymbol.
        final String symbol = FnKeys.stableSymbol(mod, fnName, callParams);
        LLVMValueRef llvmFn = LLVMAddFunction(mod,
                new BytePointer(symbol), fnType);

        Reference.reachabilityFence(paramTypes);
        Reference.reachabilityFence(paramArray);

        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                    "LLVMAddFunction failed for: " + symbol);

        LLVMSetLinkage(llvmFn, LLVMExternalLinkage);
        adHocExternalNames.add(fnName);
        globalValueMap.put("@" + symbol, llvmFn);
        // De gewone naam blijft bij de eerste (kaal-genaamde) overload
        // horen: een latere overload overschrijft hem niet, anders zou een
        // legacy-lookup op de kale naam aan een verkeerd symbool binden.
        globalValueMap.putIfAbsent("@" + fnName, llvmFn);
        globalValueMap.put("@" + FnKeys.fnKey(fnName, callParams), llvmFn);
    }

    // -------------------------------------------------------
    // Body emitteren (stap 2)
    // -------------------------------------------------------

    /**
     * Emitteert de functie-body.
     * Externe functies worden overgeslagen.
     */
    public void emitBody(IRFunction fn) {
        if (fn.isExternal()) return;
        currentFnNameForDiagnostics = fn.name;

        // Per beschermd blok de binnenste handler-keten (zie de pre-scan).
        Map<String, HandlerChain> chainByProtected = new HashMap<>();

        final String symbol = symbolOf(fn);
        final List<IRType> paramKey = FnKeys.declaredParamsOf(fn);
        // Eerst de klasse-gekwalificeerde sleutel (uniek per IRFunction-
        // instantie, dus per klasse): zonder deze stap vindt een kale
        // overload-sleutel die door meerdere klassen wordt gedeeld (bv.
        // "Builder_build#", gedeeld door elke nested Builder-klasse met een
        // no-arg build()) altijd de LAATST gedeclareerde llvmFn, en plakt
        // deze body als een los, onbereikbaar basic block aan de verkeerde
        // (reeds-bezette) functie — zie declareSignature().
        final String qualified = classLayouts != null
                ? classLayouts.qualifiedName(fn) : null;
        LLVMValueRef llvmFn = qualified != null
                ? globalValueMap.get("@" + qualifiedKey(fn, qualified, paramKey))
                : null;
        final var overloadKey = FnKeys.fnKey(symbol, paramKey);
        if (llvmFn == null || llvmFn.isNull()) {
            llvmFn = globalValueMap.get("@" + overloadKey);
        }
        if (llvmFn == null || llvmFn.isNull()) {
            llvmFn = globalValueMap.get("@" + symbol);
        }
        if (llvmFn == null || llvmFn.isNull())
            throw new IllegalStateException(
                "Function not declared: " + overloadKey);

        // Substitutie: levert de C-runtime dit symbool, dan wint die
        // implementatie en blijft de functie hier een externe declaratie.
        // Geldt voor VM-gebonden JDK-methoden (System, Class, Thread,
        // reflectie, ...) waarvan de bytecode zonder VM-opstart en natives
        // niet kan werken.
        if (runtimeSymbols.contains(LLVMGetValueName(llvmFn).getString())) {
            // Een echte (gecompileerde) body die de C-runtime vervangt: het
            // mixen van beide representaties is een bekende valkuil (een
            // C-StringBuilder.append op een gecompileerd object verloor zijn
            // tekst). NABU_TRACE_OVERRIDES=1 / -Dnabu.traceOverrides toont welke.
            if (!fn.blocks().isEmpty() && TRACE_OVERRIDES) {
                System.err.println("[nabu/LLVM] C-runtime vervangt gecompileerde body: "
                        + LLVMGetValueName(llvmFn).getString());
            }
            return;
        }

        // Schone lokale staat per functie
        Map<String, LLVMValueRef> localMap = new HashMap<>();
        blockMap.clear();
        tryHandlerMap.clear();
        // Ook handlerBodyBlocks: het is een veld op deze (hergebruikte)
        // emitter. Zonder clear zou een volgende functie met een bloklabel
        // 'try.catch.0' het LLVM-blok van een EERDERE functie terugvinden en
        // zijn instructies daar in emiteren: landingpads hopen zich dan op in
        // één functie en verwijzingen wijzen naar een andere functie.
        handlerBodyBlocks.clear();
        handlerExceptionSlots.clear();
        instructions.setLocalValueMap(localMap);
        instructions.setFunctionEmitter(this);
        currentFnNameForDiagnostics = fn.name;

        // Parameters koppelen
        final int declaredParams = LLVMCountParams(llvmFn);
        if (declaredParams != fn.params.size()) {
            throw new IllegalStateException(
                    "Overload-naming-collision: " + overloadKey
                            + " declares " + declaredParams + " params, expected "
                            + fn.params.size());
        }
        for (int i = 0; i < fn.params.size(); i++) {
            LLVMValueRef param     = LLVMGetParam(llvmFn, i);
            String       paramName = IRValue.nameOf(fn.params.get(i));
            if (param == null || param.isNull()) {
                throw new IllegalStateException(
                        "LLVMGetParam(" + i + ") null for " + fn.name
                                + " (declaredParamCount=" + declaredParams + ")");
            }
            LLVMSetValueName2(param,
                new BytePointer(paramName), paramName.length());
            localMap.put(paramName, param);
        }

        // Alle basisblokken aanmaken (voor vooruitverwijzingen in branches)
        for (IRBasicBlock block : fn.blocks()) {
            LLVMBasicBlockRef bb = LLVMAppendBasicBlockInContext(ctx, llvmFn,
                    new BytePointer(block.label()));
            blockMap.put(block.label(), bb);
        }

        // Pre-scan: TryCatchRegion-metadata → per try-range de handler-keten.
        // Elke range krijgt één landingspad met daarachter een keten van
        // type-checks; een exception die geen enkele clause matcht gaat naar
        // @nabu_resume zodat de unwind doorloopt (in plaats van opnieuw te
        // worden gegooid, wat de buitenste handler zou overslaan).
        List<String> blockOrder = fn.blocks().stream()
                .map(IRBasicBlock::label)
                .toList();

        // Handlers per range (tryStart -> tryEnd), in IR-volgorde. De eerste
        // handler uit die volgorde krijgt het landingspad.
        Map<String, List<IRInstruction.TryCatchRegion>> regionsByRange =
                new LinkedHashMap<>();
        for (IRBasicBlock block : fn.blocks()) {
            for (var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion tc) {
                    regionsByRange
                            .computeIfAbsent(rangeKey(tc.tryStartLabel(), tc.tryEndLabel()),
                                    k -> new ArrayList<>())
                            .add(tc);
                }
            }
        }

        // Per range één keten; de eerste handler-label is het landingspad.
        List<HandlerChain> chains = new ArrayList<>();
        Map<String, HandlerChain> chainByLanding = new LinkedHashMap<>();
        for (var entry : regionsByRange.entrySet()) {
            List<IRInstruction.TryCatchRegion> regions = entry.getValue();
            HandlerChain chain = new HandlerChain(
                    regions.get(0).handlerLabel(),
                    regions.stream().map(r -> r.handlerLabel()).toList(),
                    regions.stream().map(r -> r.exceptionType()).toList(),
                    blockOrder.indexOf(regions.get(0).tryStartLabel()),
                    blockOrder.indexOf(regions.get(0).tryEndLabel()));
            chains.add(chain);
            chainByLanding.put(chain.landingLabel(), chain);
        }

        // tryHandlerMap: per beschermd blok de handler waar de invoke naartoe
        // unwindt. Bij geneste ranges wint de binnenste (kleinste) range:
        // het throw-blok van de binnenste try moet de binnenste handler
        // bereiken, niet de buitenste.
        for (HandlerChain chain : chains) {
            int startIdx = chain.startIdx();
            int endIdx = chain.endIdx();
            for (int i = startIdx; i < endIdx; i++) {
                String blockLabel = blockOrder.get(i);
                HandlerChain current = chainByProtected.get(blockLabel);
                if (current == null || isInnerRange(chain, current)) {
                    chainByProtected.put(blockLabel, chain);
                    tryHandlerMap.put(blockLabel, chain.landingLabel());
                }
            }
        }

        // Phi-slot allocas: SSA-phi-artefacten (`%phi...`-temps) draaien via
        // gedeelde stackslots (wat het ASM-backend via slot-allocatie doet).
        // Zonder een alloca kan de Store/Load op zo'n temp niet resolven.
        Map<String, LLVMTypeRef> phiSlots = new HashMap<>();
        for (IRBasicBlock block : fn.blocks()) {
            for (var instr : block.instructions()) {
                collectPhiTemps(instr, phiSlots);
            }
        }
        if (!phiSlots.isEmpty()) {
            LLVMBasicBlockRef entryBlock = blockMap.get(fn.blocks().get(0).label());
            // Boven aan het entry-blok plaatsen (het entry kan al terminator
            // bevatten na optimizer-reductie; post-terminator-geinstrueer brengt
            // de LLVM-builder in native disarray).
            LLVMValueRef firstInstr = LLVMGetFirstInstruction(entryBlock);
            if (firstInstr != null && !firstInstr.isNull()) {
                LLVMPositionBuilder(builder, entryBlock, firstInstr);
            } else {
                LLVMPositionBuilderAtEnd(builder, entryBlock);
            }
            for (var entry : phiSlots.entrySet()) {
                LLVMValueRef slotPtr = LLVMBuildAlloca(
                        builder, entry.getValue(),
                        new BytePointer("." + entry.getKey()));
                localMap.put(entry.getKey(), slotPtr);
                instructions.setLocalValueMap(localMap);
            }
            if (firstInstr != null && !firstInstr.isNull()) {
                LLVMPositionBuilderAtEnd(builder, entryBlock);
            }
        }

        // Eén body-blok en één exception-slot per handler-label, gedeeld door
        // alle try-ranges die naar die handler leiden. Bytecode laat dezelfde
        // handler door meerdere ranges gebruiken (bv. een finally die in
        // stukken is geknipt, of een handler die in de ene range de eerste en
        // in een andere een latere clause is); een body-blok per range zou
        // alle blokken behalve het laatste zonder instructies achterlaten.
        if (!chains.isEmpty()) {
            LLVMBasicBlockRef entryBlock = blockMap.get(fn.blocks().get(0).label());
            LLVMValueRef firstInstr = LLVMGetFirstInstruction(entryBlock);
            if (firstInstr != null && !firstInstr.isNull()) {
                LLVMPositionBuilder(builder, entryBlock, firstInstr);
            } else {
                LLVMPositionBuilderAtEnd(builder, entryBlock);
            }
            for (HandlerChain chain : chains) {
                for (String handlerLabel : chain.handlerLabels()) {
                    if (handlerBodyBlocks.containsKey(handlerLabel)) continue;
                    handlerBodyBlocks.put(handlerLabel,
                            LLVMAppendBasicBlockInContext(ctx, llvmFn,
                                    new BytePointer("handler." + handlerLabel + ".body")));
                    handlerExceptionSlots.put(handlerLabel, LLVMBuildAlloca(
                            builder, LLVMPointerTypeInContext(ctx, 0),
                            new BytePointer(".exn.slot." + handlerLabel)));
                }
            }
            LLVMPositionBuilderAtEnd(builder, entryBlock);
        }

        // Personality functie instellen als er try-regio's zijn
        if (!chains.isEmpty()) {
            LLVMValueRef personality = globalValueMap.get("@__gcc_personality_v0");
            if (personality != null && !personality.isNull()) {
                LLVMSetPersonalityFn(llvmFn, personality);
            }
        }

        // REFCOUNT: escape-analyse vóórdat de body geëmitteerd wordt, zodat de
        // retain/release-keuzes bij elke allocatie al bekend zijn.
        instructions.refCounts().beginFunction(fn, llvmFn);

        // REFCOUNT: de initialisatie-vlaggen per lokale referentie-slot staan
        // bovenaan het entry-blok. De builder staat nog aan het einde van het
        // laatste alloca uit de phi-slot-lus, dus positioneer hem opnieuw.
        if (instructions.refCounts().isEnabled()) {
            LLVMBasicBlockRef entry = blockMap.get(fn.blocks().get(0).label());
            LLVMValueRef firstInstr = LLVMGetFirstInstruction(entry);
            if (firstInstr != null && !firstInstr.isNull()) {
                LLVMPositionBuilder(builder, entry, firstInstr);
            } else {
                LLVMPositionBuilderAtEnd(builder, entry);
            }
            instructions.refCounts().emitSlotInitFlags();
        }

        // Instructies emitteren per blok, in reverse post-order: zo is de
        // definitie van elke SSA-waarde al geëmitteerd vóór elk gebruik dat ze
        // domineert. In IR-volgorde kwam bv. een lus-update-blok vóór de
        // body-tail die de nieuwe waarde berekent; resolveValue gaf dan undef
        // en picocli's quoteElements verloor zijn accumulator (`result += ..`).
        // De LLVM-bloklayout blijft de IR-volgorde (blockMap is al aangemaakt).
        final Map<String, List<String>> exceptionSuccessors = new HashMap<>();
        for (var entry : chainByProtected.entrySet()) {
            exceptionSuccessors.put(entry.getKey(), entry.getValue().handlerLabels());
        }
        // Alle lokale slots vooraf: de bytecode-lowering plaatst een Alloca waar
        // de variabele voor het eerst geschreven wordt, en in reverse
        // post-order kan een store elders dan eerder aan de beurt zijn
        // (`store .., ptr undef`). emitAlloca zet ze sowieso in het entry-blok.
        final java.util.Set<IRInstruction> preEmitted =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (!fn.blocks().isEmpty()) {
            LLVMPositionBuilderAtEnd(builder, blockMap.get(fn.blocks().get(0).label()));
        }
        for (IRBasicBlock block : fn.blocks()) {
            for (var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Alloca) {
                    instructions.emit(instr);
                    preEmitted.add(instr);
                }
            }
        }
        for (IRBasicBlock block : reversePostOrder(fn, exceptionSuccessors)) {
            // Een catch-handler uit een keten heeft geen eigen LLVM-blok meer:
            // de catch-body gaat in het body-blok dat de keten heeft gemaakt,
            // en de oorspronkelijke IR-handlerblok blijft leeg.
            LLVMBasicBlockRef handlerBody = handlerBodyBlocks.get(block.label());
            LLVMPositionBuilderAtEnd(builder, blockMap.get(block.label()));

            // Eén landingspad per try-range, in het blok van de eerste
            // handler uit die range. De catch-body van élke handler uit de
            // range gaat in een eigen body-blok; de type-checks staan
            // geketend achter elkaar en vallen door naar @nabu_resume.
            HandlerChain chain = chainByLanding.get(block.label());
            if (chain != null) {
                emitLandingPadChain(chain);
            }

            if (block == fn.blocks().get(0) && classLayouts != null && fn.name != null
                    && !fn.name.endsWith("_clinit") && !fn.name.contains("<clinit>")
                    && (fn.isConstructor()
                            || io.github.potjerodekool.nabu.lang.Flags.hasFlag(
                                    fn.getFlags(), io.github.potjerodekool.nabu.lang.Flags.STATIC))) {
                // Een statische methode of constructor initialiseert zijn
                // klasse bij het eerste gebruik (JLS §12.4.1).
                instructions.emitClassInit(classLayouts.ownerInternalName(fn.name));
            }

            if (handlerBody != null) {
                // De catch-body leest de exception uit het slot van zijn eigen
                // handler: welk landingspad hem ook bereikte, die heeft hem
                // daar neergezet.
                LLVMPositionBuilderAtEnd(builder, handlerBody);
                String exnName = "%exn." + block.label();
                LLVMValueRef exn = LLVMBuildLoad2(builder,
                        LLVMPointerTypeInContext(ctx, 0),
                        handlerExceptionSlots.get(block.label()), exnName);
                instructions.getLocalValueMap().put(exnName, exn);
            }

            for (var instr : block.instructions()) {
                if (instr instanceof IRInstruction.TryCatchRegion) continue;
                if (preEmitted.contains(instr)) continue;
                instructions.emit(instr);
            }

            // Een handlerblok dat alleen nog zijn TryCatchRegion-metadata
            // bevatte (de body is naar het body-blok verhuisd) heeft nu geen
            // instructies en dus geen terminator: het pad is via het body-blok
            // onbereikbaar, dus unreachable is hier correct.
            if (handlerBody != null
                    && LLVMGetFirstInstruction(blockMap.get(block.label())) == null) {
                LLVMPositionBuilderAtEnd(builder, blockMap.get(block.label()));
                LLVMBuildUnreachable(builder);
            }
        }

        // REFCOUNT: de ene exit-pad afronden (returns sprongen er tijdens het
        // emitteren naartoe).
        instructions.refCounts().finishFunction();
    }

    /**
     * De blokken van {@code fn} in reverse post-order vanaf het entry-blok,
     * over normale sprongen en exception-randen (beschermd blok → handlers).
     * Onbereikbare blokken volgen daarna in IR-volgorde.
     */
    static List<IRBasicBlock> reversePostOrder(final IRFunction fn,
                                               final Map<String, List<String>> exceptionSuccessors) {
        final List<IRBasicBlock> blocks = fn.blocks();
        if (blocks.isEmpty()) {
            return blocks;
        }
        final Map<String, IRBasicBlock> byLabel = new HashMap<>();
        for (IRBasicBlock block : blocks) {
            byLabel.put(block.label(), block);
        }
        final java.util.Set<String> visited = new java.util.HashSet<>();
        final List<IRBasicBlock> postOrder = new java.util.ArrayList<>();
        // Iteratieve DFS (diepe CFG's van grote methodes).
        final java.util.Deque<java.util.Iterator<String>> stack = new java.util.ArrayDeque<>();
        final java.util.Deque<IRBasicBlock> path = new java.util.ArrayDeque<>();
        final IRBasicBlock entry = blocks.get(0);
        visited.add(entry.label());
        path.push(entry);
        stack.push(successorsOf(entry, exceptionSuccessors).iterator());
        while (!stack.isEmpty()) {
            final var it = stack.peek();
            if (it.hasNext()) {
                final IRBasicBlock next = byLabel.get(it.next());
                if (next != null && visited.add(next.label())) {
                    path.push(next);
                    stack.push(successorsOf(next, exceptionSuccessors).iterator());
                }
            } else {
                stack.pop();
                postOrder.add(path.pop());
            }
        }
        final List<IRBasicBlock> order = new java.util.ArrayList<>(postOrder.size());
        for (int i = postOrder.size() - 1; i >= 0; i--) {
            order.add(postOrder.get(i));
        }
        for (IRBasicBlock block : blocks) {
            if (!visited.contains(block.label())) {
                order.add(block);
            }
        }
        return order;
    }

    private static List<String> successorsOf(final IRBasicBlock block,
                                             final Map<String, List<String>> exceptionSuccessors) {
        final List<String> successors = new java.util.ArrayList<>();
        for (var instr : block.instructions()) {
            if (instr instanceof IRInstruction.Branch branch) {
                successors.add(branch.targetLabel());
            } else if (instr instanceof IRInstruction.CondBranch condBranch) {
                successors.add(condBranch.trueLabel());
                successors.add(condBranch.falseLabel());
            }
        }
        final List<String> handlers = exceptionSuccessors.get(block.label());
        if (handlers != null) {
            successors.addAll(handlers);
        }
        return successors;
    }

    /** Een try-range met al zijn handlers, in IR-volgorde. */
    private record HandlerChain(
            String landingLabel,
            List<String> handlerLabels,
            List<String> exceptionTypes,
            int startIdx,
            int endIdx) {
    }

    /** Sleutel voor een try-range: beide grensblokken bepalen de range. */
    private static String rangeKey(final String tryStart, final String tryEnd) {
        return tryStart + "->" + tryEnd;
    }

    /**
     * Of {@code candidate} binnen {@code current} ligt. Bij geneste ranges
     * hoort een throw de binnenste handler te bereiken, dus de range met de
     * hoogste start (bij gelijke start de kleinste spanne) wint.
     */
    private static boolean isInnerRange(final HandlerChain candidate,
                                        final HandlerChain current) {
        if (candidate.startIdx() != current.startIdx()) {
            return candidate.startIdx() > current.startIdx();
        }
        return candidate.endIdx() < current.endIdx();
    }

    /** Verzamelt alle `%phi...`-temps van een instructie met hun IRType. */
    private void collectPhiTemps(final IRInstruction instr,
                                 final Map<String, LLVMTypeRef> into) {
        switch (instr) {
            case IRInstruction.Store st -> addPhiValue(st.ptr(), into);
            case IRInstruction.Load ld -> addPhiValue(ld.ptr(), into);
            case IRInstruction.BinaryOp op -> {
                addPhiValue(op.left(), into);
                addPhiValue(op.right(), into);
            }
            case IRInstruction.Call call -> addPhiValues(call.args(), into);
            case IRInstruction.Cast c -> addPhiValue(c.source(), into);
            case IRInstruction.Phi phi -> {
                addPhiValue(phi.result(), into);
                for (var incoming : phi.incomingValues()) {
                    addPhiValue(incoming.value(), into);
                }
            }
            case IRInstruction.Move m -> { addPhiValue(m.value(), into); }
            case IRInstruction.Return r -> addPhiValue(r.value(), into);
            case IRInstruction.CondBranch cb -> addPhiValue(cb.condition(), into);
            default -> {
            }
        }
    }

    private void addPhiValue(final IRValue value,
                             final Map<String, LLVMTypeRef> into) {
        if (value == null) return;
        if (value instanceof IRValue.Temp t
                && t.name().startsWith("%phi")
                && !into.containsKey(t.name())
                && t.type() != null) {
            // De phi-temp HÉÉLT de waarde: het slot moet dus groot genoeg zijn
            // voor die waarde zelf. `t.type()` is het alloca-elementtype, precies
            // wat de ASM-backend via SlotAllocator.slotOfAlloca reserveert.
            // Een extra pointee-unwrap zou een referentie-Ptr tot `i8` afbreken
            // (1 byte) terwijl de store er 8 bytes in wegschrijft.
            into.put(t.name(), types.map(t.type()));
        }
    }

    private void addPhiValues(final List<? extends IRValue> values,
                              final Map<String, LLVMTypeRef> into) {
        if (values == null) return;
        for (var v : values) addPhiValue(v, into);
    }

    /**
     * Emitteert het landingspad van één try-range plus de geketende
     * type-checks van al zijn handlers.
     *
     * De gegenereerde structuur (voor de handlers h0, h1, ... van de range):
     *   %&lt;h0&gt;:
     *     %lpad = landingpad { ptr, i32 } cleanup
     *     %raw   = extractvalue %lpad, 0
     *     %exn.&lt;h0&gt; = call i8* @nabu_catch(ptr %raw)
     *     %match.0 = call i32 @nabu_can_catch(ptr %exn.&lt;h0&gt;, i8* @.str.&lt;t0&gt;)
     *     br i1 %ok.0, label %handler.&lt;h0&gt;.body, label %handler.&lt;h0&gt;.check.1
     *   %handler.&lt;h0&gt;.check.1:
     *     %match.1 = call i32 @nabu_can_catch(ptr %exn.&lt;h0&gt;, i8* @.str.&lt;t1&gt;)
     *     br i1 %ok.1, label %handler.&lt;h1&gt;.body, label %handler.&lt;h0&gt;.resume
     *   %handler.&lt;h0&gt;.resume:
     *     call void @nabu_resume(ptr %exn.&lt;h0&gt;)
     *     unreachable
     *   %handler.&lt;hi&gt;.body:            &lt;- hierin wordt de catch-body geëmit
     *
     * <p>Een catch-all (geen exceptionType) springt rechtstreeks naar zijn
     * body: hij vat alles, dus de keten stopt daar.
     *
     * <p>Het pad eindigt bij {@code @nabu_resume} en niet bij
     * {@code @nabu_throw}: de exception wordt niet opnieuw gegooid maar de
     * unwind vervolgd, zodat een buitenliggende handler hem nog kan afvangen.
     */
    private void emitLandingPadChain(final HandlerChain chain) {
        final String landingLabel = chain.landingLabel();
        final List<String> handlerLabels = chain.handlerLabels();
        final List<String> exceptionTypes = chain.exceptionTypes();

        LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
        LLVMTypeRef i32   = LLVMInt32TypeInContext(ctx);
        LLVMTypeRef lpType = LLVMStructTypeInContext(ctx,
                new PointerPointer<>(new LLVMTypeRef[]{i8Ptr, i32}), 2, 0);

        // Landingpad met een echte catch-clause zodat LLVM een LSDA
        // (.gcc_except_table) emitteert. Zonder een echte catch-clause zou LLVM
        // geen LSDA genereren en kan de nabu-persoonlijkheid
        // (nabu_seh_personality) het landingspad niet vinden.
        //
        // NB: de type-match gebeurt in code via nabu_can_catch, dus de clause
        // hoef hier geen specifiek type-info te zijn; elke gegarandeerd
        // aanwezige typeinfo-global dient als LSDA-trigger. Die wordt in
        // LLVMModuleEmitter als `@_ZNabunabu.exceptionsEtype_info` geboord.
        LLVMValueRef personality = globalValueMap.get("@__gcc_personality_v0");
        LLVMValueRef landingPad = LLVMBuildLandingPad(builder, lpType,
                personality != null ? personality : LLVMConstNull(i8Ptr),
                1, new BytePointer("lpad"));
        LLVMSetCleanup(landingPad, 1);

        LLVMValueRef excTypeInfo = globalValueMap.get("@nabu_exception_typeinfo");
        if (excTypeInfo != null) {
            LLVMAddClause(landingPad, excTypeInfo);
        } else {
            // Uiterste valback: geen clause (dan geen LSDA, maar geen crash).
            LLVMValueRef anyTypeInfo = globalValueMap.get("@_ZNabuMainEtype_info");
            if (anyTypeInfo != null) {
                LLVMAddClause(landingPad, anyTypeInfo);
            }
        }

        // Raw exception pointer extraheren en nabu_catch() -> nabu_object*
        LLVMValueRef rawExPtr = LLVMBuildExtractValue(builder, landingPad, 0,
                new BytePointer("%raw_exn." + landingLabel));
        LLVMValueRef catchFn = globalValueMap.get("@nabu_catch");
        if (catchFn == null) {
            throw new IllegalStateException("Runtime helper @nabu_catch not defined");
        }
        String exnName = "%exn." + landingLabel;
        LLVMTypeRef catchFnType = getNabuCatchType();
        PointerPointer<Pointer> catchArgs = new PointerPointer<>(1);
        catchArgs.put(0, rawExPtr);
        LLVMValueRef exPtr = LLVMBuildCall2(builder, catchFnType, catchFn, catchArgs, 1,
                exnName);
        Reference.reachabilityFence(catchArgs);
        // De exception-pointer wordt door álle checks én het resume-blok gebruikt.
        // Hij moet daarom de hele keten overleven: zonder deze sterke referentie
        // kan JavaCPP de wrapper collecteren zodra er nieuwe blokken bij komen en
        // wordt de pointer dangling, wat in LLVMBuildCall2 segfault geeft.
        final List<LLVMValueRef> keepAlive = new ArrayList<>();
        keepAlive.add(exPtr);
        keepAlive.add(catchFn);
        instructions.getLocalValueMap().put(exnName, exPtr);

        // Function-eigenaar voor de nieuwe blokken
        LLVMValueRef currentFn = LLVMGetBasicBlockParent(LLVMGetInsertBlock(builder));

        // Het (gedeelde) body-blok per handler uit de range; de exception gaat
        // via het slot van elke handler naar zijn body.
        List<LLVMBasicBlockRef> bodyBlocks = new ArrayList<>(handlerLabels.size());
        for (String handlerLabel : handlerLabels) {
            bodyBlocks.add(handlerBodyBlocks.get(handlerLabel));
            LLVMBuildStore(builder, exPtr, handlerExceptionSlots.get(handlerLabel));
        }
        // Eén resume-blok: geen enkele clause matcht, dus de unwind gaat door.
        LLVMBasicBlockRef resumeBlock = LLVMAppendBasicBlockInContext(ctx, currentFn,
                new BytePointer("handler." + landingLabel + ".resume"));

        LLVMValueRef canCatchFn = globalValueMap.get("@nabu_can_catch");
        if (canCatchFn == null) {
            throw new IllegalStateException("Runtime helper @nabu_can_catch not defined");
        }

        // De body van de laatste handler is het blok waarin de catch-body-
        // instructies van de landingspad-handler worden geëmit; de overige
        // body-blokken worden vanuit de check-keten bereikt.
        for (int i = 0; i < handlerLabels.size(); i++) {
            final String exType = exceptionTypes.get(i);
            final boolean last = i == handlerLabels.size() - 1;

            if (exType == null) {
                // catch-all: geen type-check, spring direct naar de body.
                LLVMBuildBr(builder, bodyBlocks.get(i));
                // De keten stopt hier: elke clause erna is vanuit deze range
                // onbereikbaar. Hun (gedeelde) body-blokken krijgen hun
                // instructies gewoon bij het emitteren van hun eigen IR-blok.
                // Het resume-blok is nu zonder springdoel; geef het een
                // terminator zodat het geen blok zonder terminator wordt.
                LLVMPositionBuilderAtEnd(builder, resumeBlock);
                LLVMBuildUnreachable(builder);
                return;
            }

            String typeName = normalizeCatchType(exType);
            LLVMValueRef typeStr = constants.resolveString(typeName,
                    io.github.potjerodekool.nabu.backend.ir.IRGlobal.Linkage.PRIVATE);

            PointerPointer<Pointer> ccParams = new PointerPointer<>(2);
            ccParams.put(0, exPtr);
            ccParams.put(1, typeStr);
            // NB: het param-type-buffer moet TYPE-refs bevatten (LLVMTypeOf van
            // de argumenten), niet de waarde-refs zelf — LLVMTypeRef en
            // LLVMValueRef zijn allebei llvm::Value*, en het doorgeven van
            // waarde-tips aan LLVMFunctionType geeft garbage-paramtypes die
            // LLVMs verifier laten crashen / "call parameter type does not
            // match" melden.
            PointerPointer<Pointer> ccParamTypes = new PointerPointer<>(2);
            ccParamTypes.put(0, LLVMTypeOf(exPtr));
            ccParamTypes.put(1, LLVMTypeOf(typeStr));
            LLVMTypeRef canCatchFnType = LLVMFunctionType(i32, ccParamTypes, 2, 0);
            LLVMValueRef match = LLVMBuildCall2(builder, canCatchFnType, canCatchFn,
                    ccParams, 2, "%catch.match." + i);
            LLVMValueRef ok = LLVMBuildICmp(builder, LLVMIntNE, match,
                    LLVMConstInt(i32, 0, 0), "%catch.ok." + i);
            Reference.reachabilityFence(ccParams);
            Reference.reachabilityFence(ccParamTypes);

            LLVMBasicBlockRef next = last
                    ? resumeBlock
                    : LLVMAppendBasicBlockInContext(ctx, currentFn,
                            new BytePointer("handler." + landingLabel + ".check." + (i + 1)));
            LLVMBuildCondBr(builder, ok, bodyBlocks.get(i), next);
            if (last) break;

            // De volgende check komt in zijn eigen blok te staan, zodat het
            // pad van een eerdere handler niet door de volgende check loopt.
            LLVMPositionBuilderAtEnd(builder, next);
        }

        // Resume-pad: de exception is door geen enkele clause afgevangen, dus
        // de unwind vervolgen (een buitenliggende handler kan hem nog pakken).
        LLVMPositionBuilderAtEnd(builder, resumeBlock);
        LLVMValueRef resumeFn = globalValueMap.get("@nabu_resume");
        if (resumeFn == null) {
            throw new IllegalStateException("Runtime helper @nabu_resume not defined");
        }
        PointerPointer<Pointer> resumeArgs = new PointerPointer<>(1);
        keepAlive.add(resumeFn);
        keepAlive.add(canCatchFn);
        // Het parameter-type van @nabu_resume: exact zoals LLVMModuleEmitter het
        // heeft gedeclareerd (opaque i8ptr), niet het getypte i8* van
        // getNabuCatchType(). Een gemengde typed/opaque-pointer LLVM zou een
        // mismatch hier de call op een type bouwen dat de functie niet heeft,
        // wat in LLVMBuildCall2 segfault geeft.
        PointerPointer<Pointer> resumeParamTypes = new PointerPointer<>(1);
        resumeParamTypes.put(0, LLVMPointerTypeInContext(ctx, 0));
        LLVMTypeRef resumeFnType = LLVMFunctionType(LLVMVoidTypeInContext(ctx),
                resumeParamTypes, 1, 0);
        resumeArgs.put(0, keepAlive.get(0));
        LLVMBuildCall2(builder, resumeFnType, resumeFn, resumeArgs, 1, "");
        LLVMBuildUnreachable(builder);
        Reference.reachabilityFence(resumeFn);
        Reference.reachabilityFence(resumeFnType);
        Reference.reachabilityFence(resumeParamTypes);
        Reference.reachabilityFence(resumeArgs);

        Reference.reachabilityFence(keepAlive);
    }

    /** Normaliseert een catch-type naar de interne naam met slashes (test.Exception -> test/Exception). */
    private static String normalizeCatchType(String exType) {
        if (exType == null) return null;
        String s = ClassLayouts.normalizeInternalName(exType);
        if (s == null) return null;
        return s.replace('.', '/');
    }

    /**
     * Retourneert het LLVM type voor de nabu_catch functie: i8* (i8*)
     */
    private LLVMTypeRef getNabuCatchType() {
        LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
        PointerPointer<Pointer> params = new PointerPointer<>(1);
        params.put(0, i8Ptr);
        return LLVMFunctionType(i8Ptr, params, 1, 0);
    }
}
