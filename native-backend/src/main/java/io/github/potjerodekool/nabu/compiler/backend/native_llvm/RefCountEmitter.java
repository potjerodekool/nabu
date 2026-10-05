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
import java.util.function.Function;

import static org.bytedeco.llvm.global.LLVM.*;

/**
 * Emissie voor {@code GcStrategy.REFCOUNT}.
 *
 * <h2>Gekozen semantiek: sterke eigenaar per toewijzing</h2>
 *
 * Er is precies één eigenaar van een referentie. De eigenaar draagt de
 * teller en geeft hem af op het moment dat de toewijzing doodgaat:
 *
 * <ol>
 *   <li><b>bij allocatie</b> — {@code HeapAlloc}/{@code AllocaArray} (en de
 *       {@code nabu_init_object}-fallback) krijgen één {@code nabu_retain}.
 *       De frame die allocereert is dan de eigenaar.</li>
 *   <li><b>overschrijving van een referentie-slot</b> — een {@code Store} in
 *       een lokale slot-{@code alloca} release't eerst wat erin stond. Dat is
 *       de toewijzing die vrijkomt. De nieuwe waarde <em>verhuist</em> de
 *       eigenaar van de frame naar de slot (geen retain: eigendom gaat over,
 *       het wordt niet gekopieerd).</li>
 *   <li><b>de exit-pad</b> — elke functie krijgt één {@code nabu.rc.exit}
 *       waarin de frame afscheid neemt van wat hij nog bezit: de
 *       frame-eigen allocaties, plus de huidige inhoud van elke lokale
 *       referentie-slot. Elke {@code ret} in de functie wordt een
 *       {@code br} naar die ene pad; de terugkeerwaarde komt via een phi.</li>
 * </ol>
 *
 * <h2>Ontsnapping (escape)</h2>
 *
 * <p>Waarden die de frame verlaten (returnwaarde, object-veld, array-element,
 * call-argument, {@code throw}) worden door de statische analyse als
 * <em>escaping</em> gemarkeerd. Escaping allocaties krijgen géén retain en
 * worden door de exit-pad niet vrijgegeven: de ontvanger bezit ze. Dat maakt
 * de teller per object exact kloppend (elke retain heeft precies één release)
 * en voorkomt vroegtijdig vrijgeven.
 *
 * <p>Een call-argument ontsnapt altijd, behalve voor de helpers in
 * {@link #READ_ONLY_HELPERS}. De backend weet niet wat een callee met een
 * pointer doet, en "lekt" is daar een beter antwoord dan "vrijgegeven terwijl
 * de callee hem nog gebruikt". Het gevolg is dat REFCOUNT in deze vorm
 &gt; elke methode-call die een object meeneemt een lek oplevert. Een
 * interprocedurale analyse is de volgende stap, geen extra hack.
 *
 * <h2>Cycli en object-graphs</h2>
 *
 * <p>Eigendom wordt per toewijzing geteld, niet per bereikbaarheid. Een
 * object dat via een ander object bereikbaar wordt gemaakt, wordt dus niet
 * meegezwapt en niet door de frame vrijgegeven: dat object en alles wat het
 * bereikbaar maakt lekt. Een cyclus is daarom geen speciaal geval, maar wel
 * een bekende, bewuste beperking. Er is geen finalizer-traversaal en geen
 * zwakke teller; wie dat nodig heeft kiest {@code BOEHM}.
 *
 * <h2>Randgevallen</h2>
 *
 * <ul>
 *   <li>Elke lokale referentie-slot krijgt een initialisatie-vlag. Een
 *       nimmer beschreven slot bevat ongeinitialiseerd alloca-geheugen, en
 *       daar een identiteits-guard op loslaten zou een willekeurige pointer
 *       kunnen vrijgeven. Zonder de vlag gebeurt er niets.</li>
 *   <li>Een alloc die in twee verschillende referentie-slots belandt zou
 *       dubbel bevrijd kunnen worden. Zo'n allocatie wordt daarom uit beide
 *       sweep-lijsten gehaald (lek in plaats van double-free).</li>
 *   <li>Een referentie-slot dat in een lus twee keer wordt beschreven kan op
 *       de tweede iteratie twee keer dezelfde waarde releasen. Dat is de
 *       enige constructie die dit schema aantoonbaar onjuist maakt, dus die
 *       wordt geweigerd met een expliciete melding in plaats van stille
 *       heap-corruptie.</li>
 *   <li>Een lokale slot-verwijzing die aan de table ontsnapt (bijv.
 *       {@code return slot;}) maakt de hele slot ontsnappend: de frame
 *       weet dan niet meer wat de ontvanger ermee doet.</li>
 * </ul>
 */
