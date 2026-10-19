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

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The generated {@code ReflectionData} of a native executable: the
 * {@code Method} objects of the classes in the reflection configuration
 * ({@code methods}: what {@code getDeclaredMethods} and {@code getMethods}
 * return), their {@code Constructor} objects ({@code constructors}) and the
 * dispatcher behind {@code Method.invoke} and {@code Constructor.newInstance}
 * ({@code invoke}),
 * for those methods and for the methods of proxied interfaces. A native
 * executable cannot call a method by name; the dispatcher calls each known
 * method by a key "owner#name#descriptor".
 */
public final class ReflectionDataGenerator {

    static final String REFLECTION_DATA = RuntimeCalls.RUNTIME_PACKAGE + "ReflectionData";

    /** Bit in the modifiers handed to the runtime: the method is declared in an interface (Method.isDefault). */
    public static final int INTERFACE_OWNER = 0x40000000;

    /** Bit in the modifiers of a constructor: its class is abstract (newInstance throws InstantiationException). */
    public static final int ABSTRACT_OWNER = 0x20000000;

    /**
     * A method (or constructor, named {@code <init>}) known to reflection:
     * declaring class (internal name), name, descriptor, access flags,
     * declared exceptions (internal names) and generic signature (or null).
     */
    public record ReflectedMethod(String owner, boolean ownerIsInterface, String name, String desc, int modifiers,
                                  List<String> exceptions, String signature) {

        public ReflectedMethod(final String owner, final boolean ownerIsInterface, final String name,
                               final String desc, final int modifiers) {
            this(owner, ownerIsInterface, name, desc, modifiers, List.of(), null);
        }

        boolean isConstructor() {
            return "<init>".equals(name);
        }

        String key() {
            return owner + "#" + name + "#" + desc;
        }

        boolean isStatic() {
            return (modifiers & Opcodes.ACC_STATIC) != 0;
        }

        int runtimeModifiers() {
            return ownerIsInterface ? modifiers | INTERFACE_OWNER : modifiers;
        }
    }

    /** The methods of one class: those getDeclaredMethods and getMethods return, and its registered constructors. */
    public record ClassMethods(List<ReflectedMethod> declared, List<ReflectedMethod> publics,
                               List<ReflectedMethod> constructors) {
    }

    private ReflectionDataGenerator() {
    }

    /**
     * @param classes      binary class name → its methods.
     * @param alsoDispatch further methods {@code Method.invoke} must reach (proxied interfaces).
     */
    static ClassNode generate(final Map<String, ClassMethods> classes, final Collection<ReflectedMethod> alsoDispatch) {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = REFLECTION_DATA;
        node.superName = "java/lang/Object";

        final Map<String, ReflectedMethod> dispatched = new LinkedHashMap<>();
        for (final ReflectedMethod method : alsoDispatch) {
            dispatched.putIfAbsent(method.key(), method);
        }
        for (final ClassMethods methods : classes.values()) {
            for (final ReflectedMethod method : methods.declared()) {
                dispatched.putIfAbsent(method.key(), method);
            }
            for (final ReflectedMethod method : methods.publics()) {
                dispatched.putIfAbsent(method.key(), method);
            }
            for (final ReflectedMethod constructor : methods.constructors()) {
                dispatched.putIfAbsent(constructor.key(), constructor);
            }
        }
        node.methods.add(methods(classes));
        node.methods.add(constructors(classes));
        node.methods.add(invoke(List.copyOf(dispatched.values())));
        return node;
    }

