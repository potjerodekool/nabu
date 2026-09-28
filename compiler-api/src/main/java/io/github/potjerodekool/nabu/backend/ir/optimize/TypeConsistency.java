package io.github.potjerodekool.nabu.backend.ir.optimize;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.debug.SourceLocation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * SSA Type Consistency Pass
 *
 * Detects temps that are used with different types in different control-flow paths
 * and splits them into separate temps with phi-nodes at merge points.
 */
public final class TypeConsistency {

    private TypeConsistency() {}

    public static IRFunction run(final IRFunction function) {
        System.out.println("[TypeConsistency] Running on " + function.name);

        // Step 1: Collect all temp types per block
        final Map<IRBasicBlock, Map<String, Set<IRType>>> blockTempTypes = new HashMap<>();
        for (final var block : function.blocks()) {
            final Map<String, Set<IRType>> tempTypes = new HashMap<>();
            for (final var instr : block.instructions()) {
                collectTempTypes(instr, tempTypes);
            }
            blockTempTypes.put(block, tempTypes);
        }

        // Step 2: Find temps with conflicting types across blocks
        final Map<String, Set<IRType>> globalTempTypes = new HashMap<>();
        for (final var entry : blockTempTypes.entrySet()) {
            for (final var tempEntry : entry.getValue().entrySet()) {
                globalTempTypes.computeIfAbsent(tempEntry.getKey(), k -> new HashSet<>()).addAll(tempEntry.getValue());
            }
        }

        final Set<String> conflictedTemps = globalTempTypes.entrySet().stream()
                .filter(e -> e.getValue().stream().filter(t -> !(t instanceof IRType.Void)).collect(Collectors.toSet()).size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        if (conflictedTemps.isEmpty()) {
            System.out.println("[TypeConsistency] No conflicts in " + function.name);
            return function;
        }

        System.out.println("[TypeConsistency] Conflicted temps in " + function.name + ": " + conflictedTemps);

        // Step 3: Build CFG
        final var cfg = buildCFG(function);

        // Step 4: For each conflicted temp, split into separate temps per type-kind
        var currentFunction = function;
        for (final var tempName : conflictedTemps) {
            final var types = globalTempTypes.get(tempName);
            currentFunction = splitTemp(currentFunction, tempName, types, cfg);
        }

        return currentFunction;
    }

    private static void collectTempTypes(final IRInstruction instr,
                                          final Map<String, Set<IRType>> tempTypes) {
        switch (instr) {
            case IRInstruction.BinaryOp op -> {
                addType(op.result(), tempTypes);
                addType(op.left(), tempTypes);
                addType(op.right(), tempTypes);
            }
            case IRInstruction.Load ld -> {
                addType(ld.result(), tempTypes);
                addType(ld.ptr(), tempTypes);
            }
            case IRInstruction.Store st -> {
                addType(st.ptr(), tempTypes);
                addType(st.value(), tempTypes);
            }
            case IRInstruction.Move m -> {
                addType(m.result(), tempTypes);
                addType(m.value(), tempTypes);
            }
            case IRInstruction.Call call -> {
                addType(call.result(), tempTypes);
                call.args().forEach(a -> addType(a, tempTypes));
            }
            case IRInstruction.IndirectCall ic -> {
                addType(ic.result(), tempTypes);
                addType(ic.callee(), tempTypes);
                ic.args().forEach(a -> addType(a, tempTypes));
            }
            case IRInstruction.CondBranch cb -> addType(cb.condition(), tempTypes);
            case IRInstruction.Return ret -> {
                if (ret.value() != null) addType(ret.value(), tempTypes);
            }
            case IRInstruction.Cast c -> {
                addType(c.result(), tempTypes);
                addType(c.source(), tempTypes);
            }
            case IRInstruction.InstanceOf i -> {
                addType(i.result(), tempTypes);
                addType(i.source(), tempTypes);
            }
            case IRInstruction.ArrayLoad al -> {
                addType(al.result(), tempTypes);
                addType(al.array(), tempTypes);
                addType(al.index(), tempTypes);
            }
            case IRInstruction.ArrayStore as -> {
                addType(as.array(), tempTypes);
                addType(as.index(), tempTypes);
                addType(as.value(), tempTypes);
            }
            case IRInstruction.ArrayLength al -> {
                addType(al.result(), tempTypes);
                addType(al.array(), tempTypes);
            }
            case IRInstruction.Alloca al -> addType(al.result(), tempTypes);
            case IRInstruction.AllocaArray aa -> {
                addType(aa.result(), tempTypes);
                addType(aa.size(), tempTypes);
            }
            case IRInstruction.HeapAlloc ha -> addType(ha.result(), tempTypes);
            case IRInstruction.MonitorEnter me -> addType(me.object(), tempTypes);
            case IRInstruction.MonitorExit mx -> addType(mx.object(), tempTypes);
            case IRInstruction.Throw t -> {
                if (t.result() != null) addType(t.result(), tempTypes);
            }
            case IRInstruction.Pop p -> addType(p.result(), tempTypes);
            case IRInstruction.Phi phi -> {
                addType(phi.result(), tempTypes);
                phi.incomingValues().forEach(in -> addType(in.value(), tempTypes));
            }
            case IRInstruction.TryCatchRegion tc -> {
                // Exception handler temps
            }
            default -> {}
        }
    }

    private static void addType(final IRValue value,
                                 final Map<String, Set<IRType>> tempTypes) {
        if (value instanceof IRValue.Temp temp) {
            if (temp.type() != null && !(temp.type() instanceof IRType.Void)) {
                tempTypes.computeIfAbsent(temp.name(), k -> new HashSet<>()).add(temp.type());
            }
        }
    }

    private static IRFunction splitTemp(final IRFunction function,
                                         final String tempName,
                                         final Set<IRType> types,
                                         final Map<IRBasicBlock, Set<IRBasicBlock>> cfg) {
        // Group types by kind
        final Map<String, Set<IRType>> kindGroups = new HashMap<>();
        for (final var type : types) {
            final var kind = typeKind(type);
            kindGroups.computeIfAbsent(kind, k -> new HashSet<>()).add(type);
        }

        if (kindGroups.size() <= 1) {
            return function; // No cross-kind conflict
        }

        // Create new temps per kind-group
        final Map<IRType, String> typeToNewTemp = new HashMap<>();
        for (final var entry : kindGroups.entrySet()) {
            for (final var type : entry.getValue()) {
                typeToNewTemp.put(type, tempName + "$" + entry.getKey());
            }
        }

        // Replace temp usages in all instructions
        for (final var block : function.blocks()) {
            final var newInstrs = new ArrayList<IRInstruction>();
            for (final var instr : block.instructions()) {
                final var newInstr = replaceTempInInstruction(instr, tempName, typeToNewTemp);
                newInstrs.add(newInstr);
            }
            block.instructions().clear();
            block.instructions().addAll(newInstrs);
        }

        // Add phi-nodes at merge points
        addPhiForSplitTemp(function, tempName, typeToNewTemp, buildCFG(function));

        return function;
    }

    private static String typeKind(final IRType type) {
        // Use full type key for precise splitting
        return typeKey(type);
    }

    private static String typeKey(final IRType type) {
        if (type instanceof IRType.Int(int bits)) return "I" + bits;
        if (type instanceof IRType.Float(int bits)) return "F" + bits;
        if (type instanceof IRType.Bool) return "Z";
        if (type instanceof IRType.Ptr ptr) return "R" + typeToString(ptr.pointee());
        if (type instanceof IRType.Array arr) return "A" + typeToString(arr.elem());
        return "O" + type.toString();
    }

    private static String typeToString(final IRType type) {
        if (type == null) return "?";
        if (type instanceof IRType.Ptr ptr) return "ptr(" + typeToString(ptr.pointee()) + ")";
        return type.toString();
    }

    

private static IRInstruction replaceTempInInstruction(final IRInstruction instr,
                                                           final String oldTemp,
                                                           final Map<IRType, String> typeToNewTemp) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> new IRInstruction.BinaryOp(
                    replaceTyped(op.result(), typeToNewTemp), op.op(),
                    replaceTyped(op.left(), typeToNewTemp), replaceTyped(op.right(), typeToNewTemp), op.location());
            case IRInstruction.Load ld -> new IRInstruction.Load(
                    replaceTyped(ld.result(), typeToNewTemp), ld.type(),
                    replaceTyped(ld.ptr(), typeToNewTemp), ld.location());
            case IRInstruction.Store st -> new IRInstruction.Store(
                    replaceTyped(st.ptr(), typeToNewTemp), replaceTyped(st.value(), typeToNewTemp), st.location());
            case IRInstruction.Move m -> new IRInstruction.Move(
                    replaceTyped(m.result(), typeToNewTemp), replaceTyped(m.value(), typeToNewTemp), m.location());
            case IRInstruction.Call call -> new IRInstruction.Call(
                    call.callKind(), call.returnType(), call.paramTypes(),
                    replaceTyped(call.result(), typeToNewTemp), call.function(),
                    call.args().stream().map(v -> replaceTyped(v, typeToNewTemp)).toList(), call.location());
            case IRInstruction.IndirectCall ic -> new IRInstruction.IndirectCall(
                    replaceTyped(ic.result(), typeToNewTemp), replaceTyped(ic.callee(), typeToNewTemp), ic.fnType(),
                    ic.args().stream().map(v -> replaceTyped(v, typeToNewTemp)).toList(), ic.location());
            case IRInstruction.CondBranch cb -> new IRInstruction.CondBranch(
                    replaceTyped(cb.condition(), typeToNewTemp), cb.trueLabel(), cb.falseLabel(), cb.location());
            case IRInstruction.Return ret -> new IRInstruction.Return(
                    ret.value() != null ? replaceTyped(ret.value(), typeToNewTemp) : null, ret.location());
            case IRInstruction.Cast c -> new IRInstruction.Cast(
                    replaceTyped(c.result(), typeToNewTemp), replaceTyped(c.source(), typeToNewTemp), c.targetType(), c.location());
            case IRInstruction.InstanceOf i -> new IRInstruction.InstanceOf(
                    replaceTyped(i.result(), typeToNewTemp), replaceTyped(i.source(), typeToNewTemp), i.type(), i.location());
            case IRInstruction.ArrayLoad al -> new IRInstruction.ArrayLoad(
                    replaceTyped(al.result(), typeToNewTemp), replaceTyped(al.array(), typeToNewTemp),
                    replaceTyped(al.index(), typeToNewTemp), al.elemType(), al.location());
            case IRInstruction.ArrayStore as -> new IRInstruction.ArrayStore(
                    replaceTyped(as.array(), typeToNewTemp), replaceTyped(as.index(), typeToNewTemp),
                    replaceTyped(as.value(), typeToNewTemp), as.elemType(), as.location());
            case IRInstruction.ArrayLength al -> new IRInstruction.ArrayLength(
                    replaceTyped(al.result(), typeToNewTemp), replaceTyped(al.array(), typeToNewTemp), al.location());
            case IRInstruction.Alloca al -> new IRInstruction.Alloca(
                    replaceTyped(al.result(), typeToNewTemp), al.allocType(), al.location());
            case IRInstruction.AllocaArray aa -> new IRInstruction.AllocaArray(
                    replaceTyped(aa.result(), typeToNewTemp), aa.allocType(), replaceTyped(aa.size(), typeToNewTemp), aa.location());
            case IRInstruction.HeapAlloc ha -> new IRInstruction.HeapAlloc(
                    replaceTyped(ha.result(), typeToNewTemp), ha.allocType(), ha.location());
            case IRInstruction.MonitorEnter me -> new IRInstruction.MonitorEnter(
                    replaceTyped(me.object(), typeToNewTemp), me.location());
            case IRInstruction.MonitorExit mx -> new IRInstruction.MonitorExit(
                    replaceTyped(mx.object(), typeToNewTemp), mx.location());
            case IRInstruction.Throw t -> new IRInstruction.Throw(
                    t.result() != null ? replaceTyped(t.result(), typeToNewTemp) : null, t.type(), t.location());
            case IRInstruction.Pop p -> new IRInstruction.Pop(
                    replaceTyped(p.result(), typeToNewTemp), p.location());
            case IRInstruction.Phi phi -> new IRInstruction.Phi(
                    replaceTyped(phi.result(), typeToNewTemp),
                    phi.incomingValues().stream()
                            .map(in -> new IRInstruction.Phi.Incoming(replaceTyped(in.value(), typeToNewTemp), in.fromBlock()))
                            .toList(),
                    phi.location());
            case IRInstruction.TryCatchRegion tc -> new IRInstruction.TryCatchRegion(
                    tc.tryStartLabel(), tc.tryEndLabel(), tc.handlerLabel(), tc.exceptionType(), tc.location());
            default -> instr;
        };
    }

    private static IRValue replaceTyped(final IRValue value,
                                         final Map<IRType, String> typeToNewTemp) {
        if (!(value instanceof IRValue.Temp temp)) return value;
        // If this temp is one we're splitting, replace with the correct typed version
        // We need to infer which type to use based on the temp's declared type
        if (typeToNewTemp.containsKey(temp.type())) {
            final var newName = typeToNewTemp.get(temp.type());
            return new IRValue.Temp(newName, temp.type());
        }
        return value;
    }

    private static IRValue replace(final IRValue value) {
        if (!(value instanceof IRValue.Temp temp)) return value;
        // We don't know the exact type here, so we keep the original.
        // The phi-nodes will handle the merging.
        return value;
    }

    private static void addPhiForSplitTemp(final IRFunction function,
                                            final String oldTemp,
                                            final Map<IRType, String> typeToNewTemp,
                                            final Map<IRBasicBlock, Set<IRBasicBlock>> cfg) {
        // Find blocks that define the old temp
        final Set<IRBasicBlock> defBlocks = new HashSet<>();
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                if (writtenTemps(instr).contains(oldTemp)) {
                    defBlocks.add(block);
                }
            }
        }

        // Find merge points (blocks with multiple predecessor defs)
        for (final var block : function.blocks()) {
            final var preds = cfg.getOrDefault(block, Collections.emptySet());
            final var defPreds = new HashSet<>(preds);
            defPreds.retainAll(defBlocks);

            if (defPreds.size() > 1) {
                // Merge point - add phi for each split temp
                for (final var entry : typeToNewTemp.entrySet()) {
                    final var newTemp = entry.getValue();
                    final var newType = entry.getKey();

                    final var incoming = defPreds.stream()
                            .map(p -> new IRInstruction.Phi.Incoming(
                                    new IRValue.Temp(getTempForPred(p, oldTemp, typeToNewTemp), newType),
                                    p))
                            .toList();

                    final var phi = new IRInstruction.Phi(
                            new IRValue.Temp(newTemp, newType),
                            incoming,
                            SourceLocation.UNKNOWN);

                    // Insert phi at start of block (after existing phis)
                    int insertIdx = 0;
                    for (final var instr : block.instructions()) {
                        if (instr instanceof IRInstruction.Phi) {
                            insertIdx++;
                        } else {
                            break;
                        }
                    }
                    block.instructions().add(insertIdx, phi);
                }
            }
        }
    }

    private static String getTempForPred(final IRBasicBlock pred,
                                          final String oldTemp,
                                          final Map<IRType, String> typeToNewTemp) {
        for (final var instr : pred.instructions()) {
            if (writtenTemps(instr).contains(oldTemp)) {
                final var type = inferType(instr);
                return typeToNewTemp.getOrDefault(type, oldTemp);
            }
        }
        return oldTemp;
    }

    private static IRType inferType(final IRInstruction instr) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> op.result().type();
            case IRInstruction.Load ld -> ld.result().type();
            case IRInstruction.Store st -> st.value().type();
            case IRInstruction.Move m -> m.result().type();
            case IRInstruction.Call call -> call.result() != null ? call.result().type() : null;
            case IRInstruction.IndirectCall ic -> ic.result() != null ? ic.result().type() : null;
            case IRInstruction.Cast c -> c.result().type();
            case IRInstruction.InstanceOf i -> i.result().type();
            case IRInstruction.ArrayLoad al -> al.result().type();
            case IRInstruction.Alloca al -> al.allocType();
            case IRInstruction.AllocaArray aa -> aa.result().type();
            case IRInstruction.HeapAlloc ha -> ha.result().type();
            case IRInstruction.Phi phi -> phi.result().type();
            default -> null;
        };
    }

    private static List<String> writtenTemps(final IRInstruction instr) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> List.of(IRValue.nameOf(op.result()));
            case IRInstruction.Load ld -> List.of(IRValue.nameOf(ld.result()));
            case IRInstruction.Alloca al -> List.of(IRValue.nameOf(al.result()));
            case IRInstruction.Store st -> st.ptr() instanceof IRValue.Temp t ? List.of(IRValue.nameOf(t)) : List.of();
            case IRInstruction.Move m -> List.of(IRValue.nameOf(m.result()));
            case IRInstruction.Call call -> call.result() != null ? List.of(IRValue.nameOf(call.result())) : List.of();
            case IRInstruction.IndirectCall ic -> ic.result() != null ? List.of(IRValue.nameOf(ic.result())) : List.of();
            case IRInstruction.ArrayLoad al -> List.of(IRValue.nameOf(al.result()));
            case IRInstruction.AllocaArray aa -> List.of(IRValue.nameOf(aa.result()));
            case IRInstruction.HeapAlloc ha -> List.of(IRValue.nameOf(ha.result()));
            case IRInstruction.Cast c -> List.of(IRValue.nameOf(c.result()));
            case IRInstruction.InstanceOf i -> List.of(IRValue.nameOf(i.result()));
            case IRInstruction.Phi phi -> List.of(IRValue.nameOf(phi.result()));
            default -> List.of();
        };
    }

    private static Map<IRBasicBlock, Set<IRBasicBlock>> buildCFG(final IRFunction function) {
        final Map<IRBasicBlock, Set<IRBasicBlock>> cfg = new HashMap<>();
        for (final var block : function.blocks()) {
            cfg.put(block, new HashSet<>());
        }
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                if (instr instanceof IRInstruction.Branch br) {
                    final var target = function.blocks().stream()
                            .filter(b -> b.label().equals(br.targetLabel()))
                            .findFirst().orElse(null);
                    if (target != null) {
                        cfg.get(target).add(block);
                    }
                } else if (instr instanceof IRInstruction.CondBranch cb) {
                    for (final var t : new String[]{cb.trueLabel(), cb.falseLabel()}) {
                        final var target = function.blocks().stream()
                                .filter(b -> b.label().equals(t))
                                .findFirst().orElse(null);
                        if (target != null) {
                            cfg.get(target).add(block);
                        }
                    }
                }
            }
        }
        return cfg;
    }
}