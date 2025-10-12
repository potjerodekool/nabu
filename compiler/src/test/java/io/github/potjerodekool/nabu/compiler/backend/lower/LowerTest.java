package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.test.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.test.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.tree.impl.CConstantCaseLabel;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.tree.statement.builder.SwitchStatementBuilder;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CCaseStatement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LowerTest extends AbstractCompilerTest {

    private final ClassSymbolLoader loader = new TestClassElementLoader();

    private final Types types = loader.getTypes();
    private Lower lower;

    @BeforeEach
    void setup() {
        final var methodResolver = mock(MethodResolver.class);

        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName("SomeClass")
                .build();

        final var method = new MethodSymbolBuilderImpl()
                .simpleName("someMethod")
                .enclosingElement(clazz)
                .build();

        when(methodResolver.resolveMethod(any(MethodInvocationTree.class)))
                .thenReturn(Optional.of(types.getExecutableType(
                        method,
                        List.of(),
                        null,
                        List.of(),
                        new ArrayList<>()
                )));

        lower = new Lower(getCompilerContext());
    }

    @Test
    void visitBinaryExpression() {
        final var module = loader.getSymbolTable().getUnnamedModule();

        assertBinaryExpression(
                """
                        shortValue > value.shortValue()""",
                createParameterIdentifier("shortValue", types.getPrimitiveType(TypeKind.SHORT)),
                createParameterIdentifier("value", loader.loadClass(module, Constants.SHORT).asType())
        );

        assertBinaryExpression(
                """
                        intValue > value.intValue()""",
                createParameterIdentifier("intValue", types.getPrimitiveType(TypeKind.INT)),
                createParameterIdentifier("value", loader.loadClass(module, Constants.INTEGER).asType())
        );

        assertBinaryExpression(
                """
                        floatValue > value.floatValue()""",
                createParameterIdentifier("floatValue", types.getPrimitiveType(TypeKind.FLOAT)),
                createParameterIdentifier("value", loader.loadClass(module, Constants.FLOAT).asType())
        );

        assertBinaryExpression(
                """
                        longValue > value.longValue()""",
                createParameterIdentifier("longValue", types.getPrimitiveType(TypeKind.LONG)),
                createParameterIdentifier("value", loader.loadClass(module, Constants.LONG).asType())
        );

        assertBinaryExpression(
                """
                        doubleValue > value.doubleValue()""",
                createParameterIdentifier("doubleValue", types.getPrimitiveType(TypeKind.DOUBLE)),
                createParameterIdentifier("value", loader.loadClass(module, Constants.DOUBLE).asType())
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
        final var module = loader.getSymbolTable().getJavaBase();
        final var stringType = loader.loadClass(module, Constants.STRING).asType();
        final var listType = loader.loadClass(module, "java.util.List").asType();
        final var stringListType = types.getDeclaredType(
                listType.asTypeElement(),
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

        final var localVariable = new VariableDeclaratorTreeBuilder()
                .kind(Kind.LOCAL_VARIABLE)
                .modifiers(new Modifiers())
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

        final var pck = mock(PackageSymbol.class);
        when(pck.getModuleSymbol())
                .thenReturn(module);

        final var clazz = mock(ClassSymbol.class);
        when(clazz.getEnclosingElement())
                .thenReturn(pck);

        final var method = mock(MethodSymbol.class);
        when(method.getEnclosingElement())
                .thenReturn(clazz);


        final var classType = mock(DeclaredType.class);
        when(classType.asTypeElement())
                .thenReturn(clazz);

        final var cu = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                0,
                0
        );

        final var lowerContext = new LowerContext(cu);

        final var result = enhancedForStatement.accept(lower, lowerContext);
        final var actual = TreePrinter.print(result);

        assertEquals(
                """
                        for (var $p0 : java.util.Iterator = list.iterator();$p0.hasNext();){
                            var s : java.lang.String = (java.lang.String) $p0.next();
                        }
                        """,
                actual
        );
    }

    @Test
    void visitSwitchWithEnum() {
        final var methods = SomEnum.class.getDeclaredMethods();

        final var packageSymbol = loader.findOrCreatePackage(
                null,
                "foo.bar"
        );

        final var currentClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("TT")
                .enclosingElement(packageSymbol)
                .build();

        currentClass.setMembers(new WritableScope());

        final var currentClassTree = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .nestingKind(io.github.potjerodekool.nabu.tree.element.NestingKind.TOP_LEVEL)
                .simpleName("TT")
                .modifiers(new Modifiers())
                .build();

        currentClassTree.setClassSymbol(currentClass);

        final var cu = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                0,
                0
        );


        getCompilerContext().getTypeEnter().put(
                currentClass,
                currentClassTree,
                cu
        );

        currentClass.setCompleter(getCompilerContext().getTypeEnter());
        currentClass.complete();

        final var enumClass = loader.loadClass(
                null,
                Constants.ENUM
        );

        final var stateClass = new ClassSymbolBuilder()
                .simpleName("State")
                .enclosingElement(packageSymbol)
                .kind(ElementKind.ENUM)
                .superclass(enumClass.asType())
                .build();

        final var onConstant = new VariableSymbolBuilderImpl()
                .kind(ElementKind.ENUM_CONSTANT)
                .simpleName("ON")
                .type(null)
                .build();

        stateClass.addEnclosedElement(onConstant);

        stateClass.setMembers(new WritableScope());

        final var selector = IdentifierTree.create("state");

        final var stateVariable = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("state")
                .type(stateClass.asType())
                .build();

        selector.setSymbol(stateVariable);

        final var caselabel = new CConstantCaseLabel(
                IdentifierTree.create("ON"),
                -1,
                -1
        );

        final var caseStatement = new CCaseStatement(
                CaseStatement.CaseKind.STATEMENT,
                List.of(caselabel),
                new CBlockStatementTree(List.of()),
                -1,
                -1
        );

        final var switchStatement = new SwitchStatementBuilder()
                .selector(selector)
                .cases(List.of(caseStatement))
                .build();

        final var lowerContext = new LowerContext(cu);
        lowerContext.currentClass = currentClassTree;

        final var result = switchStatement.accept(lower, lowerContext);
        final var actual = TreePrinter.print(result);
        final var expected = """
                switch(foo.bar.TT$1.$SwitchMap$foo$bar$State[state.ordinal()])
                {
                    case ON : {

                    }

                }
                """;

        assertEquals(
                expected,
                actual
        );
    }

    private IdentifierTree createParameterIdentifier(final String name,
                                                     final TypeMirror typeMirror) {
        final var identifierTree = IdentifierTree.create(name);

        final var paramElement = new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName(name)
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

enum SomEnum {

}