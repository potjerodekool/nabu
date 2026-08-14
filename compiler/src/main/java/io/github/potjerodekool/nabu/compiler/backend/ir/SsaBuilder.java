package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.debug.SourceLocation;
import io.github.potjerodekool.nabu.compiler.ir.*;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.*;

/**
 * SSA-construction pass: converteert alloca/load/store IR naar volledige SSA-vorm
 * door het plaatsen van phi-functies bij merge-punten en het hernoemen van variabelen.
 *
 * Algoritme (gebaseerd op Cytron et al.):
 *   1. Identificeer alle alloca-instructies (lokale variabelen)
 *   2. Bereken dominator-boom en dominance frontiers
 *   3. Plaats phi-functies op dominance frontiers
 *   4. Hernoem variabelen (verwijder stores, vervang loads door moves)
 *
 * Na deze pass:
 *   - Alloca-instructies voor geconverteerde variabelen worden verwijderd
 *   - Store-instructies worden verwijderd (de opgeslagen waarde wordt de SSA-versie)
 *   - Load-instructies worden vervangen door Move-instructies
 *   - Phi-functies worden geplaatst bij merge-punten
 */
public class SsaBuilder {

    public static void run(final IRModule module) {
        for (final var function : module.functions()) {
            if (!function.isExternal()) {
                run(function);
            }
        }
    }

    public static void run(final IRFunction function) {
        if (function.blocks().isEmpty()) return;

        // Stap 1: Verzamel alle alloca's
        final var allocas = findAllocas(function);
        if (allocas.isEmpty()) return;

        // Stap 2: Bouw predecessor-lijst
        final var predecessors = Dominators.computePredecessors(function.blocks());

        // Stap 3: Bereken dominator-boom
        final var dominators = Dominators.compute(function);

        // Stap 4: Voor elke alloca, vind store-blokken en bereken dominance frontiers
        final var phiPlacements = new LinkedHashMap<String, List<PhiPlacement>>();
        final var allocaByName = new HashMap<String, IRInstruction.Alloca>();

        for (final var alloca : allocas) {
            final var ptrName = IRValue.nameOf(alloca.result());
            allocaByName.put(ptrName, alloca);

            final var defBlocks = findStoreBlocks(function, alloca);
            if (defBlocks.size() < 2) {
                // Eén of geen definities — geen phi nodig
                continue;
            }

            // Bereken iterated dominance frontier
            final var idf = computeIdf(defBlocks, dominators);
            for (final var blockLabel : idf) {
                final var block = findBlock(function, blockLabel);
                if (block == null) continue;

                // Maak phi met placeholder incoming values
                final var predecessorsOfBlock = predecessors.getOrDefault(blockLabel, Set.of());
                final var incoming = new ArrayList<IRInstruction.Phi.Incoming>();
                for (final var predLabel : predecessorsOfBlock) {
                    final var predBlock = findBlock(function, predLabel);
                    if (predBlock != null) {
                        incoming.add(new IRInstruction.Phi.Incoming(
                                IRValue.undef(alloca.allocType()), predBlock));
                    }
                }

                if (!incoming.isEmpty()) {
                    final var phiResult = new IRValue.Temp(
                            "%phi." + ptrName.substring(1) + "." + blockLabel,
                            alloca.allocType());
                    final var phi = new IRInstruction.Phi(phiResult, incoming, SourceLocation.UNKNOWN);
                    phiPlacements.computeIfAbsent(blockLabel, k -> new ArrayList<>())
                            .add(new PhiPlacement(phi, alloca));
                }
            }
        }

        // Stap 5: Voeg phi's toe aan blokken (aan het begin, vóór andere instructies)
        for (final var entry : phiPlacements.entrySet()) {
            final var block = findBlock(function, entry.getKey());
            if (block == null) continue;

            // Verzamel huidige instructies
            final var currentInstrs = new ArrayList<>(block.instructions());

            // Verwijder huidige instructies
            for (final var instr : List.copyOf(currentInstrs)) {
                block.removeInstruction(instr);
            }

            // Voeg phi's toe eerst
            for (final var placement : entry.getValue()) {
                block.add(placement.phi());
            }

            // Voeg overige instructies terug
            for (final var instr : currentInstrs) {
                block.add(instr);
            }
        }

        // Stap 6: SSA Renaming
        rename(function, allocas, phiPlacements);

        // Stap 7: Verwijder overgebleven alloca's die niet meer gebruikt worden
        removeAllocas(function, allocas);
    }

