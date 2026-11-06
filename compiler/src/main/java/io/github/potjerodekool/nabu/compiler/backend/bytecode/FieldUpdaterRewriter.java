package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * {@code AtomicReferenceFieldUpdater.newUpdater(Owner.class, Value.class, "field")} (and the Integer and Long
 * variants), as the JDK and libraries call it from static initializers ({@code SQLException}, H2, Tomcat, ...). The
 * JDK's implementation checks access through the caller's class and reads the field through {@code Unsafe}; neither is
 * there in a native executable. When the arguments are constants, the call becomes {@code new Updater()} of a class
 * generated for that field, which reads and writes the field directly: the threads of a native program run under one
 * lock and are only switched at safepoints, which a compare-and-set does not contain.
 */
final class FieldUpdaterRewriter {

    private static final String PREFIX = "java/util/concurrent/atomic/";

    /** The generated updater classes by internal name; the class loader of the closure asks for them here. */
    private static final Map<String, ClassNode> GENERATED = new ConcurrentHashMap<>();

    private FieldUpdaterRewriter() {
    }

    static ClassNode generated(final String internalName) {
        return GENERATED.get(internalName);
    }

    /** The kind of a field updater: its class, the descriptor of a call and the type of the value it holds. */
    private enum Kind {
        REFERENCE("AtomicReferenceFieldUpdater", "(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;)", 3),
        INTEGER("AtomicIntegerFieldUpdater", "(Ljava/lang/Class;Ljava/lang/String;)", 2),
        LONG("AtomicLongFieldUpdater", "(Ljava/lang/Class;Ljava/lang/String;)", 2);

        final String simpleName;
        final String argsDesc;
        final int arguments;

        Kind(final String simpleName, final String argsDesc, final int arguments) {
            this.simpleName = simpleName;
            this.argsDesc = argsDesc;
            this.arguments = arguments;
        }

        String owner() {
            return PREFIX + simpleName;
        }
    }

