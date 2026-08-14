package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

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
            changed |= propagateBlock(block);
        }

        return changed;
    }

    private boolean propagateBlock(final IRBasicBlock block) {
        boolean changed = false;
        final Map<String, IRValue> copies = new LinkedHashMap<>();

        // Stap 1: Verzamel alle moves in dit blok
        final var instructions = block.instructions();
        final List<Integer> moveIndices = new ArrayList<>();

        for (int i = 0; i < instructions.size(); i++) {
            final var instr = instructions.get(i);
            if (instr instanceof IRInstruction.Move move) {
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
        }
        return value;
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
            default -> instr;
        };
    }

    private IRValue replaceValue(final IRValue value,
                                 final Map<String, IRValue> copies) {
        if (value instanceof IRValue.Temp temp) {
            final var resolved = resolveCopies(temp, copies);
            if (resolved != temp) return resolved;
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