final class RefCountEmitter {

    private final LLVMBuilderRef builder;
    private final LLVMContextRef ctx;
    private final TypeMapper types;
    private final Map<String, LLVMValueRef> globalValueMap;
    private final Function<IRValue, LLVMValueRef> resolver;
    private final boolean enabled;

    // --- per-functie analyse-uitkomst ---
    private IRFunction fn;
    private LLVMValueRef llvmFn;
    /** Allocatie-resultaten in allocatievolgorde. */
    private final List<String> allocs = new ArrayList<>();
    /** Allocaties die de frame ontsnapt: geen retain, geen release. */
    private final Set<String> escapingAllocs = new HashSet<>();
    /** Lokale referentie-slots: naam -> kandidaten (allocs die er ooit in gezet zijn). */
    private final Map<String, List<String>> slotCandidates = new LinkedHashMap<>();
    /** IRType van het element dat elke slot opslaat (nodig om bij de sweep te loaden). */
    private final Map<String, IRType> slotElementTypes = new LinkedHashMap<>();
    /** Slots die ontsnappen: niet door de frame gesweept. */
    private final Set<String> escapingSlots = new HashSet<>();
    /** Aantal Store-instructies per slot, voor de luscontrole. */
    private final Map<String, Integer> slotStoreCount = new LinkedHashMap<>();
    /** Per slot de alloca van de initialisatie-vlag (zie emitSlotInitFlags). */
    private final Map<String, LLVMValueRef> slotInitFlags = new LinkedHashMap<>();
    /** Allocs die op exit direct worden vrijgegeven (niet in een slot beland). */
    private final List<String> freeAtExit = new ArrayList<>();
    /**
     * Allocs die in meer dan één referentie-slot belanden. Zowel de
     * overschrijvings- als de sweep-release laat ze met rust: één release is
     * niet te bewijzen zonder de hele toewijzingsketen te volgen, en een
     * vergeet-release is veiliger dan een dubbele.
     */
    private final Set<String> sharedAllocs = new HashSet<>();

    // --- per-functie emissie-state ---
    /** Allocaties die tot nu toe daadwerkelijk geëmitteerd zijn. */
    private final Set<String> defined = new LinkedHashSet<>();
    private final List<LLVMBasicBlockRef> returnBlocks = new ArrayList<>();
    private final List<LLVMValueRef> returnValues = new ArrayList<>();
    private LLVMBasicBlockRef exitBlock;

    RefCountEmitter(LLVMBuilderRef builder,
                   LLVMContextRef ctx,
                   TypeMapper types,
                   Map<String, LLVMValueRef> globalValueMap,
                   boolean enabled,
                   Function<IRValue, LLVMValueRef> resolver) {
        this.builder = builder;
        this.ctx = ctx;
        this.types = types;
        this.globalValueMap = globalValueMap;
        this.enabled = enabled;
        this.resolver = resolver;
    }

    boolean isEnabled() {
        return enabled;
    }

    // -------------------------------------------------------
    // Analyse
    // -------------------------------------------------------

