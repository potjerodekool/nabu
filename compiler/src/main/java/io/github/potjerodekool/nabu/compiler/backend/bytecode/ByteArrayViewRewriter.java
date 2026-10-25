package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.HashMap;
import java.util.Map;

/**
 * A class with {@code VarHandle}s from {@code MethodHandles.byteArrayViewVarHandle} (GHASH, GaloisCounterMode,
 * ChaCha20Cipher) cannot run in a native executable: VarHandles are invoked signature-polymorphically and compiled
 * at run time. The handle only says which byte order the {@code get} and {@code set} calls use, so the handle
 * becomes a string ({@code "B"} or {@code "L"}) and the calls become calls of
 * {@link io.github.potjerodekool.nabu.compiler.backend.bytecode.runtime.ByteArrayViews}.
 */
final class ByteArrayViewRewriter {

    private static final String VIEWS = RuntimeCalls.RUNTIME_PACKAGE + "ByteArrayViews";

    private ByteArrayViewRewriter() {
    }

    static void rewrite(final ClassNode classNode) {
        final Map<String, String> orders = new HashMap<>();
        for (final MethodNode method : classNode.methods) {
            if ("<clinit>".equals(method.name)) {
                findHandles(method, orders);
            }
        }
        if (orders.isEmpty()) {
            return;
        }
        for (final MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (final AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                        && field.owner.equals(classNode.name) && orders.containsKey(field.name)) {
                    method.instructions.set(insn, new LdcInsnNode(orders.get(field.name)));
                } else if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && field.owner.equals(classNode.name) && orders.containsKey(field.name)) {
                    // the class initializer's own store: the handle is not a value any more
                    method.instructions.set(insn, new InsnNode(Opcodes.POP));
                } else if (insn instanceof MethodInsnNode call && "java/lang/invoke/VarHandle".equals(call.owner)
                        && ("get".equals(call.name) || "set".equals(call.name))) {
                    final String view = viewName(call);
                    if (view != null) {
                        method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, VIEWS, view,
                                "(Ljava/lang/Object;" + call.desc.substring(1), false));
                    }
                } else if (insn instanceof MethodInsnNode call && "java/lang/invoke/MethodHandles".equals(call.owner)
                        && "byteArrayViewVarHandle".equals(call.name)) {
                    // (Class, ByteOrder) -> the VarHandle: the order is known from the field, drop the arguments
                    method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP2));
                    method.instructions.set(insn, new InsnNode(Opcodes.ACONST_NULL));
                }
            }
            method.maxStack = Math.max(method.maxStack, 4);
        }
    }

    /** {@code getLong} for {@code VarHandle.get([BI)J}, {@code setInt} for {@code set([BII)V}; null when unknown. */
    private static String viewName(final MethodInsnNode call) {
        final Type type = Type.getMethodType(call.desc);
        final Type[] args = type.getArgumentTypes();
        if (args.length < 2 || !"[B".equals(args[0].getDescriptor()) || args[1].getSort() != Type.INT) {
            return null;
        }
        final Type value = "get".equals(call.name) ? type.getReturnType() : args.length == 3 ? args[2] : null;
        if (value == null) {
            return null;
        }
        final String suffix = switch (value.getSort()) {
            case Type.LONG -> "Long";
            case Type.INT -> "Int";
            case Type.SHORT -> "Short";
            case Type.CHAR -> "Char";
            default -> null;
        };
        return suffix == null ? null : call.name + suffix;
    }

    /** Reads {@code LDC <array class>; GETSTATIC ByteOrder.X; INVOKESTATIC byteArrayViewVarHandle; PUTSTATIC f}. */
    private static void findHandles(final MethodNode clinit, final Map<String, String> orders) {
        AbstractInsnNode previous = null;
        AbstractInsnNode beforePrevious = null;
        final AbstractInsnNode[] insns = clinit.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            final AbstractInsnNode insn = insns[i];
            if (insn.getOpcode() < 0) {
                continue;
            }
            if (insn instanceof MethodInsnNode call && "byteArrayViewVarHandle".equals(call.name)
                    && previous instanceof FieldInsnNode order && beforePrevious instanceof LdcInsnNode) {
                AbstractInsnNode next = insn.getNext();
                while (next != null && next.getOpcode() < 0) {
                    next = next.getNext();
                }
                if (next instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC) {
                    orders.put(store.name, "BIG_ENDIAN".equals(order.name) ? "B" : "L");
                }
            }
            beforePrevious = previous;
            previous = insn;
        }
    }
}
