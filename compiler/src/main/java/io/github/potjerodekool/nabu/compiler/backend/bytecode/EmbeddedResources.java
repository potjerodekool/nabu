package io.github.potjerodekool.nabu.compiler.backend.bytecode;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
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
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resources a native program carries (compare GraalVM's resource
 * configuration). A native program has no class path or module image to
 * read resources from: the JDK reads e.g. the data of its
 * {@code BreakIterator}s through {@code Module.getResourceAsStream}, and an
 * application its own files through {@code Class.getResourceAsStream}.
 *
 * <p>Everything stays bytecode: the generated class {@link #CLASS} returns
 * the bytes of each resource (stored as Latin-1 string constants) from
 * {@code data(String)}; {@code ResourceSupport} (see {@link RuntimeCalls})
 * builds streams and URLs on it. The resources are a few JDK files plus
 * those the application's resource configuration selects.
 */
final class EmbeddedResources {

    /** The generated class (its placeholder in the compiler has the same name). */
    static final String CLASS = RuntimeCalls.RUNTIME_PACKAGE + "Resources";

    /** JDK resources, by their path within the module java.base. */
    private static final List<String> JDK_RESOURCES = List.of(
            "sun/text/resources/LineBreakIteratorData",
            "sun/text/resources/WordBreakIteratorData",
            "sun/text/resources/SentenceBreakIteratorData",
            "sun/text/resources/CharacterBreakIteratorData");

    /** Keeps each constant well below the class-file limit of 65535 UTF-8 bytes; a Latin-1 char takes up to 2. */
    private static final int CHUNK = 16_000;

    private static final String DATA_DESC = "(Ljava/lang/String;I)[B";

    private EmbeddedResources() {
    }

    /**
     * A class of the runtime package ({@code ResourceSupport} and its nested
     * classes), read from the compiler itself; null for any other name.
     */
    static ClassNode runtimeClass(final String internalName) {
        if (!internalName.startsWith(RuntimeCalls.RUNTIME_PACKAGE) || CLASS.equals(internalName)) {
            return null;
        }
        try (InputStream in = EmbeddedResources.class.getClassLoader()
                .getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                return null;
            }
            final ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@code public static byte[] data(String name, int index)}: the
     * contents of the index-th occurrence of a resource (a path can occur in
     * several class-path roots), or null.
     *
     * @param applicationResources resource path → contents per occurrence,
     *                             from the resource configuration.
     */
    static ClassNode build(final Map<String, List<byte[]>> applicationResources) {
        final ClassNode node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER;
        node.name = CLASS;
        node.superName = "java/lang/Object";

        final Map<String, List<byte[]>> resources = new LinkedHashMap<>();
        jdkResources().forEach((name, bytes) -> resources.put(name, List.of(bytes)));
        applicationResources.forEach(resources::putIfAbsent);

        final MethodNode data = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "data", DATA_DESC, null, null);
        final InsnList code = data.instructions;
        for (final var resource : resources.entrySet()) {
            final LabelNode nextName = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new LdcInsnNode(resource.getKey()));
            code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals",
                    "(Ljava/lang/Object;)Z", false));
            code.add(new JumpInsnNode(Opcodes.IFEQ, nextName));
            final List<byte[]> occurrences = resource.getValue();
            for (int index = 0; index < occurrences.size(); index++) {
                final LabelNode nextIndex = new LabelNode();
                code.add(new VarInsnNode(Opcodes.ILOAD, 1));
                code.add(new LdcInsnNode(index));
                code.add(new JumpInsnNode(Opcodes.IF_ICMPNE, nextIndex));
                pushBytes(code, occurrences.get(index));
                code.add(new InsnNode(Opcodes.ARETURN));
                code.add(nextIndex);
            }
            code.add(new InsnNode(Opcodes.ACONST_NULL));
            code.add(new InsnNode(Opcodes.ARETURN));
            code.add(nextName);
        }
        code.add(new InsnNode(Opcodes.ACONST_NULL));
        code.add(new InsnNode(Opcodes.ARETURN));
        data.maxStack = 4;
        data.maxLocals = 2;
        node.methods.add(data);
        return node;
    }

    /** {@code new StringBuilder().append(chunk)...toString().getBytes(ISO_8859_1)}. */
    private static void pushBytes(final InsnList code, final byte[] bytes) {
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
    }

    private static Map<String, byte[]> jdkResources() {
        final Map<String, byte[]> resources = new LinkedHashMap<>();
        final FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        for (final String resource : JDK_RESOURCES) {
            try {
                final Path path = jrt.getPath("/modules/java.base/" + resource);
                if (Files.exists(path)) {
                    resources.put(resource, Files.readAllBytes(path));
                }
            } catch (final IOException e) {
                // Not in this JDK: the JDK code that reads it gets null.
            }
        }
        return resources;
    }
}