    /**
     * Start een nieuwe functie: reset de per-functie state en draai de
     * escape-analyse. Wordt aangeroepen vóórdat de body geëmitteerd wordt,
     * zodat de retain/release-keuzes bij elke allocatie al bekend zijn.
     */
    void beginFunction(IRFunction fn, LLVMValueRef llvmFn) {
        this.fn = fn;
        this.llvmFn = llvmFn;
        allocs.clear();
        escapingAllocs.clear();
        slotCandidates.clear();
        slotElementTypes.clear();
        escapingSlots.clear();
        slotStoreCount.clear();
        slotInitFlags.clear();
        freeAtExit.clear();
        sharedAllocs.clear();
        defined.clear();
        returnBlocks.clear();
        returnValues.clear();
        exitBlock = null;
        sweepCandidatesCache = null;
        if (enabled) {
            analyze();
        }
    }

    /**
     * Runtime-helpers die een pointer alleen <em>lezen</em> en dus nooit
     * eigendom overnemen. Voor deze geldt dat een argument niet ontsnapt; voor
     * elke andere call wel, omdat de backend niet weet wat de callee ermee
     * doet. Bewust een korte lijst en niet een patroon: een verkeerd
     * vermelden zou een object voortijdig vrijgeven, en een te veel
     * vermelden lekt alleen.
     */
    private static final Set<String> READ_ONLY_HELPERS = Set.of(
            "nabu_ref_count",     // REFCOUNT-diagnostiek
            "nabu_instanceof",    // typecheck op de header
            "nabu_can_catch"      // typecheck op de header
    );

