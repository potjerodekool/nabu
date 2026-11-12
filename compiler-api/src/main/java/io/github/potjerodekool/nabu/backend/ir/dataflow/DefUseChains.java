package io.github.potjerodekool.nabu.backend.ir.dataflow;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Def-Use en Use-Def chains op basis van de IR.
 */
public final class DefUseChains {

    public record Def(String name, IRBasicBlock block, IRInstruction instruction) {}
    public record Use(String name, IRBasicBlock block, IRInstruction instruction) {}

    private final Map<String, List<Use>> defToUses = new LinkedHashMap<>();
    private final Map<String, List<Def>> useToDefs = new LinkedHashMap<>();

    public DefUseChains(IRFunction function) {
        // Verzamel defs
        final Map<String, Def> defByName = new LinkedHashMap<>();
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                final var res = instr.result();
                if (res != null) {
                    final String name = valueName(res);
                    if (name != null) {
                        final var def = new Def(name, block, instr);
                        defByName.put(name, def);
                        defToUses.computeIfAbsent(name, k -> new ArrayList<>());
                    }
                }
            }
        }
        // Verzamel uses
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                for (final var name : usedNames(instr)) {
                    final var use = new Use(name, block, instr);
                    useToDefs.computeIfAbsent(name, k -> new ArrayList<>());
                    final var def = defByName.get(name);
                    if (def != null) {
                        defToUses.computeIfAbsent(name, k -> new ArrayList<>()).add(use);
                        useToDefs.get(name).add(def);
                    }
                }
            }
        }
    }

    public Map<String, List<Use>> defToUses() { return defToUses; }
    public Map<String, List<Def>> useToDefs() { return useToDefs; }

    public List<Use> usesOf(String defName) {
        return defToUses.getOrDefault(defName, List.of());
    }

    public List<Def> defsOf(String useName) {
        return useToDefs.getOrDefault(useName, List.of());
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }

    private List<String> usedNames(IRInstruction instr) {
        final var names = new ArrayList<String>();
        // reuse LiveVariables logic inline
        switch (instr) {
            case IRInstruction.BinaryOp op -> { add(op.left(), names); add(op.right(), names); }
            case IRInstruction.Load ld -> add(ld.ptr(), names);
            case IRInstruction.Store st -> { add(st.ptr(), names); add(st.value(), names); }
            case IRInstruction.Call c -> { for (var a : c.args()) add(a, names); }
            case IRInstruction.IndirectCall ic -> { add(ic.callee(), names); for (var a : ic.args()) add(a, names); }
            case IRInstruction.CondBranch cb -> add(cb.condition(), names);
            case IRInstruction.Return r -> { if (r.value() != null) add(r.value(), names); }
            case IRInstruction.Cast c -> add(c.source(), names);
            case IRInstruction.Phi p -> { for (var inc : p.incomingValues()) add(inc.value(), names); }
            case IRInstruction.Move m -> add(m.value(), names);
            case IRInstruction.Throw t -> { if (t.result() != null) add(t.result(), names); }
            case IRInstruction.InstanceOf io -> add(io.source(), names);
            case IRInstruction.AllocaArray aa -> add(aa.size(), names);
            case IRInstruction.ArrayLoad al -> { add(al.array(), names); add(al.index(), names); }
            case IRInstruction.ArrayStore as -> { add(as.array(), names); add(as.index(), names); add(as.value(), names); }
            case IRInstruction.ArrayLength al -> add(al.array(), names);
            case IRInstruction.MonitorEnter me -> add(me.object(), names);
            case IRInstruction.MonitorExit mx -> add(mx.object(), names);
            default -> {}
        }
        return names;
    }

    private void add(IRValue v, List<String> names) {
        if (v instanceof IRValue.Temp t) names.add(t.name());
        else if (v instanceof IRValue.Named n) names.add(n.name());
        else if (v instanceof IRValue.Values vs) {
            for (var inner : vs.values()) add(inner, names);
        }
    }
}