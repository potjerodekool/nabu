package io.github.potjerodekool.nabu.backend.ir.dataflow.analysis;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowAnalysis;
import io.github.potjerodekool.nabu.backend.ir.dataflow.DataFlowDirection;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Interval-analyse (voorwaarts): bepaalt per integer-waarde een interval [lo,hi].
 * Gebruikt voor bounds-check eliminatie.
 */
public class IntervalAnalysis implements DataFlowAnalysis<Map<String, Interval>> {

    @Override
    public DataFlowDirection direction() { return DataFlowDirection.FORWARD; }

    @Override
    public Map<String, Interval> boundaryValue(IRFunction function) { return new LinkedHashMap<>(); }

    @Override
    public Map<String, Interval> initialValue() { return new LinkedHashMap<>(); }

    @Override
    public Map<String, Interval> transfer(IRBasicBlock block, Map<String, Interval> in) {
        final var out = new LinkedHashMap<>(in);
        for (final var instr : block.instructions()) {
            final var res = instr.result();
            final String name = (res == null) ? null : valueName(res);
            if (name == null) continue;
            if (!(res.type() instanceof IRType.Int) && !(res.type() instanceof IRType.Bool)) {
                // not an integer; skip
                // but bool is int-like; handle below
            }
            Interval iv = null;
            switch (instr) {
                case IRInstruction.Move m -> {
                    if (m.value() instanceof IRValue.ConstInt ci) iv = Interval.point(ci.value());
                    else if (m.value() instanceof IRValue.ConstBool cb) iv = Interval.point(cb.value() ? 1 : 0);
                    else iv = in.getOrDefault(valueName(m.value()), Interval.top());
                }
                case IRInstruction.Cast c -> iv = in.getOrDefault(valueName(c.source()), Interval.top());
                case IRInstruction.Phi phi -> {
                    Interval join = Interval.bottom();
                    boolean any = false;
                    for (final var inc : phi.incomingValues()) {
                        Interval inv;
                        final String inName = valueName(inc.value());
                        if (inName == null) {
                            if (inc.value() instanceof IRValue.ConstInt ci) inv = Interval.point(ci.value());
                            else if (inc.value() instanceof IRValue.ConstBool cb) inv = Interval.point(cb.value() ? 1 : 0);
                            else inv = Interval.top();
                        } else {
                            inv = in.getOrDefault(inName, Interval.top());
                        }
                        if (!any) { join = inv; any = true; }
                        else join = joinInterval(join, inv);
                    }
                    if (!any) join = Interval.top();
                    iv = join;
                }
                case IRInstruction.BinaryOp op -> {
                    final Interval li = getInterval(op.left(), in);
                    final Interval ri = getInterval(op.right(), in);
                    iv = evalBinary(op.op(), li, ri);
                }
                case IRInstruction.ArrayLength al -> {
                    // length non-negative; track as [0, +inf) (or exact if known)
                    iv = Interval.range(0, Long.MAX_VALUE);
                }
                default -> {
                    // unknown producer -> top
                    iv = Interval.top();
                }
            }
            if (iv != null) {
                out.put(name, iv);
            }
        }
        return out;
    }

    private Interval getInterval(IRValue v, Map<String, Interval> env) {
        if (v instanceof IRValue.ConstInt ci) return Interval.point(ci.value());
        if (v instanceof IRValue.ConstBool cb) return Interval.point(cb.value() ? 1 : 0);
        final String n = valueName(v);
        if (n == null) return Interval.top();
        return env.getOrDefault(n, Interval.top());
    }

