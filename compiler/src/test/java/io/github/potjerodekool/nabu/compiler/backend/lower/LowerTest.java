package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorBuilder;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LowerTest {

    private Lower lower;
    private ClassElementLoader loader;
    private Types types;

    @BeforeEach
    void setup() {
        loader = new AsmClassElementLoader();
        loader.postInit();
        this.types = loader.getTypes();
        final var context = mock(CompilerContext.class);
        final var methodResolver = mock(MethodResolver.class);

        final var clazz = new ClassBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .name("SomeClass")
                .build();

        final var method = new MethodBuilder()
                .name("someMethod")
                .enclosingElement(clazz)
                .build();

        when(methodResolver.resolveMethod(any(MethodInvocationTree.class), any()))
                .thenReturn(types.getExecutableType(
                        method,
                        List.of(),
                        null,
                        List.of(),
                        new ArrayList<>()
                ));

        when(context.getClassElementLoader())
                .thenReturn(loader);
        when(context.getMethodResolver())
                .thenReturn(methodResolver);

        lower = new Lower(context);
    }

    @Test
    void visitBinaryExpression() {
        assertBinaryExpression(
                """
                        shortValue > value.shortValue()""",
                createParameterIdentifier("shortValue", types.getPrimitiveType(TypeKind.SHORT)),
                createParameterIdentifier("value", loader.loadClass(Constants.SHORT).asType())
        );

        assertBinaryExpression(
                """
                        intValue > value.intValue()""",
                createParameterIdentifier("intValue", types.getPrimitiveType(TypeKind.INT)),
                createParameterIdentifier("value", loader.loadClass(Constants.INTEGER).asType())
        );

        assertBinaryExpression(
                """
                        floatValue > value.floatValue()""",
                createParameterIdentifier("floatValue", types.getPrimitiveType(TypeKind.FLOAT)),
                createParameterIdentifier("value", loader.loadClass(Constants.FLOAT).asType())
        );

        assertBinaryExpression(
                """
                        longValue > value.longValue()""",
                createParameterIdentifier("longValue", types.getPrimitiveType(TypeKind.LONG)),
                createParameterIdentifier("value", loader.loadClass(Constants.LONG).asType())
        );

        assertBinaryExpression(
                """
                        doubleValue > value.doubleValue()""",
                createParameterIdentifier("doubleValue", types.getPrimitiveType(TypeKind.DOUBLE)),
                createParameterIdentifier("value", loader.loadClass(Constants.DOUBLE).asType())
        );
    }

    private void assertBinaryExpression(final String expected,
                                        final ExpressionTree left,
                                        final ExpressionTree right) {
        final var binaryExpression = TreeMaker.binaryExpressionTree(
                left,
                Tag.GT,
                right,
                -1,
                -1
        );

        final var newBinaryExpression = binaryExpression.accept(lower, null);
        final var actual = TreePrinter.print(newBinaryExpression);
        assertEquals(expected, actual);
    }


    @Test
    void visitEnhancedForStatement() {
        final var stringType = loader.loadClass(Constants.STRING).asType();
        final var listType = loader.loadClass("java.util.List").asType();
        final var stringListType = types.getDeclaredType(
                listType.getTypeElement(),
                stringType
        );

        final var listTypeTree = TreeMaker.typeApplyTree(
                createIdentifier("List", listType),
                List.of(createIdentifier("String", stringType)),
                -1,
                -1
        );

        final var stringTypeTree = createIdentifier(
                "String",
                stringType
        );

        listTypeTree.setType(stringListType);

        final var localVariable = new VariableDeclaratorBuilder()
                .kind(Kind.LOCAL_VARIABLE)
                .modifiers(new CModifiers())
                .type(stringTypeTree)
                .name(IdentifierTree.create("s"))
                .build();

        final var enhancedForStatement = TreeMaker.enhancedForStatement(
                localVariable,
                createIdentifier("list", stringListType),
                TreeMaker.blockStatement(List.of(), -1, -1),
                -1,
                -1
        );

        final var result = enhancedForStatement.accept(lower, null);
        final var actual = TreePrinter.print(result);

        assertEquals(
                """
                        for (java.util.Iterator $p0 = list.iterator();
                        $p0.hasNext();){
                        java.lang.String s = (java.lang.String) $p0.next();
                        }
                        """,
                actual
        );
    }

    private IdentifierTree createParameterIdentifier(final String name,
                                                     final TypeMirror typeMirror) {
        final var identifierTree = IdentifierTree.create(name);

        final var paramElement = new VariableBuilder()
                .kind(ElementKind.PARAMETER)
                .name(name)
                .type(typeMirror)
                .build();

        identifierTree.setSymbol(paramElement);
        return identifierTree;
    }

    private IdentifierTree createIdentifier(final String name,
                                            final TypeMirror typeMirror) {
        final var tree = IdentifierTree.create(name);
        tree.setType(typeMirror);
        return tree;
    }
}