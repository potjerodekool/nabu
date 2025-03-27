package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.SymbolBuilders;
import io.github.potjerodekool.nabu.compiler.backend.lower.widen.WideningConverter;
import io.github.potjerodekool.nabu.compiler.resolve.Boxer;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;
import io.github.potjerodekool.nabu.compiler.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.CModifiers;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.ArrayList;
import java.util.List;

public class Lower extends AbstractTreeTranslator {

    private final CompilerContext compilerContext;
    private final WideningConverter wideningConverter;
    private final Boxer boxer;
    private final Caster caster;
    private final MethodResolver methodResolver;
    private final Types types;
    private final ClassElementLoader loader;
    private int varCounter = 0;

    public Lower(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getClassElementLoader().getTypes();
        this.boxer = new Boxer(
                loader,
                compilerContext.getMethodResolver()
        );
        this.caster = new Caster();
        this.wideningConverter = new WideningConverter(types);
        this.methodResolver = compilerContext.getMethodResolver();
    }

    @Override
    public Tree visitCompilationUnit(final CompilationUnit compilationUnit, final Scope scope) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);
        return super.visitCompilationUnit(compilationUnit, globalScope);
    }

    @Override
    public Tree visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                      final Scope scope) {
        var left = wideningConverter.convert(
                binaryExpression.getLeft(),
                binaryExpression.getRight()
        );

        var right = wideningConverter.convert(
                binaryExpression.getRight(),
                binaryExpression.getLeft()
        );

        left = TreeUtils.typeOf(right).accept(caster, left);
        right = TreeUtils.typeOf(left).accept(caster, right);

        left = unboxIfNeeded(left, right);
        right = unboxIfNeeded(right, left);

        if (left != binaryExpression.getLeft()
                || right != binaryExpression.getRight()) {
            return binaryExpression.builder()
                    .left(left)
                    .right(right)
                    .build();
        }

        return binaryExpression;
    }

    @Override
    public Tree visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final Scope scope) {
        final var module = scope.findModuleElement();

        final var expression = (ExpressionTree) enhancedForStatement.getExpression().accept(this, scope);
        final var localVariable = (VariableDeclaratorTree) enhancedForStatement.getLocalVariable().accept(this, scope);
        final var statement = (StatementTree) enhancedForStatement.getStatement().accept(this, scope);

        var methodInvocation = TreeMaker.methodInvocationTree(
                expression,
                IdentifierTree.create("iterator"),
                List.of(),
                List.of(),
                -1,
                -1
        );

        methodInvocation.setMethodType(methodResolver.resolveMethod(methodInvocation, null));

        final var localVariableType = (DeclaredType) localVariable.getType().getType();
        final var iteratorName = generateVariableName();
        final var iteratorClassElement = loader.loadClass(
                module,
                "java.util.Iterator"
        );

        final var iteratorType = types.getDeclaredType(iteratorClassElement, localVariableType);

        final var localVariableElement = SymbolBuilders.variableSymbolBuilder()
                .kind(ElementKind.LOCAL_VARIABLE)
                .name(iteratorName)
                .type(iteratorType)
                .build();

        final var iteratorTypeTree = TreeMaker.typeApplyTree(
                IdentifierTree.create("java.util.Iterator"),
                List.of(),
                -1,
                -1
        );

        iteratorTypeTree.setType(iteratorType);

        final var forInit = new VariableDeclaratorTreeBuilder()
                .kind(Kind.LOCAL_VARIABLE)
                .modifiers(new CModifiers())
                .type(iteratorTypeTree)
                .name(createIdentifier(iteratorName, localVariableElement))
                .value(methodInvocation)
                .build();

        final var check = TreeMaker.methodInvocationTree(
                createIdentifier(
                        iteratorName,
                        localVariableElement
                ),
                IdentifierTree.create(
                        "hasNext"
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        check.setMethodType(methodResolver.resolveMethod(check, null));

        final var typeTree = createIdentifier(localVariableType);

        final var nextInvocation = TreeMaker.methodInvocationTree(
                createIdentifier(iteratorName, localVariableElement),
                IdentifierTree.create("next"),
                List.of(),
                List.of(),
                -1,
                -1
        );

        nextInvocation.setMethodType(methodResolver.resolveMethod(nextInvocation, null));

        final var castTypeTree = IdentifierTree.create(typeTree.getName());

        castTypeTree.setType(iteratorType);

        final var cast = TreeMaker.castExpressionTree(
                castTypeTree,
                nextInvocation,
                -1,
                -1
        );

        cast.setType(localVariableType);

        final var statements = new ArrayList<StatementTree>();
        statements.add(localVariable.builder()
                .type(typeTree)
                .value(cast)
                .build());

        if (statement instanceof BlockStatementTree blockStatement) {
            statements.addAll(blockStatement.getStatements());
        } else {
            statements.add(statement);
        }

        final var newBody = TreeMaker.blockStatement(
                statements,
                -1,
                -1
        );

        return TreeMaker.forStatement(
                List.of(forInit),
                check,
                List.of(),
                newBody,
                -1,
                -1
        );
    }

    private IdentifierTree createIdentifier(final DeclaredType declaredType) {
        final var classSymbol = (TypeElement) declaredType.asElement();
        final var className = classSymbol.getQualifiedName();
        final var identifier = IdentifierTree.create(className);
        identifier.setType(declaredType);
        return identifier;
    }

    private IdentifierTree createIdentifier(final String name,
                                            final Element element) {
        final var identifier = IdentifierTree.create(name);
        identifier.setSymbol(element);
        return identifier;
    }

    private String generateVariableName() {
        return "$p" + varCounter++;
    }

    public ExpressionTree unboxIfNeeded(final ExpressionTree left,
                                        final ExpressionTree right) {
        final var leftType = TreeUtils.typeOf(left);
        final var rightType = TreeUtils.typeOf(right);

        if (leftType instanceof DeclaredType
                && rightType instanceof PrimitiveType primitiveType) {
            return primitiveType.accept(boxer, left);
        }

        return left;
    }
}
