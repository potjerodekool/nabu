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
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

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
     * The entries of a dispatcher are divided over methods by the hash code of their key: the code of a method of
     * the JVM is at most 64 KB, and a program with Spring has thousands of reflected methods. A bucket holds this
     * many classes (methods, constructors) on average.
     */
    private static final int CLASSES_PER_METHOD = 4;

    /** The same for the calls of {@code invoke}: methods and constructors per generated method on average. */
    private static final int CALLS_PER_METHOD = 50;

    private static final String METHODS_DESC = "(Ljava/lang/String;Z)[Ljava/lang/reflect/Method;";
    private static final String CONSTRUCTORS_DESC = "(Ljava/lang/String;)[Ljava/lang/reflect/Constructor;";
    private static final String INVOKE_DESC =
            "(Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";

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
                               List<ReflectedMethod> constructors, java.util.Set<String> queryOnly) {

        public ClassMethods(final List<ReflectedMethod> declared, final List<ReflectedMethod> publics,
                            final List<ReflectedMethod> constructors) {
            this(declared, publics, constructors, java.util.Set.of());
        }
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
            // A method that is only registered for queries (a type that the hints name without members) is
            // returned by getDeclaredMethods but has no call in the dispatcher.
            for (final ReflectedMethod method : methods.declared()) {
                if (!methods.queryOnly().contains(method.key())) {
                    dispatched.putIfAbsent(method.key(), method);
                }
            }
            for (final ReflectedMethod method : methods.publics()) {
                if (!methods.queryOnly().contains(method.key())) {
                    dispatched.putIfAbsent(method.key(), method);
                }
            }
            for (final ReflectedMethod constructor : methods.constructors()) {
                if (!methods.queryOnly().contains(constructor.key())) {
                    dispatched.putIfAbsent(constructor.key(), constructor);
                }
            }
        }
        if (Boolean.getBoolean("nabu.closureStats")) {
            long declared = 0, publics = 0, constructors = 0, bytes = 0;
            final java.util.Set<String> distinct = new java.util.HashSet<>();
            for (final ClassMethods methods : classes.values()) {
                declared += methods.declared().size();
                publics += methods.publics().size();
                constructors += methods.constructors().size();
                for (final var list : List.of(methods.declared(), methods.publics(), methods.constructors())) {
                    for (final ReflectedMethod m : list) {
                        distinct.add(m.key());
                        bytes += m.owner().length() + m.name().length() + m.desc().length()
                                + (m.signature() != null ? m.signature().length() : 0);
                    }
                }
            }
            System.err.println("[closureStats] reflection data: " + classes.size() + " classes, " + declared
                    + " declared + " + publics + " public + " + constructors + " constructor entries (" + distinct.size()
                    + " distinct), " + dispatched.size() + " dispatched, " + bytes / 1024 + " KB of strings");
        }
        // A method that is in many lists (getMethods repeats the inherited public methods of every class) is made by one
        // case of get(id), not by code in each list.
        final Map<ReflectedMethod, Integer> ids = new LinkedHashMap<>();
        for (final ClassMethods methods : classes.values()) {
            for (final var list : List.of(methods.declared(), methods.publics(), methods.constructors())) {
                for (final ReflectedMethod method : list) {
                    ids.putIfAbsent(method, ids.size());
                }
            }
        }
        node.methods.addAll(getters(List.copyOf(ids.keySet())));
        node.methods.addAll(methods(classes, ids));
        node.methods.addAll(constructors(classes, ids));
        node.methods.addAll(invoke(List.copyOf(dispatched.values())));
        return node;
    }

    /** {@code Method[] methods(String className, boolean publicOnly)}, null for a class without data. */
    private static List<MethodNode> methods(final Map<String, ClassMethods> classes,
                                            final Map<ReflectedMethod, Integer> ids) {
        final var buckets = bucket(classes.entrySet(), Map.Entry::getKey, CLASSES_PER_METHOD);
        final List<MethodNode> result = new ArrayList<>();
        final List<String> chunkNames = new ArrayList<>();

        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).isEmpty()) {
                chunkNames.add(null);
                continue;
            }
            final var name = "methods$" + i;
            chunkNames.add(name);
            result.add(methodsChunk(name, buckets.get(i), ids));
        }

        result.add(dispatcher("methods", METHODS_DESC, null, chunkNames, code -> {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
        }));
        return result;
    }

    private static MethodNode methodsChunk(final String name,
                                           final List<Map.Entry<String, ClassMethods>> classes,
                                           final Map<ReflectedMethod, Integer> ids) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                METHODS_DESC, null, null);
        final InsnList code = node.instructions;
        for (final var entry : classes) {
            final LabelNode next = new LabelNode();
            final LabelNode declared = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(entry.getKey()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, next));
            code.add(new VarInsnNode(Opcodes.ILOAD, 1));
            code.add(new JumpInsnNode(Opcodes.IFEQ, declared));
            methodArray(code, entry.getValue().publics(), ids);
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(declared);
            methodArray(code, entry.getValue().declared(), ids);
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
    private static List<MethodNode> constructors(final Map<String, ClassMethods> classes,
                                                 final Map<ReflectedMethod, Integer> ids) {
        final var withConstructors = classes.entrySet().stream()
                .filter(entry -> !entry.getValue().constructors().isEmpty())
                .toList();
        final var buckets = bucket(withConstructors, Map.Entry::getKey, CLASSES_PER_METHOD);
        final List<MethodNode> result = new ArrayList<>();
        final List<String> chunkNames = new ArrayList<>();

        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).isEmpty()) {
                chunkNames.add(null);
                continue;
            }
            final var name = "constructors$" + i;
            chunkNames.add(name);
            result.add(constructorsChunk(name, buckets.get(i), ids));
        }

        result.add(dispatcher("constructors", CONSTRUCTORS_DESC, null, chunkNames, code -> {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
        }));
        return result;
    }

    private static MethodNode constructorsChunk(final String name,
                                                final List<Map.Entry<String, ClassMethods>> classes,
                                                final Map<ReflectedMethod, Integer> ids) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                CONSTRUCTORS_DESC, null, null);
        final InsnList code = node.instructions;
        for (final var entry : classes) {
            final LabelNode next = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(entry.getKey()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, next));
            executableArray(code, entry.getValue().constructors(), "java/lang/reflect/Constructor", ids);
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(next);
        }
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.maxStack = 16;
        node.maxLocals = 1;
        return node;
    }

    /** Divides the entries over a power of two buckets, by the hash code of their key. */
    private static <T> List<List<T>> bucket(final Collection<T> entries,
                                            final Function<T, String> key,
                                            final int perBucket) {
        int buckets = 1;
        while ((long) buckets * perBucket < entries.size()) {
            buckets <<= 1;
        }

        final List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < buckets; i++) {
            result.add(new ArrayList<>());
        }
        for (final T entry : entries) {
            result.get(key.apply(entry).hashCode() & (buckets - 1)).add(entry);
        }
        return result;
    }

    /**
     * The method that used to be one big method: it picks the method for the key (its first argument) with a table
     * switch on the hash code, and passes all its arguments on. {@code chunkNames} has a power of two entries, and
     * a null for a bucket without entries. {@code missing} is the code for a key that is in no bucket.
     */
    private static MethodNode dispatcher(final String name,
                                         final String desc,
                                         final String[] exceptions,
                                         final List<String> chunkNames,
                                         final Consumer<InsnList> missing) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, desc, null,
                exceptions);
        final InsnList code = node.instructions;
        final Type[] args = Type.getArgumentTypes(desc);
        final int mask = chunkNames.size() - 1;
        final LabelNode other = new LabelNode();
        final LabelNode[] labels = new LabelNode[chunkNames.size()];

        for (int i = 0; i < labels.length; i++) {
            labels[i] = chunkNames.get(i) == null ? other : new LabelNode();
        }

        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        pushInt(code, mask);
        code.add(new InsnNode(Opcodes.IAND));
        code.add(new TableSwitchInsnNode(0, mask, other, labels));

        int locals = 0;
        for (final Type arg : args) {
            locals += arg.getSize();
        }

        for (int i = 0; i < labels.length; i++) {
            if (chunkNames.get(i) == null) {
                continue;
            }
            code.add(labels[i]);
            int slot = 0;
            for (final Type arg : args) {
                code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
                slot += arg.getSize();
            }
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REFLECTION_DATA, chunkNames.get(i), desc, false));
            code.add(new InsnNode(Opcodes.ARETURN));
        }

        code.add(other);
        missing.accept(code);
        node.maxStack = Math.max(4, locals + 1);
        node.maxLocals = locals;
        return node;
    }

    private static void methodArray(final InsnList code, final List<ReflectedMethod> methods,
                                    final Map<ReflectedMethod, Integer> ids) {
        executableArray(code, methods, "java/lang/reflect/Method", ids);
    }

    private static void executableArray(final InsnList code, final List<ReflectedMethod> methods,
                                        final String elementType, final Map<ReflectedMethod, Integer> ids) {
        if (ids.size() < 0xD000) {
            // the ids of the list as the characters of a string (id + 1: no NUL): one call instead of code per entry
            final StringBuilder chars = new StringBuilder();
            for (final ReflectedMethod method : methods) {
                chars.append((char) (ids.get(method) + 1));
            }
            final boolean constructors = elementType.equals("java/lang/reflect/Constructor");
            code.add(new LdcInsnNode(chars.toString()));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REFLECTION_DATA, constructors ? "constructorList" : "methodList",
                    "(Ljava/lang/String;)[L" + elementType + ";", false));
            return;
        }
        pushInt(code, methods.size());
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, elementType));
        for (int i = 0; i < methods.size(); i++) {
            code.add(new InsnNode(Opcodes.DUP));
            pushInt(code, i);
            pushInt(code, ids.get(methods.get(i)));
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REFLECTION_DATA, "get", "(I)Ljava/lang/Object;", false));
            code.add(new TypeInsnNode(Opcodes.CHECKCAST,
                    methods.get(i).isConstructor() ? "java/lang/reflect/Constructor" : "java/lang/reflect/Method"));
            code.add(new InsnNode(Opcodes.AASTORE));
        }
    }

    /** Entries of one {@code get$k} method. */
    private static final int GETS_PER_METHOD = 200;

    /**
     * {@code Object get(int id)}: a new Method (or Constructor) object for the method with that id, as
     * {@link #pushMethod} makes it. A switch on the id over chunks, so that no method of the class gets too big.
     */
    private static List<MethodNode> getters(final List<ReflectedMethod> methods) {
        final List<MethodNode> result = new ArrayList<>();
        final int chunks = (methods.size() + GETS_PER_METHOD - 1) / GETS_PER_METHOD;
        for (int k = 0; k < chunks; k++) {
            final int from = k * GETS_PER_METHOD;
            final int to = Math.min(methods.size(), from + GETS_PER_METHOD);
            final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get$" + k,
                    "(I)Ljava/lang/Object;", null, null);
            final InsnList code = node.instructions;
            final LabelNode other = new LabelNode();
            final LabelNode[] labels = new LabelNode[to - from];
            for (int i = 0; i < labels.length; i++) {
                labels[i] = new LabelNode();
            }
            code.add(new VarInsnNode(Opcodes.ILOAD, 0));
            pushInt(code, from);
            code.add(new InsnNode(Opcodes.ISUB));
            code.add(new TableSwitchInsnNode(0, labels.length - 1, other, labels));
            for (int i = 0; i < labels.length; i++) {
                code.add(labels[i]);
                pushMethodObject(code, methods.get(from + i));
                code.add(new InsnNode(Opcodes.ARETURN));
            }
            code.add(other);
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
            node.maxStack = 8;
            node.maxLocals = 1;
            result.add(node);
        }
        final MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get",
                "(I)Ljava/lang/Object;", null, null);
        final InsnList code = get.instructions;
        if (chunks == 0) {
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
        } else {
            final LabelNode other = new LabelNode();
            final LabelNode[] labels = new LabelNode[chunks];
            for (int k = 0; k < chunks; k++) {
                labels[k] = new LabelNode();
            }
            code.add(new VarInsnNode(Opcodes.ILOAD, 0));
            pushInt(code, GETS_PER_METHOD);
            code.add(new InsnNode(Opcodes.IDIV));
            code.add(new TableSwitchInsnNode(0, chunks - 1, other, labels));
            for (int k = 0; k < chunks; k++) {
                code.add(labels[k]);
                code.add(new VarInsnNode(Opcodes.ILOAD, 0));
                code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REFLECTION_DATA, "get$" + k,
                        "(I)Ljava/lang/Object;", false));
                code.add(new InsnNode(Opcodes.ARETURN));
            }
            code.add(other);
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
        }
        get.maxStack = 4;
        get.maxLocals = 1;
        result.add(get);
        result.add(listBuilder("methodList", "java/lang/reflect/Method"));
        result.add(listBuilder("constructorList", "java/lang/reflect/Constructor"));
        return result;
    }

    /**
     * {@code Element[] name(String ids)}: an array with get(c - 1) for each character c of the string.
     */
    private static MethodNode listBuilder(final String name, final String elementType) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                "(Ljava/lang/String;)[L" + elementType + ";", null, null);
        final InsnList code = node.instructions;
        final LabelNode loop = new LabelNode();
        final LabelNode done = new LabelNode();
        // a = new Element[ids.length()]
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        code.add(new TypeInsnNode(Opcodes.ANEWARRAY, elementType));
        code.add(new VarInsnNode(Opcodes.ASTORE, 1));
        code.add(new InsnNode(Opcodes.ICONST_0));
        code.add(new VarInsnNode(Opcodes.ISTORE, 2));
        code.add(loop);
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARRAYLENGTH));
        code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        // a[i] = (Element) get(ids.charAt(i) - 1)
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new VarInsnNode(Opcodes.ALOAD, 0));
        code.add(new VarInsnNode(Opcodes.ILOAD, 2));
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
        code.add(new InsnNode(Opcodes.ICONST_1));
        code.add(new InsnNode(Opcodes.ISUB));
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REFLECTION_DATA, "get", "(I)Ljava/lang/Object;", false));
        code.add(new TypeInsnNode(Opcodes.CHECKCAST, elementType));
        code.add(new InsnNode(Opcodes.AASTORE));
        code.add(new IincInsnNode(2, 1));
        code.add(new JumpInsnNode(Opcodes.GOTO, loop));
        code.add(done);
        code.add(new VarInsnNode(Opcodes.ALOAD, 1));
        code.add(new InsnNode(Opcodes.ARETURN));
        node.maxStack = 5;
        node.maxLocals = 3;
        return node;
    }

    /**
     * {@code ProxySupport.method(Owner.class, name, descriptor, modifiers,
     * exceptions, signature)}: a Method, or a Constructor for {@code <init>}
     * (as an Object: the caller casts).
     */
    static void pushMethod(final InsnList code, final ReflectedMethod method) {
        pushMethodObject(code, method);
        code.add(new TypeInsnNode(Opcodes.CHECKCAST,
                method.isConstructor() ? "java/lang/reflect/Constructor" : "java/lang/reflect/Method"));
    }

    /** As {@link #pushMethod}, without the cast. */
    private static void pushMethodObject(final InsnList code, final ReflectedMethod method) {
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
    }

    /**
     * {@code invoke(String key, Object target, Object[] args)}: per method
     * {@code return box(((Owner) target).name(unbox(args[0]), ...))} (no
     * target for a static method), the call itself wrapped as Method.invoke
     * does.
     */
    private static List<MethodNode> invoke(final List<ReflectedMethod> methods) {
        final var buckets = bucket(methods, ReflectedMethod::key, CALLS_PER_METHOD);
        final List<MethodNode> result = new ArrayList<>();
        final List<String> chunkNames = new ArrayList<>();

        for (int i = 0; i < buckets.size(); i++) {
            if (buckets.get(i).isEmpty()) {
                chunkNames.add(null);
                continue;
            }
            final var name = "invoke$" + i;
            chunkNames.add(name);
            result.add(invokeChunk(name, buckets.get(i)));
        }

        // A key that is in no bucket gets the same exception as a key that is in its bucket but not there.
        result.add(dispatcher("invoke", INVOKE_DESC, new String[] {"java/lang/Exception"}, chunkNames, code -> {
            code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalArgumentException"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalArgumentException", "<init>",
                    "(Ljava/lang/String;)V", false));
            code.add(new InsnNode(Opcodes.ATHROW));
        }));
        return result;
    }

    private static MethodNode invokeChunk(final String name, final List<ReflectedMethod> methods) {
        final MethodNode node = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name,
                INVOKE_DESC, null, new String[] {"java/lang/Exception"});
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
        // The receiver (NEW and DUP for a constructor), the arguments so far, and for an argument: the array, the
        // index and the element; the result of a call is at most two slots. This was a fixed 12, which a method
        // with many parameters overflowed.
        int maxStack = 8;
        for (final ReflectedMethod method : methods) {
            final int receiver = method.isConstructor() ? 2 : method.isStatic() ? 0 : 1;
            int width = 0;
            for (final Type parameter : Type.getArgumentTypes(method.desc())) {
                width += parameter.getSize();
            }
            maxStack = Math.max(maxStack, receiver + width + 4);
        }
        node.maxStack = maxStack;
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
