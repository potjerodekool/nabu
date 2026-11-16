package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;

import java.util.*;

/**
 * Scalar replacement (conservatief): voor niet-ontsnappende allocs die volledig in
 * één basic block gebruikt worden, worden veld-lezingen en -schrijvingen vervangen
 * door lokale temps en de alloc wordt verwijderd. Opt-in via -Dnabu.scalarReplace=true.
 */
public class ScalarReplacement {

    public static boolean enabled() {
        return "true".equalsIgnoreCase(System.getProperty("nabu.scalarReplace", "false"));
    }

    public static IRModule run(final IRModule module, final Map<String, IRFunction> callees) {
        if (!enabled()) return module;
        final var newFunctions = module.functions().stream()
                .map(f -> runFunction(f, callees))
                .toList();
        return module.withFunctions(newFunctions);
    }

    private static IRFunction runFunction(final IRFunction f, final Map<String, IRFunction> callees) {
        if (f.isExternal()) return f;
        final EscapeAnalysis.Result esc = EscapeAnalysis.analyze(f, callees);
        if (esc.nonEscaping.isEmpty()) return f;

        boolean anyChanged = false;
        final List<IRBasicBlock> newBlocks = new ArrayList<>();
        for (final IRBasicBlock b : f.blocks()) {
            final TransformResult tr = transformBlock(b, esc.nonEscaping);
            newBlocks.add(tr.block);
            anyChanged |= tr.changed;
        }
        if (!anyChanged) return f;
        return f.withBlocks(newBlocks);
    }

    private static class TransformResult {
        final IRBasicBlock block;
        final boolean changed;
        TransformResult(IRBasicBlock b, boolean c) { block = b; changed = c; }
    }

    private static TransformResult transformBlock(final IRBasicBlock block, final Set<String> nonEscaping) {
        // Verzamel allocs in dit blok die niet ontsnappen.
        final Map<String, IRInstruction> allocInstrs = new LinkedHashMap<>();
        for (final IRInstruction in : block.instructions()) {
            if (in instanceof IRInstruction.HeapAlloc ha) {
                final String n = nameOf(ha.result());
                if (n != null && nonEscaping.contains(n)) allocInstrs.put(n, in);
            } else if (in instanceof IRInstruction.StackAlloc sa) {
                final String n = nameOf(sa.result());
                if (n != null && nonEscaping.contains(n)) allocInstrs.put(n, in);
            }
        }
        if (allocInstrs.isEmpty()) return new TransformResult(block, false);

        // Voor elke alloc: verzamel veld-toegangen in volgorde.
        // Map: allocTemp -> list of (instruction index, field name, isStore, value/result)
        // We werken op een kopie van de instructies.
        final List<IRInstruction> instrs = new ArrayList<>(block.instructions());
        boolean changed = false;

        for (final String allocName : allocInstrs.keySet()) {
            // Vind alle veld-toegangen op deze alloc in dit blok.
            // Eerst checken: alle uses van allocName moeten veld-toegangen in dit blok zijn.
            boolean safe = true;
            final List<Integer> accessIndices = new ArrayList<>();
            for (int i = 0; i < instrs.size(); i++) {
                final IRInstruction in = instrs.get(i);
                if (usesAlloc(in, allocName)) {
                    if (!isFieldAccessOn(in, allocName)) { safe = false; break; }
                    accessIndices.add(i);
                }
            }
            if (!safe || accessIndices.isEmpty()) continue;

            // Distinct veldnamen.
            final Set<String> fields = new LinkedHashSet<>();
            for (int i : accessIndices) {
                final String fn = fieldName(instrs.get(i));
                if (fn != null) fields.add(fn);
            }

            // Track huidige temp per veld.
            final Map<String, String> currentTemp = new HashMap<>();
            final Map<String, IRType> fieldType = new HashMap<>();
            for (final String fn : fields) {
                // vind type uit eerste toegang
                for (int i : accessIndices) {
                    final String f = fieldName(instrs.get(i));
                    if (fn.equals(f)) {
                        final IRInstruction in = instrs.get(i);
                        IRType t = fieldTypeOf(in);
                        if (t != null) { fieldType.put(fn, t); break; }
                    }
                }
            }

            // Vervang toegangen.
            // We verwerken in volgorde; voor elke store: Move(freshTemp, value), update current.
            // Voor elke load: als current temp bestaat, vervang load door Move(result, currentTemp).
            // Als geen current temp (read before write), skip transformatie voor deze alloc.
            int fresh = 0;
            boolean abort = false;
            // Map van instruction index -> replacement (null = remove)
            final Map<Integer, IRInstruction> replacements = new HashMap<>();
            final Set<Integer> toRemove = new HashSet<>();

            for (int i : accessIndices) {
                final IRInstruction in = instrs.get(i);
                final String fn = fieldName(in);
                if (fn == null) { abort = true; break; }
                final IRType ft = fieldType.get(fn);
                if (ft == null) { abort = true; break; }

                if (in instanceof IRInstruction.Store) {
                    // Store(alloc.field, value) -> Move(freshTemp, value)
                    final IRValue value = ((IRInstruction.Store) in).value();
                    final String newTemp = "sr" + (fresh++) + "_" + fn;
                    final IRValue temp = new IRValue.Temp(newTemp, ft);
                    replacements.put(i, new IRInstruction.Move(temp, value, in.location()));
                    currentTemp.put(fn, newTemp);
                } else if (in instanceof IRInstruction.Load) {
                    final String ct = currentTemp.get(fn);
                    if (ct == null) { abort = true; break; } // read before write
                    final IRValue result = ((IRInstruction.Load) in).result();
                    final IRValue temp = new IRValue.Temp(ct, ft);
                    replacements.put(i, new IRInstruction.Move(result, temp, in.location()));
                } else {
                    abort = true; break;
                }
            }

            if (abort) continue;

            // Pas replacements toe.
            for (int i = 0; i < instrs.size(); i++) {
                if (replacements.containsKey(i)) {
                    instrs.set(i, replacements.get(i));
                }
            }
            // Verwijder de alloc-instructie.
            for (int i = 0; i < instrs.size(); i++) {
                final IRInstruction in = instrs.get(i);
                if (in == allocInstrs.get(allocName)) {
                    instrs.remove(i);
                    // adjust accessIndices? already applied
                    break;
                }
            }
            changed = true;
        }

        final IRBasicBlock newBlock = new IRBasicBlock(block.label(), instrs);
        return new TransformResult(newBlock, changed);
    }

