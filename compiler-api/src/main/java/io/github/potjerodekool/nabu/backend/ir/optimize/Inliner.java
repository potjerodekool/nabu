package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;

import java.util.*;

/**
 * Minimale, conservatieve inliner: inlint alleen callees die uit één blok bestaan,
 * geen control flow, geen calls, geen stores/throws/monitors/allocas en eindigen
 * in een Return. Alleen directe calls (IRInstruction.Call) naar een callee in
 * dezelfde module. Temp-namen worden hernoemd om botsingen te voorkomen.
 */
public class Inliner implements OptimizationPass {

    private final Map<String, IRFunction> callees;
    private final Map<String, Integer> callCounts;
    /** Callee -> set of callees it calls (voor recursie-detectie). */
    private final Map<String, Set<String>> callGraph = new HashMap<>();
    private int freshCounter = 0;

    // Drempels (instructies per callee-blok).
    private static final int THRESHOLD_SINGLE_USE = 80;
    private static final int THRESHOLD_MULTI_USE  = 15;

    public Inliner(final Map<String, IRFunction> callees, final Map<String, Integer> callCounts) {
        this.callees = callees;
        this.callCounts = callCounts;
        // Bouw call-graph voor recursie-detectie.
        for (final Map.Entry<String, IRFunction> e : callees.entrySet()) {
            final Set<String> cs = new HashSet<>();
            for (final IRBasicBlock b : e.getValue().blocks()) {
                for (final IRInstruction in : b.instructions()) {
                    if (in instanceof IRInstruction.Call c && callees.containsKey(c.function())) {
                        cs.add(c.function());
                    }
                }
            }
            callGraph.put(e.getKey(), cs);
        }
    }

    @Override
    public boolean run(final IRFunction caller) {
        // Conservatief: niet inlinen in functies met try/catch, omdat inlining
        // de invoke/landing-pad-grenzen en exception-semantiek kan veranderen.
        for (final IRBasicBlock b : caller.blocks()) {
            for (final IRInstruction in : b.instructions()) {
                if (in instanceof IRInstruction.TryCatchRegion) return false;
            }
        }
        boolean changedOverall = false;
        // Herhaal tot geen call meer inlinbaar is (inlining kan nieuwe kansen openen).
        for (int iter = 0; iter < 5; iter++) {
            boolean changed = inlineOnce(caller);
            if (!changed) break;
            changedOverall = true;
        }
        return changedOverall;
    }

