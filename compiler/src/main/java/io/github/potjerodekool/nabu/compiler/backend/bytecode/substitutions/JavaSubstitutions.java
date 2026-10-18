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
    private static final Map<String, Class<?>> SUBSTITUTIONS = Map.of(
            "jdk/internal/util/ByteArray", ByteArraySubstitution.class,
            "jdk/internal/util/ByteArrayLittleEndian", ByteArrayLittleEndianSubstitution.class,
            "java/lang/Enum", EnumSubstitution.class);

    /** JDK classes whose {@code <clinit>} is emptied. */
    private static final Set<String> EMPTY_CLINIT = Set.of(
            "jdk/internal/util/ByteArray",
            "jdk/internal/util/ByteArrayLittleEndian");

    private JavaSubstitutions() {
    }

    public static void apply(final ClassNode target) {
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
            if (method.name.startsWith("<")) {
                continue;
            }
            for (final MethodNode replacement : source.methods) {
                if (replacement.name.equals(method.name) && replacement.desc.equals(method.desc)) {
                    redirect(replacement.instructions, source.name, target.name);
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
