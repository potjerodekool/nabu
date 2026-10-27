package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK classes that reach their fields through {@code VarHandle}s ({@code ConcurrentLinkedQueue},
 * {@code ConcurrentLinkedDeque}, ...) cannot run in a native executable: the handles come from
 * {@code MethodHandles.lookup().findVarHandle(...)} and are invoked signature-polymorphically. A native program
 * has one thread, so every access is a plain field access: the {@code VarHandle} calls on an instance field become
 * calls of synthetic static methods in the same class, which read and write the field directly
 * (compare-and-set is "compare, then set"); handles of array elements ({@code arrayElementVarHandle}) likewise.
 * Handles of static fields are left alone.
 */
final class FieldVarHandleRewriter {

    /** The field a handle stands for, or for an array handle ({@link #ARRAY}) the descriptor of its elements. */
    private record Handle(String owner, String name, String desc) {
        boolean isArray() {
            return ARRAY.equals(owner);
        }
    }

    private static final String ARRAY = "[]";

    private record Site(MethodNode method, MethodInsnNode call, FieldInsnNode handleLoad, Handle handle) {
    }

    /**
     * The handles of the classes rewritten so far, by class: a nested class (ConcurrentLinkedQueue$Node) uses the
     * handles of its outer class, which it can only find here.
     */
    private static final Map<String, Map<String, Handle>> REGISTRY = new java.util.concurrent.ConcurrentHashMap<>();

    private FieldVarHandleRewriter() {
    }

