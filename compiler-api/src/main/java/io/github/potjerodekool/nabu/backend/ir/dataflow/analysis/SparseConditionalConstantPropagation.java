package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Sparse Conditional Constant Propagation (SCCP): propageert constanten
 * over het CFG en volgt welke branches uitvoerbaar zijn ("tracking over
 * branches"). Markeert onbereikbare blokken/edges.
 */
public class SparseConditionalConstantPropagation {

    public enum Lattice { UNDEFINED, CONSTANT, OVERDEFINED }

    public record ValueInfo(Lattice lattice, IRValue constant) {}

    private final IRFunction function;
    private final Map<String, Lattice> lat = new HashMap<>();          // name -> lattice
    private final Map<String, IRValue> constVal = new HashMap<>();     // name -> constant
    private final Map<String, Boolean> blockExec = new HashMap<>();
    private final Set<String> edgeExec = new HashSet<>();              // "from->to"
    private final Map<String, List<IRInstruction>> uses = new HashMap<>(); // value name -> using instrs
    private final Deque<String> cfgWl = new ArrayDeque<>();
    private final Deque<String> ssaWl = new ArrayDeque<>();

    public SparseConditionalConstantPropagation(IRFunction function) {
        this.function = function;
        for (final var b : function.blocks()) {
            blockExec.put(b.label(), false);
        }
        // uses
        for (final var b : function.blocks()) {
            for (final var instr : b.instructions()) {
                for (final var name : usedNames(instr)) {
                    uses.computeIfAbsent(name, k -> new ArrayList<>()).add(instr);
                }
                final var res = instr.result();
                if (res != null) {
                    final String n = valueName(res);
                    if (n != null) lat.putIfAbsent(n, Lattice.UNDEFINED);
                }
            }
        }
    }

    public static Result analyze(IRFunction function) {
        if (function.isExternal() || function.blocks().isEmpty()) {
            return new SparseConditionalConstantPropagation(function).result();
        }
        final var sccp = new SparseConditionalConstantPropagation(function);
        sccp.run();
        return sccp.result();
    }

    private void run() {
        markBlockExecutable(function.entryBlock().label());
        while (!cfgWl.isEmpty() || !ssaWl.isEmpty()) {
            while (!cfgWl.isEmpty()) {
                final var b = cfgWl.poll();
                visitBlock(b);
            }
            while (!ssaWl.isEmpty()) {
                final var v = ssaWl.poll();
                for (final var instr : uses.getOrDefault(v, List.of())) {
                    // find owning block
                    final var owner = blockOf(instr);
                    if (owner != null && blockExec.getOrDefault(owner, false)) {
                        if (instr instanceof IRInstruction.Phi) {
                            visitPhi((IRInstruction.Phi) instr, owner.label());
                        } else {
                            visitInstruction(instr, owner.label());
                        }
                    }
                }
            }
        }
    }

    private void markBlockExecutable(String label) {
        if (blockExec.getOrDefault(label, false)) return;
        blockExec.put(label, true);
        cfgWl.add(label);
    }

    private void markEdgeExecutable(String from, String to) {
        final String key = from + "->" + to;
        if (edgeExec.contains(key)) return;
        edgeExec.add(key);
        if (!blockExec.getOrDefault(to, false)) {
            markBlockExecutable(to);
        } else {
            // block already executable: reprocess phis
            final var blk = findBlock(to);
            if (blk != null) {
                for (final var instr : blk.instructions()) {
                    if (instr instanceof IRInstruction.Phi phi) {
                        visitPhi(phi, to);
                    }
                }
            }
        }
    }

    private void visitBlock(String label) {
        final var blk = findBlock(label);
        if (blk == null) return;
        for (final var instr : blk.instructions()) {
            if (instr instanceof IRInstruction.Phi phi) {
                visitPhi(phi, label);
            } else {
                visitInstruction(instr, label);
            }
        }
        // terminator
        final var t = blk.getTerminator();
        if (t instanceof IRInstruction.CondBranch cb) {
            IRValue condConst = null;
            Lattice cl;
            if (cb.condition() instanceof IRValue.ConstBool cbv) {
                cl = Lattice.CONSTANT;
                condConst = cbv;
            } else {
                final String cn = valueName(cb.condition());
                cl = lat.getOrDefault(cn, Lattice.OVERDEFINED);
                condConst = constVal.get(cn);
            }
            if (cl == Lattice.CONSTANT && condConst instanceof IRValue.ConstBool cbv) {
                markEdgeExecutable(label, cbv.value() ? cb.trueLabel() : cb.falseLabel());
            } else {
                markEdgeExecutable(label, cb.trueLabel());
                markEdgeExecutable(label, cb.falseLabel());
            }
        } else if (t instanceof IRInstruction.Branch br) {
            markEdgeExecutable(label, br.targetLabel());
        }
    }

    private void visitPhi(IRInstruction.Phi phi, String blockLabel) {
        Lattice newVal = Lattice.UNDEFINED;
        IRValue newConst = null;
        boolean hasUndef = false;
        for (final var inc : phi.incomingValues()) {
            final String from = inc.fromBlock().label();
            if (!edgeExec.contains(from + "->" + blockLabel)) continue;
            final String name = valueName(inc.value());
            Lattice ol;
            IRValue oc;
            if (name == null) {
                // constant value
                ol = Lattice.CONSTANT;
                oc = inc.value();
            } else {
                ol = lat.getOrDefault(name, Lattice.UNDEFINED);
                oc = constVal.get(name);
            }
            newVal = meetLattice(newVal, ol);
            if (newVal == Lattice.CONSTANT) {
                if (newConst == null) newConst = oc;
                else if (!Objects.equals(newConst, oc)) newVal = Lattice.OVERDEFINED;
            }
        }
        // if no executable incoming edges, leave undefined
        updateLattice(phi.result(), newVal, newConst);
    }

    private void visitInstruction(IRInstruction instr, String blockLabel) {
        final var res = instr.result();
        String name = (res == null) ? null : valueName(res);
        Lattice newVal;
        IRValue newConst = null;

        if (instr instanceof IRInstruction.BinaryOp op) {
            final Lattice ll = lat.getOrDefault(valueName(op.left()), op.left() instanceof IRValue.ConstInt || op.left() instanceof IRValue.ConstBool ? Lattice.CONSTANT : Lattice.OVERDEFINED);
            final Lattice rl = lat.getOrDefault(valueName(op.right()), op.right() instanceof IRValue.ConstInt || op.right() instanceof IRValue.ConstBool ? Lattice.CONSTANT : Lattice.OVERDEFINED);
            // constants
            IRValue lc = constFor(op.left());
            IRValue rc = constFor(op.right());
            if (ll == Lattice.UNDEFINED || rl == Lattice.UNDEFINED) {
                newVal = Lattice.UNDEFINED;
            } else if (ll == Lattice.OVERDEFINED || rl == Lattice.OVERDEFINED) {
                newVal = Lattice.OVERDEFINED;
            } else {
                // both constant
                newVal = Lattice.CONSTANT;
                newConst = fold(op, lc, rc);
                if (newConst == null) { newVal = Lattice.OVERDEFINED; }
            }
        } else if (instr instanceof IRInstruction.Move m) {
            final String srcName = valueName(m.value());
            Lattice sl;
            IRValue sc;
            if (srcName == null) {
                sl = Lattice.CONSTANT; sc = m.value();
            } else {
                sl = lat.getOrDefault(srcName, Lattice.UNDEFINED);
                sc = constVal.get(srcName);
            }
            newVal = sl;
            newConst = sc;
        } else if (instr instanceof IRInstruction.Cast c) {
            final String srcName = valueName(c.source());
            if (srcName == null) { newVal = Lattice.CONSTANT; newConst = c.source(); }
            else { newVal = lat.getOrDefault(srcName, Lattice.UNDEFINED); newConst = constVal.get(srcName); }
        } else if (res != null) {
            // other defining instructions -> overdefined (conservative)
            newVal = Lattice.OVERDEFINED;
        } else {
            return; // void instruction, no result to track
        }

        if (name != null) {
            updateLattice(res, newVal, newConst);
        }
    }

    private void updateLattice(IRValue result, Lattice newVal, IRValue newConst) {
        final String name = valueName(result);
        if (name == null) return;
        final Lattice old = lat.getOrDefault(name, Lattice.UNDEFINED);
        if (newVal == old) {
            // maybe const changed? if constant and different -> overdef
            if (old == Lattice.CONSTANT && newVal == Lattice.CONSTANT) {
                if (!Objects.equals(constVal.get(name), newConst)) {
                    lat.put(name, Lattice.OVERDEFINED);
                    constVal.remove(name);
                    ssaWl.add(name);
                }
            }
            return;
        }
        // meet old and new
        Lattice merged;
        if (old == Lattice.UNDEFINED) merged = newVal;
        else if (newVal == Lattice.UNDEFINED) merged = old;
        else if (old == Lattice.OVERDEFINED || newVal == Lattice.OVERDEFINED) merged = Lattice.OVERDEFINED;
        else if (old == Lattice.CONSTANT && newVal == Lattice.CONSTANT) {
            if (Objects.equals(constVal.get(name), newConst)) merged = Lattice.CONSTANT;
            else merged = Lattice.OVERDEFINED;
        } else merged = Lattice.OVERDEFINED;

        lat.put(name, merged);
        if (merged == Lattice.CONSTANT) constVal.put(name, newConst);
        else constVal.remove(name);
        ssaWl.add(name);
    }

    private Lattice meetLattice(Lattice a, Lattice b) {
        if (a == Lattice.UNDEFINED) return b;
        if (b == Lattice.UNDEFINED) return a;
        if (a == Lattice.OVERDEFINED || b == Lattice.OVERDEFINED) return Lattice.OVERDEFINED;
        return Lattice.CONSTANT; // both constant; caller checks equality
    }

    private IRValue constFor(IRValue v) {
        if (v instanceof IRValue.ConstInt || v instanceof IRValue.ConstBool ||
            v instanceof IRValue.ConstFloat || v instanceof IRValue.ConstString) {
            return v;
        }
        final String n = valueName(v);
        if (n == null) return null;
        if (lat.getOrDefault(n, Lattice.UNDEFINED) == Lattice.CONSTANT) {
            return constVal.get(n);
        }
        return null;
    }

    private IRValue fold(IRInstruction.BinaryOp op, IRValue l, IRValue r) {
        if (l instanceof IRValue.ConstInt li && r instanceof IRValue.ConstInt ri) {
            long res = switch (op.op()) {
                case ADD -> li.value() + ri.value();
                case SUB -> li.value() - ri.value();
                case MUL -> li.value() * ri.value();
                case DIV -> ri.value() == 0 ? li.value() : li.value() / ri.value();
                case MOD -> ri.value() == 0 ? li.value() : li.value() % ri.value();
                default -> 0;
            };
            // only fold arithmetic ops
            if (op.op() == IRInstruction.BinaryOp.Op.ADD || op.op() == IRInstruction.BinaryOp.Op.SUB ||
                op.op() == IRInstruction.BinaryOp.Op.MUL || op.op() == IRInstruction.BinaryOp.Op.DIV ||
                op.op() == IRInstruction.BinaryOp.Op.MOD) {
                return new IRValue.ConstInt(res, li.type());
            }
        }
        if (l instanceof IRValue.ConstBool lb && r instanceof IRValue.ConstBool rb) {
            if (op.op() == IRInstruction.BinaryOp.Op.AND) return new IRValue.ConstBool(lb.value() && rb.value());
            if (op.op() == IRInstruction.BinaryOp.Op.OR) return new IRValue.ConstBool(lb.value() || rb.value());
            if (op.op() == IRInstruction.BinaryOp.Op.EQ) return new IRValue.ConstBool(lb.value() == rb.value());
            if (op.op() == IRInstruction.BinaryOp.Op.NEQ) return new IRValue.ConstBool(lb.value() != rb.value());
        }
        return null;
    }

    private IRBasicBlock findBlock(String label) {
        for (final var b : function.blocks()) if (b.label().equals(label)) return b;
        return null;
    }

    private IRBasicBlock blockOf(IRInstruction instr) {
        for (final var b : function.blocks()) {
            if (b.instructions().contains(instr)) return b;
        }
        return null;
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }

    private List<String> usedNames(IRInstruction instr) {
        final var names = new ArrayList<String>();
        // reuse pattern
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

    // Result
    public Result result() {
        final Map<String, ValueInfo> values = new LinkedHashMap<>();
        for (final var e : lat.entrySet()) {
            values.put(e.getKey(), new ValueInfo(e.getValue(), constVal.get(e.getKey())));
        }
        final Map<String, Boolean> blocks = new LinkedHashMap<>(blockExec);
        final Set<String> edges = new LinkedHashSet<>(edgeExec);
        return new Result(values, blocks, edges);
    }

    public record Result(Map<String, ValueInfo> values,
                        Map<String, Boolean> blockExecutable,
                        Set<String> edgeExecutable) {
        public boolean isExecutable(String block) {
            return blockExecutable.getOrDefault(block, false);
        }
        public ValueInfo value(String name) {
            return values.get(name);
        }
    }
}