package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import io.github.potjerodekool.nabu.compiler.backend.bytecode.ReflectionDataGenerator.ReflectedMethod;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

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
 * <p>{@code ProxyClasses} creates them by interface list; {@code Method.invoke}
 * on their methods goes through the dispatcher of {@link ReflectionDataGenerator}.
 */
public final class ProxyClassGenerator {

    /** A proxied method: its declaring class (internal name), name, descriptor, declared exceptions and access flags. */
    public record ProxyMethod(String owner, boolean ownerIsInterface, String name, String desc,
                              List<String> exceptions, int modifiers) {

        ReflectedMethod reflected() {
            return new ReflectedMethod(owner, ownerIsInterface, name, desc, modifiers, exceptions, null);
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
            new ProxyMethod("java/lang/Object", false, "hashCode", "()I", List.of(), Opcodes.ACC_PUBLIC),
            new ProxyMethod("java/lang/Object", false, "equals", "(Ljava/lang/Object;)Z", List.of(),
                    Opcodes.ACC_PUBLIC),
            new ProxyMethod("java/lang/Object", false, "toString", "()Ljava/lang/String;", List.of(),
                    Opcodes.ACC_PUBLIC));

    private ProxyClassGenerator() {
    }

    /** Internal name → class: the proxy classes and {@code ProxyClasses}. */
    public static Map<String, ClassNode> generate(final List<ProxySpec> specs) {
        final Map<String, ClassNode> classes = new LinkedHashMap<>();
        final List<String> keys = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            final ProxySpec spec = specs.get(i);
            final String name = PROXY_PREFIX + i;
            classes.put(name, proxyClass(name, spec));
            names.add(name);
            keys.add(String.join(",", spec.interfaces().stream().map(n -> n.replace('/', '.')).toList()));
        }
        classes.put(PROXY_CLASSES, proxyClasses(keys, names));
        return classes;
    }

    /** The methods a handler can get, which Method.invoke must be able to call. */
    public static List<ReflectedMethod> dispatchedMethods(final List<ProxySpec> specs) {
        final Map<String, ReflectedMethod> methods = new LinkedHashMap<>();
        for (final ProxyMethod method : OBJECT_METHODS) {
            methods.putIfAbsent(method.reflected().key(), method.reflected());
        }
        for (final ProxySpec spec : specs) {
            for (final ProxyMethod method : spec.methods()) {
                methods.putIfAbsent(method.reflected().key(), method.reflected());
            }
        }
        return List.copyOf(methods.values());
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

    /** {@code return (R) h.invoke(this, ProxySupport.method(Owner.class, name, desc, mods), new Object[] {args})}. */
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
        ReflectionDataGenerator.pushMethod(code, method.reflected());
        int local = 1;
        if (params.length == 0) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
        } else {
            ReflectionDataGenerator.pushInt(code, params.length);
            code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            for (int i = 0; i < params.length; i++) {
                code.add(new InsnNode(Opcodes.DUP));
                ReflectionDataGenerator.pushInt(code, i);
                code.add(new VarInsnNode(params[i].getOpcode(Opcodes.ILOAD), local));
                ReflectionDataGenerator.box(code, params[i]);
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
            ReflectionDataGenerator.unbox(code, returnType);
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
        node.maxStack = 16;
        node.maxLocals = local + 1;
        return node;
    }

    private static ClassNode proxyClasses(final List<String> keys, final List<String> names) {
        final ClassNode node = newClass(PROXY_CLASSES, "java/lang/Object", List.of(),
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL);

        final MethodNode create = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "create",
                "(Ljava/lang/String;" + HANDLER_DESC + ")Ljava/lang/Object;", null, null);
        for (int i = 0; i < keys.size(); i++) {
            final LabelNode next = new LabelNode();
            stringEquals(create.instructions, keys.get(i), next);
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
            stringEquals(isProxyClass.instructions, name.replace('/', '.'), next);
            isProxyClass.instructions.add(new InsnNode(Opcodes.ICONST_1));
            isProxyClass.instructions.add(new InsnNode(Opcodes.IRETURN));
            isProxyClass.instructions.add(next);
        }
        isProxyClass.instructions.add(new InsnNode(Opcodes.ICONST_0));
        isProxyClass.instructions.add(new InsnNode(Opcodes.IRETURN));
        isProxyClass.maxStack = 2;
        isProxyClass.maxLocals = 1;
        node.methods.add(isProxyClass);
        return node;
    }

    /** {@code if (!arg0.equals(value)) goto next}. */
    private static void stringEquals(final InsnList code, final String value, final LabelNode next) {
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new LdcInsnNode(value));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                "(Ljava/lang/Object;)Z", false));
        code.add(new JumpInsnNode(Opcodes.IFEQ, next));
    }
}