    private Interval evalBinary(IRInstruction.BinaryOp.Op op, Interval a, Interval b) {
        if (a.isBottom() || b.isBottom()) return Interval.bottom();
        if (a.isTop() || b.isTop()) {
            // For ADD/SUB we can still bound if one side top? Conservative: top
            // except comparisons yield [0,1]
            if (op == IRInstruction.BinaryOp.Op.EQ || op == IRInstruction.BinaryOp.Op.NEQ ||
                op == IRInstruction.BinaryOp.Op.LT || op == IRInstruction.BinaryOp.Op.LTE ||
                op == IRInstruction.BinaryOp.Op.GT || op == IRInstruction.BinaryOp.Op.GTE) {
                return Interval.range(0, 1);
            }
            return Interval.top();
        }
        return switch (op) {
            case ADD -> Interval.range(safeAdd(a.lo(), b.lo()), safeAdd(a.hi(), b.hi()));
            case SUB -> Interval.range(safeSub(a.lo(), b.hi()), safeSub(a.hi(), b.lo()));
            case MUL -> {
                long[] prods = {a.lo()*b.lo(), a.lo()*b.hi(), a.hi()*b.lo(), a.hi()*b.hi()};
                long mn = prods[0], mx = prods[0];
                for (long p : prods) { mn = Math.min(mn, p); mx = Math.max(mx, p); }
                yield Interval.range(mn, mx);
            }
            case DIV -> Interval.top(); // conservative
            case MOD -> Interval.top();
            case SHL, SHR, USHR -> Interval.top();
            case AND, OR, XOR, BITAND, BITOR, BITXOR -> Interval.top();
            default -> {
                if (op == IRInstruction.BinaryOp.Op.EQ || op == IRInstruction.BinaryOp.Op.NEQ ||
                    op == IRInstruction.BinaryOp.Op.LT || op == IRInstruction.BinaryOp.Op.LTE ||
                    op == IRInstruction.BinaryOp.Op.GT || op == IRInstruction.BinaryOp.Op.GTE) {
                    yield Interval.range(0, 1);
                }
                yield Interval.top();
            }
        };
    }

    private long safeAdd(long a, long b) {
        long r = a + b;
        // clamp
        if (a > 0 && b > 0 && r < 0) return Long.MAX_VALUE;
        if (a < 0 && b < 0 && r > 0) return Long.MIN_VALUE;
        return r;
    }

    private long safeSub(long a, long b) {
        long r = a - b;
        if (a >= 0 && b < 0 && r < 0) return Long.MAX_VALUE;
        if (a < 0 && b > 0 && r > 0) return Long.MIN_VALUE;
        return r;
    }

    private Interval joinInterval(Interval a, Interval b) {
        if (a.isBottom()) return b;
        if (b.isBottom()) return a;
        if (a.isTop() || b.isTop()) return Interval.top();
        return Interval.range(Math.min(a.lo(), b.lo()), Math.max(a.hi(), b.hi()));
    }

    @Override
    public Map<String, Interval> meet(Map<String, Interval> a, Map<String, Interval> b) {
        final var r = new LinkedHashMap<>(a);
        final var keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (final var k : keys) {
            final Interval va = a.getOrDefault(k, Interval.top());
            final Interval vb = b.getOrDefault(k, Interval.top());
            r.put(k, joinInterval(va, vb));
        }
        return r;
    }

    @Override
    public boolean equalsValue(Map<String, Interval> a, Map<String, Interval> b) {
        return Objects.equals(a, b);
    }

    private static final long[] THRESHOLDS = {0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 4096, 65536, Integer.MAX_VALUE, Long.MAX_VALUE};

    private static long widenBound(long val, boolean upper) {
        if (upper) {
            for (long t : THRESHOLDS) {
                if (val <= t) return t;
            }
            return Long.MAX_VALUE;
        } else {
            for (int i = THRESHOLDS.length - 1; i >= 0; i--) {
                if (val >= THRESHOLDS[i]) return THRESHOLDS[i];
            }
            return Long.MIN_VALUE;
        }
    }

    @Override
    public Map<String, Interval> widen(Map<String, Interval> a, Map<String, Interval> b) {
        // Widening met thresholds: verbreed grenzen naar de volgende threshold
        // i.p.v. direct naar top, voor betere precisie bij loops.
        final var r = new LinkedHashMap<>(a);
        final var keys = new LinkedHashSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (final var k : keys) {
            final Interval va = a.getOrDefault(k, Interval.top());
            final Interval vb = b.getOrDefault(k, Interval.top());
            if (va.isTop() || vb.isTop()) {
                r.put(k, Interval.top());
            } else if (vb.lo() < va.lo() || vb.hi() > va.hi()) {
                // groei -> verbreed naar thresholds
                long newLo = (vb.lo() < va.lo()) ? widenBound(vb.lo(), false) : va.lo();
                long newHi = (vb.hi() > va.hi()) ? widenBound(vb.hi(), true) : va.hi();
                r.put(k, Interval.range(newLo, newHi));
            } else {
                r.put(k, vb);
            }
        }
        return r;
    }

