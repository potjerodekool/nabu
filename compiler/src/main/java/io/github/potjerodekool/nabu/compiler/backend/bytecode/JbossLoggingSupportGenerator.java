package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * jboss-logging's {@code Logger.getMessageLogger(MethodHandles.lookup(), Interface.class, category)}, which the
 * generated code of Hibernate and others calls in its static initializers, for native programs. The original finds the
 * generated {@code Interface_$logger} class and its constructor through {@code MethodHandles.Lookup}; here that is
 * {@code Class.forName} and {@code Constructor.newInstance} (the hints register the constructor, as the Lookup
 * calls did under the tracing agent). The class is generated because the compiler does not depend on jboss-logging.
 */
final class JbossLoggingSupportGenerator {

    static final String SUPPORT = RuntimeCalls.RUNTIME_PACKAGE + "JbossLoggingSupport";

    private static final String LOGGER = "org/jboss/logging/Logger";

    private JbossLoggingSupportGenerator() {
    }

    static ClassNode generate() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = SUPPORT;
        node.superName = "java/lang/Object";
        node.methods.add(messageLogger("getMessageLogger",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;", false));
        node.methods.add(messageLogger("getMessageLogger",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/String;Ljava/util/Locale;)"
                        + "Ljava/lang/Object;", true));
        node.methods.add(messageBundle("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;)Ljava/lang/Object;", false));
        node.methods.add(messageBundle("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/util/Locale;)Ljava/lang/Object;", true));
        node.methods.add(nullLookup());
        return node;
    }

    /** {@code static Object getMessageLogger(Lookup, Class type, String category[, Locale])}. */
    private static MethodNode messageLogger(final String name, final String desc, final boolean withLocale) {
        final MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null, null);
        final var i = m.instructions;
        // Logger log = Logger.getLogger(category);
        i.add(new VarInsnNode(Opcodes.ALOAD, 2));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LOGGER, "getLogger",
                "(Ljava/lang/String;)L" + LOGGER + ";", false));
        i.add(new VarInsnNode(Opcodes.ASTORE, withLocale ? 4 : 3));
        final int log = withLocale ? 4 : 3;
        // StringBuilder name = new StringBuilder().append(type.getName()).append("_$logger");
        i.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        i.add(new LdcInsnNode("_$logger"));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        // Class impl = Class.forName(name, true, type.getClassLoader());
        i.add(new InsnNode(Opcodes.ICONST_1));
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        // Constructor c = impl.getConstructor(new Class[] { Logger.class });
        i.add(new InsnNode(Opcodes.ICONST_1));
        i.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new InsnNode(Opcodes.ICONST_0));
        i.add(new LdcInsnNode(org.objectweb.asm.Type.getObjectType(LOGGER)));
        i.add(new InsnNode(Opcodes.AASTORE));
        // Not Class.getConstructor: the redirects of the reflection calls do not apply to classes of this package.
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeCalls.REFLECT_SUPPORT, "getConstructor",
                "(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/reflect/Constructor;", false));
        // return c.newInstance(new Object[] { log });
        i.add(new InsnNode(Opcodes.ICONST_1));
        i.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new InsnNode(Opcodes.ICONST_0));
        i.add(new VarInsnNode(Opcodes.ALOAD, log));
        i.add(new InsnNode(Opcodes.AASTORE));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeCalls.REFLECT_SUPPORT, "newInstance",
                "(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;)Ljava/lang/Object;", false));
        i.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 8;
        m.maxLocals = withLocale ? 5 : 4;
        return m;
    }

    /** {@code static Object getBundle(Lookup, Class type[, Locale])}: the {@code Type_$bundle} class, read from its
     * INSTANCE field (the original finds both through the Lookup). Only the default locale. */
    private static MethodNode messageBundle(final String desc, final boolean withLocale) {
        final MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "getBundle", desc, null, null);
        final var i = m.instructions;
        i.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        i.add(new InsnNode(Opcodes.DUP));
        i.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getName", "()Ljava/lang/String;", false));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        i.add(new LdcInsnNode("$bundle"));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        i.add(new InsnNode(Opcodes.ICONST_1));
        i.add(new VarInsnNode(Opcodes.ALOAD, 1));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
                "()Ljava/lang/ClassLoader;", false));
        i.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
                "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        // return impl.getField("INSTANCE").get(null);
        i.add(new LdcInsnNode("INSTANCE"));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getField",
                "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false));
        i.add(new InsnNode(Opcodes.ACONST_NULL));
        i.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Field", "get",
                "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        i.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 4;
        m.maxLocals = withLocale ? 3 : 2;
        return m;
    }

    /** {@code static MethodHandles.Lookup nullLookup()}: there are no method handles; null is what is passed on. */
    private static MethodNode nullLookup() {
        final MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "nullLookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", null, null);
        m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        m.instructions.add(new InsnNode(Opcodes.ARETURN));
        m.maxStack = 1;
        m.maxLocals = 0;
        return m;
    }
}