    private boolean inlineOnce(final IRFunction caller) {
        final Set<String> loopBlocks = computeLoopBlocks(caller);
        for (final IRBasicBlock block : new ArrayList<>(caller.blocks())) {
            final boolean inLoop = loopBlocks.contains(block.label());
            final List<IRInstruction> instrs = new ArrayList<>(block.instructions());
            for (int i = 0; i < instrs.size(); i++) {
                final IRInstruction instr = instrs.get(i);
                if (!(instr instanceof IRInstruction.Call call)) continue;
                final IRFunction callee = callees.get(call.function());
                if (callee == null || callee == caller) continue;
                if (call.returnType() != null && !call.returnType().equals(callee.returnType)) continue;
                // Recursie-detectie: inline niet als callee (transitief) caller kan bereiken.
                if (wouldRecurse(call.function(), caller.name)) continue;
                if (!eligible(callee, inLoop)) continue;
                if (!argsSimple(call.args())) continue;

                // Inline.
                if (doInline(caller, block, i, call, callee)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean eligible(final IRFunction callee, final boolean inLoop) {
        if (callee.isExternal()) return false;
        final List<IRBasicBlock> blocks = callee.blocks();
        if (blocks.isEmpty()) return false;
        if (blocks.size() > 10) return false; // multi-blok: beperkt tot kleine callees
        // Tel Returns: precies één.
        int returns = 0;
        for (final IRBasicBlock b : blocks) {
            if (b.instructions().isEmpty()) return false;
            final IRInstruction term = b.getTerminator();
            if (term instanceof IRInstruction.Return) returns++;
            else if (term instanceof IRInstruction.Branch || term instanceof IRInstruction.CondBranch) {
                // ok
            } else {
                return false; // onbekende terminator
            }
            for (final IRInstruction in : b.instructions()) {
                if (in == term) continue;
                if (!isSafe(in)) return false;
            }
        }
        if (returns != 1) return false;

        // Grootte-drempel over alle blokken. In een loop inlinen we agressiever.
        int totalSize = 0;
        for (final IRBasicBlock b : blocks) totalSize += b.instructions().size();
        final int uses = callCounts.getOrDefault(callee.name, 1);
        int threshold = (uses <= 1) ? (THRESHOLD_SINGLE_USE * 2) : THRESHOLD_MULTI_USE;
        if (inLoop) threshold *= 2;
        if (totalSize > threshold) return false;
        return true;
    }

    private boolean isSafe(final IRInstruction in) {
        return in instanceof IRInstruction.BinaryOp
                || in instanceof IRInstruction.Move
                || in instanceof IRInstruction.Cast
                || in instanceof IRInstruction.InstanceOf
                || in instanceof IRInstruction.Load
                || in instanceof IRInstruction.ArrayLength;
    }

    private boolean argsSimple(final List<IRValue> args) {
        for (final IRValue a : args) {
            if (a instanceof IRValue.Temp || a instanceof IRValue.Named) continue;
            if (a.getClass().getSimpleName().startsWith("Const")) continue;
            return false;
        }
        return true;
    }

    private boolean doInline(final IRFunction caller, final IRBasicBlock block,
                             final int callIndex, final IRInstruction.Call call,
                             final IRFunction callee) {
        // Param-binding.
        final Map<String, IRValue> paramSubst = new HashMap<>();
        for (int pi = 0; pi < callee.params.size() && pi < call.args().size(); pi++) {
            final IRValue param = callee.params.get(pi);
            final String pname = safeName(param);
            if (pname != null) paramSubst.put(pname, call.args().get(pi));
        }

        // Temp rename map over alle callee-blokken.
        final Map<String, String> tempRename = new HashMap<>();
        for (final IRBasicBlock cb : callee.blocks()) {
            for (final IRInstruction in : cb.instructions()) {
                collectTemps(in, tempRename);
            }
        }
        final Map<String, String> finalRename = new HashMap<>();
        for (final String t : tempRename.keySet()) {
            finalRename.put(t, "inl" + (freshCounter++) + "_" + t);
        }

        // Label rename map.
        final String prefix = "inl" + freshCounter + "_";
        freshCounter++;
        final Map<String, String> labelRename = new HashMap<>();
        for (final IRBasicBlock cb : callee.blocks()) {
            labelRename.put(cb.label(), prefix + cb.label());
        }

        // Vind de enige Return.
        IRBasicBlock retBlock = null;
        for (final IRBasicBlock cb : callee.blocks()) {
            if (cb.getTerminator() instanceof IRInstruction.Return) { retBlock = cb; break; }
        }
        if (retBlock == null) return false;

        // after-label voor suffix.
        final String afterLabel = prefix + "after";
        final String entryLabel = labelRename.get(callee.blocks().get(0).label());

        // Clone callee blocks (instructies) met rename.
        final List<IRBasicBlock> clonedBlocks = new ArrayList<>();
        for (final IRBasicBlock cb : callee.blocks()) {
            final String newLabel = labelRename.get(cb.label());
            final List<IRInstruction> newInstrs = new ArrayList<>();
            final IRInstruction term = cb.getTerminator();
            for (final IRInstruction in : cb.instructions()) {
                if (in == term) continue;
                final IRInstruction cl = cloneInstr(in, finalRename, paramSubst);
                if (cl == null) return false;
                newInstrs.add(cl);
            }
            // terminator
            if (term instanceof IRInstruction.Return ret) {
                if (ret.value() != null && call.result() != null) {
                    final IRValue rv = substitute(ret.value(), finalRename, paramSubst);
                    newInstrs.add(new IRInstruction.Move(call.result(), rv, call.location()));
                }
                newInstrs.add(new IRInstruction.Branch(afterLabel, call.location()));
            } else if (term instanceof IRInstruction.Branch br) {
                newInstrs.add(new IRInstruction.Branch(labelRename.getOrDefault(br.targetLabel(), br.targetLabel()), br.location()));
            } else if (term instanceof IRInstruction.CondBranch cb2) {
                newInstrs.add(new IRInstruction.CondBranch(
                        substitute(cb2.condition(), finalRename, paramSubst),
                        labelRename.getOrDefault(cb2.trueLabel(), cb2.trueLabel()),
                        labelRename.getOrDefault(cb2.falseLabel(), cb2.falseLabel()),
                        cb2.location()));
            } else {
                return false;
            }
            clonedBlocks.add(new IRBasicBlock(newLabel, newInstrs));
        }

        // Split caller block: prefix (0..callIndex-1) + Branch to entryLabel.
        final List<IRInstruction> allInstrs = new ArrayList<>(block.instructions());
        final List<IRInstruction> prefixInstrs = new ArrayList<>(allInstrs.subList(0, callIndex));
        final List<IRInstruction> suffix = new ArrayList<>(allInstrs.subList(callIndex + 1, allInstrs.size()));
        // remove call already excluded (callIndex skipped via subList)
        // prefix terminator
        prefixInstrs.add(new IRInstruction.Branch(entryLabel, call.location()));

        // Mutate current block to prefix.
        for (IRInstruction x : new ArrayList<>(block.instructions())) block.removeInstruction(x);
        for (IRInstruction x : prefixInstrs) block.add(x);

        // Append cloned callee blocks.
        for (IRBasicBlock cb : clonedBlocks) {
            caller.addBlock(cb);
        }

        // Append after-block with suffix + original terminator handling.
        // The original block's terminator was the last element of allInstrs; it's in suffix? 
        // suffix = instructions after call, INCLUDING the original terminator (since subList(callIndex+1, size)).
        // So suffix already contains terminator. Good.
        final IRBasicBlock afterBlock = new IRBasicBlock(afterLabel, suffix);
        caller.addBlock(afterBlock);

        return true;
    }

    /** True als callee (transitief) caller kan bereiken -> inlining zou recursie creëren. */
    private boolean wouldRecurse(final String calleeName, final String callerName) {
        final Set<String> visited = new HashSet<>();
        final Deque<String> stack = new ArrayDeque<>();
        stack.push(calleeName);
        while (!stack.isEmpty()) {
            final String cur = stack.pop();
            if (cur.equals(callerName)) return true;
            if (!visited.add(cur)) continue;
            final Set<String> cs = callGraph.get(cur);
            if (cs != null) stack.addAll(cs);
        }
        return false;
    }

    /** Blokken die in een lus liggen (niet-triviale SCC of self-loop). */
    private Set<String> computeLoopBlocks(final IRFunction f) {
        final Set<String> result = new HashSet<>();
        final List<IRBasicBlock> blocks = f.blocks();
        if (blocks.isEmpty()) return result;
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) index.put(blocks.get(i).label(), i);

        // Successors per blok.
        final Map<String, List<String>> succ = new HashMap<>();
        for (final IRBasicBlock b : blocks) {
            final List<String> ss = new ArrayList<>();
            final IRInstruction t = b.getTerminator();
            if (t instanceof IRInstruction.Branch br) {
                ss.add(br.targetLabel());
            } else if (t instanceof IRInstruction.CondBranch cb) {
                ss.add(cb.trueLabel());
                ss.add(cb.falseLabel());
            }
            succ.put(b.label(), ss);
        }

        // Tarjan SCC.
        final int[] indices = new int[blocks.size()];
        final int[] low = new int[blocks.size()];
        final boolean[] onStack = new boolean[blocks.size()];
        final Deque<Integer> stack = new ArrayDeque<>();
        int[] counter = {0};
        final List<List<Integer>> sccs = new ArrayList<>();

        // iterative DFS to avoid recursion depth issues
        // Use recursive (functions are small).
        // Map label -> block index.
        final Map<String, Integer> labelToIdx = new HashMap<>();
        for (int i = 0; i < blocks.size(); i++) labelToIdx.put(blocks.get(i).label(), i);

        // Recursive tarjan
        // We'll implement recursively with a helper.
        // Use arrays indexed by block index.
        // Need adjacency in indices.
        final List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            final List<Integer> a = new ArrayList<>();
            for (final String s : succ.getOrDefault(blocks.get(i).label(), List.of())) {
                final Integer j = labelToIdx.get(s);
                if (j != null) a.add(j);
            }
            adj.add(a);
        }

        // recursive tarjan: start from every unvisited node.
        final boolean[] visited = new boolean[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            if (!visited[i]) tarjan(i, adj, indices, low, onStack, stack, counter, sccs, visited, blocks.size());
        }

        for (final List<Integer> scc : sccs) {
            if (scc.size() > 1) {
                for (final int idx : scc) result.add(blocks.get(idx).label());
            } else if (scc.size() == 1) {
                final int idx = scc.get(0);
                // self-loop?
                if (adj.get(idx).contains(idx)) result.add(blocks.get(idx).label());
            }
        }
        return result;
    }

    private void tarjan(final int v, final List<List<Integer>> adj, final int[] indices,
                        final int[] low, final boolean[] onStack, final Deque<Integer> stack,
                        final int[] counter, final List<List<Integer>> sccs,
                        final boolean[] visited, final int n) {
        indices[v] = low[v] = ++counter[0];
        stack.push(v);
        onStack[v] = true;
        visited[v] = true;
        for (final int w : adj.get(v)) {
            if (!visited[w]) {
                tarjan(w, adj, indices, low, onStack, stack, counter, sccs, visited, n);
                low[v] = Math.min(low[v], low[w]);
            } else if (onStack[w]) {
                low[v] = Math.min(low[v], indices[w]);
            }
        }
        if (low[v] == indices[v]) {
            final List<Integer> scc = new ArrayList<>();
            int w;
            do {
                w = stack.pop();
                onStack[w] = false;
                scc.add(w);
            } while (w != v);
            sccs.add(scc);
        }
    }

    private String safeName(final IRValue v) {
        try { return IRValue.nameOf(v); } catch (IllegalArgumentException e) { return null; }
    }

    private void collectTemps(final IRInstruction in, final Map<String, String> out) {
        // result
        final IRValue r = safeResult(in);
        if (r != null) {
            try { out.put(IRValue.nameOf(r), IRValue.nameOf(r)); } catch (IllegalArgumentException ignored) {}
        }
        // operands
        collectTempsFromInstr(in, out);
    }

    private IRValue safeResult(final IRInstruction in) {
        try { return in.result(); } catch (Exception e) { return null; }
    }

    private void collectTempsFromInstr(final IRInstruction in, final Map<String, String> out) {
        // walk all IRValue fields via reflection on records
        if (in instanceof IRInstruction.BinaryOp b) {
            collectValue(b.left(), out); collectValue(b.right(), out);
        } else if (in instanceof IRInstruction.Move m) {
            collectValue(m.value(), out);
        } else if (in instanceof IRInstruction.Cast c) {
            collectValue(c.source(), out);
        } else if (in instanceof IRInstruction.InstanceOf io) {
            collectValue(io.source(), out);
        } else if (in instanceof IRInstruction.Load ld) {
            collectValue(ld.ptr(), out);
        } else if (in instanceof IRInstruction.ArrayLength al) {
            collectValue(al.array(), out);
        } else if (in instanceof IRInstruction.ArrayLoad al) {
            collectValue(al.array(), out); collectValue(al.index(), out);
        } else if (in instanceof IRInstruction.Return ret) {
            if (ret.value() != null) collectValue(ret.value(), out);
        }
    }

    private void collectValue(final IRValue v, final Map<String, String> out) {
        if (v == null) return;
        if (v instanceof IRValue.Temp t) out.put(t.name(), t.name());
        else if (v instanceof IRValue.Values vals) {
            for (final IRValue inner : vals.values()) collectValue(inner, out);
        }
    }

    private IRInstruction cloneInstr(final IRInstruction in, final Map<String, String> tempRename,
                                    final Map<String, IRValue> paramSubst) {
        if (in instanceof IRInstruction.BinaryOp b) {
            return new IRInstruction.BinaryOp(
                    substitute(b.result(), tempRename, paramSubst),
                    b.op(),
                    substitute(b.left(), tempRename, paramSubst),
                    substitute(b.right(), tempRename, paramSubst),
                    b.location());
        }
        if (in instanceof IRInstruction.Move m) {
            return new IRInstruction.Move(
                    substitute(m.result(), tempRename, paramSubst),
                    substitute(m.value(), tempRename, paramSubst),
                    m.location());
        }
        if (in instanceof IRInstruction.Cast c) {
            return new IRInstruction.Cast(
                    substitute(c.result(), tempRename, paramSubst),
                    substitute(c.source(), tempRename, paramSubst),
                    c.targetType(),
                    c.location());
        }
        if (in instanceof IRInstruction.InstanceOf io) {
            return new IRInstruction.InstanceOf(
                    substitute(io.result(), tempRename, paramSubst),
                    substitute(io.source(), tempRename, paramSubst),
                    io.type(),
                    io.location());
        }
        if (in instanceof IRInstruction.Load ld) {
            return new IRInstruction.Load(
                    substitute(ld.result(), tempRename, paramSubst),
                    ld.type(),
                    substitute(ld.ptr(), tempRename, paramSubst),
                    ld.location());
        }
        if (in instanceof IRInstruction.ArrayLength al) {
            return new IRInstruction.ArrayLength(
                    substitute(al.result(), tempRename, paramSubst),
                    substitute(al.array(), tempRename, paramSubst),
                    al.location());
        }
        if (in instanceof IRInstruction.ArrayLoad al) {
            return new IRInstruction.ArrayLoad(
                    substitute(al.result(), tempRename, paramSubst),
                    substitute(al.array(), tempRename, paramSubst),
                    substitute(al.index(), tempRename, paramSubst),
                    al.elemType(),
                    al.location());
        }
        return null;
    }

    private IRValue substitute(final IRValue v, final Map<String, String> tempRename,
                              final Map<String, IRValue> paramSubst) {
        if (v instanceof IRValue.Temp t) {
            final String mapped = paramSubst.containsKey(t.name()) ? null : tempRename.get(t.name());
            if (paramSubst.containsKey(t.name())) {
                return paramSubst.get(t.name());
            }
            if (mapped != null) {
                return new IRValue.Temp(mapped, t.type());
            }
            return v;
        }
        if (v instanceof IRValue.ConstInt c) return c;
        if (v instanceof IRValue.ConstBool b) return b;
        if (v instanceof IRValue.Named n) {
            if (paramSubst.containsKey(n.name())) return paramSubst.get(n.name());
            return n;
        }
        return v;
    }

    // IRValue.nameOf helper
    static {
    }
}