    private void analyze() {
        final var instrs = new ArrayList<IRInstruction>();
        for (final var block : fn.blocks()) {
            instrs.addAll(block.instructions());
        }

        for (final var instr : instrs) {
            switch (instr) {
                case IRInstruction.HeapAlloc ha -> allocs.add(nameOf(ha.result()));
                case IRInstruction.AllocaArray aa -> allocs.add(nameOf(aa.result()));
                case IRInstruction.Store st -> {
                    if (isLocalSlot(st.ptr())) {
                        final var slot = nameOf(st.ptr());
                        slotStoreCount.merge(slot, 1, Integer::sum);
                        slotCandidates.computeIfAbsent(slot, k -> new ArrayList<>());
                        slotElementTypes.put(slot, st.ptr().type() instanceof IRType.Ptr p
                                ? p.pointee() : st.ptr().type());
                    }
                }
                default -> { }
            }
        }

        // Rechtstreeks ontsnappende waarden (syntactisch; zonder de
        // tussenlaag van slots).
        final var directEscapes = new HashSet<String>();
        for (final var instr : instrs) {
            switch (instr) {
                case IRInstruction.Return r -> mark(r.value(), directEscapes);
                case IRInstruction.Store st when !isLocalSlot(st.ptr()) ->
                        mark(st.value(), directEscapes);
                case IRInstruction.ArrayStore as -> mark(as.value(), directEscapes);
                case IRInstruction.Throw th -> mark(th.result(), directEscapes);
                case IRInstruction.Call c when !READ_ONLY_HELPERS.contains(c.function()) ->
                        c.args().forEach(a -> mark(a, directEscapes));
                case IRInstruction.IndirectCall ic ->
                        ic.args().forEach(a -> mark(a, directEscapes));
                default -> { }
            }
        }

        // Slots: een slot ontsnapt zodra een uit hem geladen waarde
        // ontsnapt. Vervolgens maakt iedere alloc die in een ontsnappende
        // slot belandt zelf ook ontsnappend. Dit herhaalt tot een fixpoint,
        // zodat ook ketens als slot1 <- a; x = load slot1; slot2 <- x;
        // field = load slot2 worden meegenomen.
        final var slotLoads = new LinkedHashMap<String, Set<String>>();
        for (final var instr : instrs) {
            if (instr instanceof IRInstruction.Load ld && isLocalSlot(ld.ptr())) {
                slotLoads.computeIfAbsent(nameOf(ld.ptr()), k -> new LinkedHashSet<>())
                        .add(nameOf(ld.result()));
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;

            // Rechtstreeks ontsnapende allocaties: geen retain, geen release.
            // (Een alloc die in een ontsnappende slot belandt, volgt hieronder.)
            for (final var name : directEscapes) {
                if (allocs.contains(name)) {
                    escapingAllocs.add(name);
                }
            }

            for (final var slot : slotCandidates.keySet()) {
                if (escapingSlots.contains(slot)) continue;
                final var loads = slotLoads.getOrDefault(slot, Set.of());
                if (loads.stream().anyMatch(directEscapes::contains)) {
                    escapingSlots.add(slot);
                    changed = true;
                }
            }

            for (final var slot : escapingSlots) {
                for (final var candidate : slotCandidates.getOrDefault(slot, List.of())) {
                    if (allocs.contains(candidate) && escapingAllocs.add(candidate)) {
                        changed = true;
                    }
                }
            }

            for (final var slot : slotCandidates.keySet()) {
                if (escapingSlots.contains(slot)) continue;
                final var candidates = slotCandidates.get(slot);
                if (candidates.stream().anyMatch(escapingAllocs::contains)) {
                    escapingSlots.add(slot);
                    changed = true;
                }
            }
        }

        // Kandidaatlijst per slot opbouwen (echte allocaties, in
        // opslagvolgorde), minus degenen die de frame toch al ontsnappen.
        final var sweepCandidates = new LinkedHashMap<String, List<String>>();
        for (final var instr : instrs) {
            if (instr instanceof IRInstruction.Store st && isLocalSlot(st.ptr())) {
                final var slot = nameOf(st.ptr());
                if (escapingSlots.contains(slot)) continue;
                final var value = nameOf(st.value());
                if (!allocs.contains(value) || escapingAllocs.contains(value)) continue;
                final var list = sweepCandidates.computeIfAbsent(slot, k -> new ArrayList<>());
                if (!list.contains(value)) {
                    list.add(value);
                }
            }
        }

        // Een alloc die in twee referentie-slots terechtkomt is dubbel-eigendom:
        // we laten hem dan lekken (retain zonder release) in plaats van hem
        // dubbel vrij te geven. Tellen over álle slots heen, want per slot is
        // de lijst al dedup-gehouden.
        final var occurrences = new HashMap<String, Integer>();
        for (final var list : sweepCandidates.values()) {
            for (final var candidate : list) {
                occurrences.merge(candidate, 1, Integer::sum);
            }
        }
        occurrences.forEach((candidate, count) -> {
            if (count > 1) {
                sharedAllocs.add(candidate);
            }
        });

        // Wat overblijft wordt bij frame-einde vrijgegeven: alles wat de
        // frame zelf alloceerde en niet in een gesweept slot belandde.
        final var inSweptSlot = new HashSet<String>();
        sweepCandidates.values().forEach(inSweptSlot::addAll);
        for (final var alloc : allocs) {
            if (escapingAllocs.contains(alloc) || inSweptSlot.contains(alloc)) continue;
            freeAtExit.add(alloc);
        }

        rejectUnsoundSlotWrites();
    }

    /**
     * Eén constructie maakt dit schema aantoonbaar onjuist: een lus die een
     * referentie-slot beschrijft. De release-van-overschrijving is dan op de
     * tweede iteratie gebaseerd op de waarde van de eerste, en de teller kan
     * verder dan één keer worden verlaagd. Dat weigeren we expliciet in plaats
     * van stille heap-corruptie te leveren.
     *
     * <p>Een slot dat in verschillende blokken beschreven wordt mag wél: de
     * initialisatie-vlag en de identiteits-guard weten daar runtime wat er in
     * zit, ongeacht welk pad de functie aflegt.
     */
    private void rejectUnsoundSlotWrites() {
        final var blocks = fn.blocks();
        final var successors = new LinkedHashMap<String, List<String>>();
        for (final var block : blocks) {
            final var terminator = block.getTerminator();
            final List<String> targets = new ArrayList<>(2);
            if (terminator instanceof IRInstruction.Branch br) {
                targets.add(br.targetLabel());
            } else if (terminator instanceof IRInstruction.CondBranch cb) {
                targets.add(cb.trueLabel());
                targets.add(cb.falseLabel());
            }
            successors.put(block.label(), targets);
        }

        // Een terugkerende rand bestaat als een blok zichzelf kan bereiken.
        boolean hasBackEdge = false;
        for (final var start : successors.keySet()) {
            final var seen = new HashSet<String>();
            final Deque<String> work = new ArrayDeque<>(successors.get(start));
            while (!work.isEmpty() && !hasBackEdge) {
                final var label = work.poll();
                if (!seen.add(label)) continue;
                if (label.equals(start)) {
                    hasBackEdge = true;
                    break;
                }
                work.addAll(successors.getOrDefault(label, List.of()));
            }
            if (hasBackEdge) break;
        }
        if (!hasBackEdge) return;

        for (final var slot : slotStoreCount.keySet()) {
            if (slotStoreCount.get(slot) > 1 && !escapingSlots.contains(slot)) {
                throw new IllegalStateException(
                        "GcStrategy.REFCOUNT ondersteunt geen lus die een referentie-"
                                + "variabele meer dan één keer beschrijft (functie '"
                                + fn.name + "', slot '" + slot + "'). De release-van-"
                                + "overschrijving zou op de tweede iteratie tweemaal "
                                + "vrijgeven. Gebruik GcStrategy.NONE of BOEHM, of "
                                + "haal de toewijzing uit de lus.");
            }
        }
    }

    // -------------------------------------------------------
    // Emissie
    // -------------------------------------------------------

    /**
     * Maakt bovenaan het entry-blok per lokale referentie-slot een
     * initialisatie-vlag aan en zet die op 0. De builder moet al in het
     * entry-blok staan; zie {@code FunctionEmitter}.
     */
    void emitSlotInitFlags() {
        if (!enabled) return;
        for (final var slot : slotCandidates.keySet()) {
            if (escapingSlots.contains(slot)) continue;
            final var flag = LLVMBuildAlloca(builder, LLVMInt8TypeInContext(ctx),
                    new BytePointer("nabu.rc.init." + slot));
            slotInitFlags.put(slot, flag);
            LLVMBuildStore(builder, flagUnwritten(), flag);
        }
    }

    /**
     * Na de allocatie: één retain, tenzij de allocatie ontsnapt. De
     * ontsnappende variant wordt helemaal niet geteld — de ontvanger krijgt
     * de eigendom zonder dat er ooit een teller bestaat.
     */
    void onAlloc(IRValue result, LLVMValueRef objectPtr) {
        if (!enabled) return;
        final var name = nameOf(result);
        if (!allocs.contains(name) || escapingAllocs.contains(name)) return;
        defined.add(name);
        emitRetain(objectPtr);
    }

    /**
     * Vóór een {@code Store} in een lokale referentie-slot: release wat er
     * nog in zit. De initialisatie-vlag zegt of de slot op de weg hierheen
     * al beschreven is; zonder die vlag zou de load het ongeinitialiseerde
     * geheugen van de alloca lezen en de identiteits-guard zou een
     * willekeurige pointer kunnen vrijgeven.
     */
    void onStoreToSlot(IRValue slotPtr, LLVMValueRef slotAddress) {
        if (!enabled) return;
        if (!isLocalSlot(slotPtr)) return;
        final var slot = nameOf(slotPtr);
        if (escapingSlots.contains(slot)) return;

        final var candidates = candidatesInSlot(slot);
        if (!candidates.isEmpty()) {
            final LLVMValueRef current = LLVMBuildLoad2(builder,
                    types.map(slotElementTypes.get(slot)), slotAddress,
                    "nabu.rc.old." + slot);
            emitGuardedRelease(current, candidates, "nabu.rc.overwrite." + slot,
                    slotInitFlags.get(slot));
        }
        // Vanaf nu is de slot gedefinieerd.
        final var flag = slotInitFlags.get(slot);
        if (flag != null && !flag.isNull()) {
            LLVMBuildStore(builder, flagWritten(), flag);
        }
    }


    /**
     * De allocs die eerdere stores in hetzelfde blok in deze slot hebben
     * gezet, en die dus daadwerkelijk in de slot kunnen staan op dit punt.
     * Leeg bij de eerste store: dan is de slotinhoud onbepaald en mag er
     * niets vrijkomen.
     */
    /**
     * Alle allocs die ooit in deze slot zijn gezet. De identiteits-guard
     * bepaalt bij runtime welke er daadwerkelijk in zit, dus het is
     * overgenerous om ze allemaal te noemen; te weinig zou een levend
     * object laten slinken.
     */
    private List<LLVMValueRef> candidatesInSlot(String slot) {
        final var result = new ArrayList<LLVMValueRef>();
        for (final var name : namesStoredInto(slot)) {
            addCandidate(result, name);
        }
        return result;
    }

    private void addCandidate(List<LLVMValueRef> into, String name) {
        if (!allocs.contains(name) || !defined.contains(name)) return;
        // Een ontsnappende alloc hebben we nooit een retain gegeven, en een
        // gedeelde hebben we aan meerdere slots toegewezen: geen van beide is
        // hier te vrijgeven.
        if (escapingAllocs.contains(name) || sharedAllocs.contains(name)) return;
        final var value = resolver.apply(new IRValue.Temp(name, ptrType()));
        if (value != null && !value.isNull()) {
            into.add(value);
        }
    }

    private List<String> namesStoredInto(String slot) {
        final var result = new ArrayList<String>();
        for (final var block : fn.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Store st
                        && isLocalSlot(st.ptr())
                        && nameOf(st.ptr()).equals(slot)) {
                    final var value = nameOf(st.value());
                    if (allocs.contains(value) && !result.contains(value)) {
                        result.add(value);
                    }
                }
            }
        }
        return result;
    }

    private IRType ptrType() {
        return new IRType.Ptr(IRType.I8);
    }

    /**
     * Een {@code Return} wordt een sprong naar de exit-pad; de waarde wordt
     * onthouden voor de phi die daar staat. De exit-pad zelf wordt pas in
     * {@link #finishFunction()} ge-emit.
     */
    void onReturn(IRValue returnValue) {
        if (!enabled) return;
        if (exitBlock == null) {
            exitBlock = LLVMAppendBasicBlockInContext(ctx, llvmFn,
                    new BytePointer("nabu.rc.exit"));
        }
        returnBlocks.add(LLVMGetInsertBlock(builder));
        returnValues.add(returnValue == null ? null : resolver.apply(returnValue));
        LLVMBuildBr(builder, exitBlock);
    }

    /**
     * Emitteert de exit-pad en de bijbehorende return-phi. Aanroepen nadat
     * alle blokken van de functie geëmitteerd zijn.
     */
    void finishFunction() {
        if (!enabled || exitBlock == null) return;

        // Eerst de builder in de exit-pad zetten: de phi hoort daar thuis, en
        // de builder stond nog in het laatste return-blok.
        LLVMPositionBuilderAtEnd(builder, exitBlock);

        LLVMValueRef returnPhi = null;
        final var returnType = fn.returnType;
        if (returnType instanceof IRType.Void) {
            for (final var value : returnValues) {
                if (value != null) {
                    throw new IllegalStateException(
                            "RET-pad zonder waarde in een void-functie: " + fn.name);
                }
            }
        } else {
            returnPhi = buildReturnPhi(returnType);
        }

        for (final var alloc : freeAtExit) {
            final var value = resolver.apply(new IRValue.Temp(alloc, ptrType()));
            if (value == null || value.isNull()) continue;
            if (isReturnedAlloc(returnPhi, alloc)) continue;
            emitRelease(value);
        }

        for (final var entry : sweepCandidates().entrySet()) {
            final var slotAddress = slotAddress(entry.getKey());
            if (slotAddress == null || slotAddress.isNull()) continue;
            final var current = LLVMBuildLoad2(builder,
                    types.map(slotElementTypes.get(entry.getKey())), slotAddress,
                    "nabu.rc.slot." + entry.getKey());
            emitGuardedRelease(current, entry.getValue(),
                    "nabu.rc.sweep." + entry.getKey(),
                    slotInitFlags.get(entry.getKey()));
        }

        if (returnPhi != null) {
            LLVMBuildRet(builder, returnPhi);
        } else {
            LLVMBuildRetVoid(builder);
        }
    }

    private Map<String, List<LLVMValueRef>> sweepCandidatesCache;

    private Map<String, List<LLVMValueRef>> sweepCandidates() {
        if (sweepCandidatesCache != null) {
            return sweepCandidatesCache;
        }
        final var result = new LinkedHashMap<String, List<LLVMValueRef>>();
        for (final var slot : slotCandidates.keySet()) {
            if (escapingSlots.contains(slot)) continue;
            final var candidates = new ArrayList<LLVMValueRef>();
            for (final var name : namesStoredInto(slot)) {
                if (escapingAllocs.contains(name) || sharedAllocs.contains(name)) continue;
                final var value = resolver.apply(new IRValue.Temp(name, ptrType()));
                if (value != null && !value.isNull()) {
                    candidates.add(value);
                }
            }
            if (!candidates.isEmpty()) {
                result.put(slot, candidates);
            }
        }
        sweepCandidatesCache = result;
        return result;
    }

    private LLVMValueRef slotAddress(String slot) {
        return resolver.apply(new IRValue.Temp(slot, new IRType.Ptr(IRType.I8)));
    }

    /**
     * Bouwt de phi over alle return-sites. De IR kan per pad een andere
     * waarde teruggeven, dus zonder phi zou de exit-pad de verkeerde waarde
     * teruggeven (of helemaal geen).
     */
    private LLVMValueRef buildReturnPhi(IRType returnType) {
        final var blocks = new PointerPointer<>(returnBlocks.size());
        final var values = new PointerPointer<>(returnBlocks.size());
        for (int i = 0; i < returnBlocks.size(); i++) {
            blocks.put(i, returnBlocks.get(i));
            final var value = returnValues.get(i);
            if (value == null || value.isNull()) {
                throw new IllegalStateException(
                        "RET zonder waarde in een niet-void-functie: " + fn.name
                                + " (retType=" + returnType + ")");
            }
            values.put(i, value);
        }
        final var phi = LLVMBuildPhi(builder, types.map(returnType),
                new BytePointer("nabu.rc.ret"));
        LLVMAddIncoming(phi, values, blocks, returnBlocks.size());
        Reference.reachabilityFence(blocks);
        Reference.reachabilityFence(values);
        return phi;
    }

    /**
     * Een alloc die als returnwaarde uit de frame ontsnapt, wordt niet
     * vrijgegeven. De escape-analyse heeft dat alvast gemarkeerd, dus dit is
     * een extra vangnet voor het geval de phi-identiteit het beter kan.
     */
    private boolean isReturnedAlloc(LLVMValueRef returnPhi, String alloc) {
        return returnPhi == null || escapingAllocs.contains(alloc);
    }

    private void emitRetain(LLVMValueRef objectPtr) {
        emitHelper("@nabu_retain", objectPtr);
    }

    private void emitRelease(LLVMValueRef objectPtr) {
        emitHelper("@nabu_release", objectPtr);
    }

    private void emitHelper(String key, LLVMValueRef arg) {
        final var fn = globalValueMap.get(key);
        if (fn == null || fn.isNull()) {
            throw new IllegalStateException("Runtime helper " + key + " niet gedefinieerd");
        }
        final var args = new PointerPointer<>(1);
        args.put(0, arg);
        // Een void-call mag geen naam hebben: de LLVM-verifier weigert
        // "instruction has a name, but provides a void value". De context
        // staat in de namen van de omliggende loads/vergelijkingen.
        LLVMBuildCall2(builder, LLVMGlobalGetValueType(fn), fn, args, 1, "");
        Reference.reachabilityFence(args);
    }

    /** i8-constanten voor de initialisatie-vlag. */
    private LLVMValueRef flagWritten() {
        return LLVMConstInt(LLVMInt8TypeInContext(ctx), 1, 0);
    }

    private LLVMValueRef flagUnwritten() {
        return LLVMConstInt(LLVMInt8TypeInContext(ctx), 0, 0);
    }

    /**
     * Geeft precies één keer vrij, en alleen als de waarde echt één van onze
     * eigen allocaties is én de slot ook echt beschreven is. De
     * vergelijkingen zorgen ervoor dat een NULL, een string-constant of een
     * parameter (waar we de eigendom niet van hebben) ongemoeid blijft;
     * {@code nabu_release(NULL)} is een no-op, dus de select hoeft geen tak
     * te maken.
     *
     * @param initFlag alloca van de initialisatie-vlag, of null als de slot
     *                 per definitie al beschreven is
     */
    private void emitGuardedRelease(LLVMValueRef candidate,
                                    List<LLVMValueRef> owned,
                                    String name,
                                    LLVMValueRef initFlag) {
        if (owned.isEmpty()) return;
        LLVMValueRef isOwned = LLVMBuildICmp(builder, LLVMIntEQ, candidate, owned.getFirst(),
                new BytePointer(name + ".own0"));
        for (int i = 1; i < owned.size(); i++) {
            final var eq = LLVMBuildICmp(builder, LLVMIntEQ, candidate, owned.get(i),
                    new BytePointer(name + ".own" + i));
            isOwned = LLVMBuildOr(builder, isOwned, eq, new BytePointer(name + ".own"));
        }
        if (initFlag != null && !initFlag.isNull()) {
            // Ongeïnitialiseerd alloca-geheugen: de vergelijking hierboven zou
            // op toeval kunnen slaan. Zonder de vlag zou dat een willekeurige
            // pointer zijn die we vrijgeven.
            final var written = LLVMBuildLoad2(builder, LLVMInt8TypeInContext(ctx), initFlag,
                    new BytePointer(name + ".init"));
            isOwned = LLVMBuildAnd(builder, isOwned,
                    LLVMBuildICmp(builder, LLVMIntNE, written, flagUnwritten(),
                            new BytePointer(name + ".isinit")),
                    new BytePointer(name + ".guarded"));
        }
        final var target = LLVMBuildSelect(builder, isOwned, candidate,
                LLVMConstNull(LLVMPointerTypeInContext(ctx, 0)),
                new BytePointer(name + ".sweep"));
        emitRelease(target);
    }

    // -------------------------------------------------------
    // Hulp
    // -------------------------------------------------------

    /** Een lokale referentie-slot: het doel van een Store is een Temp (alloca), geen veld. */
    private static boolean isLocalSlot(IRValue target) {
        return target instanceof IRValue.Temp temp
                && !(temp.type() instanceof IRType.Function)
                && isReferenceType(temp.type());
    }

    /**
     * Is dit type een referentie? Descriptor-vorm ({@code L...;} of
     * {@code [...]}) is eenduidig. Een pointer zonder descriptor is
     * gedegradeerd (vaak een {@code i8*} na type-erasure) en behandelen we
     * óók als referentie: de identiteits-guard in de sweep zorgt dat we
     * uitsluitend onze eigen heap-allocaties vrijgeven.
     */
    private static boolean isReferenceType(IRType type) {
        if (!(type instanceof IRType.Ptr ptr)) return false;
        if (ptr.pointee() instanceof IRType.Function) return false;
        final var descriptor = ptr.jvmDescriptor();
        return descriptor == null || descriptor.startsWith("L") || descriptor.startsWith("[");
    }

    private void mark(IRValue value, Set<String> into) {
        if (value == null) return;
        switch (value) {
            case IRValue.Temp t -> into.add(t.name());
            case IRValue.Values v -> v.values().forEach(x -> mark(x, into));
            default -> { }
        }
    }

    private static String nameOf(IRValue value) {
        return IRValue.nameOf(value);
    }
}