    private static boolean usesAlloc(final IRInstruction in, final String allocName) {
        // check if allocName appears as a temp in the instruction
        final List<IRValue> vals = readsAndPtr(in);
        for (final IRValue v : vals) {
            if (v instanceof IRValue.Temp t && t.name().equals(allocName)) return true;
            if (v instanceof IRValue.Values vs) {
                for (final IRValue inner : vs.values()) {
                    if (inner instanceof IRValue.Temp t && t.name().equals(allocName)) return true;
                }
            }
        }
        // result is the alloc def, not a use
        return false;
    }

    private static boolean isFieldAccessOn(final IRInstruction in, final String allocName) {
        if (in instanceof IRInstruction.Load ld) {
            return isFieldPtrOn(ld.ptr(), allocName);
        }
        if (in instanceof IRInstruction.Store st) {
            return isFieldPtrOn(st.ptr(), allocName);
        }
        return false;
    }

    private static boolean isFieldPtrOn(final IRValue ptr, final String allocName) {
        if (!(ptr instanceof IRValue.Values vs)) return false;
        if (vs.values().isEmpty()) return false;
        final IRValue first = vs.values().get(0);
        if (!(first instanceof IRValue.Temp t) || !t.name().equals(allocName)) return false;
        // last must be Named field
        final IRValue last = vs.values().getLast();
        return last instanceof IRValue.Named;
    }

    private static String fieldName(final IRInstruction in) {
        IRValue ptr = null;
        if (in instanceof IRInstruction.Load ld) ptr = ld.ptr();
        else if (in instanceof IRInstruction.Store st) ptr = st.ptr();
        if (!(ptr instanceof IRValue.Values vs) || vs.values().isEmpty()) return null;
        final IRValue last = vs.values().getLast();
        return (last instanceof IRValue.Named n) ? n.name() : null;
    }

    private static IRType fieldTypeOf(final IRInstruction in) {
        if (in instanceof IRInstruction.Load ld) return ld.result().type();
        if (in instanceof IRInstruction.Store st) return st.value().type();
        return null;
    }

    private static List<IRValue> readsAndPtr(final IRInstruction in) {
        final List<IRValue> out = new ArrayList<>();
        if (in instanceof IRInstruction.Load ld) { out.add(ld.ptr()); out.add(ld.result()); }
        else if (in instanceof IRInstruction.Store st) { out.add(st.ptr()); out.add(st.value()); }
        else if (in instanceof IRInstruction.Call c) { out.addAll(c.args()); if (c.result() != null) out.add(c.result()); }
        else if (in instanceof IRInstruction.BinaryOp b) { out.add(b.left()); out.add(b.right()); if (b.result() != null) out.add(b.result()); }
        else if (in instanceof IRInstruction.Move m) { out.add(m.value()); if (m.result() != null) out.add(m.result()); }
        else if (in instanceof IRInstruction.Return r) { if (r.value() != null) out.add(r.value()); }
        else if (in instanceof IRInstruction.Phi p) { for (var inc : p.incomingValues()) out.add(inc.value()); if (p.result() != null) out.add(p.result()); }
        return out;
    }

    private static String nameOf(final IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}