    @Override
    public Map<String, Interval> refine(Map<String, Interval> v, IRBasicBlock predecessorBlock,
                                       IRValue condition, boolean truthValue) {
        // Zoek de vergelijking die `condition` definieert.
        IRInstruction.BinaryOp cmp = null;
        if (condition instanceof IRValue.Temp || condition instanceof IRValue.Named) {
            final String cname = (condition instanceof IRValue.Temp t) ? t.name() : ((IRValue.Named) condition).name();
            for (final var instr : predecessorBlock.instructions()) {
                if (instr instanceof IRInstruction.BinaryOp b && instr.result() != null) {
                    final String rname = (instr.result() instanceof IRValue.Temp t) ? t.name()
                            : (instr.result() instanceof IRValue.Named n) ? n.name() : null;
                    if (cname.equals(rname)) { cmp = b; break; }
                }
            }
        }
        if (cmp == null) return v;
        final var op = cmp.op();
        if (op != IRInstruction.BinaryOp.Op.LT && op != IRInstruction.BinaryOp.Op.LTE &&
            op != IRInstruction.BinaryOp.Op.GT && op != IRInstruction.BinaryOp.Op.GTE &&
            op != IRInstruction.BinaryOp.Op.EQ && op != IRInstruction.BinaryOp.Op.NEQ) {
            return v;
        }

        // Vind de variabele-zijde en de constante-zijde.
        IRValue varSide = null;
        Long constVal = null;
        if (cmp.left() instanceof IRValue.ConstInt ci) { constVal = ci.value(); varSide = cmp.right(); }
        else if (cmp.right() instanceof IRValue.ConstInt ci) { constVal = ci.value(); varSide = cmp.left(); }
        else return v;

        final String varName = valueName(varSide);
        if (varName == null) return v;

        final var cur = v.getOrDefault(varName, Interval.top());
        if (cur.isTop() || cur.isBottom()) {
            // verfijn van top: gebruik een ruime initiale range
            // (we kunnen niet weten de oorspronkelijke range; gebruik top als basis)
        }
        long lo = cur.isTop() ? Long.MIN_VALUE : cur.lo();
        long hi = cur.isTop() ? Long.MAX_VALUE : cur.hi();
        if (cur.isBottom()) return v;

        // Pas de vergelijking toe afhankelijk van truthValue en op.
        // We verfijnen de interval van varSide.
        boolean takeTrue = truthValue;
        // Normaliseer: werk met de conditie "var op const"
        // Als const aan de linkerkant stond, spiegel de operator.
        boolean constOnLeft = cmp.left() instanceof IRValue.ConstInt;
        IRInstruction.BinaryOp.Op normOp = op;
        if (constOnLeft) {
            // const op var -> var (gespiegeld) const
            normOp = switch (op) {
                case LT -> IRInstruction.BinaryOp.Op.GT;
                case LTE -> IRInstruction.BinaryOp.Op.GTE;
                case GT -> IRInstruction.BinaryOp.Op.LT;
                case GTE -> IRInstruction.BinaryOp.Op.LTE;
                default -> op;
            };
        }

        if (takeTrue) {
            switch (normOp) {
                case LT -> hi = Math.min(hi, constVal - 1);
                case LTE -> hi = Math.min(hi, constVal);
                case GT -> lo = Math.max(lo, constVal + 1);
                case GTE -> lo = Math.max(lo, constVal);
                case EQ -> { lo = constVal; hi = constVal; }
                case NEQ -> {
                    // verwijder een enkele waarde; interval kan dit niet exact, skip
                }
                default -> {}
            }
        } else {
            switch (normOp) {
                case LT -> lo = Math.max(lo, constVal);
                case LTE -> lo = Math.max(lo, constVal + 1);
                case GT -> hi = Math.min(hi, constVal);
                case GTE -> hi = Math.min(hi, constVal - 1);
                case EQ -> {
                    // var != const: interval kan dit niet exact, skip
                }
                case NEQ -> {
                    // var == const: interval kan dit niet exact, skip
                }
                default -> {}
            }
        }

        if (lo > hi) {
            // onbereikbaar pad
            final var result = new LinkedHashMap<>(v);
            result.put(varName, Interval.bottom());
            return result;
        }
        final var result = new LinkedHashMap<>(v);
        result.put(varName, Interval.range(lo, hi));
        return result;
    }

    private String valueName(IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}