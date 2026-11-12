package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowDirection;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Available Expressions analysis (voorwaarts, "must"-analyse).
 * Een expressie is available als zij op alle paden naar dit punt is berekend
 * en haar operanden niet zijn hergedefinieerd.
 */
public class AvailableExpressions implements DataFlowAnalysis<Set<String>> {

    private final Set<String> universal;
    private final Map<String, Set<String>> gen = new LinkedHashMap<>();
    private final Map<String, Set<String>> kill = new LinkedHashMap<>();

    public AvailableExpressions(IRFunction function) {
        // Verzamel alle expressies
        final Set<String> all = new LinkedHashSet<>();
        final Map<String, Set<String>> defsByBlock = new LinkedHashMap<>();
        for (final var block : function.blocks()) {
            final Set<String> defs = new LinkedHashSet<>();
            for (final var instr : block.instructions()) {
                final var res = instr.result();
                if (res != null) {
                    final String name = valueName(res);
                    if (name != null) defs.add(name);
                }
                if (instr instanceof IRInstruction.BinaryOp op) {
                    all.add(exprKey(op));
                }
            }
            defsByBlock.put(block.label(), defs);
        }
        this.universal = all;

        for (final var block : function.blocks()) {
            final Set<String> g = new LinkedHashSet<>();
            final Set<String> k = new LinkedHashSet<>();
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.BinaryOp op) {
                    g.add(exprKey(op));
                }
                final var res = instr.result();
                if (res != null) {
                    final String name = valueName(res);
                    if (name != null) {
                        // kill expressies die deze variabele gebruiken
                        for (final var e : all) {
                            if (e.contains(":" + name + ":") || e.endsWith(":" + name) || e.contains(":" + name)) {
                                // conservative: kill if name appears as operand token
                            }
                        }
                    }
                }
            }
            // kill: expressies die een hergedefinieerde variabele bevatten
            final Set<String> blockDefs = defsByBlock.get(block.label());
            for (final var e : all) {
                for (final var d : blockDefs) {
                    if (operandTokens(e).contains(d)) k.add(e);
                }
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
    public Set<String> initialValue() { return new LinkedHashSet<>(universal); }

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
        r.retainAll(b);
        return r;
    }

    @Override
    public boolean equalsValue(Set<String> a, Set<String> b) {
        return Objects.equals(a, b);
    }

    private String exprKey(IRInstruction.BinaryOp op) {
        return op.op().name() + ":" + valueToken(op.left()) + ":" + valueToken(op.right());
    }

    private String valueToken(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        if (v instanceof IRValue.ConstInt c) return "C" + c.value();
        if (v instanceof IRValue.ConstBool c) return "C" + c.value();
        if (v instanceof IRValue.ConstFloat c) return "C" + c.value();
        if (v instanceof IRValue.ConstString c) return "CS" + c.value();
        return v.toString();
    }

    private List<String> operandTokens(String expr) {
        final var parts = expr.split(":");
        if (parts.length < 3) return List.of();
        return List.of(parts[1], parts[2]);
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}