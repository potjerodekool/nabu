package io.github.potjerodekool.nabu.compiler.backend.native_llvm;

import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which functions need a safepoint poll at their entry.
 *
 * <p>A loop polls on every jump back, a wait polls inside the runtime. The poll at a function's entry exists for
 * the one remaining way to run long without either: a long chain of calls, which needs a cycle in the call
 * graph. A function that cannot be on such a cycle does not need it, and in a hot loop that calls small helpers
 * the extra poll is a load, a test and a branch per call that LLVM cannot merge (the load is atomic).</p>
 *
 * <p>A function polls when it is on a cycle of direct calls (itself included), or when it makes a call whose
 * target is not known here (virtual, interface, indirect), which can close a cycle. Calls to functions that are
 * not part of the program (the C runtime) are leaves.</p>
 */
final class SafepointPlan {

    private SafepointPlan() {
    }

    /** The names of the functions that poll at their entry, or null when every function that calls does. */
    static Set<String> entryPolls(final List<IRModule> modules) {
        if (Boolean.getBoolean("nabu.safepointEveryCall")) {
            return null;
        }
        final Map<String, IRFunction> defined = new HashMap<>();
        for (final IRModule module : modules) {
            for (final IRFunction fn : module.functions()) {
                if (!fn.isExternal()) {
                    defined.put(fn.name, fn);
                }
            }
        }
        final Set<String> polls = new HashSet<>();
        final Map<String, List<String>> edges = new HashMap<>();
        for (final IRFunction fn : defined.values()) {
            final List<String> out = new ArrayList<>();
            boolean unknown = false;
            for (final IRBasicBlock block : fn.blocks()) {
                for (final IRInstruction in : block.instructions()) {
                    if (in instanceof IRInstruction.IndirectCall) {
                        unknown = true;
                    } else if (in instanceof IRInstruction.Call c) {
                        if (c.callKind() == CallKind.VIRTUAL || c.callKind() == CallKind.INTERFACE) {
                            unknown = true;
                        } else {
                            final String target = resolve(c.function(), defined);
                            if (target != null) {
                                out.add(target);
                            } else if (c.function().indexOf('.') >= 0) {
                                // looks like a Java symbol, but is not one we know: it may call back
                                unknown = true;
                            }
                        }
                    }
                }
            }
            edges.put(fn.name, out);
            if (unknown) {
                polls.add(fn.name);
            }
        }
        markCycles(edges, polls);
        return polls;
    }

    /**
     * The function a call names. A source class has a short owner in its function names ({@code Main_fib}), while
     * a call may carry the package ({@code t53.Main_fib}); the backend finds the function either way.
     */
    private static String resolve(final String name, final Map<String, IRFunction> defined) {
        if (defined.containsKey(name)) {
            return name;
        }
        final int dot = name.lastIndexOf('.');
        if (dot >= 0 && defined.containsKey(name.substring(dot + 1))) {
            return name.substring(dot + 1);
        }
        return null;
    }

    /** Tarjan's strongly connected components, iteratively (the call graph of a JDK closure is deep). */
    private static void markCycles(final Map<String, List<String>> edges, final Set<String> polls) {
        final Map<String, Integer> index = new HashMap<>();
        final Map<String, Integer> low = new HashMap<>();
        final Set<String> onStack = new HashSet<>();
        final Deque<String> stack = new ArrayDeque<>();
        int counter = 0;
        for (final String start : edges.keySet()) {
            if (index.containsKey(start)) {
                continue;
            }
            final Deque<String> work = new ArrayDeque<>();
            final Deque<Integer> next = new ArrayDeque<>();
            work.push(start);
            next.push(0);
            index.put(start, counter);
            low.put(start, counter);
            counter++;
            stack.push(start);
            onStack.add(start);
            while (!work.isEmpty()) {
                final String v = work.peek();
                final int i = next.pop();
                final List<String> out = edges.get(v);
                if (i < out.size()) {
                    next.push(i + 1);
                    final String w = out.get(i);
                    if (w.equals(v)) {
                        polls.add(v);
                    }
                    if (!index.containsKey(w)) {
                        index.put(w, counter);
                        low.put(w, counter);
                        counter++;
                        stack.push(w);
                        onStack.add(w);
                        work.push(w);
                        next.push(0);
                    } else if (onStack.contains(w)) {
                        low.put(v, Math.min(low.get(v), index.get(w)));
                    }
                } else {
                    work.pop();
                    if (low.get(v).equals(index.get(v))) {
                        final List<String> component = new ArrayList<>();
                        String w;
                        do {
                            w = stack.pop();
                            onStack.remove(w);
                            component.add(w);
                        } while (!w.equals(v));
                        if (component.size() > 1) {
                            polls.addAll(component);
                        }
                    }
                    if (!work.isEmpty()) {
                        final String parent = work.peek();
                        low.put(parent, Math.min(low.get(parent), low.get(v)));
                    }
                }
            }
        }
    }
}
