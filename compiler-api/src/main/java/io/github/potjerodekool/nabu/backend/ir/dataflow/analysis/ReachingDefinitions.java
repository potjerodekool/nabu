package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowDirection;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Reaching Definitions analysis (voorwaarts, "may"-analyse).
 * Bepaalt welke definities van variabelen een gegeven punt kunnen bereiken.
 */
public class ReachingDefinitions implements DataFlowAnalysis<Set<String>> {

    private final Map<String, Set<String>> gen = new LinkedHashMap<>();
    private final Map<String, Set<String>> kill = new LinkedHashMap<>();
    private final Set<String> allDefs = new LinkedHashSet<>();

    public ReachingDefinitions(IRFunction function) {
        // Verzamel alle definities
        final Map<String, List<String>> defsByVar = new LinkedHashMap<>();
        for (final var block : function.blocks()) {
            int idx = 0;
            for (final var instr : block.instructions()) {
                final var res = instr.result();
                if (res != null) {
                    final String name = valueName(res);
                    if (name != null) {
                        final String defId = name + "@" + block.label() + "#" + idx;
                        allDefs.add(defId);
                        defsByVar.computeIfAbsent(name, k -> new ArrayList<>()).add(defId);
                    }
                }
                idx++;
            }
        }
        // gen/kill per blok
        for (final var block : function.blocks()) {
            final Set<String> g = new LinkedHashSet<>();
            final Set<String> k = new LinkedHashSet<>();
            int idx = 0;
            for (final var instr : block.instructions()) {
                final var res = instr.result();
                if (res != null) {
                    final String name = valueName(res);
                    if (name != null) {
                        final String defId = name + "@" + block.label() + "#" + idx;
                        g.add(defId);
                        // kill: alle andere defs van dezelfde variabele
                        for (final var other : defsByVar.getOrDefault(name, List.of())) {
                            if (!other.equals(defId)) k.add(other);
                        }
                    }
                }
                idx++;
            }
            gen.put(block.label(), g);
            kill.put(block.label(), k);
        }
    }

    @Override
    public DataFlowDirection direction() { return DataFlowDirection.FORWARD; }

    @Override
    public Set<String> boundaryValue(IRFunction function) { return new LinkedHashSet<>(); }

    @Override
    public Set<String> initialValue() { return new LinkedHashSet<>(); }

    @Override
    public Set<String> transfer(IRBasicBlock block, Set<String> in) {
        final var out = new LinkedHashSet<>(in);
        out.removeAll(kill.getOrDefault(block.label(), Set.of()));
        out.addAll(gen.getOrDefault(block.label(), Set.of()));
        return out;
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

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}