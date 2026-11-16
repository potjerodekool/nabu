package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowDirection;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Live Variables analysis (achterwaarts, "may"-analyse).
 * Een variabele is live op een punt als haar waarde later nog gebruikt wordt.
 */
public class LiveVariables implements DataFlowAnalysis<Set<String>> {

    @Override
    public DataFlowDirection direction() { return DataFlowDirection.BACKWARD; }

    @Override
    public Set<String> boundaryValue(IRFunction function) { return new LinkedHashSet<>(); }

    @Override
    public Set<String> initialValue() { return new LinkedHashSet<>(); }

    @Override
    public Set<String> transfer(IRBasicBlock block, Set<String> out) {
        // use/def per blok
        final var use = new LinkedHashSet<String>();
        final var def = new LinkedHashSet<String>();
        for (final var instr : block.instructions()) {
            // gebruik eerst
            collectUses(instr, use, def);
            final var res = instr.result();
            if (res != null) {
                final String name = valueName(res);
                if (name != null) def.add(name);
            }
        }
        // in = (out - def) ∪ use
        final var result = new LinkedHashSet<>(out);
        result.removeAll(def);
        result.addAll(use);
        return result;
    }

    private void collectUses(IRInstruction instr, Set<String> use, Set<String> def) {
        // Variabelen die gebruikt worden vóór herdefinitie in dit blok tellen als use.
        // We verzamelen alle gebruikte namen; def-filtering gebeurt via ordering hierboven
        // (vereenvoudigd: alle uses, minus defs die eerder in blok voorkomen).
        // Voor correctheid scannen we in volgorde.
        // (Implementatie: eerst uses toevoegen indien niet al gedefinieerd in dit blok.)
        List<String> usedNames = usedNames(instr);
        for (final var n : usedNames) {
            if (!def.contains(n)) use.add(n);
        }
    }

    private List<String> usedNames(IRInstruction instr) {
        final var names = new ArrayList<String>();
        switch (instr) {
            case IRInstruction.BinaryOp op -> { addValue(op.left(), names); addValue(op.right(), names); }
            case IRInstruction.Load ld -> addValue(ld.ptr(), names);
            case IRInstruction.Store st -> { addValue(st.ptr(), names); addValue(st.value(), names); }
            case IRInstruction.Call c -> { for (var a : c.args()) addValue(a, names); }
            case IRInstruction.IndirectCall ic -> { addValue(ic.callee(), names); for (var a : ic.args()) addValue(a, names); }
            case IRInstruction.CondBranch cb -> addValue(cb.condition(), names);
            case IRInstruction.Return r -> { if (r.value() != null) addValue(r.value(), names); }
            case IRInstruction.Cast c -> addValue(c.source(), names);
            case IRInstruction.Phi p -> { for (var inc : p.incomingValues()) addValue(inc.value(), names); }
            case IRInstruction.Move m -> addValue(m.value(), names);
            case IRInstruction.Throw t -> { if (t.result() != null) addValue(t.result(), names); }
            case IRInstruction.InstanceOf io -> addValue(io.source(), names);
            case IRInstruction.AllocaArray aa -> addValue(aa.size(), names);
            case IRInstruction.ArrayLoad al -> { addValue(al.array(), names); addValue(al.index(), names); }
            case IRInstruction.ArrayStore as -> { addValue(as.array(), names); addValue(as.index(), names); addValue(as.value(), names); }
            case IRInstruction.ArrayLength al -> addValue(al.array(), names);
            case IRInstruction.MonitorEnter me -> addValue(me.object(), names);
            case IRInstruction.MonitorExit mx -> addValue(mx.object(), names);
            default -> {}
        }
        return names;
    }

    private void addValue(IRValue v, List<String> names) {
        if (v instanceof IRValue.Temp t) names.add(t.name());
        else if (v instanceof IRValue.Named n) names.add(n.name());
        else if (v instanceof IRValue.Values vs) {
            for (var inner : vs.values()) addValue(inner, names);
        }
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }

    @Override
    public Set<String> meet(Set<String> a, Set<String> b) {
        final var r = new LinkedHashSet<>(a);
        r.addAll(b);
        return r;
    }

    @Override
    public boolean equalsValue(Set<String> a, Set<String> b) {
        return Objects.equals(a, b);
    }
}