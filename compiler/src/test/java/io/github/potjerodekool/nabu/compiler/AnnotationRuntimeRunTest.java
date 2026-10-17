package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Een annotatie uit broncode moet tijdens runtime via reflectie zichtbaar zijn:
 * ACC_ANNOTATION op het @interface, de superinterface
 * java.lang.annotation.Annotation, geen constructor in de interface, en
 * {@code @Retention(RetentionPolicy.RUNTIME)} als enumwaarde (was een klasse-
 * waarde, zodat de retention terugviel op CLASS); en standaardwaarden van
 * annotatie-elementen (AnnotationDefault), die de Java-parser wegliet.
 */
class AnnotationRuntimeRunTest {

    @TempDir
    Path tempDir;

    private Class<?> compileAndLoad(final String className, final String code) throws Exception {
        final var srcDir = tempDir.resolve("src");
        final var javaFile = srcDir.resolve("mini/" + className + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, code);

        final var outDir = tempDir.resolve("out");
        final var rootPath = "C:\\projects\\nabu\\compiler";
        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SYSTEM, rootPath + "\\src\\test\\resources")
                .option(CompilerOption.SOURCE_PATH, srcDir.toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, tempDir.resolve("gen").toString())
                .option(CompilerOption.BACKEND, "ASM")
                .option(CompilerOption.INCREMENTAL, "false")
                .build();

        final var compiler = new NabuCompiler();
        final var errors = new AtomicInteger();
        final var messages = new java.util.ArrayList<String>();
        compiler.setListener(d -> {
            if (d.getKind().name().equals("ERROR")) {
                errors.incrementAndGet();
                messages.add(d.getMessage(java.util.Locale.ROOT).toString());
            }
        });
        final var rc = compiler.compile(options);
        assertEquals(0, rc + errors.get(), String.join("\n", messages));

        final var loader = new URLClassLoader(new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader());
        return loader.loadClass("mini." + className);
    }

    @Test
    void sourceAnnotationVisibleAtRuntime() throws Exception {
        final var cls = compileAndLoad("Tagged", """
                package mini;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;
                @Tag(name = "t", values = {"a", "b"}, kind = ElementType.TYPE)
                public class Tagged {
                    public static String run() {
                        Tag tag = Tagged.class.getAnnotation(Tag.class);
                        return tag.name() + " " + tag.values().length + " " + tag.kind()
                                + " " + tag.separator() + " " + tag.aliases().length + " " + tag.scope()
                                + " " + tag.type().getSimpleName() + " " + tag.order();
                    }
                }
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD})
                @interface Tag {
                    String name();
                    String[] values();
                    ElementType kind();
                    String separator() default "=";
                    String[] aliases() default {};
                    ElementType scope() default ElementType.FIELD;
                    Class<?> type() default Void.class;
                    int order() default -1;
                }
                """);
        assertEquals("t 2 TYPE = 0 FIELD Void -1", cls.getMethod("run").invoke(null));
    }
}
