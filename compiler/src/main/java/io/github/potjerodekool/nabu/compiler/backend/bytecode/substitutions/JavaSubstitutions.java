package io.github.potjerodekool.nabu.compiler.backend.bytecode.substitutions;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;

/**
 * Substitutions written in Java (compare GraalVM's {@code @Substitute}): a
 * class in this package supplies the body of every JDK method that has the
 * same name and descriptor. The body is copied into the JDK class, with
 * references to the substitution class redirected to the JDK class, so a
 * substitution may call its own (equally substituted) methods.
 *
 * <p>For some classes the {@code <clinit>} becomes empty as well: their
 * methods are substituted because they depend on what the initializer sets
 * up (typically {@code VarHandle}s, which do not exist in a native
 * executable).</p>
 */
public final class JavaSubstitutions {

    /** JDK class (internal name) → substitution class. */
    private static final Map<String, Class<?>> SUBSTITUTIONS = Map.ofEntries(
            Map.entry("jdk/internal/util/ByteArray", ByteArraySubstitution.class),
            Map.entry("jdk/internal/util/ByteArrayLittleEndian", ByteArrayLittleEndianSubstitution.class),
            Map.entry("sun/security/provider/ByteArrayAccess", ByteArrayAccessSubstitution.class),
            Map.entry("sun/security/provider/MD5", MD5Substitution.class),
            Map.entry("java/security/SecureRandom", SecureRandomSubstitution.class),
            Map.entry("java/util/concurrent/atomic/AtomicReferenceArray", AtomicReferenceArraySubstitution.class),
            Map.entry("java/util/concurrent/atomic/AtomicIntegerArray", AtomicIntegerArraySubstitution.class),
            Map.entry("java/util/concurrent/atomic/AtomicLongArray", AtomicLongArraySubstitution.class),
            Map.entry("java/math/BigInteger", BigIntegerSubstitution.class),
            Map.entry("java/lang/Enum", EnumSubstitution.class),
            Map.entry("java/lang/Throwable", ThrowableSubstitution.class),
            Map.entry("jdk/internal/loader/BootLoader", BootLoaderSubstitution.class),
            Map.entry("java/nio/file/Files", FilesSubstitution.class),
            Map.entry("java/security/Security$SecPropLoader", SecurityPropertiesSubstitution.class),
            Map.entry("jdk/internal/event/EventHelper", EventHelperSubstitution.class),
            Map.entry("sun/security/provider/SeedGenerator", SeedGeneratorSubstitution.class),
            Map.entry("java/nio/channels/FileChannel", FileChannelSubstitution.class),
            Map.entry("java/lang/ProcessImpl", ProcessImplSubstitution.class),
            Map.entry("sun/nio/fs/WindowsPath", WindowsPathSubstitution.class),
            Map.entry("java/util/ResourceBundle", ResourceBundleSubstitution.class),
            Map.entry("java/lang/ThreadGroup", ThreadGroupSubstitution.class));

    /** JDK classes whose constructors are substituted as well (same descriptor). */
    private static final Set<String> SUBSTITUTED_CONSTRUCTORS = Set.of("java/lang/ThreadGroup");

    /** JDK classes whose {@code <clinit>} is emptied. */
    private static final Set<String> EMPTY_CLINIT = Set.of(
            "jdk/internal/util/ByteArray",
            "jdk/internal/util/ByteArrayLittleEndian",
            "sun/security/provider/SeedGenerator",
            "sun/security/provider/ByteArrayAccess",
            "java/util/concurrent/atomic/AtomicReferenceArray",
            "java/util/concurrent/atomic/AtomicIntegerArray",
            "java/util/concurrent/atomic/AtomicLongArray",
            "java/lang/ProcessImpl",
            "jdk/internal/event/EventHelper");

    private JavaSubstitutions() {
    }

    public static void apply(final ClassNode target) {
        if ("jdk/internal/misc/UnsafeConstants".equals(target.name)) {
            setUnsafeConstants(target);
            return;
        }
        final Class<?> substitution = SUBSTITUTIONS.get(target.name);
        if (substitution == null) {
            return;
        }
        final ClassNode source = read(substitution);
        for (final MethodNode method : target.methods) {
            if ("<clinit>".equals(method.name) && EMPTY_CLINIT.contains(target.name)) {
                final InsnList insns = new InsnList();
                insns.add(new InsnNode(Opcodes.RETURN));
                method.instructions = insns;
                method.tryCatchBlocks = new ArrayList<>();
                method.localVariables = null;
                continue;
            }
            if (method.name.startsWith("<")
                    && !("<init>".equals(method.name) && SUBSTITUTED_CONSTRUCTORS.contains(target.name))) {
                continue;
            }
            for (final MethodNode replacement : source.methods) {
                if (replacement.name.equals(method.name) && (replacement.desc.equals(method.desc)
                        || covariantReturn(replacement, method, target, source))) {
                    redirect(replacement.instructions, source.name, target.name);
                    method.access &= ~Opcodes.ACC_NATIVE;
                    method.instructions = replacement.instructions;
                    method.tryCatchBlocks = replacement.tryCatchBlocks;
                    method.localVariables = null;
                    method.maxStack = replacement.maxStack;
                    method.maxLocals = replacement.maxLocals;
                    break;
                }
            }
        }
    }

    /**
     * The target method has a covariant return type that the substitution cannot name (a package-private
     * class, as in {@code WindowsPath.toRealPath}): same name and parameters, object return types, not a bridge.
     */
    private static boolean covariantReturn(final MethodNode replacement, final MethodNode method,
                                           final ClassNode target, final ClassNode source) {
        final int end = replacement.desc.indexOf(')');
        if (end != method.desc.indexOf(')')
                || !replacement.desc.regionMatches(0, method.desc, 0, end + 1)
                || replacement.desc.charAt(end + 1) != 'L' || method.desc.charAt(end + 1) != 'L'
                || (method.access & Opcodes.ACC_BRIDGE) != 0) {
            return false;
        }
        return true;
    }

    /**
     * The JVM sets the fields of {@code UnsafeConstants} when it starts; the class initializer only has
     * placeholders (0). A zero address size makes {@code Unsafe.allocateMemory} round every size to 0, which
     * breaks direct buffers. The address size and page size of the target are set here (64-bit, 4 KiB pages).
     */
    private static void setUnsafeConstants(final ClassNode target) {
        for (final MethodNode method : target.methods) {
            if (!"<clinit>".equals(method.name)) {
                continue;
            }
            for (final AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
                        && insn.getPrevious() != null && insn.getPrevious().getOpcode() == Opcodes.ICONST_0) {
                    if ("ADDRESS_SIZE0".equals(field.name)) {
                        method.instructions.set(insn.getPrevious(), new org.objectweb.asm.tree.IntInsnNode(Opcodes.BIPUSH, 8));
                    } else if ("PAGE_SIZE".equals(field.name)) {
                        method.instructions.set(insn.getPrevious(), new org.objectweb.asm.tree.IntInsnNode(Opcodes.SIPUSH, 4096));
                    }
                }
            }
        }
    }

    private static void redirect(final InsnList insns, final String from, final String to) {
        for (final AbstractInsnNode insn : insns.toArray()) {
            if (insn instanceof MethodInsnNode call && from.equals(call.owner)) {
                call.owner = to;
            } else if (insn instanceof FieldInsnNode field && from.equals(field.owner)) {
                field.owner = to;
            }
        }
    }

    private static ClassNode read(final Class<?> type) {
        final String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Substitution class not found: " + resource);
            }
            final ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