    /** {@code Method[] methods(String className, boolean publicOnly)}, null for a class without data. */
    private static MethodNode methods(final Map<String, ClassMethods> classes) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "methods",
                "(Ljava/lang/String;Z)[Ljava/lang/reflect/Method;", null, null);
        final InsnList code = node.instructions;
        for (final var entry : classes.entrySet()) {
            final LabelNode next = new LabelNode();
            final LabelNode declared = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(entry.getKey()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, next));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new JumpInsnNode(Opcodes.IFEQ, declared));
            methodArray(code, entry.getValue().publics());
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(declared);
            methodArray(code, entry.getValue().declared());
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(next);
        }
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.maxStack = 16;
        node.maxLocals = 2;
        return node;
    }

    /** {@code Constructor[] constructors(String className)}, null for a class without data. */
    private static MethodNode constructors(final Map<String, ClassMethods> classes) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "constructors",
                "(Ljava/lang/String;)[Ljava/lang/reflect/Constructor;", null, null);
        final InsnList code = node.instructions;
        for (final var entry : classes.entrySet()) {
            if (entry.getValue().constructors().isEmpty()) {
                continue;
            }
            final LabelNode next = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(entry.getKey()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, next));
            executableArray(code, entry.getValue().constructors(), "java/lang/reflect/Constructor");
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(next);
        }
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.maxStack = 16;
        node.maxLocals = 1;
        return node;
    }

    private static void methodArray(final InsnList code, final List<ReflectedMethod> methods) {
        executableArray(code, methods, "java/lang/reflect/Method");
    }

    private static void executableArray(final InsnList code, final List<ReflectedMethod> methods,
                                        final String elementType) {
        pushInt(code, methods.size());
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, elementType));
        for (int i = 0; i < methods.size(); i++) {
            code.add(new InsnNode(Opcodes.DUP));
            pushInt(code, i);
            pushMethod(code, methods.get(i));
            code.add(new InsnNode(Opcodes.AASTORE));
        }
    }

    /**
     * {@code ProxySupport.method(Owner.class, name, descriptor, modifiers,
     * exceptions, signature)}: a Method, or a Constructor for {@code <init>}
     * (as an Object: the caller casts).
     */
    static void pushMethod(final InsnList code, final ReflectedMethod method) {
        code.add(new LdcInsnNode(Type.getObjectType(method.owner())));
        code.add(new LdcInsnNode(method.name()));
        code.add(new LdcInsnNode(method.desc()));
        code.add(new LdcInsnNode(method.runtimeModifiers()));
        if (method.exceptions().isEmpty()) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            code.add(new LdcInsnNode(String.join(",", method.exceptions())));
        }
        if (method.signature() == null) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            code.add(new LdcInsnNode(method.signature()));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RuntimeCalls.PROXY_SUPPORT, "method",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;ILjava/lang/String;Ljava/lang/String;)"
                        + "Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST,
                method.isConstructor() ? "java/lang/reflect/Constructor" : "java/lang/reflect/Method"));
    }

    /**
     * {@code invoke(String key, Object target, Object[] args)}: per method
     * {@code return box(((Owner) target).name(unbox(args[0]), ...))} (no
     * target for a static method), the call itself wrapped as Method.invoke
     * does.
     */
    private static MethodNode invoke(final List<ReflectedMethod> methods) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "invoke",
                "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", null,
                new String[] {"java/lang/Exception"});
        final InsnList code = node.instructions;
        final LabelNode wrap = new LabelNode();
        final List<TryCatchBlockNode> handlers = new ArrayList<>();
        for (final ReflectedMethod method : methods) {
            final LabelNode next = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(method.key()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, next));
            final Type[] params = Type.getArgumentTypes(method.desc());
            if (method.isConstructor() && (method.modifiers() & ABSTRACT_OWNER) != 0) {
                code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/InstantiationException"));
                code.add(new InsnNode(Opcodes.DUP));
                code.add(new LdcInsnNode(method.owner().replace('/', '.')));
                code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/InstantiationException", "<init>",
                        "(Ljava/lang/String;)V", false));
                code.add(new InsnNode(Opcodes.ATHROW));
                code.add(next);
                continue;
            }
            if (method.isConstructor()) {
                code.add(new TypeInsnNode(Opcodes.NEW, method.owner()));
                code.add(new InsnNode(Opcodes.DUP));
            } else if (!method.isStatic()) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 1));
                code.add(new TypeInsnNode(Opcodes.CHECKCAST, method.owner()));
            }
            for (int i = 0; i < params.length; i++) {
                code.add(new VarInsnNode(Opcodes.ALOAD, 2));
                pushInt(code, i);
                code.add(new InsnNode(Opcodes.AALOAD));
                unbox(code, params[i]);
            }
            final LabelNode start = new LabelNode();
            final LabelNode end = new LabelNode();
            code.add(start);
            final int opcode = method.isConstructor() ? Opcodes.INVOKESPECIAL
                    : method.isStatic() ? Opcodes.INVOKESTATIC
                    : method.ownerIsInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
            code.add(new MethodInsnNode(opcode, method.owner(), method.name(), method.desc(), method.ownerIsInterface()));
            code.add(end);
            final Type returnType = Type.getReturnType(method.desc());
            if (method.isConstructor()) {
                // The new object (NEW, DUP) is left on the stack.
            } else if (returnType.getSort() == Type.VOID) {
                code.add(new InsnNode(Opcodes.ACONST_NULL));
            } else {
                box(code, returnType);
            }
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(next);
            handlers.add(new TryCatchBlockNode(start, end, wrap, "java/lang/Throwable"));
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
        node.tryCatchBlocks.addAll(handlers);
        node.maxStack = 12;
        node.maxLocals = 4;
        return node;
    }

    static void pushInt(final InsnList code, final int value) {
        if (value <= 5) {
            code.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value <= Byte.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value <= Short.MAX_VALUE) {
            code.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            code.add(new LdcInsnNode(value));
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

    static void box(final InsnList code, final Type type) {
        final String wrapper = wrapper(type);
        if (wrapper != null) {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper, "valueOf",
                    "(" + type.getDescriptor() + ")L" + wrapper + ";", false));
        }
    }

    /** Object on the stack → a value of {@code type}: a cast, or a cast and xxxValue() for a primitive. */
    static void unbox(final InsnList code, final Type type) {
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
