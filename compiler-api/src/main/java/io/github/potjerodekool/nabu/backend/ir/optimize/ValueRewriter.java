package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Eén plek waar IR-instructies opnieuw opgebouwd worden met andere operands.
 *
 * <p>{@code IRInstruction} biedt geen generieke operand-accessor: elke subtype
 * is een eigen record met eigen velden. Een herschrijver moet die allemaal
 * kennen, anders blijven er instructies over die naar de oude waarde wijzen
 * (in LLVM leidt dat dan tot een niet-dominante {@code invoke}-definitie).
 * Daarom staat de volledige, uitputtende switch hier en niet verspreid over de
 * passes die hem nodig hebben.
 *
 * <p>De switch dekt alle subtypes die een waarde kunnen lezen. De overige
 * subtypes ({@code Alloca}, {@code Branch}, {@code HeapAlloc},
 * {@code TryCatchRegion}, {@code Unreachable}) hebben geen value-operands en
 * worden ongewijzigd teruggegeven.
 */
public final class ValueRewriter {

    private ValueRewriter() {
    }

    /**
     * Herschrijft de reads van één instructie met behulp van {@code onTemp}.
     *
     * @param instr  de instructie
     * @param onTemp toegepast op elke {@link IRValue.Temp} die als operand
     *               voorkomt; retourneert de vervangende waarde
     * @return de herbouwde instructie, of {@code instr} zelf als niets veranderde
     */
    public static IRInstruction rewrite(final IRInstruction instr,
                                        final UnaryOperator<IRValue> onTemp) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> {
                final var l = replaceValue(op.left(), onTemp);
                final var r = replaceValue(op.right(), onTemp);
                yield (l != op.left() || r != op.right())
                        ? new IRInstruction.BinaryOp(op.result(), op.op(), l, r, op.location())
                        : op;
            }
            case IRInstruction.Load ld -> {
                final var p = replaceValue(ld.ptr(), onTemp);
                yield (p != ld.ptr())
                        ? new IRInstruction.Load(ld.result(), ld.type(), p, ld.location())
                        : ld;
            }
            case IRInstruction.Store st -> {
                final var p = replaceValue(st.ptr(), onTemp);
                final var v = replaceValue(st.value(), onTemp);
                yield (p != st.ptr() || v != st.value())
                        ? new IRInstruction.Store(p, v, st.location())
                        : st;
            }
            case IRInstruction.Call call -> {
                final var newArgs = replaceArgs(call.args(), onTemp);
                yield (newArgs != call.args())
                        ? new IRInstruction.Call(
                            call.callKind(), call.returnType(), call.paramTypes(),
                            call.result(), call.function(), newArgs, call.location())
                        : call;
            }
            case IRInstruction.IndirectCall ic -> {
                final var c = replaceValue(ic.callee(), onTemp);
                final var newArgs = replaceArgs(ic.args(), onTemp);
                yield (c != ic.callee() || newArgs != ic.args())
                        ? new IRInstruction.IndirectCall(ic.result(), c, ic.fnType(), newArgs, ic.location())
                        : ic;
            }
            case IRInstruction.CondBranch cb -> {
                final var c = replaceValue(cb.condition(), onTemp);
                yield (c != cb.condition())
                        ? new IRInstruction.CondBranch(c, cb.trueLabel(), cb.falseLabel(), cb.location())
                        : cb;
            }
            case IRInstruction.Return ret -> {
                if (ret.value() == null) yield ret;
                final var v = replaceValue(ret.value(), onTemp);
                yield (v != ret.value())
                        ? new IRInstruction.Return(v, ret.location())
                        : ret;
            }
            case IRInstruction.Cast cast -> {
                final var s = replaceValue(cast.source(), onTemp);
                yield (s != cast.source())
                        ? new IRInstruction.Cast(cast.result(), s, cast.targetType(), cast.location())
                        : cast;
            }
            case IRInstruction.InstanceOf io -> {
                final var s = replaceValue(io.source(), onTemp);
                yield (s != io.source())
                        ? new IRInstruction.InstanceOf(io.result(), s, io.type(), io.location())
                        : io;
            }
            case IRInstruction.ArrayLoad al -> {
                final var a = replaceValue(al.array(), onTemp);
                final var idx = replaceValue(al.index(), onTemp);
                yield (a != al.array() || idx != al.index())
                        ? new IRInstruction.ArrayLoad(al.result(), a, idx, al.elemType(), al.location())
                        : al;
            }
            case IRInstruction.ArrayStore as -> {
                final var a = replaceValue(as.array(), onTemp);
                final var idx = replaceValue(as.index(), onTemp);
                final var v = replaceValue(as.value(), onTemp);
                yield (a != as.array() || idx != as.index() || v != as.value())
                        ? new IRInstruction.ArrayStore(a, idx, v, as.elemType(), as.location())
                        : as;
            }
            case IRInstruction.ArrayLength al -> {
                final var a = replaceValue(al.array(), onTemp);
                yield (a != al.array())
                        ? new IRInstruction.ArrayLength(al.result(), a, al.location())
                        : al;
            }
            case IRInstruction.AllocaArray aa -> {
                final var s = replaceValue(aa.size(), onTemp);
                yield (s != aa.size())
                        ? new IRInstruction.AllocaArray(aa.result(), aa.allocType(), s, aa.location())
                        : aa;
            }
            case IRInstruction.MonitorEnter me -> {
                final var o = replaceValue(me.object(), onTemp);
                yield (o != me.object())
                        ? new IRInstruction.MonitorEnter(o, me.location())
                        : me;
            }
            case IRInstruction.MonitorExit mx -> {
                final var o = replaceValue(mx.object(), onTemp);
                yield (o != mx.object())
                        ? new IRInstruction.MonitorExit(o, mx.location())
                        : mx;
            }
            case IRInstruction.Throw t -> {
                final var v = replaceValue(t.result(), onTemp);
                yield (v != t.result())
                        ? new IRInstruction.Throw(v, t.type(), t.location())
                        : t;
            }
            case IRInstruction.Pop p -> {
                final var v = replaceValue(p.result(), onTemp);
                yield (v != p.result())
                        ? new IRInstruction.Pop(v, p.location())
                        : p;
            }
            case IRInstruction.Phi phi -> {
                boolean anyChanged = false;
                final var newIncoming = new ArrayList<IRInstruction.Phi.Incoming>();
                for (final var incoming : phi.incomingValues()) {
                    final var v = replaceValue(incoming.value(), onTemp);
                    newIncoming.add(new IRInstruction.Phi.Incoming(v, incoming.fromBlock()));
                    if (v != incoming.value()) anyChanged = true;
                }
                yield anyChanged
                        ? new IRInstruction.Phi(phi.result(), newIncoming, phi.location())
                        : phi;
            }
            case IRInstruction.Move move -> {
                final var v = replaceValue(move.value(), onTemp);
                yield (v != move.value())
                        ? new IRInstruction.Move(move.result(), v, move.location())
                        : move;
            }
            // Geen value-operands.
            case IRInstruction.Alloca ignored -> ignored;
            case IRInstruction.Branch ignored -> ignored;
            case IRInstruction.HeapAlloc ignored -> ignored;
            case IRInstruction.TryCatchRegion ignored -> ignored;
            case IRInstruction.Unreachable ignored -> ignored;
        };
    }

    /**
     * Herscherft alle reads van {@code block} in place.
     *
     * @return {@code true} als er minstens één instructie veranderde
     */
    public static boolean rewriteReadsIn(final IRBasicBlock block,
                                         final UnaryOperator<IRValue> onTemp) {
        boolean changed = false;
        final var instructions = block.instructions();
        for (int i = 0; i < instructions.size(); i++) {
            final var replaced = rewrite(instructions.get(i), onTemp);
            if (replaced != instructions.get(i)) {
                block.setInstruction(i, replaced);
                changed = true;
            }
        }
        return changed;
    }

    /** Alle waarden die {@code instr} leest. */
    public static List<IRValue> readsOf(final IRInstruction instr) {
        final var reads = new ArrayList<IRValue>();
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
            case IRInstruction.Pop p -> reads.add(p.result());
            case IRInstruction.Phi phi -> {
                for (final var incoming : phi.incomingValues()) {
                    reads.add(incoming.value());
                }
            }
            case IRInstruction.Move m -> reads.add(m.value());
            default -> {
            }
        }
        return reads;
    }

    /** Vervangt temps binnen één operand, inclusief binnen veld-adresketens. */
    public static IRValue replaceValue(final IRValue value,
                                        final UnaryOperator<IRValue> onTemp) {
        if (value instanceof IRValue.Temp temp) {
            return onTemp.apply(temp);
        }
        if (value instanceof IRValue.Values chains) {
            // Veld-adresketens bevatten SSA-temps; ook binnenin vervangen,
            // anders verwijst de resterende keten naar een waarde die nog
            // nergens gedefinieerd is.
            final var inner = chains.values();
            List<IRValue> newInner = null;
            for (int i = 0; i < inner.size(); i++) {
                final var replaced = replaceValue(inner.get(i), onTemp);
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

    /** Vervangt temps binnen een operandlijst. */
    public static List<IRValue> replaceArgs(final List<IRValue> args,
                                            final UnaryOperator<IRValue> onTemp) {
        List<IRValue> newArgs = null;
        for (int i = 0; i < args.size(); i++) {
            final var replaced = replaceValue(args.get(i), onTemp);
            if (replaced != args.get(i)) {
                if (newArgs == null) newArgs = new ArrayList<>(args);
                newArgs.set(i, replaced);
            }
        }
        return newArgs != null ? newArgs : args;
    }
}