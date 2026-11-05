package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * {@code Method.getParameters} and {@code Constructor.getParameters} of a native program: Method and Constructor
 * objects are made by the C runtime, which cannot make a {@code java.lang.reflect.Parameter}. Parameter's constructor
 * is package-private, so the class that calls it is generated (the lowering does not check access). The parameters
 * have no names (as for a class compiled without {@code -parameters}): {@code arg0}, {@code arg1}, ...
 */
final class ParameterSupportGenerator {

    static final String PARAMETER_SUPPORT = RuntimeCalls.RUNTIME_PACKAGE + "ParameterSupport";

    private static final String PARAMETER = "java/lang/reflect/Parameter";

    private ParameterSupportGenerator() {
    }

    static ClassNode generate() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = PARAMETER_SUPPORT;
        node.superName = "java/lang/Object";
        node.methods.add(parametersOf("ofConstructor", "java/lang/reflect/Constructor"));
        node.methods.add(parametersOf("ofMethod", "java/lang/reflect/Method"));
        return node;
    }

    /** {@code static Parameter[] name(Owner e)}: one Parameter per declared parameter. */
    private static MethodNode parametersOf(final String name, final String owner) {
        final MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "(L" + owner + ";)[L" + PARAMETER + ";", null, null);
        final LabelNode loop = new LabelNode();
        final LabelNode end = new LabelNode();
        final var i = m.instructions;
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, "getParameterCount", "()I", false));
        i.add(new VarInsnNode(Opcodes.ISTORE, 1));
        i.add(new VarInsnNode(Opcodes.ILOAD, 1));
        i.add(new TypeInsnNode(Opcodes.ANEWARRAY, PARAMETER));
        i.add(new VarInsnNode(Opcodes.ASTORE, 2));
        i.add(new InsnNode(Opcodes.ICONST_0));
        i.add(new VarInsnNode(Opcodes.ISTORE, 3));
        i.add(loop);
        i.add(new VarInsnNode(Opcodes.ILOAD, 3));
        i.add(new VarInsnNode(Opcodes.ILOAD, 1));
        i.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        i.add(new VarInsnNode(Opcodes.ALOAD, 2));
        i.add(new VarInsnNode(Opcodes.ILOAD, 3));
        i.add(new TypeInsnNode(Opcodes.NEW, PARAMETER));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new InsnNode(Opcodes.ACONST_NULL));
        i.add(new InsnNode(Opcodes.ICONST_0));
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new VarInsnNode(Opcodes.ILOAD, 3));
        i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, PARAMETER, "<init>",
                "(Ljava/lang/String;ILjava/lang/reflect/Executable;I)V", false));
        i.add(new InsnNode(Opcodes.AASTORE));
        i.add(new IincInsnNode(3, 1));
        i.add(new JumpInsnNode(Opcodes.GOTO, loop));
        i.add(end);
        i.add(new VarInsnNode(Opcodes.ALOAD, 2));
        i.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 8;
        m.maxLocals = 4;
        return m;
    }
}
