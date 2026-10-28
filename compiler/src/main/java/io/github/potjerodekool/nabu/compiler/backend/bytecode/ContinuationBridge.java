package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
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
        return node;
    }
}
