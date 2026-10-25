package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Synthesized class with the thread builders of {@code Thread}. {@code Thread} itself is provided by the C runtime,
 * so {@code Thread.ofVirtual()}, {@code ofPlatform()} and {@code startVirtualThread} are redirected here (see
 * {@link RuntimeCalls}). The builders are the JDK's own ({@code ThreadBuilders}); their constructors are not
 * accessible to source code, which is why this is generated as bytecode.
 */
final class ThreadBridge {

    static final String CLASS = RuntimeCalls.RUNTIME_PACKAGE + "ThreadBridge";

    private static final String VIRTUAL_BUILDER = "java/lang/ThreadBuilders$VirtualThreadBuilder";

    private static final String PLATFORM_BUILDER = "java/lang/ThreadBuilders$PlatformThreadBuilder";

    private ThreadBridge() {
    }

    static ClassNode build() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CLASS;
        node.superName = "java/lang/Object";
        node.methods.add(newBuilder("ofVirtual", "Ljava/lang/Thread$Builder$OfVirtual;", VIRTUAL_BUILDER));
        node.methods.add(newBuilder("ofPlatform", "Ljava/lang/Thread$Builder$OfPlatform;", PLATFORM_BUILDER));

        final MethodNode start = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "startVirtualThread",
                "(Ljava/lang/Runnable;)Ljava/lang/Thread;", null, null);
        pushNew(start, VIRTUAL_BUILDER);
        start.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        start.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Thread$Builder", "start",
                "(Ljava/lang/Runnable;)Ljava/lang/Thread;", true));
        start.instructions.add(new InsnNode(Opcodes.ARETURN));
        start.maxStack = 3;
        start.maxLocals = 1;
        node.methods.add(start);
        return node;
    }

    private static MethodNode newBuilder(final String name, final String returnType, final String builder) {
        final MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "()" + returnType,
                null, null);
        pushNew(method, builder);
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 2;
        method.maxLocals = 0;
        return method;
    }

    private static void pushNew(final MethodNode method, final String type) {
        method.instructions.add(new TypeInsnNode(Opcodes.NEW, type));
        method.instructions.add(new InsnNode(Opcodes.DUP));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false));
    }
}
