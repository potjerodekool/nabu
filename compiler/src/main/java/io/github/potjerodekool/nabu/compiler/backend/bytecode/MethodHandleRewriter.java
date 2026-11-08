package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code MethodHandle.invoke} and {@code invokeExact} are signature polymorphic: each call has its own descriptor,
 * the JDK links it at run time. A native program has no such handles; the handles that libraries make of reflection
 * objects (see MethodHandleSupport) are called through a helper that packs the arguments into an array. The helper
 * of a descriptor is a private static synthetic method of the class that makes the call:
 * {@code $mh$N(Object handle, A1 a1, ...)} returns what the call returns.
 */
final class MethodHandleRewriter {

    private static final String SUPPORT = RuntimeCalls.RUNTIME_PACKAGE + "MethodHandleSupport";

    private MethodHandleRewriter() {
    }

    static void rewrite(final ClassNode classNode) {
        if (classNode.name.startsWith(RuntimeCalls.RUNTIME_PACKAGE)) {
            return;
        }
        final Map<String, String> helpers = new HashMap<>();
        for (final MethodNode method : new java.util.ArrayList<>(classNode.methods)) {
            if (method.instructions == null) {
                continue;
            }
            for (final AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/invoke/MethodHandle".equals(call.owner)
                        && ("invoke".equals(call.name) || "invokeExact".equals(call.name))) {
                    String helper = helpers.get(call.desc);
                    if (helper == null) {
                        helper = "$mh$" + helpers.size();
                        helpers.put(call.desc, helper);
                        classNode.methods.add(helper(helper, call.desc));
                    }
                    method.instructions.set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, helper,
                            "(Ljava/lang/Object;" + call.desc.substring(1),
                            (classNode.access & Opcodes.ACC_INTERFACE) != 0));
                    method.maxStack = Math.max(method.maxStack, 4);
                }
            }
        }
    }

    private static MethodNode helper(final String name, final String callDesc) {
        final Type[] arguments = Type.getArgumentTypes(callDesc);
        final Type result = Type.getReturnType(callDesc);
        final MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                "(Ljava/lang/Object;" + callDesc.substring(1), null, new String[] {"java/lang/Throwable"});
        final InsnList i = m.instructions;
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new LdcInsnNode(arguments.length));
        i.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int local = 1;
        for (int a = 0; a < arguments.length; a++) {
            i.add(new InsnNode(Opcodes.DUP));
            i.add(new LdcInsnNode(a));
            i.add(new VarInsnNode(arguments[a].getOpcode(Opcodes.ILOAD), local));
            box(i, arguments[a]);
            i.add(new InsnNode(Opcodes.AASTORE));
            local += arguments[a].getSize();
        }
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SUPPORT, "invoke",
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        unbox(i, result);
        m.maxStack = 8;
        m.maxLocals = local;
        return m;
    }

    private static void box(final InsnList i, final Type type) {
        final String wrapper = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };
        if (wrapper != null) {
            i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                    "(" + type.getDescriptor() + ")L" + wrapper + ";", false));
        }
    }

    private static void unbox(final InsnList i, final Type type) {
        switch (type.getSort()) {
            case Type.VOID -> {
                i.add(new InsnNode(Opcodes.POP));
                i.add(new InsnNode(Opcodes.RETURN));
            }
            case Type.BOOLEAN -> {
                i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                i.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.CHAR -> {
                i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
                i.add(new InsnNode(Opcodes.IRETURN));
            }
            case Type.BYTE, Type.SHORT, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE -> {
                final String method = switch (type.getSort()) {
                    case Type.BYTE -> "byteValue";
                    case Type.SHORT -> "shortValue";
                    case Type.INT -> "intValue";
                    case Type.LONG -> "longValue";
                    case Type.FLOAT -> "floatValue";
                    default -> "doubleValue";
                };
                i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
                i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", method,
                        "()" + type.getDescriptor(), false));
                i.add(new InsnNode(type.getOpcode(Opcodes.IRETURN)));
            }
            default -> {
                if (!"Ljava/lang/Object;".equals(type.getDescriptor())) {
                    i.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
                }
                i.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }
}
