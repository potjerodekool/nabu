package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.lang.model.element.builder.TypeElementBuilder;
import io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.Lombok;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.type.DeclaredType;
import lombok.Getter;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LombokTest extends AbstractCompilerTest {


    @Override
    protected String getClassPath() {
        return createClassPath(
                getLocationOfClass(Getter.class)
        );
    }

    @Test
    void apply() {
        final var context = getCompilerContext();
        final var module = context.getModules().getUnnamedModule();
        final var loader = context.getClassElementLoader();

        final var getterAnnotation = TreeMaker.annotationTree(IdentifierTree.create("Getter"), List.of(), 0, 0);
        final var getterType = loader.loadClass(module, "lombok.Getter").asType();
        getterAnnotation.setType(getterType);

        final var packageIdentifier = IdentifierTree.create("PACKAGE");
        packageIdentifier.setSymbol(
                context.getElementBuilders().variableElementBuilder()
                        .simpleName("PACKAGE")
                        .build()
        );

        final var accessLevelValue = TreeMaker.fieldAccessExpressionTree(
                IdentifierTree.create("AccessLevel"),
                packageIdentifier,
                0,
                0
        );

        final var packageAccessLevel = TreeMaker.assignmentExpression(
                IdentifierTree.create("value"),
                accessLevelValue,
                0,
                0
        );

        final var setterAnnotation = TreeMaker.annotationTree(IdentifierTree.create("Setter"), List.of(
                packageAccessLevel
        ), 0, 0);
        final var setterType = loader.loadClass(module, "lombok.Setter").asType();
        setterAnnotation.setType(setterType);

        final var packageAccess = getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("PACKAGE")
                .build();

        final var clazz = createClass(
                builder -> {
                    builder
                            .enclosedElement(createFieldForClass(Constants.STRING, "id"))
                            .enclosedElement(createFieldForClass(Constants.STRING, "firstName"))
                            .enclosedElement(createFieldForClass(Constants.STRING, "lastName"));

                    builder.annotations(
                            createAnnotation("lombok.Getter"),
                            createAnnotation("lombok.Setter",
                                    Map.of(createMethod("value"), new CClassAttribute(packageAccess.asType()))
                            )
                    );
                }
        );

        final var classDeclaration = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .simpleName("Person")
                .enclosedElements(List.of(
                        createField(Constants.STRING, "id"),
                        createField(Constants.STRING, "firstName"),
                        createField(Constants.STRING, "lastName")
                ))
                .modifiers(
                        new Modifiers(
                                List.of(
                                        getterAnnotation,
                                        setterAnnotation
                                ),
                                0
                        )
                )
                .build();

        final var lombok = new Lombok(context);
        classDeclaration.setClassSymbol(clazz);
        lombok.apply(classDeclaration);

        final var writer = new StringWriter();

        getCompilerContext().getElements().printElements(
                writer,
                clazz
        );

        final var actual = fixNewLines(writer.getBuffer().toString());
        final var expected = loadResource("lombok-files/Person.nabu");
        assertEquals(expected, actual);
    }

    private String fixNewLines(final String text) {
        return text.replace("\r", "");
    }

    private VariableDeclaratorTree createField(final String className,
                                               final String fieldName) {
        final var context = getCompilerContext();
        final var loader = context.getClassElementLoader();
        final var stringType = loader.loadClass(null, className).asType();

        final var fieldTypeIdentifier = IdentifierTree.create(className);
        fieldTypeIdentifier.setType(stringType);

        return new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .name(IdentifierTree.create(fieldName))
                .variableType(fieldTypeIdentifier)
                .build();
    }

    private ClassSymbol createClass(final Consumer<TypeElementBuilder<?>> consumer) {
        final var builder = getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Person");

        consumer.accept(builder);

        return (ClassSymbol) builder
                .build();
    }

    private VariableElement createFieldForClass(final String className,
                                                final String fieldName) {
        final var context = getCompilerContext();
        final var loader = context.getClassElementLoader();
        final var type = loader.loadClass(null, className).asType();

        return getCompilerContext().getElementBuilders()
                .variableElementBuilder()
                .kind(ElementKind.FIELD)
                .type(type)
                .simpleName(fieldName)
                .build();
    }

    private ExecutableElement createMethod(final String name) {
        final var context = getCompilerContext();
        return context.getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName(name)
                .build();
    }

    private AnnotationMirror createAnnotation(final String className) {
        final var context = getCompilerContext();
        final var loader = context.getClassElementLoader();
        final var type = (DeclaredType) loader.loadClass(null, className).asType();

        return AnnotationBuilder.createAnnotation(
                type,
                Collections.emptyMap()
        );
    }

    private AnnotationMirror createAnnotation(final String className,
                                              final Map<ExecutableElement, AnnotationValue> values) {
        final var context = getCompilerContext();
        final var loader = context.getClassElementLoader();
        final var type = (DeclaredType) loader.loadClass(null, className).asType();

        return AnnotationBuilder.createAnnotation(
                type,
                values
        );
     }

}