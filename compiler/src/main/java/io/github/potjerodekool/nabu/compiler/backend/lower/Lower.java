package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.Element;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.backend.lower.widen.WideningConverter;
import io.github.potjerodekool.nabu.compiler.resolve.Boxer;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.Tree;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.Types;

import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.resolveType;

public class Lower extends AbstractTreeTranslator {

    private final WideningConverter wideningConverter;
    private final Boxer boxer;
    private final Caster caster;
    private final MethodResolver methodResolver;
    private final Types types;
    private final ClassElementLoader loader;
    private int varCounter = 0;

    public Lower(final CompilerContext compilerContext) {
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

        left = resolveType(right).accept(caster, left);
        right = resolveType(left).accept(caster, right);

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
    public Tree visitEnhancedForStatement(final EnhancedForStatement enhancedForStatement, final Scope scope) {
        final var expression = (ExpressionTree) enhancedForStatement.getExpression().accept(this, scope);
        final var localVariable = (CVariableDeclaratorStatement) enhancedForStatement.getLocalVariable().accept(this, scope);
        final var statement = (Statement) enhancedForStatement.getStatement().accept(this, scope);

        final var methodInvocation = new MethodInvocationTree()
                .target(expression)
                .name(new IdentifierTree("iterator"));

        methodInvocation.setMethodType(methodResolver.resolveMethod(methodInvocation));

        final var localVariableType = (DeclaredType) localVariable.getType().getType();
        final var iteratorName = generateVariableName();
        final var iteratorClassElement = loader.resolveClass("java.util.Iterator");

        final var iteratorType = types.getDeclaredType(iteratorClassElement, localVariableType);

        final var localVariableElement = new VariableBuilder()
                .kind(ElementKind.VARIABLE)
                .name(iteratorName)
                .type(iteratorType)
                .build();

        final var iteratorTypeTree = new TypeApplyTree(
                new IdentifierTree("java.util.Iterator"),
                null
        );
        iteratorTypeTree.setType(iteratorType);

        final var forInit = new CVariableDeclaratorStatement(
                iteratorTypeTree,
                createIdentifier(iteratorName, localVariableElement),
                methodInvocation
        );

        final var check = new MethodInvocationTree()
                .target(createIdentifier(
                        iteratorName,
                        localVariableElement
                ))
                .name(new IdentifierTree("hasNext"));

        check.setMethodType(methodResolver.resolveMethod(check));

        final var typeTree = createIdentifier(localVariableType);

        final var nextInvocation = new MethodInvocationTree()
                .target(createIdentifier(iteratorName, localVariableElement))
                .name(new IdentifierTree("next"));

        nextInvocation.setMethodType(methodResolver.resolveMethod(nextInvocation));

        final var castTypeTree = new IdentifierTree(typeTree.getName());

        castTypeTree.setType(iteratorType);

        final var cast = new CastExpressionTree()
                .expression(nextInvocation)
                        .targetType(castTypeTree);

        cast.setType(localVariableType);

        final var newBody = new BlockStatement();
        newBody.statement(localVariable.builder()
                        .type(typeTree)
                .value(cast)
                .build());

        if (statement instanceof BlockStatement blockStatement) {
            newBody.statement(blockStatement.getStatements());
        } else {
            newBody.statement(statement);
        }

        return new ForStatement(
                forInit,
                check,
                null,
                newBody
        );
    }

    private IdentifierTree createIdentifier(final DeclaredType declaredType) {
        final var classSymbol = (TypeElement) declaredType.asElement();
        final var className = classSymbol.getQualifiedName();

        final var identifier = new IdentifierTree(className);
        identifier.setType(declaredType);
        return identifier;
    }

    private IdentifierTree createIdentifier(final String name,
                                            final Element element) {
        final var identifier = new IdentifierTree(name);
        identifier.setSymbol(element);
        return identifier;
    }

    private String generateVariableName() {
        return "$p" + varCounter++;
    }

    public ExpressionTree unboxIfNeeded(final ExpressionTree left,
                                        final ExpressionTree right) {
        final var leftType = resolveType(left);
        final var rightType = resolveType(right);

        if (leftType instanceof DeclaredType
                && rightType instanceof PrimitiveType primitiveType) {
            return primitiveType.accept(boxer, left);
        }

        return left;
    }
}
