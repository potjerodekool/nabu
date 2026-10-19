package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dynamic proxy classes for a native executable, generated at compile time
 * for the interface lists of the proxy configuration (as GraalVM
 * {@code native-image} does). Each proxy class extends {@code ProxyBase}
 * and implements its interfaces; every method boxes its arguments, calls
 * {@code h.invoke(this, method, args)} and unboxes the result, as a JDK proxy
 * does: unchecked exceptions and the declared checked ones pass, any other
 * checked exception is wrapped in an {@code UndeclaredThrowableException}.
 * {@code hashCode}, {@code equals} and {@code toString} go to the handler
 * too.
 *
 * <p>{@code ProxyClasses} creates them by interface list and calls the
 * interface methods on a target for {@code Method.invoke} (see
 * {@code ProxySupport}).
 */
public final class ProxyClassGenerator {

    /** A proxied method: its declaring class (internal name), name, descriptor and declared exceptions. */
    public record ProxyMethod(String owner, boolean ownerIsInterface, String name, String desc,
                              List<String> exceptions) {

        String key() {
            return owner + "#" + name + "#" + desc;
        }
    }

    /** The interfaces (internal names, in order) of one proxy class and their methods. */
    public record ProxySpec(List<String> interfaces, List<ProxyMethod> methods) {
    }

    static final String PROXY_CLASSES = RuntimeCalls.RUNTIME_PACKAGE + "ProxyClasses";

    private static final String PROXY_BASE = RuntimeCalls.RUNTIME_PACKAGE + "ProxyBase";
    private static final String PROXY_PREFIX = RuntimeCalls.RUNTIME_PACKAGE + "NabuProxy";
    private static final String HANDLER = "java/lang/reflect/InvocationHandler";
    private static final String HANDLER_DESC = "L" + HANDLER + ";";

    /** The methods of Object a proxy forwards to its handler. */
    public static final List<ProxyMethod> OBJECT_METHODS = List.of(
            new ProxyMethod("java/lang/Object", false, "hashCode", "()I", List.of()),
            new ProxyMethod("java/lang/Object", false, "equals", "(Ljava/lang/Object;)Z", List.of()),
            new ProxyMethod("java/lang/Object", false, "toString", "()Ljava/lang/String;", List.of()));

    private ProxyClassGenerator() {
    }

    /** Internal name → class: the proxy classes and {@code ProxyClasses}. */
    public static Map<String, ClassNode> generate(final List<ProxySpec> specs) {
        final Map<String, ClassNode> classes = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        final Map<String, ProxyMethod> dispatched = new LinkedHashMap<>();
        for (final ProxyMethod method : OBJECT_METHODS) {
            dispatched.put(method.key(), method);
        }
        for (int i = 0; i < specs.size(); i++) {
            final ProxySpec spec = specs.get(i);
            final String name = PROXY_PREFIX + i;
            classes.put(name, proxyClass(name, spec));
            names.add(name);
            keys.add(String.join(",", spec.interfaces().stream().map(n -> n.replace('/', '.')).toList()));
            for (final ProxyMethod method : spec.methods()) {
                dispatched.putIfAbsent(method.key(), method);
            }
        }
        classes.put(PROXY_CLASSES, proxyClasses(keys, names, List.copyOf(dispatched.values())));
        return classes;
    }

    private static ClassNode newClass(final String name, final String superName, final List<String> interfaces,
                                      final int access) {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = access | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = superName;
        node.interfaces = new ArrayList<>(interfaces);
        return node;
    }

    private static ClassNode proxyClass(final String name, final ProxySpec spec) {
        final ClassNode node = newClass(name, PROXY_BASE, spec.interfaces(), Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);

        final MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(" + HANDLER_DESC + ")V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, PROXY_BASE, "<init>",
                "(" + HANDLER_DESC + ")V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 2;
        init.maxLocals = 2;
        node.methods.add(init);

        final Set<String> seen = new LinkedHashSet<>();
        final List<ProxyMethod> methods = new ArrayList<>(OBJECT_METHODS);
        methods.addAll(spec.methods());
        for (final ProxyMethod method : methods) {
            if (seen.add(method.name() + method.desc())) {
                node.methods.add(forwardingMethod(method));
            }
        }
        return node;
    }

