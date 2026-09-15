package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Copy Propagation: vervangt Move-instructies door hun bronwaarde.
 *
 * In SSA-vorm zijn moves ontstaan uit:
 *   - SsaBuilder (loads die door Moves worden vervangen)
 *   - PhiElimination (store/load sequenties)
 *
 * Voorbeeld:
 *   %a = 42
 *   %b = move %a       →  verwijder move, vervang %b door %a overal
 *   %c = %b + 1        →  %c = %a + 1
 */
public class CopyPropagation implements OptimizationPass {

    @Override
    public boolean run(final IRFunction function) {
        boolean changed = false;

        for (final var block : function.blocks()) {
            changed |= propagateBlock(block, function);
        }

        return changed;
    }

    private boolean propagateBlock(final IRBasicBlock block,
                                   final IRFunction function) {
        boolean changed = false;
        final Map<String, IRValue> copies = new LinkedHashMap<>();

        // Stap 1: Verzamel alle moves in dit blok die alleen in dit blok
        // gebruikt worden. Een Move wiens resultaat óók in een ander blok
        // gelezen wordt mag NIET verwijderd worden: de cross-block uses
        // zouden anders naar een niet-gedefinieerde temp verwijzen
        // ('Onbekende register'/'uninitialized slot').
        final var instructions = block.instructions();
        final List<Integer> moveIndices = new ArrayList<>();

        for (int i = 0; i < instructions.size(); i++) {
            final var instr = instructions.get(i);
            if (instr instanceof IRInstruction.Move move
                    && isUsedOnlyInBlock(move.result(), block, function)) {
                final var src = resolveCopies(move.value(), copies);
                copies.put(IRValue.nameOf(move.result()), src);
                moveIndices.add(i);
                changed = true;
            }
        }

        if (!changed) {
            return false;
        }

        // Stap 2: Verwijder alle moves (achterstevoren zodat indices geldig blijven)
        final var instrList = new ArrayList<>(block.instructions());
        for (int i = moveIndices.size() - 1; i >= 0; i--) {
            block.removeInstruction(instrList.get(moveIndices.get(i)));
        }

        // Stap 3: Vervang referenties in overgebleven instructies
        final var remaining = new ArrayList<>(block.instructions());
        for (int i = 0; i < remaining.size(); i++) {
            final var instr = remaining.get(i);
            final var replaced = replaceInInstruction(instr, copies);
            if (replaced != instr) {
                block.setInstruction(i, replaced);
            }
        }

        return changed;
    }

    /**
     * Loss een keten van copies op tot de uiteindelijke bronwaarde.
     */
    private IRValue resolveCopies(final IRValue value,
                                  final Map<String, IRValue> copies) {
        if (value instanceof IRValue.Temp temp) {
            final var mapping = copies.get(temp.name());
            if (mapping != null) {
                return resolveCopies(mapping, copies);
            }
        } else if (value instanceof IRValue.Values chains) {
            final var inner = chains.values();
            List<IRValue> newInner = null;
            for (int i = 0; i < inner.size(); i++) {
                final var resolved = resolveCopies(inner.get(i), copies);
                if (resolved != inner.get(i)) {
                    if (newInner == null) newInner = new ArrayList<>(inner);
                    newInner.set(i, resolved);
                }
            }
            if (newInner != null) {
                return new IRValue.Values(newInner);
            }
        }
        return value;
    }

    /**
     * True als het resultaat uitsluitend in dit blok gebruikt wordt (ten
     * minste één keer). Alleen dan mag de definiërende Move verwijderd en
     * de copy doorgedrukt worden.
     */
    private boolean isUsedOnlyInBlock(final IRValue value,
                                   final IRBasicBlock block,
                                   final IRFunction function) {
        if (!(value instanceof IRValue.Temp temp)) {
            return false;
        }
        final var name = temp.name();

        boolean usedInOtherBlock = false;
        boolean usedInThisBlock = false;
        for (final var other : function.blocks()) {
            for (final var instr : other.instructions()) {
                if (instr instanceof IRInstruction.Move move
                        && IRValue.nameOf(instr.result()).equals(name)) {
                    // De Move zelf is geen 'use'
                    continue;
                }
                if (references(instr, name)) {
                    if (other == block) {
                        usedInThisBlock = true;
                    } else {
                        usedInOtherBlock = true;
                    }
                }
            }
        }
        return !usedInOtherBlock;
    }

