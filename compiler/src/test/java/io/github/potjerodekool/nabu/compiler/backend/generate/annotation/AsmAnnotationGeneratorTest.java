package io.github.potjerodekool.nabu.compiler.backend.generate.annotation;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.backend.generate.asm.annotation.AsmAnnotationGenerator;
import io.github.potjerodekool.nabu.compiler.resolve.impl.ClassUtils;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.type.DeclaredType;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.github.potjerodekool.nabu.compiler.backend.generate.asm.AsmUtils.isVisible;
import static org.junit.jupiter.api.Assertions.*;

class AsmAnnotationGeneratorTest {

    @Test
    void generateAnnotationWithLiteral() {
        final var deprecatedClass = new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .simpleName("Deprecated")
                .build();

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) deprecatedClass.asType(),
                Map.of(
                        new MethodSymbolBuilderImpl()
                                .simpleName("since")
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

        final var descriptor = ClassUtils.getDescriptor(annotation.getAnnotationType());
        final var annotationVisitor = classWriterMock.visitAnnotation(
                descriptor,
                isVisible(annotation)
        );

        AsmAnnotationGenerator.generate(
                annotation,
                annotationVisitor
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LDeprecated;(since="0.2") // invisible
                """;
        assertEquals(expected, actual);
    }

    @Test
    void generateAnnotationEnumValue() {
        final var transactionalClass = new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .simpleName("Transactional")
                .build();

        final var txTypeType = new ClassSymbolBuilder()
                .kind(ElementKind.ENUM)
                .simpleName("TxType")
                .build()
                .asType();

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) transactionalClass.asType(),
                Map.of(
                        new MethodSymbolBuilderImpl()
                                .simpleName("value")
                                .build(),
                        AnnotationBuilder.createEnumValue(
                                (DeclaredType) txTypeType,
                                new VariableSymbolBuilderImpl()
                                        .simpleName("REQUIRED")
                                        .type(transactionalClass.asType())
                                        .enclosingElement(txTypeType.asTypeElement())
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

        final var descriptor = ClassUtils.getDescriptor(annotation.getAnnotationType());
        final var annotationVisitor = classWriterMock.visitAnnotation(
                descriptor,
                isVisible(annotation)
        );

        AsmAnnotationGenerator.generate(
                annotation,
                annotationVisitor
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LTransactional;(value=LTxType;.REQUIRED) // invisible
                """;
        assertEquals(expected, actual);
    }

    @Test
    void generateAnnotationArrayValue() {
        final var suppressWarningsClass = new ClassSymbolBuilder()
                .kind(ElementKind.ANNOTATION_TYPE)
                .simpleName("SuppressWarnings")
                .build();

        final var stringClazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("String")
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
                        new MethodSymbolBuilderImpl()
                                .simpleName("value")
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

        final var descriptor = ClassUtils.getDescriptor(annotation.getAnnotationType());
        final var annotationVisitor = classWriterMock.visitAnnotation(
                descriptor,
                isVisible(annotation)
        );

        AsmAnnotationGenerator.generate(
                annotation,
                annotationVisitor
        );

        final var actual = asText(printer.getText());

        final var expected = """
                
                  @LSuppressWarnings;(value={"foo", "bar"}) // invisible
                """;
        assertEquals(expected, actual);
    }

    @Test
    void generateAnnotationWithAnnotation() {
        final var parentClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Parent")
                .build();

        final var childClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Child")
                .build();

        final AnnotationValue childAnnotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) childClass.asType(),
                Map.of()
        );

        final var annotation = AnnotationBuilder.createAnnotation(
                (DeclaredType) parentClass.asType(),
                Map.of(
                        new MethodSymbolBuilderImpl()
                                .simpleName("child")
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

        final var descriptor = ClassUtils.getDescriptor(annotation.getAnnotationType());
        final var annotationVisitor = classWriterMock.visitAnnotation(
                descriptor,
                isVisible(annotation)
        );

        AsmAnnotationGenerator.generate(
                annotation,
                annotationVisitor
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