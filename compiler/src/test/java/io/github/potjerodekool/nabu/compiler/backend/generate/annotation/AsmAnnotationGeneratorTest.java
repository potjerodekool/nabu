package io.github.potjerodekool.nabu.compiler.backend.generate.annotation;

import io.github.potjerodekool.nabu.compiler.ast.element.AnnotationValue;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.annotation.AsmAnnotationGenerator;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AsmAnnotationGeneratorTest {

    @Test
    void generateAnnotationWithLiteral() {
        final var deprecatedClass = new ClassBuilder()
                .name("Deprecated")
                .build();

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) deprecatedClass.asType(),
                Map.of(
                        new MethodBuilder()
                                .name("since")
                                .build(),
                        AnnotationBuilder.createConstantValue("0.2")
                )
        );

        final var output = new ByteArrayOutputStream();
        final var writer = new PrintWriter(output);
        final var printer = new Textifier();

        final TraceClassVisitor classWriterMock = new TraceClassVisitor(
                null,
                printer,
                writer
        );

        AsmAnnotationGenerator.generate(
                annotation,
                classWriterMock
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LDeprecated;(since="0.2") // invisible
                """;
        assertEquals(expected, actual);
    }

    @Test
    void generateAnnotationEnumValue() {
        final var transactionalClass = new ClassBuilder()
                .name("Transactional")
                .build();

        final var txTypeType = new ClassBuilder()
                .name("TxType")
                .build()
                .asType();

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) transactionalClass.asType(),
                Map.of(
                        new MethodBuilder()
                                .name("value")
                                .build(),
                        AnnotationBuilder.createEnumValue(
                                (DeclaredType) txTypeType,
                                new VariableBuilder()
                                        .name("REQUIRED")
                                        .type(transactionalClass.asType())
                                        .enclosingElement(txTypeType.getTypeElement())
                                        .build()
                        )
                )
        );

        final var output = new ByteArrayOutputStream();
        final var writer = new PrintWriter(output);
        final var printer = new Textifier();

        final TraceClassVisitor classWriterMock = new TraceClassVisitor(
                null,
                printer,
                writer
        );

        AsmAnnotationGenerator.generate(
                annotation,
                classWriterMock
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LTransactional;(value=LTxType;.REQUIRED) // invisible
                """;
        assertEquals(expected, actual);
    }

    @Test
    void generateAnnotationArrayValue() {
        final var suppressWarningsClass = new ClassBuilder()
                .name("SuppressWarnings")
                .build();

        final var stringClazz = new ClassBuilder()
                .name("String")
                .build();

        final var arrayValue = AnnotationBuilder.createArrayValue(
                stringClazz.asType(),
                List.of(
                        AnnotationBuilder.createConstantValue("foo"),
                        AnnotationBuilder.createConstantValue("bar")
                )
        );

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) suppressWarningsClass.asType(),
                Map.of(
                        new MethodBuilder()
                                .name("value")
                                .build(),
                        arrayValue
                )
        );

        final var output = new ByteArrayOutputStream();
        final var writer = new PrintWriter(output);
        final var printer = new Textifier();

        final TraceClassVisitor classWriterMock = new TraceClassVisitor(
                null,
                printer,
                writer
        );

        AsmAnnotationGenerator.generate(
                annotation,
                classWriterMock
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LSuppressWarnings;(value={"foo", "bar"}) // invisible
                """;
        assertEquals(expected, actual);
    }

    @Test
    void generateAnnotationWithAnnotation() {
        final var parentClass = new ClassBuilder()
                .name("Parent")
                .build();

        final var childClass = new ClassBuilder()
                .name("Child")
                .build();

        final AnnotationValue childAnnotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) childClass.asType(),
                Map.of()
        );

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) parentClass.asType(),
                Map.of(
                        new MethodBuilder()
                                .name("child")
                                .build(),
                        childAnnotation
                )
        );

        final var output = new ByteArrayOutputStream();
        final var writer = new PrintWriter(output);
        final var printer = new Textifier();

        final TraceClassVisitor classWriterMock = new TraceClassVisitor(
                null,
                printer,
                writer
        );

        AsmAnnotationGenerator.generate(
                annotation,
                classWriterMock
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LParent;(child=@LChild;()) // invisible
                """;
        assertEquals(expected, actual);
    }

    private String asText(final List<?> list) {
        return list.stream()
                .map(this::asString)
                .collect(Collectors.joining(""));
    }

    private String asString(final Object value) {
        if (value instanceof String s) {
            return s;
        } else if (value instanceof List<?> list) {
            return asText(list);
        } else {
            throw new UnsupportedOperationException();
        }
    }

}