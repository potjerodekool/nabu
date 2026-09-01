package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LambdaDesugarTest {

    @Test
    void lambdaContextGeneratesUniqueNames() {
        final var context = new LambdaContext();
        final var name1 = context.generateLambdaMethodName("main");
        final var name2 = context.generateLambdaMethodName("main");

        assertEquals("lambda$main$0", name1);
        assertEquals("lambda$main$1", name2);
    }

    @Test
    void lambdaContextGeneratesIndependentCountersPerFunction() {
        final var context = new LambdaContext();
        final var name1 = context.generateLambdaMethodName("foo");
        final var name2 = context.generateLambdaMethodName("bar");
        final var name3 = context.generateLambdaMethodName("foo");

        assertEquals("lambda$foo$0", name1);
        assertEquals("lambda$bar$0", name2);
        assertEquals("lambda$foo$1", name3);
    }

    @Test
    void simpleScopeDefinesAndResolvesLocal() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var scope = new SimpleScope(tree, context);

        final var element = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("x")
                .build();

        scope.define(element);

        assertTrue(scope.locals().contains("x"));
        assertSame(element, scope.resolve("x"));
    }

    @Test
    void simpleScopeReturnsNullForUnresolved() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var scope = new SimpleScope(tree, context);

        assertNull(scope.resolve("unknown"));
    }

    @Test
    void simpleScopeResolvesFromParent() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var childScope = parentScope.childScope(tree);

        final var element = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("y")
                .build();

        parentScope.define(element);

        assertTrue(childScope.locals().isEmpty());
        assertSame(element, childScope.resolve("y"));
    }

    @Test
    void simpleScopeChildScopeHasCorrectParent() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var childScope = parentScope.childScope(tree);

        assertSame(parentScope, childScope.getParent());
    }

    @Test
    void lambdaScopeGetOwnerReturnsTree() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var scope = new SimpleScope(tree, context);

        assertSame(tree, scope.getOwner());
    }

    @Test
    void lambdaScopeGetLambdaContextReturnsContext() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var scope = new SimpleScope(tree, context);

        assertSame(context, scope.getLambdaContext());
    }

    @Test
    void immutableScopeReturnsEmptyLocals() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var immutableScope = new ImmutableScope(parentScope, tree);

        assertTrue(immutableScope.locals().isEmpty());
    }

    @Test
    void immutableScopeDefineIsNoOp() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var immutableScope = new ImmutableScope(parentScope, tree);

        final var element = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("x")
                .build();

        immutableScope.define(element);

        assertTrue(immutableScope.locals().isEmpty());
    }

    @Test
    void immutableScopeResolveReturnsNull() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var immutableScope = new ImmutableScope(parentScope, tree);

        assertNull(immutableScope.resolve("anything"));
    }

    @Test
    void immutableScopeChildScopeReturnsSameScopeForSameOwner() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var immutableScope = new ImmutableScope(parentScope, tree);

        final var child = immutableScope.childScope(tree);
        assertSame(immutableScope, child);
    }

    @Test
    void immutableScopeChildScopeReturnsNewScopeForDifferentOwner() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var otherTree = TreeMaker.identifier("other", 2, 2);
        final var parentScope = new SimpleScope(tree, context);
        final var immutableScope = new ImmutableScope(parentScope, tree);

        final var child = immutableScope.childScope(otherTree);
        assertNotSame(immutableScope, child);
        assertSame(immutableScope, child.getParent());
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveType() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType intType = new CPrimitiveType(TypeKind.INT);

        final var result = intType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitNoType() {
        final var creator = new TypeExpressionCreator();
        final var noType = new io.github.potjerodekool.nabu.compiler.type.impl.CNoType();

        final var result = noType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeBoolean() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType boolType = new CPrimitiveType(TypeKind.BOOLEAN);

        final var result = boolType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeLong() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType longType = new CPrimitiveType(TypeKind.LONG);

        final var result = longType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeChar() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType charType = new CPrimitiveType(TypeKind.CHAR);

        final var result = charType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeFloat() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType floatType = new CPrimitiveType(TypeKind.FLOAT);

        final var result = floatType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeDouble() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType doubleType = new CPrimitiveType(TypeKind.DOUBLE);

        final var result = doubleType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeShort() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType shortType = new CPrimitiveType(TypeKind.SHORT);

        final var result = shortType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitPrimitiveTypeByte() {
        final var creator = new TypeExpressionCreator();
        final PrimitiveType byteType = new CPrimitiveType(TypeKind.BYTE);

        final var result = byteType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitWildcardType() {
        final var creator = new TypeExpressionCreator();
        final var wildcardType = new io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType(
                null,
                io.github.potjerodekool.nabu.type.BoundKind.UNBOUND,
                null
        );

        final var result = wildcardType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitVariableType() {
        final var creator = new TypeExpressionCreator();
        final var variableType = new io.github.potjerodekool.nabu.compiler.type.impl.CVariableType(null);

        final var result = variableType.accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void lambdaScopeGetCurrentFunctionDeclarationFromParent() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var childScope = parentScope.childScope(tree);

        assertNull(childScope.getCurrentFunctionDeclaration());
    }

    @Test
    void lambdaScopeGetCurrentClassDeclarationFromParent() {
        final var context = new LambdaContext();
        final var tree = TreeMaker.identifier("owner", 1, 1);
        final var parentScope = new SimpleScope(tree, context);
        final var childScope = parentScope.childScope(tree);

        assertNull(childScope.getCurrentClassDeclaration());
    }

    @Test
    void lambdaScopeGetCurrentFunctionWhenOwnerIsFunction() {
        final var context = new LambdaContext();
        final var function = new FunctionBuilder()
                .kind(Kind.METHOD)
                .simpleName("main")
                .build();
        final var scope = new SimpleScope(function, context);

        assertSame(function, scope.getCurrentFunctionDeclaration());
    }

    @Test
    void lambdaScopeGetCurrentClassWhenOwnerIsClass() {
        final var context = new LambdaContext();
        final var classDeclaration = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .simpleName("Foo")
                .build();
        final var scope = new SimpleScope(classDeclaration, context);

        assertSame(classDeclaration, scope.getCurrentClassDeclaration());
    }

    @Test
    void typeExpressionCreatorVisitUnknownTypeThrows() {
        final var creator = new TypeExpressionCreator();
        final var unknownType = new io.github.potjerodekool.nabu.compiler.type.impl.CUnknownType();

        assertThrows(UnsupportedOperationException.class, () -> unknownType.accept(creator, null));
    }

    @Test
    void typeExpressionCreatorVisitDeclaredType() {
        final var creator = new TypeExpressionCreator();
        final var stringClass = new io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol(0, "java.lang.String", null);

        final var result = stringClass.asType().accept(creator, null);

        assertNotNull(result);
    }

    @Test
    void typeExpressionCreatorVisitWildcardTypeWithBound() {
        final var creator = new TypeExpressionCreator();
        final var bound = new CPrimitiveType(TypeKind.INT);
        final var wildcardType = new io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType(
                bound, io.github.potjerodekool.nabu.type.BoundKind.EXTENDS, null
        );

        final var result = wildcardType.accept(creator, null);

        assertNotNull(result);
    }
}