    private boolean references(final IRInstruction instr, final String tempName) {
        for (final var read : readsOf(instr)) {
            if (read instanceof IRValue.Temp t && t.name().equals(tempName)) {
                return true;
            } else if (read instanceof IRValue.Values values) {
                for (final var inner : values.values()) {
                    if (inner instanceof IRValue.Temp t && t.name().equals(tempName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<IRValue> readsOf(final IRInstruction instr) {
        final var reads = new java.util.ArrayList<IRValue>();
        switch (instr) {
            case IRInstruction.BinaryOp op -> {
                reads.add(op.left());
                reads.add(op.right());
            }
            case IRInstruction.Load ld -> reads.add(ld.ptr());
            case IRInstruction.Store st -> {
                reads.add(st.ptr());
                reads.add(st.value());
            }
            case IRInstruction.Call call -> reads.addAll(call.args());
            case IRInstruction.IndirectCall ic -> {
                reads.add(ic.callee());
                reads.addAll(ic.args());
            }
            case IRInstruction.CondBranch cb -> reads.add(cb.condition());
            case IRInstruction.Return ret -> {
                if (ret.value() != null) reads.add(ret.value());
            }
            case IRInstruction.Cast c -> reads.add(c.source());
            case IRInstruction.InstanceOf i -> reads.add(i.source());
            case IRInstruction.ArrayLoad al -> {
                reads.add(al.array());
                reads.add(al.index());
            }
            case IRInstruction.ArrayStore as -> {
                reads.add(as.array());
                reads.add(as.index());
                reads.add(as.value());
            }
            case IRInstruction.ArrayLength al -> reads.add(al.array());
            case IRInstruction.AllocaArray aa -> reads.add(aa.size());
            case IRInstruction.MonitorEnter me -> reads.add(me.object());
            case IRInstruction.MonitorExit mx -> reads.add(mx.object());
            case IRInstruction.Throw t -> {
                if (t.result() != null) reads.add(t.result());
            }
            case IRInstruction.Phi phi -> {
                for (final var incoming : phi.incomingValues()) {
                    reads.add(incoming.value());
                }
            }
            case IRInstruction.Move m -> reads.add(m.value());
            default -> {}
        }
        return reads;
    }

    private IRInstruction replaceInInstruction(final IRInstruction instr,
                                               final Map<String, IRValue> copies) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> {
                var l = replaceValue(op.left(), copies);
                var r = replaceValue(op.right(), copies);
                yield (l != op.left() || r != op.right())
                        ? new IRInstruction.BinaryOp(op.result(), op.op(), l, r, op.location())
                        : op;
            }
            case IRInstruction.Load ld -> {
                var p = replaceValue(ld.ptr(), copies);
                yield (p != ld.ptr())
                        ? new IRInstruction.Load(ld.result(), ld.type(), p, ld.location())
                        : ld;
            }
            case IRInstruction.Store st -> {
                var p = replaceValue(st.ptr(), copies);
                var v = replaceValue(st.value(), copies);
                yield (p != st.ptr() || v != st.value())
                        ? new IRInstruction.Store(p, v, st.location())
                        : st;
            }
            case IRInstruction.Call call -> {
                final var newArgs = replaceArgs(call.args(), copies);
                yield (newArgs != call.args())
                        ? new IRInstruction.Call(
                            call.callKind(), call.returnType(), call.paramTypes(),
                            call.result(), call.function(), newArgs, call.location())
                        : call;
            }
            case IRInstruction.IndirectCall ic -> {
                var c = replaceValue(ic.callee(), copies);
                final var newArgs = replaceArgs(ic.args(), copies);
                yield (c != ic.callee() || newArgs != ic.args())
                        ? new IRInstruction.IndirectCall(ic.result(), c, ic.fnType(), newArgs, ic.location())
                        : ic;
            }
            case IRInstruction.CondBranch cb -> {
                var c = replaceValue(cb.condition(), copies);
                yield (c != cb.condition())
                        ? new IRInstruction.CondBranch(c, cb.trueLabel(), cb.falseLabel(), cb.location())
                        : cb;
            }
            case IRInstruction.Return ret -> {
                if (ret.value() == null) yield ret;
                var v = replaceValue(ret.value(), copies);
                yield (v != ret.value())
                        ? new IRInstruction.Return(v, ret.location())
                        : ret;
            }
            case IRInstruction.Cast cast -> {
                var s = replaceValue(cast.source(), copies);
                yield (s != cast.source())
                        ? new IRInstruction.Cast(cast.result(), s, cast.targetType(), cast.location())
                        : cast;
            }
            case IRInstruction.Phi phi -> {
                boolean anyChanged = false;
                final var newIncoming = new ArrayList<IRInstruction.Phi.Incoming>();
                for (final var incoming : phi.incomingValues()) {
                    final var v = replaceValue(incoming.value(), copies);
                    newIncoming.add(new IRInstruction.Phi.Incoming(v, incoming.fromBlock()));
                    if (v != incoming.value()) anyChanged = true;
                }
                yield anyChanged
                        ? new IRInstruction.Phi(phi.result(), newIncoming, phi.location())
                        : phi;
            }
            case IRInstruction.Move move -> {
                var v = replaceValue(move.value(), copies);
                yield (v != move.value())
                        ? new IRInstruction.Move(move.result(), v, move.location())
                        : move;
            }
            case IRInstruction.ArrayLoad al -> {
                var a = replaceValue(al.array(), copies);
                var idx = replaceValue(al.index(), copies);
                yield (a != al.array() || idx != al.index())
                        ? new IRInstruction.ArrayLoad(al.result(), a, idx, al.elemType(), al.location())
                        : al;
            }
            case IRInstruction.ArrayStore as -> {
                var a = replaceValue(as.array(), copies);
                var idx = replaceValue(as.index(), copies);
                var v = replaceValue(as.value(), copies);
                yield (a != as.array() || idx != as.index() || v != as.value())
                        ? new IRInstruction.ArrayStore(a, idx, v, as.elemType(), as.location())
                        : as;
            }
            case IRInstruction.ArrayLength al -> {
                var a = replaceValue(al.array(), copies);
                yield (a != al.array())
                        ? new IRInstruction.ArrayLength(al.result(), a, al.location())
                        : al;
            }
            case IRInstruction.InstanceOf io -> {
                var s = replaceValue(io.source(), copies);
                yield (s != io.source())
                        ? new IRInstruction.InstanceOf(io.result(), s, io.type(), io.location())
                        : io;
            }
            case IRInstruction.MonitorEnter me -> {
                var o = replaceValue(me.object(), copies);
                yield (o != me.object())
                        ? new IRInstruction.MonitorEnter(o, me.location())
                        : me;
            }
            case IRInstruction.MonitorExit mx -> {
                var o = replaceValue(mx.object(), copies);
                yield (o != mx.object())
                        ? new IRInstruction.MonitorExit(o, mx.location())
                        : mx;
            }
            case IRInstruction.Throw t -> {
                var v = replaceValue(t.result(), copies);
                yield (v != t.result())
                        ? new IRInstruction.Throw(v, t.type(), t.location())
                        : t;
            }
            case IRInstruction.Pop p -> {
                var v = replaceValue(p.result(), copies);
                yield (v != p.result())
                        ? new IRInstruction.Pop(v, p.location())
                        : p;
            }
            case IRInstruction.AllocaArray aa -> {
                var s = replaceValue(aa.size(), copies);
                yield (s != aa.size())
                        ? new IRInstruction.AllocaArray(aa.result(), aa.allocType(), s, aa.location())
                        : aa;
            }
            default -> instr;
        };
    }

    private IRValue replaceValue(final IRValue value,
                                 final Map<String, IRValue> copies) {
        if (value instanceof IRValue.Temp temp) {
            final var resolved = resolveCopies(temp, copies);
            if (resolved != temp) return resolved;
        } else if (value instanceof IRValue.Values chains) {
            // Field-address ketens (IRValue.Values) bevatten SSA-temps;
            // ook binnenin vervangen, anders verwijst de resterende
            // keten naar een temp wiens Move verwijderd is (uninit slot).
            final var inner = chains.values();
            List<IRValue> newInner = null;
            for (int i = 0; i < inner.size(); i++) {
                final var replaced = replaceValue(inner.get(i), copies);
                if (replaced != inner.get(i)) {
                    if (newInner == null) newInner = new ArrayList<>(inner);
                    newInner.set(i, replaced);
                }
            }
            if (newInner != null) {
                return new IRValue.Values(newInner);
            }
        }
        return value;
    }

    private List<IRValue> replaceArgs(final List<IRValue> args,
                                      final Map<String, IRValue> copies) {
        List<IRValue> newArgs = null;
        for (int i = 0; i < args.size(); i++) {
            final var replaced = replaceValue(args.get(i), copies);
            if (replaced != args.get(i)) {
                if (newArgs == null) newArgs = new ArrayList<>(args);
                newArgs.set(i, replaced);
            }
        }
        return newArgs != null ? newArgs : args;
    }
}
