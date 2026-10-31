package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Synthesized class that lets the C runtime interrupt the channel a thread is blocked on.
 * {@code Thread.interrupt()} is native here; the JDK's version calls {@code Interruptible.interrupt(Thread)} of the
 * channel the thread declared with {@code blockedOn}, which closes the channel (then {@code postInterrupt()}, as the JDK
 * does once it has released the lock). The interface is not accessible to
 * source code, so the call is generated as bytecode (as {@link ContinuationBridge} does for continuations).
 *
 * <pre>
 * static void interrupt(Object blocker, Object thread) {
 *     ((Interruptible) blocker).interrupt((Thread) thread);
 *     ((Interruptible) blocker).postInterrupt();
 * }
 * </pre>
 */
final class InterruptBridge {

    static final String CLASS = RuntimeCalls.RUNTIME_PACKAGE + "InterruptBridge";

    static final String INTERRUPTIBLE = "sun/nio/ch/Interruptible";

    static final String INTERRUPT = "interrupt";

    static final String INTERRUPT_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

    private InterruptBridge() {
    }

    static ClassNode build() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CLASS;
        node.superName = "java/lang/Object";

        final MethodNode interrupt = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, INTERRUPT,
                INTERRUPT_DESC, null, null);
        interrupt.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        interrupt.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, INTERRUPTIBLE));
        interrupt.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        interrupt.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Thread"));
        interrupt.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, INTERRUPTIBLE, "interrupt",
                "(Ljava/lang/Thread;)V", true));
        interrupt.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        interrupt.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, INTERRUPTIBLE));
        interrupt.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, INTERRUPTIBLE, "postInterrupt",
                "()V", true));
        interrupt.instructions.add(new InsnNode(Opcodes.RETURN));
        interrupt.maxStack = 2;
        interrupt.maxLocals = 2;
        node.methods.add(interrupt);
        return node;
    }
}
