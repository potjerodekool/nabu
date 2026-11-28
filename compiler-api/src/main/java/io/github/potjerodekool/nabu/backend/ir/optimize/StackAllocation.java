package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Converteert niet-ontsnappende HeapAllocs naar StackAllocs, zodat de backend ze
 * als alloca (stack) neerzet i.p.v. GC_malloc (heap). Opt-in via -Dnabu.stackAlloc=true.
 */
public class StackAllocation {

    private static int counter;
    private static final int[] RANGE = parseRange(System.getProperty("nabu.stackAlloc.range"));

    private static int[] parseRange(final String text) {
        if (text == null) return null;
        final String[] p = text.split("-");
        return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
    }

    /** Bisecting a miscompilation: -Dnabu.stackAlloc.range=lo-hi converts only the allocations lo..hi-1, in order. */
    private static boolean inRange() {
        final int i = counter++;
        return RANGE == null || (i >= RANGE[0] && i < RANGE[1]);
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("nabu.stackAlloc", "true"));
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
        final String dump = System.getProperty("nabu.dumpIR");
        if (dump != null && java.util.Arrays.stream(dump.split(",")).anyMatch(f.name::startsWith)) {
            System.err.println("[nabu/IR] DUMPFN " + f.name + " params=" + f.params);
            for (final IRBasicBlock bb : f.blocks()) {
                System.err.println("[nabu/IR] DUMPFN  block " + bb.label());
                for (final IRInstruction ii : bb.instructions()) System.err.println("[nabu/IR] DUMPFN    " + ii);
            }
        }
        final EscapeAnalysis.Result esc = EscapeAnalysis.analyze(f, callees);
        if (esc.nonEscaping.isEmpty()) return f;

        boolean changed = false;
        final List<IRBasicBlock> newBlocks = new ArrayList<>();
        for (final IRBasicBlock b : f.blocks()) {
            final List<IRInstruction> newInstrs = new ArrayList<>();
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.HeapAlloc ha) {
                    final String n = nameOf(ha.result());
                    if (n != null && esc.nonEscaping.contains(n) && inRange()) {
                        if (Boolean.getBoolean("nabu.stackAllocList")) {
                            System.err.println("[nabu/IR] STACKALLOC " + f.name + " " + n + " "
                                    + (ha.allocType() instanceof io.github.potjerodekool.nabu.backend.ir.types.IRType.Ptr p
                                    ? p.jvmDescriptor() : ha.allocType()));
                        }
                        newInstrs.add(new IRInstruction.StackAlloc(ha.result(), ha.allocType(), ha.location()));
                        changed = true;
                        continue;
                    }
                }
                newInstrs.add(in);
            }
            newBlocks.add(new IRBasicBlock(b.label(), newInstrs));
        }
        if (!changed) return f;
        return f.withBlocks(newBlocks);
    }

    private static String nameOf(final IRValue v) {
        if (v instanceof IRValue.Temp t) return t.name();
        if (v instanceof IRValue.Named n) return n.name();
        return null;
    }
}