    static void rewrite(final ClassNode classNode, final Function<String, ClassNode> loader) {
        if (classNode.name.startsWith(PREFIX)) {
            return;
        }
        for (final MethodNode method : classNode.methods) {
            if (method.instructions == null || method.instructions.size() == 0) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
                final AbstractInsnNode next = insn.getNext();
                if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                        && "newUpdater".equals(call.name)) {
                    rewriteCall(method.instructions, call, loader);
                }
                insn = next;
            }
        }
    }

    private static void rewriteCall(final InsnList instructions, final MethodInsnNode call,
                                    final Function<String, ClassNode> loader) {
        Kind kind = null;
        for (final Kind candidate : Kind.values()) {
            if (candidate.owner().equals(call.owner)
                    && call.desc.equals(candidate.argsDesc + "L" + candidate.owner() + ";")) {
                kind = candidate;
            }
        }
        if (kind == null) {
            return;
        }
        // The arguments: constants pushed right before the call (labels and line numbers in between are skipped).
        final AbstractInsnNode[] pushed = new AbstractInsnNode[kind.arguments];
        AbstractInsnNode cursor = call;
        for (int i = kind.arguments - 1; i >= 0; i--) {
            cursor = cursor.getPrevious();
            while (cursor != null && cursor.getOpcode() < 0) {
                cursor = cursor.getPrevious();
            }
            if (!(cursor instanceof LdcInsnNode)) {
                return;
            }
            pushed[i] = cursor;
        }
        final Object ownerConstant = ((LdcInsnNode) pushed[0]).cst;
        final Object nameConstant = ((LdcInsnNode) pushed[kind.arguments - 1]).cst;
        if (!(ownerConstant instanceof Type ownerType) || ownerType.getSort() != Type.OBJECT
                || !(nameConstant instanceof String fieldName)) {
            return;
        }
        final String owner = ownerType.getInternalName();
        final ClassNode ownerNode = loader.apply(owner);
        if (ownerNode == null) {
            return;
        }
        String foundDesc = null;
        for (final FieldNode field : ownerNode.fields) {
            if (field.name.equals(fieldName) && (field.access & Opcodes.ACC_STATIC) == 0) {
                foundDesc = field.desc;
            }
        }
        final String fieldDesc = foundDesc;
        if (fieldDesc == null
                || (kind == Kind.INTEGER && !"I".equals(fieldDesc))
                || (kind == Kind.LONG && !"J".equals(fieldDesc))
                || (kind == Kind.REFERENCE && fieldDesc.length() == 1)) {
            return;
        }
        final String name = RuntimeCalls.RUNTIME_PACKAGE + "FieldUpdater$" + kind.simpleName.substring(6, 9) + "$"
                + owner.replace('/', '_') + "$" + fieldName;
        final String generatedName = name;
        final Kind updaterKind = kind;
        GENERATED.computeIfAbsent(generatedName, n -> generate(n, updaterKind, owner, fieldName, fieldDesc));

        for (final AbstractInsnNode p : pushed) {
            instructions.remove(p);
        }
        instructions.insertBefore(call, new TypeInsnNode(Opcodes.NEW, generatedName));
        instructions.insertBefore(call, new InsnNode(Opcodes.DUP));
        instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESPECIAL, generatedName, "<init>", "()V", false));
        instructions.remove(call);
    }

    private static ClassNode generate(final String name, final Kind kind, final String owner, final String field,
                                      final String fieldDesc) {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = name;
        node.superName = kind.owner();

        final MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, kind.owner(), "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        node.methods.add(init);

        final String valueDesc = kind == Kind.REFERENCE ? "Ljava/lang/Object;" : kind == Kind.INTEGER ? "I" : "J";
        final int valueSize = kind == Kind.LONG ? 2 : 1;
        final int loadOpcode = kind == Kind.REFERENCE ? Opcodes.ALOAD : kind == Kind.INTEGER ? Opcodes.ILOAD : Opcodes.LLOAD;
        final int returnOpcode = kind == Kind.REFERENCE ? Opcodes.ARETURN : kind == Kind.INTEGER ? Opcodes.IRETURN : Opcodes.LRETURN;

        // boolean compareAndSet(Object obj, expect, update) and weakCompareAndSet
        final String casDesc = "(Ljava/lang/Object;" + valueDesc + valueDesc + ")Z";
        final MethodNode cas = new MethodNode(Opcodes.ACC_PUBLIC, "compareAndSet", casDesc, null, null);
        final InsnList c = cas.instructions;
        final LabelNode failed = new LabelNode();
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        c.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, fieldDesc));
        c.add(new VarInsnNode(loadOpcode, 2));
        switch (kind) {
            case REFERENCE -> c.add(new JumpInsnNode(Opcodes.IF_ACMPNE, failed));
            case INTEGER -> c.add(new JumpInsnNode(Opcodes.IF_ICMPNE, failed));
            case LONG -> {
                c.add(new InsnNode(Opcodes.LCMP));
                c.add(new JumpInsnNode(Opcodes.IFNE, failed));
            }
        }
        c.add(new VarInsnNode(Opcodes.ALOAD, 1));
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        c.add(new VarInsnNode(loadOpcode, 2 + valueSize));
        castValue(c, kind, fieldDesc);
        c.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, fieldDesc));
        c.add(new InsnNode(Opcodes.ICONST_1));
        c.add(new InsnNode(Opcodes.IRETURN));
        c.add(failed);
        c.add(new InsnNode(Opcodes.ICONST_0));
        c.add(new InsnNode(Opcodes.IRETURN));
        cas.maxStack = 6;
        cas.maxLocals = 2 + 2 * valueSize;
        node.methods.add(cas);

        final MethodNode weak = new MethodNode(Opcodes.ACC_PUBLIC, "weakCompareAndSet", casDesc, null, null);
        weak.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        weak.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        weak.instructions.add(new VarInsnNode(loadOpcode, 2));
        weak.instructions.add(new VarInsnNode(loadOpcode, 2 + valueSize));
        weak.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "compareAndSet", casDesc, false));
        weak.instructions.add(new InsnNode(Opcodes.IRETURN));
        weak.maxStack = 6;
        weak.maxLocals = 2 + 2 * valueSize;
        node.methods.add(weak);

        // void set(Object obj, value) and lazySet
        final String setDesc = "(Ljava/lang/Object;" + valueDesc + ")V";
        final MethodNode set = new MethodNode(Opcodes.ACC_PUBLIC, "set", setDesc, null, null);
        set.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        set.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        set.instructions.add(new VarInsnNode(loadOpcode, 2));
        castValue(set.instructions, kind, fieldDesc);
        set.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, owner, field, fieldDesc));
        set.instructions.add(new InsnNode(Opcodes.RETURN));
        set.maxStack = 4;
        set.maxLocals = 2 + valueSize;
        node.methods.add(set);

        final MethodNode lazy = new MethodNode(Opcodes.ACC_PUBLIC, "lazySet", setDesc, null, null);
        lazy.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        lazy.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        lazy.instructions.add(new VarInsnNode(loadOpcode, 2));
        lazy.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, name, "set", setDesc, false));
        lazy.instructions.add(new InsnNode(Opcodes.RETURN));
        lazy.maxStack = 4;
        lazy.maxLocals = 2 + valueSize;
        node.methods.add(lazy);

        // value get(Object obj)
        final MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC, "get", "(Ljava/lang/Object;)" + valueDesc, null, null);
        get.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        get.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, owner));
        get.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, owner, field, fieldDesc));
        get.instructions.add(new InsnNode(returnOpcode));
        get.maxStack = 2;
        get.maxLocals = 2;
        node.methods.add(get);
        return node;
    }

    /** The value of a reference updater is an Object; the field may be of a narrower type. */
    private static void castValue(final InsnList list, final Kind kind, final String fieldDesc) {
        if (kind != Kind.REFERENCE || "Ljava/lang/Object;".equals(fieldDesc)) {
            return;
        }
        final String internal = fieldDesc.startsWith("L") ? fieldDesc.substring(1, fieldDesc.length() - 1) : fieldDesc;
        list.add(new TypeInsnNode(Opcodes.CHECKCAST, internal));
    }
}