    /** {@code return (R) h.invoke(this, ProxySupport.method(Owner.class, name, desc), new Object[] {args})}. */
    private static MethodNode forwardingMethod(final ProxyMethod method) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, method.name(), method.desc(),
                null, method.exceptions().toArray(new String[0]));
        final InsnList code = node.instructions;
        final Type[] params = Type.getArgumentTypes(method.desc());
        final Type returnType = Type.getReturnType(method.desc());

        final LabelNode start = new LabelNode();
        final LabelNode end = new LabelNode();
        final LabelNode rethrow = new LabelNode();
        final LabelNode wrap = new LabelNode();

        code.add(start);
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new FieldInsnNode(Opcodes.GETFIELD, PROXY_BASE, "h", HANDLER_DESC));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(Type.getObjectType(method.owner())));
        code.add(new LdcInsnNode(method.name()));
        code.add(new LdcInsnNode(method.desc()));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeCalls.PROXY_SUPPORT, "method",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/reflect/Method;", false));
        int local = 1;
        if (params.length == 0) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            pushInt(code, params.length);
            code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            for (int i = 0; i < params.length; i++) {
                code.add(new InsnNode(Opcodes.DUP));
                pushInt(code, i);
                code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD), local));
                box(code, params[i]);
                code.add(new InsnNode(Opcodes.AASTORE));
                local += params[i].getSize();
            }
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HANDLER, "invoke",
                "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", true));
        code.add(end);
        if (returnType.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.POP));
            code.add(new InsnNode(Opcodes.RETURN));
        } else {
            unbox(code, returnType);
            code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        }

        code.add(rethrow);
        code.add(new InsnNode(Opcodes.ATHROW));

        code.add(wrap);
        code.add(new VarInsnNode(Opcodes.ASTORE, local));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/reflect/UndeclaredThrowableException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/reflect/UndeclaredThrowableException", "<init>",
                "(Ljava/lang/Throwable;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));

        node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, rethrow, "java/lang/RuntimeException"));
        node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, rethrow, "java/lang/Error"));
        for (final String exception : method.exceptions()) {
            node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, rethrow, exception));
        }
        node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, wrap, "java/lang/Throwable"));
        node.maxStack = 8;
        node.maxLocals = local + 1;
        return node;
    }

    private static ClassNode proxyClasses(final List<String> keys, final List<String> names,
                                          final List<ProxyMethod> dispatched) {
        final ClassNode node = newClass(PROXY_CLASSES, "java/lang/Object", List.of(),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);

        final MethodNode create = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(Ljava/lang/String;" + HANDLER_DESC + ")Ljava/lang/Object;", null, null);
        for (int i = 0; i < keys.size(); i++) {
            final LabelNode next = new LabelNode();
            stringEquals(create.instructions, 0, keys.get(i), next);
            create.instructions.add(new TypeInsnNode(Opcodes.NEW, names.get(i)));
            create.instructions.add(new InsnNode(Opcodes.DUP));
            create.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
            create.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, names.get(i), "<init>",
                    "(" + HANDLER_DESC + ")V", false));
            create.instructions.add(new InsnNode(Opcodes.ARETURN));
            create.instructions.add(next);
        }
        create.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
        create.instructions.add(new InsnNode(Opcodes.ARETURN));
        create.maxStack = 4;
        create.maxLocals = 2;
        node.methods.add(create);

        final MethodNode isProxyClass = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isProxyClass",
                "(Ljava/lang/String;)Z", null, null);
        for (final String name : names) {
            final LabelNode next = new LabelNode();
            stringEquals(isProxyClass.instructions, 0, name.replace('/', '.'), next);
            isProxyClass.instructions.add(new InsnNode(Opcodes.ICONST_1));
            isProxyClass.instructions.add(new InsnNode(Opcodes.IRETURN));
            isProxyClass.instructions.add(next);
        }
        isProxyClass.instructions.add(new InsnNode(Opcodes.ICONST_0));
        isProxyClass.instructions.add(new InsnNode(Opcodes.IRETURN));
        isProxyClass.maxStack = 2;
        isProxyClass.maxLocals = 1;
        node.methods.add(isProxyClass);

        node.methods.add(invoke(dispatched));
        return node;
    }

    /**
     * {@code invoke(String key, Object target, Object[] args)}: per method
     * {@code return box(((Owner) target).name(unbox(args[0]), ...))}, the
     * call itself wrapped as Method.invoke does.
     */
    private static MethodNode invoke(final List<ProxyMethod> methods) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke",
                "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null,
                new String[] {"java/lang/Exception"});
        final InsnList code = node.instructions;
        final LabelNode wrap = new LabelNode();
        for (final ProxyMethod method : methods) {
            final LabelNode next = new LabelNode();
            stringEquals(code, 0, method.key(), next);
            final Type[] params = Type.getArgumentTypes(method.desc());
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, method.owner()));
            for (int i = 0; i < params.length; i++) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                pushInt(code, i);
                code.add(new InsnNode(Opcodes.AALOAD));
                unbox(code, params[i]);
            }
            final LabelNode start = new LabelNode();
            final LabelNode end = new LabelNode();
            code.add(start);
            code.add(new MethodInsnNode(method.ownerIsInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL,
                    method.owner(), method.name(), method.desc(), method.ownerIsInterface()));
            code.add(end);
            final Type returnType = Type.getReturnType(method.desc());
            if (returnType.getSort() == Type.VOID) {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
            } else {
                box(code, returnType);
            }
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(next);
            node.tryCatchBlocks.add(new TryCatchBlockNode(start, end, wrap, "java/lang/Throwable"));
        }
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>",
                "(Ljava/lang/String;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));

        code.add(wrap);
        code.add(new VarInsnNode(Opcodes.ASTORE, 3));
        code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/reflect/InvocationTargetException"));
        code.add(new InsnNode(Opcodes.DUP));
        code.add(new VarInsnNode(Opcodes.ALOAD, 3));
        code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/reflect/InvocationTargetException", "<init>",
                "(Ljava/lang/Throwable;)V", false));
        code.add(new InsnNode(Opcodes.ATHROW));
        node.maxStack = 12;
        node.maxLocals = 4;
        return node;
    }

    /** {@code if (!local.equals(value)) goto next}. */
    private static void stringEquals(final InsnList code, final int local, final String value, final LabelNode next) {
        code.add(new VarInsnNode(Opcodes.ALOAD, local));
        code.add(new LdcInsnNode(value));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, next));
    }

    private static void pushInt(final InsnList code, final int value) {
        if (value <= 5) {
            code.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        }
    }

    private static String wrapper(final Type type) {
        return switch (type.getSort()) {
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
    }

    private static void box(final InsnList code, final Type type) {
        final String wrapper = wrapper(type);
        if (wrapper != null) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                    "(" + type.getDescriptor() + ")L" + wrapper + ";", false));
        }
    }

    /** Object on the stack → a value of {@code type}: a cast, or a cast and xxxValue() for a primitive. */
    private static void unbox(final InsnList code, final Type type) {
        final String wrapper = wrapper(type);
        if (wrapper != null) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper, type.getClassName() + "Value",
                    "()" + type.getDescriptor(), false));
        } else if (!"java/lang/Object".equals(type.getInternalName())) {
            code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getSort() == Type.ARRAY
                    ? type.getDescriptor() : type.getInternalName()));
        }
    }
}
