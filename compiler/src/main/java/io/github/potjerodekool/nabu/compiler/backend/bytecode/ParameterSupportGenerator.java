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
        node.methods.add(packageOf());
        node.methods.add(parametersOfExecutable());
        return node;
    }

    /** {@code static Parameter[] ofExecutable(Executable e)}: the constructor's or the method's parameters. */
    private static MethodNode parametersOfExecutable() {
        final MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ofExecutable",
                "(Ljava/lang/reflect/Executable;)[L" + PARAMETER + ";", null, null);
        final LabelNode method = new LabelNode();
        final var i = m.instructions;
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new TypeInsnNode(Opcodes.INSTANCEOF, "java/lang/reflect/Constructor"));
        i.add(new JumpInsnNode(Opcodes.IFEQ, method));
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/reflect/Constructor"));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PARAMETER_SUPPORT, "ofConstructor",
                "(Ljava/lang/reflect/Constructor;)[L" + PARAMETER + ";", false));
        i.add(new InsnNode(Opcodes.ARETURN));
        i.add(method);
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/reflect/Method"));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PARAMETER_SUPPORT, "ofMethod",
                "(Ljava/lang/reflect/Method;)[L" + PARAMETER + ";", false));
        i.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 2;
        m.maxLocals = 1;
        return m;
    }

    /**
     * {@code static Package packageOf(Class c)}: {@code Class.getPackage} of a native program, a Package with the name
     * of the class's package (the constructor is package-private, like Parameter's) and without module.
     */
    private static MethodNode packageOf() {
        final String pkg = "java/lang/Package";
        final MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "packageOf",
                "(Ljava/lang/Class;)L" + pkg + ";", null, null);
        final LabelNode nested = new LabelNode();
        final LabelNode made = new LabelNode();
        final var i = m.instructions;
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        i.add(new VarInsnNode(Opcodes.ASTORE, 1));
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, '.'));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "lastIndexOf", "(I)I", false));
        i.add(new VarInsnNode(Opcodes.ISTORE, 2));
        i.add(new VarInsnNode(Opcodes.ILOAD, 2));
        i.add(new JumpInsnNode(Opcodes.IFGE, nested));
        i.add(new org.objectweb.asm.tree.LdcInsnNode(""));
        i.add(new VarInsnNode(Opcodes.ASTORE, 3));
        i.add(new JumpInsnNode(Opcodes.GOTO, made));
        i.add(nested);
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new InsnNode(Opcodes.ICONST_0));
        i.add(new VarInsnNode(Opcodes.ILOAD, 2));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring", "(II)Ljava/lang/String;", false));
        i.add(new VarInsnNode(Opcodes.ASTORE, 3));
        i.add(made);
        i.add(new TypeInsnNode(Opcodes.NEW, pkg));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new VarInsnNode(Opcodes.ALOAD, 3));
        i.add(new InsnNode(Opcodes.ACONST_NULL));
        i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, pkg, "<init>", "(Ljava/lang/String;Ljava/lang/Module;)V", false));
        i.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 8;
        m.maxLocals = 4;
        return m;
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
        // the name the class file gave the parameter (MethodParameters), or null: then arg0, arg1, ...
        i.add(new VarInsnNode(Opcodes.ALOAD, 0));
        i.add(new VarInsnNode(Opcodes.ILOAD, 3));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeCalls.PROXY_SUPPORT, "parameterName",
                "(Ljava/lang/Object;I)Ljava/lang/String;", false));
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
