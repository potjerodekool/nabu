package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.Opcodes;
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
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JDK-resources die een native programma meekrijgt (vergelijk GraalVM's
 * resource-configuratie). Een native programma heeft geen module-image om
 * resources uit te lezen; de JDK leest bv. de data van zijn
 * {@code BreakIterator}s via {@code Module.getResourceAsStream}.
 *
 * <p>Alles blijft bytecode: een gesynthetiseerde klasse
 * {@code nabu/runtime/Resources} bevat de bytes (als Latin-1-string-constanten)
 * en geeft ze als {@code ByteArrayInputStream} terug; aanroepen van
 * {@code Module.getResourceAsStream(String)} in gelowerde JDK-bytecode worden
 * naar die klasse omgeleid.
 */
final class EmbeddedResources {

    /** De gesynthetiseerde klasse. */
    static final String CLASS = "nabu/runtime/Resources";

    /** Meegeleverde resources: pad binnen de module java.base. */
    private static final List<String> RESOURCES = List.of(
            "sun/text/resources/LineBreakIteratorData",
            "sun/text/resources/WordBreakIteratorData",
            "sun/text/resources/SentenceBreakIteratorData",
            "sun/text/resources/CharacterBreakIteratorData");

    /** Een UTF-8-constante mag hooguit 65535 bytes zijn; een Latin-1-teken kost er tot 2. */
    private static final int CHUNK = 16_000;

    private static final String OPEN_DESC = "(Ljava/lang/Module;Ljava/lang/String;)Ljava/io/InputStream;";

    private EmbeddedResources() {
    }

    /**
     * Leidt {@code Module.getResourceAsStream(String)} om naar
     * {@code Resources.open(Module, String)}: dezelfde stack-vorm (receiver +
     * naam), dus alleen de instructie wisselt.
     */
    static void rewriteResourceCalls(final ClassNode classNode) {
        if (CLASS.equals(classNode.name)) {
            return;
        }
        for (final MethodNode method : classNode.methods) {
            if (method.instructions == null) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "java/lang/Module".equals(call.owner)
                        && "getResourceAsStream".equals(call.name)
                        && "(Ljava/lang/String;)Ljava/io/InputStream;".equals(call.desc)) {
                    final var replacement = new MethodInsnNode(Opcodes.INVOKESTATIC, CLASS, "open", OPEN_DESC, false);
                    method.instructions.set(call, replacement);
                    insn = replacement;
                }
            }
        }
    }

    /** {@code public static InputStream open(Module module, String name)}. */
    static ClassNode build() {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CLASS;
        node.superName = "java/lang/Object";

        final MethodNode open = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "open", OPEN_DESC, null, null);
        final InsnList code = open.instructions;
        final FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (final String resource : RESOURCES) {
            final byte[] bytes;
            try {
                final Path path = jrt.getPath("/modules/java.base/" + resource);
                if (!Files.exists(path)) {
                    continue;
                }
                bytes = Files.readAllBytes(path);
            } catch (final IOException e) {
                continue;
            }
            final LabelNode next = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 1));
            code.add(new LdcInsnNode(resource));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, next));
            code.add(new TypeInsnNode(Opcodes.NEW, "java/io/ByteArrayInputStream"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
            code.add(new InsnNode(Opcodes.DUP));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
            final String latin1 = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
            for (int i = 0; i < latin1.length(); i += CHUNK) {
                code.add(new LdcInsnNode(latin1.substring(i, Math.min(latin1.length(), i + CHUNK))));
                code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            }
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false));
            code.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets", "ISO_8859_1",
                    "Ljava/nio/charset/Charset;"));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "getBytes",
                    "(Ljava/nio/charset/Charset;)[B", false));
            code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/io/ByteArrayInputStream", "<init>",
                    "([B)V", false));
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(next);
        }
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        open.maxStack = 6;
        open.maxLocals = 2;
        node.methods.add(open);
        return node;
    }
}
