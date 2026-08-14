package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.debug.SourceLocation;
import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.IRModule;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Phi-eliminatie pass: verwijdert alle Phi-instructies uit een functie
 * door ze te vervangen door move-sequenties (stores) in predecessor-blokken.
 *
 * Algoritme:
 *   1. Verzamel alle phi's per blok
 *   2. Voor elke phi: maak een nieuw tijdelijk register + load register
 *   3. Voor elke (waarde, predecessor) pair: voeg een Store toe aan
 *      het einde van het predecessor-blok (voor de terminator)
 *   4. Verwijder de phi-instructies en voeg loads toe aan het begin van het blok
 *   5. Vervang alle verwijzingen naar phi-resultaten door de load registers
 */
public class PhiElimination {

    public static void run(final IRModule module) {
        for (final var function : module.functions()) {
            if (!function.isExternal()) {
                run(function);
            }
        }
    }

    public static void run(final IRFunction function) {
        // Stap 1: Verzamel alle phi-instructies per blok
        final Map<String, List<IRInstruction.Phi>> phisByBlock = new LinkedHashMap<>();
        for (final var block : function.blocks()) {
            final var phis = block.instructions().stream()
                    .filter(IRInstruction.Phi.class::isInstance)
                    .map(IRInstruction.Phi.class::cast)
                    .toList();
            if (!phis.isEmpty()) {
                phisByBlock.put(block.label(), phis);
            }
        }

        if (phisByBlock.isEmpty()) {
            return;
        }

        // Stap 2: Voor elke phi, maak temp + load en voeg stores toe aan predecessors
        final List<PhiReplacement> allReplacements = new ArrayList<>();

        for (final var block : function.blocks()) {
            final var phis = phisByBlock.get(block.label());
            if (phis == null) continue;

            for (final var phi : phis) {
                final var temp = new IRValue.Temp(
                        "%phi." + block.label() + "." + IRValue.nameOf(phi.result()),
                        phi.result().type()
                );
                final var load = new IRValue.Temp(
                        "%phi." + block.label() + "." + IRValue.nameOf(phi.result()) + ".load",
                        phi.result().type()
                );
                final var loc = phi.location();

                for (final var incoming : phi.incomingValues()) {
                    // De fromBlock van een incoming kan een verouderde
                    // instantie zijn (bv. na Optimizer.removeRedundantBranches,
                    // die blokken herbouwt). Zoek het live blok op label.
                    final var fromBlock = function.blocks().stream()
                            .filter(b -> b.label().equals(incoming.fromBlock().label()))
                            .findFirst()
                            .orElse(incoming.fromBlock());
                    fromBlock.insertBeforeTerminator(
                            new IRInstruction.Store(temp, incoming.value(), loc)
                    );
                }

                allReplacements.add(new PhiReplacement(phi, block.label(), temp, load, loc));
            }
        }

        // Stap 3: Verwijder phi's en voeg loads toe aan het begin van blokken
        for (final var block : function.blocks()) {
            final var phis = phisByBlock.get(block.label());
            if (phis == null) continue;

            // Verwijder phi-instructies
            for (final var phi : phis) {
                block.removeInstruction(phi);
            }

            // Verzamel loads voor dit blok
            final var blockLoads = allReplacements.stream()
                    .filter(r -> r.blockLabel().equals(block.label()))
                    .toList();

            // Voeg loads toe aan het begin (in volgorde)
            for (int i = 0; i < blockLoads.size(); i++) {
                final var rep = blockLoads.get(i);
                block.add(i,
                        new IRInstruction.Load(
                                rep.load(),
                                rep.phi().result().type(),
                                rep.temp(),
                                rep.location()
                        )
                );
            }
        }

        // Stap 4: Vervang phi-resultaten door loads in alle instructies
        final Map<String, IRValue> phiToLoad = allReplacements.stream()
                .collect(Collectors.toMap(
                        rep -> IRValue.nameOf(rep.phi().result()),
                        PhiReplacement::load
                ));

        for (final var block : function.blocks()) {
            final var instrs = block.instructions();
            for (int i = 0; i < instrs.size(); i++) {
                final var old = instrs.get(i);
                if (old instanceof IRInstruction.Phi) continue;
                final var replaced = replaceInInstruction(old, phiToLoad);
                if (replaced != old) {
                    block.setInstruction(i, replaced);
                }
            }
        }
    }

