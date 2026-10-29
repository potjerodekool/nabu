package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Synthesized class that lets the C runtime start the body of a {@code jdk.internal.vm.Continuation}.
 * {@code Continuation.enter(Continuation, boolean)} is private and the class is not accessible to source code, so
 * the entry point is generated as bytecode (as {@link ThreadBridge} does for the thread builders).
 *
 * <pre>
 * static Object enter(Object continuation) {
 *     try {
 *         Continuation.enter((Continuation) continuation, false);
 *         return null;
 *     } catch (Throwable t) {
 *         return t;
 *     }
 * }
 * </pre>
 *
 * The runtime runs this on the stack of the continuation. A throwable that escapes the body is handed back to the
 * thread that ran the continuation, which throws it from {@code run()}, as the JVM does.
 */
final class ContinuationBridge {

    static final String CLASS = RuntimeCalls.RUNTIME_PACKAGE + "ContinuationBridge";

    static final String CONTINUATION = "jdk/internal/vm/Continuation";

    static final String ENTER = "enter";

    static final String ENTER_DESC = "(Ljava/lang/Object;)Ljava/lang/Object;";

    static final String START_ROOT = "startRoot";

    static final String START_ROOT_DESC = "(Ljava/lang/Object;)V";

    static final String CREATE = "create";

    static final String CREATE_DESC = "()Ljava/lang/Object;";

    private static final String OPS = RuntimeCalls.RUNTIME_PACKAGE + "VirtualThreadOps";

    private static final String VIRTUAL_THREAD = "java/lang/VirtualThread";

    private ContinuationBridge() {
    }

    static ClassNode build() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CLASS;
        node.superName = "java/lang/Object";

