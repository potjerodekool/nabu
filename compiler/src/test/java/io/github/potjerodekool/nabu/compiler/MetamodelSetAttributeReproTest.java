package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.tools.CompilerOptions;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import org.hibernate.processor.HibernateProcessor;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled
public class MetamodelSetAttributeReproTest {

    @TempDir
    Path tempDir;

    @Test
    void steSetFieldsProduceSetAttributeMetamodel() throws Exception {
        final var sourceDir = Files.createDirectories(tempDir.resolve("src/io/github/potjerodekool/test"));
        final var outDir = Files.createDirectories(tempDir.resolve("out"));
        final var genDir = Files.createDirectories(tempDir.resolve("generated"));

        final var source = """
                package io.github.potjerodekool.test;

                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.ElementCollection;
                import java.util.Set;

                @Entity
                public class Person {
                    @Id
                    private Long id;

                    @ElementCollection(targetClass = String.class)
                    private Set<String> tags;

                    public Long getId() {
                        return this.id;
                    }

                    public Set<String> getTags() {
                        return this.tags;
                    }
                }
                """;
        Files.writeString(sourceDir.resolve("Person.java"), source);

        final var compiler = new NabuCompiler();
        final var diags = new ArrayList<String>();
        compiler.setListener(diagnostic -> diags.add(
                diagnostic.getKind() + " " + diagnostic.getMessage(java.util.Locale.ROOT)
                        + " @" + diagnostic.getLineNumber() + ":" + diagnostic.getColumnNumber()
        ));

        final var options = new CompilerOptions.CompilerOptionsBuilder()
                .option(CompilerOption.SOURCE_PATH, tempDir.resolve("src").toString())
                .option(CompilerOption.CLASS_OUTPUT, outDir.toString())
                .option(CompilerOption.SOURCE_OUTPUT, genDir.toString())
                .option(
                        CompilerOption.CLASS_PATH,
                        getLocationOfClass(Entity.class)
                )
                .option(
                        CompilerOption.ANNOTATION_PROCESSOR_PATH,
                        getLocationOfClass(HibernateProcessor.class)
                )
                .option(CompilerOption.BACKEND, "ASM")
                .build();

        final var result = compiler.compile(options);

        System.out.println("== compile result=" + result);
        diags.forEach(d -> System.out.println("   DIAG: " + d));

        assertEquals(0, result, "compile moet slagen");

        final var generatedDir = genDir.resolve("io/github/potjerodekool/test");
        assertTrue(Files.exists(generatedDir.resolve("Person_.java")), "Person_.java gegenereerd");

        final var generatedSource = Files.readString(generatedDir.resolve("Person_.java"));
        System.out.println("== gegenereerd metamodel:\n" + generatedSource);

        assertTrue(
                generatedSource.contains("SetAttribute<Person, String>"),
                "tags moet als SetAttribute worden gegenereerd, was:\n" + generatedSource
        );
        assertFalse(generatedSource.contains("SingularAttribute<Person, Set>"),
                "tags mag niet als SingularAttribute<Person, Set> worden gegenereerd");

        final var classFile = outDir.resolve("io/github/potjerodekool/test/Person_.class");
        assertTrue(Files.exists(classFile), "Person_.class gecompileerd");

        try (var urlClassLoader = new java.net.URLClassLoader(
                new java.net.URL[]{outDir.toUri().toURL()},
                getClass().getClassLoader())) {
            final var clazz = Class.forName("io.github.potjerodekool.test.Person_", true, urlClassLoader);

            final var tagsField = clazz.getField("tags");
            assertEquals(
                    "jakarta.persistence.metamodel.SetAttribute",
                    tagsField.getType().getCanonicalName(),
                    "declared type van tags"
            );
            final var genericType = tagsField.getGenericType().getTypeName();
            System.out.println("== generic type van tags: " + genericType);
            assertEquals(
                    "jakarta.persistence.metamodel.SetAttribute<io.github.potjerodekool.test.Person, java.lang.String>",
                    genericType
            );

            final var classAttribute = clazz.getField("class_");
            final var className = classAttribute.getGenericType().getTypeName();
            assertFalse(className.contains("E<"), "class_ signature mag geen onopgelost typevar bevatten: " + className);
        }
    }

    private static String getLocationOfClass(final Class<?> clazz) {
        try {
            return Path.of(clazz.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }
}