    /** @param loader loads (and so rewrites) another class of the same nest, by internal name */
    static void rewrite(final ClassNode classNode, final java.util.function.Function<String, ClassNode> loader) {
        final Map<String, Handle> own = new HashMap<>();
        for (final MethodNode method : classNode.methods) {
            if ("<clinit>".equals(method.name)) {
                findHandles(classNode.name, method, own);
            }
        }
        if (!own.isEmpty()) {
            REGISTRY.put(classNode.name, own);
        }
        final String top = classNode.name.contains("$")
                ? classNode.name.substring(0, classNode.name.indexOf('$')) : classNode.name;
        final List<Site> sites = new ArrayList<>();
        for (final MethodNode method : new ArrayList<>(classNode.methods)) {
            if (!"<clinit>".equals(method.name) && method.instructions != null) {
                collectSites(classNode, method, top, loader, sites);
            }
        }
        if (sites.isEmpty() && own.isEmpty()) {
            return;
        }
        final Map<String, String> helpers = new HashMap<>();
        for (final Site site : sites) {
            final String key = site.handleLoad().owner + "." + site.handleLoad().name + "#" + site.call().name + "#"
                    + site.call().desc;
            String helper = helpers.get(key);
            if (helper == null) {
                helper = "$vh$" + site.handleLoad().name + "$" + site.call().name + "$" + helpers.size();
                if (isRuntimeObject(site.handle())) {
                    final String state = stateField(site.handle());
                    if (classNode.fields.stream().noneMatch(f -> f.name.equals(state))) {
                        classNode.fields.add(new org.objectweb.asm.tree.FieldNode(
                                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                                state, site.handle().desc(), null, null));
                    }
                }
                final MethodNode generated = generate(helper, classNode.name, site.call(), site.handle());
                if (generated == null) {
                    continue;
                }
                classNode.methods.add(generated);
                helpers.put(key, helper);
            }
            site.method().instructions.set(site.handleLoad(), new InsnNode(Opcodes.ACONST_NULL));
            site.method().instructions.set(site.call(), new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name,
                    helper, "(Ljava/lang/Object;" + site.call().desc.substring(1), false));
        }
        if (own.isEmpty()) {
            return;
        }
        // the handles themselves: no lookup, nothing to create
        for (final MethodNode method : classNode.methods) {
            if (!"<clinit>".equals(method.name)) {
                continue;
            }
            for (final AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof MethodInsnNode call && "findVarHandle".equals(call.name)
                        && ("java/lang/invoke/MethodHandles$Lookup".equals(call.owner) || isMhUtil(call))) {
                    // the lookup (for a Lookup method call) and the arguments
                    final int popped = Type.getArgumentTypes(call.desc).length + (isMhUtil(call) ? 0 : 1);
                    for (int i = 0; i < popped; i++) {
                        method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                    }
                    method.instructions.set(insn, new InsnNode(Opcodes.ACONST_NULL));
                } else if (insn instanceof MethodInsnNode call && "java/lang/invoke/MethodHandles".equals(call.owner)
                        && "lookup".equals(call.name)) {
                    method.instructions.set(insn, new InsnNode(Opcodes.ACONST_NULL));
                } else if (insn instanceof MethodInsnNode call && isArrayHandle(call)) {
                    method.instructions.insertBefore(insn, new InsnNode(Opcodes.POP));
                    method.instructions.set(insn, new InsnNode(Opcodes.ACONST_NULL));
                }
            }
            method.maxStack = Math.max(method.maxStack, 4);
        }
    }

    /**
     * Reads {@code LDC owner.class; LDC "name"; LDC type.class; INVOKEVIRTUAL Lookup.findVarHandle; PUTSTATIC f}
     * and the newer {@code MhUtil.findVarHandle(lookup, "name", type.class)}, whose owner is the class itself.
     */
    private static void findHandles(final String className, final MethodNode clinit,
                                    final Map<String, Handle> handles) {
        for (final AbstractInsnNode insn : clinit.instructions.toArray()) {
            if (insn instanceof MethodInsnNode arrayCall && isArrayHandle(arrayCall)) {
                final AbstractInsnNode arrayType = constant(previous(arrayCall));
                final AbstractInsnNode arrayStore = nextReal(arrayCall);
                if (arrayType instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                        && type.getSort() == Type.ARRAY
                        && arrayStore instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC) {
                    handles.put(store.name, new Handle(ARRAY, "", type.getDescriptor().substring(1)));
                }
                continue;
            }
            if (!(insn instanceof MethodInsnNode call) || !"findVarHandle".equals(call.name)
                    || !("java/lang/invoke/MethodHandles$Lookup".equals(call.owner) || isMhUtil(call))) {
                continue;
            }
            final boolean implicitOwner = isMhUtil(call)
                    && call.desc.startsWith("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;");
            final AbstractInsnNode typeLoad = previous(call);
            final AbstractInsnNode typeInsn = constant(typeLoad);
            final AbstractInsnNode name = typeLoad == null ? null : previous(typeLoad);
            final AbstractInsnNode owner = implicitOwner ? new LdcInsnNode(Type.getObjectType(className))
                    : name == null ? null : constant(previous(name));
            // int.class is GETSTATIC Integer.TYPE
            final AbstractInsnNode type = typeInsn instanceof FieldInsnNode primitive
                    && "TYPE".equals(primitive.name) && primitive.getOpcode() == Opcodes.GETSTATIC
                    ? new LdcInsnNode(primitiveType(primitive.owner)) : typeInsn;
            AbstractInsnNode next = call.getNext();
            while (next != null && next.getOpcode() < 0) {
                next = next.getNext();
            }
            if (type instanceof LdcInsnNode typeLdc && typeLdc.cst instanceof Type fieldType
                    && name instanceof LdcInsnNode nameLdc && nameLdc.cst instanceof String fieldName
                    && owner instanceof LdcInsnNode ownerLdc && ownerLdc.cst instanceof Type ownerType
                    && next instanceof FieldInsnNode store && store.getOpcode() == Opcodes.PUTSTATIC) {
                handles.put(store.name, new Handle(ownerType.getInternalName(), fieldName, fieldType.getDescriptor()));
            }
        }
    }

    private static boolean isArrayHandle(final MethodInsnNode call) {
        return "java/lang/invoke/MethodHandles".equals(call.owner) && "arrayElementVarHandle".equals(call.name);
    }

    private static boolean isMhUtil(final MethodInsnNode call) {
        return "jdk/internal/invoke/MhUtil".equals(call.owner);
    }

    private static Type primitiveType(final String wrapper) {
        return switch (wrapper) {
            case "java/lang/Integer" -> Type.INT_TYPE;
            case "java/lang/Long" -> Type.LONG_TYPE;
            case "java/lang/Boolean" -> Type.BOOLEAN_TYPE;
            case "java/lang/Byte" -> Type.BYTE_TYPE;
            case "java/lang/Short" -> Type.SHORT_TYPE;
            case "java/lang/Character" -> Type.CHAR_TYPE;
            case "java/lang/Float" -> Type.FLOAT_TYPE;
            case "java/lang/Double" -> Type.DOUBLE_TYPE;
            default -> Type.VOID_TYPE;
        };
    }

    /**
     * The constant a load stands for: the instruction itself, or for the load of a local variable the constant
     * stored in it just before ({@code Class<?> ltq = LinkedTransferQueue.class, tn = DualNode.class;}).
     */
    private static AbstractInsnNode constant(final AbstractInsnNode insn) {
        if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD) {
            for (AbstractInsnNode store = load.getPrevious(); store != null; store = store.getPrevious()) {
                if (store instanceof VarInsnNode candidate && candidate.getOpcode() == Opcodes.ASTORE
                        && candidate.var == load.var) {
                    final AbstractInsnNode value = previous(candidate);
                    return value instanceof LdcInsnNode ? value : insn;
                }
            }
        }
        return insn;
    }

    private static AbstractInsnNode nextReal(final AbstractInsnNode insn) {
        AbstractInsnNode next = insn.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static AbstractInsnNode previous(final AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    /** The {@code VarHandle} calls whose receiver is the load of one of the handles. */
    private static void collectSites(final ClassNode classNode, final MethodNode method, final String top,
                                     final java.util.function.Function<String, ClassNode> loader,
                                     final List<Site> sites) {
        boolean any = false;
        for (final AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode call && "java/lang/invoke/VarHandle".equals(call.owner)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        final Frame<SourceValue>[] frames;
        try {
            frames = new Analyzer<>(new SourceInterpreter()).analyze(classNode.name, method);
        } catch (AnalyzerException e) {
            return;
        }
        final AbstractInsnNode[] insns = method.instructions.toArray();
        for (int i = 0; i < insns.length; i++) {
            if (!(insns[i] instanceof MethodInsnNode call) || !"java/lang/invoke/VarHandle".equals(call.owner)
                    || frames[i] == null) {
                continue;
            }
            final Frame<SourceValue> frame = frames[i];
            final int receiver = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
            if (receiver < 0) {
                continue;
            }
            final var producers = frame.getStack(receiver).insns;
            if (producers.size() != 1) {
                continue;
            }
            final AbstractInsnNode producer = producers.iterator().next();
            if (producer instanceof FieldInsnNode load && load.getOpcode() == Opcodes.GETSTATIC
                    && "Ljava/lang/invoke/VarHandle;".equals(load.desc)
                    && (load.owner.equals(top) || load.owner.startsWith(top + "$"))) {
                if (!REGISTRY.containsKey(load.owner) && !load.owner.equals(classNode.name)) {
                    loader.apply(load.owner);
                }
                final Handle handle = REGISTRY.getOrDefault(load.owner, Map.of()).get(load.name);
                if (handle != null) {
                    sites.add(new Site(method, call, load, handle));
                }
            }
        }
    }

    /** The synthetic static method for one operation on one handle, or null when the operation is not covered. */
    private static MethodNode generate(final String name, final String hostClass, final MethodInsnNode call,
                                       final Handle handle) {
        final Type[] args = Type.getArgumentTypes(call.desc);
        final Type result = Type.getReturnType(call.desc);
        final Type field = Type.getType(handle.desc());
        final String op = call.name;
        // an array handle takes the array and the index before the values
        final int lead = handle.isArray() ? 1 : 0;
        final MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, "(Ljava/lang/Object;" + call.desc.substring(1), null, null);
        final InsnList code = method.instructions;
        final int[] slot = new int[args.length];
        int next = 1;
        for (int i = 0; i < args.length; i++) {
            slot[i] = next;
            next += args[i].getSize();
        }
        final int temp = next;
        final boolean compareKind = field.getSort() <= Type.INT || field.getSort() == Type.LONG
                || field.getSort() >= Type.ARRAY;
        if (!compareKind || args.length <= lead) {
            return null;
        }
        final MethodNode atomic = generateAtomic(method, call, handle, field, args, slot, lead);
        if (atomic != null) {
            return atomic;
        }
        if (op.startsWith("get") && !op.startsWith("getAnd") && args.length == 1 + lead) {
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(getField(handle, hostClass));
            code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        } else if (op.startsWith("set") && args.length == 2 + lead) {
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(new VarInsnNode(args[1 + lead].getOpcode(Opcodes.ILOAD), slot[1 + lead]));
            code.add(putField(handle, hostClass));
            code.add(new InsnNode(Opcodes.RETURN));
        } else if ((op.startsWith("compareAndSet") || op.startsWith("weakCompareAndSet")) && args.length == 3 + lead) {
            final LabelNode differs = new LabelNode();
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(getField(handle, hostClass));
            code.add(new VarInsnNode(args[1 + lead].getOpcode(Opcodes.ILOAD), slot[1 + lead]));
            jumpIfDifferent(code, field, differs);
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(new VarInsnNode(args[2 + lead].getOpcode(Opcodes.ILOAD), slot[2 + lead]));
            code.add(putField(handle, hostClass));
            code.add(new InsnNode(Opcodes.ICONST_1));
            code.add(new InsnNode(Opcodes.IRETURN));
            code.add(differs);
            code.add(new InsnNode(Opcodes.ICONST_0));
            code.add(new InsnNode(Opcodes.IRETURN));
        } else if (op.startsWith("compareAndExchange") && args.length == 3 + lead) {
            final LabelNode differs = new LabelNode();
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(getField(handle, hostClass));
            code.add(new VarInsnNode(field.getOpcode(Opcodes.ISTORE), temp));
            code.add(new VarInsnNode(field.getOpcode(Opcodes.ILOAD), temp));
            code.add(new VarInsnNode(args[1 + lead].getOpcode(Opcodes.ILOAD), slot[1 + lead]));
            jumpIfDifferent(code, field, differs);
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(new VarInsnNode(args[2 + lead].getOpcode(Opcodes.ILOAD), slot[2 + lead]));
            code.add(putField(handle, hostClass));
            code.add(differs);
            code.add(new VarInsnNode(field.getOpcode(Opcodes.ILOAD), temp));
            code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        } else if ((op.startsWith("getAndSet") || op.startsWith("getAndAdd")) && args.length == 2 + lead) {
            final boolean add = op.startsWith("getAndAdd");
            if (add && field.getSort() != Type.INT && field.getSort() != Type.LONG) {
                return null;
            }
            loadReceiver(code, slot[0], handle, hostClass);
            code.add(getField(handle, hostClass));
            code.add(new VarInsnNode(field.getOpcode(Opcodes.ISTORE), temp));
            loadReceiver(code, slot[0], handle, hostClass);
            if (add) {
                code.add(new VarInsnNode(field.getOpcode(Opcodes.ILOAD), temp));
                code.add(new VarInsnNode(args[1 + lead].getOpcode(Opcodes.ILOAD), slot[1 + lead]));
                code.add(new InsnNode(field.getOpcode(Opcodes.IADD)));
            } else {
                code.add(new VarInsnNode(args[1 + lead].getOpcode(Opcodes.ILOAD), slot[1 + lead]));
            }
            code.add(putField(handle, hostClass));
            code.add(new VarInsnNode(field.getOpcode(Opcodes.ILOAD), temp));
            code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        } else {
            return null;
        }
        method.maxLocals = temp + 2;
        method.maxStack = 6;
        return method;
    }

    private static final String UNSAFE = "jdk/internal/misc/Unsafe";

    /** The Unsafe name part for the kind of the field, or null when its accesses cannot be done atomically. */
    private static String unsafeKind(final Type field) {
        return switch (field.getSort()) {
            case Type.INT -> "Int";
            case Type.LONG -> "Long";
            case Type.OBJECT, Type.ARRAY -> "Reference";
            default -> null;
        };
    }

    /** The size in bytes of an element of an array of this type (every array has an 8 byte header). */
    private static int elementScale(final Type element) {
        return switch (element.getSort()) {
            case Type.BOOLEAN, Type.BYTE -> 1;
            case Type.CHAR, Type.SHORT -> 2;
            case Type.INT, Type.FLOAT -> 4;
            default -> 8;
        };
    }

    /**
     * The operation as an atomic access through Unsafe: threads run in parallel, so a read, a compare and a write
     * in separate instructions are not one atomic step. Null when the operation or the type is not covered (the
     * plain sequence is generated then).
     */
    private static MethodNode generateAtomic(final MethodNode method, final MethodInsnNode call, final Handle handle,
                                             final Type field, final Type[] args, final int[] slot, final int lead) {
        final String kind = unsafeKind(field);
        if (kind == null || isRuntimeObject(handle)) {
            return null;
        }
        final String op = call.name;
        final Type result = Type.getReturnType(call.desc);
        final String value = field.getSort() >= Type.ARRAY ? "Ljava/lang/Object;" : field.getDescriptor();
        final InsnList code = method.instructions;
        final String unsafeDesc;
        final String unsafeName;
        final int valueCount;
        if (op.startsWith("get") && !op.startsWith("getAnd") && args.length == 1 + lead) {
            unsafeName = "get" + kind + "Volatile";
            unsafeDesc = "(Ljava/lang/Object;J)" + value;
            valueCount = 0;
        } else if (op.startsWith("set") && args.length == 2 + lead) {
            unsafeName = "put" + kind + "Volatile";
            unsafeDesc = "(Ljava/lang/Object;J" + value + ")V";
            valueCount = 1;
        } else if ((op.startsWith("compareAndSet") || op.startsWith("weakCompareAndSet")) && args.length == 3 + lead) {
            unsafeName = "compareAndSet" + kind;
            unsafeDesc = "(Ljava/lang/Object;J" + value + value + ")Z";
            valueCount = 2;
        } else if (op.startsWith("compareAndExchange") && args.length == 3 + lead) {
            unsafeName = "compareAndExchange" + kind;
            unsafeDesc = "(Ljava/lang/Object;J" + value + value + ")" + value;
            valueCount = 2;
        } else if (op.startsWith("getAndSet") && args.length == 2 + lead) {
            unsafeName = "getAndSet" + kind;
            unsafeDesc = "(Ljava/lang/Object;J" + value + ")" + value;
            valueCount = 1;
        } else if (op.startsWith("getAndAdd") && args.length == 2 + lead && !"Reference".equals(kind)) {
            unsafeName = "getAndAdd" + kind;
            unsafeDesc = "(Ljava/lang/Object;J" + value + ")" + value;
            valueCount = 1;
        } else {
            return null;
        }
        code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, UNSAFE, "getUnsafe", "()L" + UNSAFE + ";", false));
        code.add(new VarInsnNode(Opcodes.ALOAD, slot[0]));
        if (handle.isArray()) {
            // the element at base 8 + index * scale
            code.add(new VarInsnNode(Opcodes.ILOAD, slot[0] + 1));
            code.add(new InsnNode(Opcodes.I2L));
            code.add(new LdcInsnNode((long) elementScale(field)));
            code.add(new InsnNode(Opcodes.LMUL));
            code.add(new LdcInsnNode(8L));
            code.add(new InsnNode(Opcodes.LADD));
        } else {
            code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, UNSAFE, "getUnsafe", "()L" + UNSAFE + ";", false));
            code.add(new LdcInsnNode(Type.getObjectType(handle.owner())));
            code.add(new LdcInsnNode(handle.name()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, UNSAFE, "objectFieldOffset",
                    "(Ljava/lang/Class;Ljava/lang/String;)J", false));
        }
        for (int i = 0; i < valueCount; i++) {
            final int argument = args.length - valueCount + i;
            code.add(new VarInsnNode(args[argument].getOpcode(Opcodes.ILOAD), slot[argument]));
        }
        code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, UNSAFE, unsafeName, unsafeDesc, false));
        if (result.getSort() == Type.VOID) {
            code.add(new InsnNode(Opcodes.RETURN));
        } else {
            code.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        }
        method.maxLocals = slot[args.length - 1] + args[args.length - 1].getSize() + 2;
        method.maxStack = 10;
        return method;
    }

    /**
     * The objects of these classes are made by the C runtime, which keeps their state in its own structure: the
     * compiled field offsets are outside the object. Their fields become static fields of the class that uses the
     * handle (one thread: the one current thread).
     */
    private static boolean isRuntimeObject(final Handle handle) {
        return "java/lang/Thread".equals(handle.owner());
    }

    private static String stateField(final Handle handle) {
        return "$vhstate$" + handle.name();
    }

    private static void loadReceiver(final InsnList code, final int slot, final Handle handle, final String host) {
        if (handle.isArray()) {
            code.add(new VarInsnNode(Opcodes.ALOAD, slot));
            code.add(new VarInsnNode(Opcodes.ILOAD, slot + 1));
        } else if (!isRuntimeObject(handle)) {
            code.add(new VarInsnNode(Opcodes.ALOAD, slot));
        }
    }

    private static AbstractInsnNode getField(final Handle handle, final String host) {
        if (handle.isArray()) {
            return new InsnNode(Type.getType(handle.desc()).getOpcode(Opcodes.IALOAD));
        }
        return isRuntimeObject(handle)
                ? new FieldInsnNode(Opcodes.GETSTATIC, host, stateField(handle), handle.desc())
                : new FieldInsnNode(Opcodes.GETFIELD, handle.owner(), handle.name(), handle.desc());
    }

    private static AbstractInsnNode putField(final Handle handle, final String host) {
        if (handle.isArray()) {
            return new InsnNode(Type.getType(handle.desc()).getOpcode(Opcodes.IASTORE));
        }
        return isRuntimeObject(handle)
                ? new FieldInsnNode(Opcodes.PUTSTATIC, host, stateField(handle), handle.desc())
                : new FieldInsnNode(Opcodes.PUTFIELD, handle.owner(), handle.name(), handle.desc());
    }

    /** Stack: two values of the field's type; jumps to {@code differs} when they are not equal. */
    private static void jumpIfDifferent(final InsnList code, final Type field, final LabelNode differs) {
        if (field.getSort() == Type.LONG) {
            code.add(new InsnNode(Opcodes.LCMP));
            code.add(new JumpInsnNode(Opcodes.IFNE, differs));
        } else if (field.getSort() >= Type.ARRAY) {
            code.add(new JumpInsnNode(Opcodes.IF_ACMPNE, differs));
        } else {
            code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, differs));
        }
    }
}