        final MethodNode enter = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, ENTER, ENTER_DESC, null, null);
        final LabelNode start = new LabelNode(new Label());
        final LabelNode end = new LabelNode(new Label());
        final LabelNode handler = new LabelNode(new Label());
        enter.instructions.add(start);
        enter.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        enter.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, CONTINUATION));
        enter.instructions.add(new InsnNode(Opcodes.ICONST_0));
        enter.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTINUATION, "enter",
                "(L" + CONTINUATION + ";Z)V", false));
        enter.instructions.add(end);
        enter.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        enter.instructions.add(new InsnNode(Opcodes.ARETURN));
        enter.instructions.add(handler);
        enter.instructions.add(new InsnNode(Opcodes.ARETURN));
        enter.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));
        enter.maxStack = 2;
        enter.maxLocals = 1;
        node.methods.add(enter);

        // The implementation of VirtualThreadOps: the methods of the JDK's VirtualThread that ThreadSupport needs.
        node.interfaces.add(OPS);
        final MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        node.methods.add(init);

        final MethodNode create = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, CREATE, CREATE_DESC, null, null);
        create.instructions.add(new TypeInsnNode(Opcodes.NEW, CLASS));
        create.instructions.add(new InsnNode(Opcodes.DUP));
        create.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CLASS, "<init>", "()V", false));
        create.instructions.add(new InsnNode(Opcodes.ARETURN));
        create.maxStack = 2;
        create.maxLocals = 0;
        node.methods.add(create);

        // static void startRoot(Object thread): the start() of a virtual thread, in the root container.
        final MethodNode startRoot = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, START_ROOT,
                START_ROOT_DESC, null, null);
        startRoot.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        startRoot.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, VIRTUAL_THREAD));
        startRoot.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "jdk/internal/vm/ThreadContainers", "root",
                "()Ljdk/internal/vm/ThreadContainer;", false));
        startRoot.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VIRTUAL_THREAD, "start",
                "(Ljdk/internal/vm/ThreadContainer;)V", false));
        startRoot.instructions.add(new InsnNode(Opcodes.RETURN));
        startRoot.maxStack = 2;
        startRoot.maxLocals = 1;
        node.methods.add(startRoot);

        final MethodNode startThread = new MethodNode(Opcodes.ACC_PUBLIC, "start", "(Ljava/lang/Thread;Ljava/lang/Object;)V",
                null, null);
        startThread.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        startThread.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, VIRTUAL_THREAD));
        startThread.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
        startThread.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "jdk/internal/vm/ThreadContainer"));
        startThread.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VIRTUAL_THREAD, "start",
                "(Ljdk/internal/vm/ThreadContainer;)V", false));
        startThread.instructions.add(new InsnNode(Opcodes.RETURN));
        startThread.maxStack = 2;
        startThread.maxLocals = 3;
        node.methods.add(startThread);

        node.methods.add(virtualCall("state", "(Ljava/lang/Thread;)Ljava/lang/Thread$State;", "threadState",
                "()Ljava/lang/Thread$State;", Opcodes.ARETURN));
        node.methods.add(virtualCall("alive", "(Ljava/lang/Thread;)Z", "alive", "()Z", Opcodes.IRETURN));
        node.methods.add(virtualCall("stackTrace", "(Ljava/lang/Thread;)[Ljava/lang/StackTraceElement;",
                "asyncGetStackTrace", "()[Ljava/lang/StackTraceElement;", Opcodes.ARETURN));

        final MethodNode continuation = new MethodNode(Opcodes.ACC_PUBLIC, "continuation",
                "(Ljava/lang/Thread;)Ljava/lang/Object;", null, null);
        continuation.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        continuation.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, VIRTUAL_THREAD));
        continuation.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, VIRTUAL_THREAD, "cont",
                "Ljdk/internal/vm/Continuation;"));
        continuation.instructions.add(new InsnNode(Opcodes.ARETURN));
        continuation.maxStack = 1;
        continuation.maxLocals = 2;
        node.methods.add(continuation);

        node.methods.add(virtualCall("interrupt", "(Ljava/lang/Thread;)V", "interrupt", "()V", Opcodes.RETURN));
        node.methods.add(virtualCall("isInterrupted", "(Ljava/lang/Thread;)Z", "isInterrupted", "()Z",
                Opcodes.IRETURN));
        node.methods.add(virtualCall("getAndClearInterrupt", "(Ljava/lang/Thread;)Z", "getAndClearInterrupt", "()Z",
                Opcodes.IRETURN));

        // Blocker.begin() only acts when VM.isBooted(), which this runtime never says; so do what it does.
        final String carrierThread = "jdk/internal/misc/CarrierThread";
        final MethodNode beginBlocking = new MethodNode(Opcodes.ACC_PUBLIC, "beginBlocking", "()Z", null, null);
        final LabelNode notCarrier = new LabelNode(new Label());
        beginBlocking.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread",
                "currentCarrierThread", "()Ljava/lang/Thread;", false));
        beginBlocking.instructions.add(new InsnNode(Opcodes.DUP));
        beginBlocking.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, carrierThread));
        beginBlocking.instructions.add(new JumpInsnNode(Opcodes.IFEQ, notCarrier));
        beginBlocking.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, carrierThread));
        beginBlocking.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, carrierThread, "beginBlocking", "()Z",
                false));
        beginBlocking.instructions.add(new InsnNode(Opcodes.IRETURN));
        beginBlocking.instructions.add(notCarrier);
        beginBlocking.instructions.add(new InsnNode(Opcodes.POP));
        beginBlocking.instructions.add(new InsnNode(Opcodes.ICONST_0));
        beginBlocking.instructions.add(new InsnNode(Opcodes.IRETURN));
        beginBlocking.maxStack = 2;
        beginBlocking.maxLocals = 1;
        node.methods.add(beginBlocking);

        final MethodNode endBlocking = new MethodNode(Opcodes.ACC_PUBLIC, "endBlocking", "(Z)V", null, null);
        final LabelNode notAttempted = new LabelNode(new Label());
        endBlocking.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        endBlocking.instructions.add(new JumpInsnNode(Opcodes.IFEQ, notAttempted));
        endBlocking.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread",
                "currentCarrierThread", "()Ljava/lang/Thread;", false));
        endBlocking.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, carrierThread));
        endBlocking.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, carrierThread, "endBlocking", "()V",
                false));
        endBlocking.instructions.add(notAttempted);
        endBlocking.instructions.add(new InsnNode(Opcodes.RETURN));
        endBlocking.maxStack = 1;
        endBlocking.maxLocals = 2;
        node.methods.add(endBlocking);

        final MethodNode join = new MethodNode(Opcodes.ACC_PUBLIC, "join", "(Ljava/lang/Thread;J)Z", null, null);
        join.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        join.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, VIRTUAL_THREAD));
        join.instructions.add(new VarInsnNode(Opcodes.LLOAD, 2));
        join.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VIRTUAL_THREAD, "joinNanos", "(J)Z", false));
        join.instructions.add(new InsnNode(Opcodes.IRETURN));
        join.maxStack = 3;
        join.maxLocals = 4;
        node.methods.add(join);
        return node;
    }

    /** {@code return ((VirtualThread) thread).method();} */
    private static MethodNode virtualCall(final String name, final String desc, final String target,
                                          final String targetDesc, final int returnOpcode) {
        final MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, VIRTUAL_THREAD));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, VIRTUAL_THREAD, target, targetDesc, false));
        method.instructions.add(new InsnNode(returnOpcode));
        method.maxStack = 1;
        method.maxLocals = 2;
        return method;
    }
}