    private static IRInstruction replaceInInstruction(final IRInstruction instr,
                                                      final Map<String, IRValue> phiToLoad) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> {
                var l = replaceIfPhi(op.left(), phiToLoad);
                var r = replaceIfPhi(op.right(), phiToLoad);
                yield (l != op.left() || r != op.right())
                        ? new IRInstruction.BinaryOp(op.result(), op.op(), l, r, op.location())
                        : op;
            }
            case IRInstruction.Load ld -> {
                var p = replaceIfPhi(ld.ptr(), phiToLoad);
                yield (p != ld.ptr())
                        ? new IRInstruction.Load(ld.result(), ld.type(), p, ld.location())
                        : ld;
            }
            case IRInstruction.Store st -> {
                var p = replaceIfPhi(st.ptr(), phiToLoad);
                var v = replaceIfPhi(st.value(), phiToLoad);
                yield (p != st.ptr() || v != st.value())
                        ? new IRInstruction.Store(p, v, st.location())
                        : st;
            }
            case IRInstruction.Call call -> {
                final var newArgs = replaceArgs(call.args(), phiToLoad);
                yield (newArgs != call.args())
                        ? new IRInstruction.Call(
                            call.callKind(), call.returnType(), call.paramTypes(),
                            call.result(), call.function(), newArgs, call.location())
                        : call;
            }
            case IRInstruction.IndirectCall ic -> {
                var c = replaceIfPhi(ic.callee(), phiToLoad);
                final var newArgs = replaceArgs(ic.args(), phiToLoad);
                yield (c != ic.callee() || newArgs != ic.args())
                        ? new IRInstruction.IndirectCall(ic.result(), c, ic.fnType(), newArgs, ic.location())
                        : ic;
            }
            case IRInstruction.CondBranch cb -> {
                var c = replaceIfPhi(cb.condition(), phiToLoad);
                yield (c != cb.condition())
                        ? new IRInstruction.CondBranch(c, cb.trueLabel(), cb.falseLabel(), cb.location())
                        : cb;
            }
            case IRInstruction.Return ret -> {
                if (ret.value() == null) yield ret;
                var v = replaceIfPhi(ret.value(), phiToLoad);
                yield (v != ret.value())
                        ? new IRInstruction.Return(v, ret.location())
                        : ret;
            }
            case IRInstruction.Cast cast -> {
                var s = replaceIfPhi(cast.source(), phiToLoad);
                yield (s != cast.source())
                        ? new IRInstruction.Cast(cast.result(), s, cast.targetType(), cast.location())
                        : cast;
            }
            case IRInstruction.Move move -> {
                var v = replaceIfPhi(move.value(), phiToLoad);
                yield (v != move.value())
                        ? new IRInstruction.Move(move.result(), v, move.location())
                        : move;
            }
            case IRInstruction.ArrayLoad al -> {
                var a = replaceIfPhi(al.array(), phiToLoad);
                var i = replaceIfPhi(al.index(), phiToLoad);
                yield (a != al.array() || i != al.index())
                        ? new IRInstruction.ArrayLoad(al.result(), a, i, al.elemType(), al.location())
                        : al;
            }
            case IRInstruction.ArrayLength al -> {
                var a = replaceIfPhi(al.array(), phiToLoad);
                yield (a != al.array())
                        ? new IRInstruction.ArrayLength(al.result(), a, al.location())
                        : al;
            }
            case IRInstruction.MonitorEnter me -> {
                var o = replaceIfPhi(me.object(), phiToLoad);
                yield (o != me.object())
                        ? new IRInstruction.MonitorEnter(o, me.location())
                        : me;
            }
            case IRInstruction.MonitorExit mx -> {
                var o = replaceIfPhi(mx.object(), phiToLoad);
                yield (o != mx.object())
                        ? new IRInstruction.MonitorExit(o, mx.location())
                        : mx;
            }
            default -> instr;
        };
    }

    private static List<IRValue> replaceArgs(final List<IRValue> args,
                                             final Map<String, IRValue> phiToLoad) {
        List<IRValue> newArgs = null;
        for (int i = 0; i < args.size(); i++) {
            final var replaced = replaceIfPhi(args.get(i), phiToLoad);
            if (replaced != args.get(i)) {
                if (newArgs == null) newArgs = new ArrayList<>(args);
                newArgs.set(i, replaced);
            }
        }
        return newArgs != null ? newArgs : args;
    }

    private static IRValue replaceIfPhi(final IRValue value,
                                         final Map<String, IRValue> phiToLoad) {
        if (value == null) return null;
        if (!(value instanceof IRValue.Temp || value instanceof IRValue.Named)) return value;
        final var name = IRValue.nameOf(value);
        final var replacement = phiToLoad.get(name);
        return replacement != null ? replacement : value;
    }

    private record PhiReplacement(
            IRInstruction.Phi phi,
            String blockLabel,
            IRValue.Temp temp,
            IRValue.Temp load,
            SourceLocation location
    ) {}
}
