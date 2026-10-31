package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A method with {@code ACC_SYNCHRONIZED} holds the monitor of {@code this} (of the class, when static) while it runs.
 * A JVM does that itself; here the flag is not looked at, so the lock is put in the bytecode, the way javac writes a
 * synchronized block:
 *
 * <pre>
 * lock = this (or the class); monitorenter(lock)
 * try { body; every return: monitorexit(lock) } catch (any) { monitorexit(lock); rethrow }
 * </pre>
 */
final class SynchronizedMethods {

    private SynchronizedMethods() {
    }

    static ClassNode apply(final ClassNode node) {
        if (node == null || node.methods == null) {
            return node;
        }
        for (final MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_SYNCHRONIZED) != 0 && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0
                    && method.instructions != null && method.instructions.size() > 0) {
                lock(node, method);
            }
        }
        return node;
    }

    private static void lock(final ClassNode owner, final MethodNode method) {
        method.access &= ~Opcodes.ACC_SYNCHRONIZED;
        final boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
        final int slot = method.maxLocals;
        method.maxLocals = slot + 1;
        method.maxStack = Math.max(method.maxStack, 2) + 1;

        final LabelNode start = new LabelNode();
        final LabelNode handler = new LabelNode();

        // every return releases the monitor first (the return value, if any, stays on the stack below the lock)
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            final AbstractInsnNode next = insn.getNext();
            final int op = insn.getOpcode();
            if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) {
                method.instructions.insertBefore(insn, new VarInsnNode(Opcodes.ALOAD, slot));
                method.instructions.insertBefore(insn, new InsnNode(Opcodes.MONITOREXIT));
            }
            insn = next;
        }

        // the prologue: take the lock
        final org.objectweb.asm.tree.InsnList prologue = new org.objectweb.asm.tree.InsnList();
        if (isStatic) {
            prologue.add(new LdcInsnNode(Type.getObjectType(owner.name)));
        } else {
            prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
        }
        prologue.add(new InsnNode(Opcodes.DUP));
        prologue.add(new VarInsnNode(Opcodes.ASTORE, slot));
        prologue.add(new InsnNode(Opcodes.MONITORENTER));
        prologue.add(start);
        method.instructions.insert(prologue);

        // the handler: release and rethrow. Placed after the body and after the inner handlers in the table.
        // the range ends where the handler starts: a separate end label would be an empty block without a terminator
        method.instructions.add(handler);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
        method.instructions.add(new InsnNode(Opcodes.MONITOREXIT));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start, handler, handler, null));
    }
}