    // -------------------------------------------------------
    // Stap 1: Zoek alloca's
    // -------------------------------------------------------

    private static List<IRInstruction.Alloca> findAllocas(IRFunction function) {
        final var allocas = new ArrayList<IRInstruction.Alloca>();
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Alloca alloca) {
                    // Alleen lokale variabelen (geen this-pointer)
                    final var ptrName = IRValue.nameOf(alloca.result());
                    if (!ptrName.equals("%this.ptr")) {
                        allocas.add(alloca);
                    }
                }
            }
        }
        return allocas;
    }

    // -------------------------------------------------------
    // Stap 4: Zoek store-blokken per alloca
    // -------------------------------------------------------

    private static Set<String> findStoreBlocks(IRFunction function,
                                                IRInstruction.Alloca alloca) {
        final var ptrName = IRValue.nameOf(alloca.result());
        final var blocks = new LinkedHashSet<String>();

        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Store store) {
                    if (isSamePointer(store.ptr(), ptrName)) {
                        blocks.add(block.label());
                    }
                }
            }
        }

        return blocks;
    }

    // -------------------------------------------------------
    // Stap 4: Bereken iterated dominance frontier
    // -------------------------------------------------------

    private static Set<String> computeIdf(Set<String> blocks, Dominators dominators) {
        final var idf = new LinkedHashSet<>(blocks);
        final var worklist = new ArrayDeque<>(blocks);

        while (!worklist.isEmpty()) {
            final var b = worklist.poll();
            final var df = dominators.getDominanceFrontier(b);
            for (final var d : df) {
                if (idf.add(d)) {
                    worklist.add(d);
                }
            }
        }

        return idf;
    }

    // -------------------------------------------------------
    // Stap 6: SSA Renaming
    // -------------------------------------------------------

    private static void rename(IRFunction function,
                                List<IRInstruction.Alloca> allocas,
                                Map<String, List<PhiPlacement>> phiPlacements) {
        // Alloca ptr names die we bijhouden
        final var trackedPtrNames = new HashSet<String>();
        for (final var alloca : allocas) {
            trackedPtrNames.add(IRValue.nameOf(alloca.result()));
        }

        // Huidige SSA-versie per alloca
        final var currentValue = new HashMap<String, IRValue>();
        // Versie-historie per blok (voor backtracking)
        final var versionHistory = new HashMap<String, Deque<IRValue>>();

        // Bouw dominator boom
        final var dominators = Dominators.compute(function);

        // Traverseer dominator boom in preorder
        renameBlock(function.entryBlock(), function, dominators, trackedPtrNames,
                currentValue, versionHistory, phiPlacements);
    }

    private static void renameBlock(IRBasicBlock block,
                                     IRFunction function,
                                     Dominators dominators,
                                     Set<String> trackedPtrNames,
                                     Map<String, IRValue> currentValue,
                                     Map<String, Deque<IRValue>> versionHistory,
                                     Map<String, List<PhiPlacement>> phiPlacements) {
        // Verzamel veranderingen die we moeten terugdraaien
        final var modifiedVars = new ArrayList<String>();

        // Stap 6a: Verwerk phi's in dit blok (creëer nieuwe SSA-versies)
        for (final var instr : block.instructions()) {
            if (instr instanceof IRInstruction.Phi phi) {
                final var phiPtrName = extractPhiVariableName(phi, trackedPtrNames);
                if (phiPtrName != null) {
                    // Creëer nieuwe SSA-versie voor dit blok
                    final var newVersion = phi.result();
                    pushVersion(phiPtrName, newVersion, currentValue, versionHistory);
                    modifiedVars.add(phiPtrName);
                }
            }
        }

        // Stap 6b: Verwerk instructies in programmavolgorde (Cytron renaming).
        // Stores en loads worden interleaved verwerkt, zodat een load de
        // versie ziet die geldig is op de positie van de load (niet de versie
        // na een latere store in hetzelfde blok).
        final var instrsToRemove = new ArrayList<IRInstruction>();
        final var instrs = block.instructions();
        for (int i = 0; i < instrs.size(); i++) {
            final var instr = instrs.get(i);

            if (instr instanceof IRInstruction.Store store) {
                final var ptrName = extractStoreVariableName(store);
                if (ptrName != null && trackedPtrNames.contains(ptrName)) {
                    // De opgeslagen waarde wordt de huidige SSA-versie
                    pushVersion(ptrName, store.value(), currentValue, versionHistory);
                    modifiedVars.add(ptrName);
                    instrsToRemove.add(store);
                }
            } else if (instr instanceof IRInstruction.Load load) {
                final var ptrName = extractLoadVariableName(load);
                if (ptrName != null && trackedPtrNames.contains(ptrName)
                        && currentValue.containsKey(ptrName)) {
                    // Vervang de load door een move van de huidige SSA-versie
                    final var ssValue = currentValue.get(ptrName);
                    final var move = new IRInstruction.Move(
                            load.result(), ssValue, load.location());
                    block.setInstruction(i, move);
                }
            }
        }

        // Verwijder stores
        for (final var instr : instrsToRemove) {
            block.removeInstruction(instr);
        }

        // Stap 6c: Update phi incoming values in opvolgende blokken
        updateSuccessorPhis(block, function, trackedPtrNames, currentValue, dominators);

        // Stap 6d: Verwerk dominator-kinderen
        for (final var childLabel : dominators.getChildren(block.label())) {
            final var child = findBlock(function, childLabel);
            if (child != null) {
                renameBlock(child, function, dominators, trackedPtrNames,
                        currentValue, versionHistory, phiPlacements);
            }
        }

        // Stap 6e: Backtrack — herstel vorige SSA-versies
        for (final var varName : modifiedVars) {
            popVersion(varName, currentValue, versionHistory);
        }
    }

    // -------------------------------------------------------
    // Phi incoming values bijwerken in opvolgende blokken
    // -------------------------------------------------------

    private static void updateSuccessorPhis(IRBasicBlock block,
                                             IRFunction function,
                                             Set<String> trackedPtrNames,
                                             Map<String, IRValue> currentValue,
                                             Dominators dominators) {
        // Voor elk opvolgend blok: update phi's die naar dit blok verwijzen
        for (final var successor : getSuccessors(block, function)) {
            final var successorBlock = findBlock(function, successor);
            if (successorBlock == null) continue;

            // Verzamel huidige instructies en identificeer phis
            final var instrs = successorBlock.instructions();
            for (int i = 0; i < instrs.size(); i++) {
                if (instrs.get(i) instanceof IRInstruction.Phi phi) {
                    final var phiPtrName = extractPhiVariableName(phi, trackedPtrNames);
                    if (phiPtrName == null) continue;
                    if (!currentValue.containsKey(phiPtrName)) continue;

                    // Zoek de incoming value voor dit blok
                    final var currentVal = currentValue.get(phiPtrName);
                    final var newIncoming = new ArrayList<IRInstruction.Phi.Incoming>();

                    for (final var incoming : phi.incomingValues()) {
                        if (incoming.fromBlock().label().equals(block.label())) {
                            newIncoming.add(new IRInstruction.Phi.Incoming(
                                    currentVal, incoming.fromBlock()));
                        } else {
                            newIncoming.add(incoming);
                        }
                    }

                    // Vervang de phi
                    final var newPhi = new IRInstruction.Phi(
                            phi.result(), newIncoming, phi.location());
                    successorBlock.setInstruction(i, newPhi);
                }
            }
        }
    }

    // -------------------------------------------------------
    // Stap 7: Verwijder alloca's
    // -------------------------------------------------------

    private static void removeAllocas(IRFunction function,
                                       List<IRInstruction.Alloca> allocas) {
        for (final var alloca : allocas) {
            final var ptrName = IRValue.nameOf(alloca.result());
            final var hasOtherUses = hasUsesOtherThanAlloca(function, alloca);

            if (!hasOtherUses) {
                for (final var block : function.blocks()) {
                    block.removeInstruction(alloca);
                }
            }
        }
    }

    private static boolean hasUsesOtherThanAlloca(IRFunction function,
                                                    IRInstruction.Alloca alloca) {
        final var ptrName = IRValue.nameOf(alloca.result());

        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr == alloca) continue;

                if (instr instanceof IRInstruction.Load load) {
                    if (isSamePointer(load.ptr(), ptrName)) return true;
                } else if (instr instanceof IRInstruction.Store store) {
                    if (isSamePointer(store.ptr(), ptrName)) return true;
                } else if (instr instanceof IRInstruction.Phi phi) {
                    for (final var incoming : phi.incomingValues()) {
                        if (incoming.value() instanceof IRValue.Temp t
                                && t.name().equals(ptrName)) return true;
                    }
                }
            }
        }

        return false;
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    private static boolean isSamePointer(IRValue value, String ptrName) {
        if (value instanceof IRValue.Temp t) {
            return t.name().equals(ptrName);
        }
        return false;
    }

    private static String extractStoreVariableName(IRInstruction.Store store) {
        if (store.ptr() instanceof IRValue.Temp t) {
            final var name = t.name();
            if (name.endsWith(".ptr")) {
                return name;
            }
        }
        return null;
    }

    private static String extractLoadVariableName(IRInstruction.Load load) {
        if (load.ptr() instanceof IRValue.Temp t) {
            final var name = t.name();
            if (name.endsWith(".ptr")) {
                return name;
            }
        }
        return null;
    }

    private static String extractPhiVariableName(IRInstruction.Phi phi,
                                                  Set<String> trackedPtrNames) {
        final var name = IRValue.nameOf(phi.result());
        // Phi resultaten hebben de vorm %phi.<variable>.<blockLabel>
        // Zowel de variabele ("result.ptr") als het bloklabel ("while.cond")
        // kunnen punten bevatten, dus zoeken we op basis van de bekende
        // variabelenamen in plaats van te parsen op de laatste punt.
        if (!name.startsWith("%phi.")) {
            return null;
        }

        for (final var ptrName : trackedPtrNames) {
            final var prefix = "%phi." + ptrName.substring(1) + ".";
            if (name.startsWith(prefix)) {
                return ptrName;
            }
        }

        return null;
    }

    private static IRBasicBlock findBlock(IRFunction function, String label) {
        for (final var block : function.blocks()) {
            if (block.label().equals(label)) return block;
        }
        return null;
    }

    private static Set<String> getSuccessors(IRBasicBlock block, IRFunction function) {
        final var successors = new LinkedHashSet<String>();
        final var terminator = block.getTerminator();

        if (terminator instanceof IRInstruction.Branch branch) {
            successors.add(branch.targetLabel());
        } else if (terminator instanceof IRInstruction.CondBranch condBranch) {
            successors.add(condBranch.trueLabel());
            successors.add(condBranch.falseLabel());
        }

        return successors;
    }

    private static void pushVersion(String varName, IRValue value,
                                     Map<String, IRValue> currentValue,
                                     Map<String, Deque<IRValue>> versionHistory) {
        currentValue.put(varName, value);
        versionHistory.computeIfAbsent(varName, k -> new ArrayDeque<>()).push(value);
    }

    private static void popVersion(String varName,
                                    Map<String, IRValue> currentValue,
                                    Map<String, Deque<IRValue>> versionHistory) {
        final var stack = versionHistory.get(varName);
        if (stack != null && !stack.isEmpty()) {
            stack.pop();
            if (!stack.isEmpty()) {
                currentValue.put(varName, stack.peek());
            } else {
                currentValue.remove(varName);
            }
        }
    }

    // -------------------------------------------------------
    // Interne data klassen
    // -------------------------------------------------------

    private record PhiPlacement(IRInstruction.Phi phi, IRInstruction.Alloca alloca) {}
}
