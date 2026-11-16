package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.backend.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.backend.ir.IRBuilder;
import io.github.potjerodekool.nabu.backend.ir.IRFunction;
import io.github.potjerodekool.nabu.backend.ir.IRModule;
import io.github.potjerodekool.nabu.backend.ir.types.IRType;
import io.github.potjerodekool.nabu.backend.ir.CallKind;
import io.github.potjerodekool.nabu.backend.ir.values.IRValue;
import io.github.potjerodekool.nabu.debug.SourceLocation;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Bytecode -> IR lowering (Fase 1, start). Leest ASM's tree-API (ClassNode/MethodNode/InsnList)
 * en vult een IRFunction met IR-instructies. Fase 1 dekt constants & stack + locals voor
 * eenvoudige integer-methoden. Overige opcodes zijn TODO.
 */
public class BytecodeIrGeneratingVisitor {

    private final IRBuilder builder;

    public BytecodeIrGeneratingVisitor(final String moduleName) {
        this.builder = new IRBuilder(moduleName);
    }

    /** Lower een enkele methode naar een IRFunction. */
    public IRFunction lowerMethod(final MethodNode method, final String ownerInternalName) {
        final String fnName = ownerInternalName.replace('/', '_') + "_" + method.name;
        final IRType returnType = DescriptorMapper.toIRType(method.desc.substring(method.desc.indexOf(')') + 1));
        final List<IRType> paramTypes = DescriptorMapper.paramTypes(method.desc);
        final List<IRValue> params = new ArrayList<>();
        for (int i = 0; i < paramTypes.size(); i++) {
            params.add(new IRValue.Temp("p" + i, paramTypes.get(i)));
        }
        builder.beginFunction(fnName, returnType, params, 0);

        final IRBasicBlock entry = builder.currentBlock();
        // Operand-stack simulatie (minimaal: alleen integer-constanten en locals).
        final Deque<IRValue> stack = new ArrayDeque<>();
        final Map<Integer, IRValue> locals = new HashMap<>();

        for (final AbstractInsnNode in : method.instructions) {
            if (in instanceof InsnNode insn) {
                switch (insn.getOpcode()) {
                    case org.objectweb.asm.Opcodes.ICONST_M1 -> stack.push(new IRValue.ConstInt(-1, IRType.I32));
                    case org.objectweb.asm.Opcodes.ICONST_0 -> stack.push(new IRValue.ConstInt(0, IRType.I32));
                    case org.objectweb.asm.Opcodes.ICONST_1 -> stack.push(new IRValue.ConstInt(1, IRType.I32));
                    case org.objectweb.asm.Opcodes.ICONST_2 -> stack.push(new IRValue.ConstInt(2, IRType.I32));
                    case org.objectweb.asm.Opcodes.ICONST_3 -> stack.push(new IRValue.ConstInt(3, IRType.I32));
                    case org.objectweb.asm.Opcodes.ICONST_4 -> stack.push(new IRValue.ConstInt(4, IRType.I32));
                    case org.objectweb.asm.Opcodes.ICONST_5 -> stack.push(new IRValue.ConstInt(5, IRType.I32));
                    case org.objectweb.asm.Opcodes.IRETURN -> {
                        final IRValue v = stack.pop();
                        builder.emitReturn(v);
                    }
                    case org.objectweb.asm.Opcodes.RETURN -> builder.emitReturn(null);
                    default -> { /* TODO: overige opcodes */ }
                }
            } else if (in instanceof IntInsnNode iin) {
                if (iin.getOpcode() == org.objectweb.asm.Opcodes.BIPUSH || iin.getOpcode() == org.objectweb.asm.Opcodes.SIPUSH) {
                    stack.push(new IRValue.ConstInt(iin.operand, IRType.I32));
                }
            } else if (in instanceof VarInsnNode vin) {
                switch (vin.getOpcode()) {
                    case org.objectweb.asm.Opcodes.ILOAD -> {
                        IRValue v = locals.get(vin.var);
                        if (v == null) v = new IRValue.Temp("l" + vin.var, IRType.I32);
                        stack.push(v);
                    }
                    case org.objectweb.asm.Opcodes.ISTORE -> {
                        final IRValue v = stack.pop();
                        locals.put(vin.var, v);
                    }
                    default -> { /* TODO */ }
                }
            } else if (in instanceof LdcInsnNode ldc) {
                if (ldc.cst instanceof Integer i) {
                    stack.push(new IRValue.ConstInt(i, IRType.I32));
                } else if (ldc.cst instanceof String s) {
                    // string constant: placeholder, echte string-global volgt later
                    stack.push(new IRValue.ConstInt(0, IRType.I32));
                }
            }
        }

        builder.endFunction();
        return builder.build().functions().getFirst();
    }

    /** Lower een ClassNode naar een IRModule (alle methoden). */
    public IRModule lowerClass(final ClassNode cls) {
        final IRModule module = new IRModule(cls.name.replace('/', '_'));
        for (final MethodNode m : cls.methods) {
            if (m.name.startsWith("<")) continue; // skip <init>/<clinit> for now
            module.addFunction(lowerMethod(m, cls.name));
        }
        return module;
    }
}