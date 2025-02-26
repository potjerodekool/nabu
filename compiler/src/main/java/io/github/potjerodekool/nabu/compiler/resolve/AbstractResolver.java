package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.VariableBuilder;
import io.github.potjerodekool.nabu.compiler.resolve.scope.LocalScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.element.Variable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;

import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.resolveType;

public abstract class AbstractResolver extends AbstractTreeVisitor<Object, Scope> {

    protected final CompilerContext compilerContext;
    protected final ClassElementLoader loader;
    protected final Types types;

    protected AbstractResolver(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
    }

    @Override
    public Object visitVariableType(final VariableTypeTree variableType, final Scope scope) {
        if (variableType.getType() == null) {
            variableType.setType(types.getVariableType(null));
        }
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement,
                                                   final Scope scope) {
        final var result = super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        var type = variableDeclaratorStatement.getType().getType();
        final var identifier = variableDeclaratorStatement.getIdent();

        if (type instanceof VariableType) {
            if (variableDeclaratorStatement.getValue() == null) {
                throw new TodoException();
            }

            final var interferedType = resolveType(variableDeclaratorStatement.getValue());
            type = types.getVariableType(interferedType);
            variableDeclaratorStatement.getType().setType(type);
        }

        final var varElement = new VariableBuilder()
                .kind(ElementKind.VARIABLE)
                .name(identifier.getName())
                .type(type)
                .build();

        scope.define(varElement);

        identifier.setSymbol(varElement);

        return result;
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Scope scope) {
        castExpressionTree.getExpression().accept(this, scope);
        castExpressionTree.getTargetType().accept(this, scope);
        return castExpressionTree;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Scope scope) {
        final var symbol = scope.resolve(identifier.getName());

        if (symbol != null) {
            identifier.setSymbol(symbol);
        } else {
            final var resolver = new Resolver(
                    loader,
                    scope.getCompilationUnit().getImportScope()
            );
            final var resolvedClass = resolver.resolveClass(identifier.getName());
            if (resolvedClass != null) {
                identifier.setType(resolvedClass.asType());
            }
        }

        return identifier;
    }

    @Override
    public Object visitWildCardExpression(final WildCardExpressionTree wildCardExpression, final Scope scope) {
        if (wildCardExpression.getExtendsBound() != null
                || wildCardExpression.getSuperBound() != null) {
            throw new TodoException();
        }

        final var type = types.getWildcardType(null, null);
        wildCardExpression.setType(type);
        return wildCardExpression;
    }


    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Scope param) {
        final var builder = ifStatementTree.builder();
        final var expression = (ExpressionTree) ifStatementTree.getExpression().accept(this, param);
        builder.expression(expression);

        ifStatementTree.getThenStatement().accept(this, param);

        if (ifStatementTree.getElseStatement() != null) {
            ifStatementTree.getElseStatement().accept(this, param);
        }

        return builder.build();
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression, final Scope param) {
        final var result = super.visitBinaryExpression(binaryExpression, param);
        final var leftType = resolveType(binaryExpression.getLeft());
        final var rightType = resolveType(binaryExpression.getRight());

        if (leftType != null
                && rightType != null) {

            if (!types.isSameType(leftType, rightType)) {
                return transformBinaryExpression(binaryExpression, leftType, rightType);
            }
        }

        return result;
    }

    private BinaryExpressionTree transformBinaryExpression(final BinaryExpressionTree binaryExpression,
                                                           final TypeMirror leftType,
                                                           final TypeMirror rightType) {
        if (leftType instanceof PrimitiveType primitiveType) {
            if (rightType instanceof PrimitiveType rightPrimitiveType) {
                return transformBinaryExpression(
                        binaryExpression,
                        primitiveType,
                        rightPrimitiveType
                );
            } else {
                return binaryExpression;
            }
        }

        return binaryExpression;
    }

    private BinaryExpressionTree transformBinaryExpression(final BinaryExpressionTree binaryExpression,
                                                           final PrimitiveType leftType,
                                                           final PrimitiveType rightType) {
        if (leftType.getKind() == TypeKind.BYTE) {
            return binaryExpression;
        } else if (leftType.getKind() == TypeKind.LONG) {
            if (rightType.getKind() == TypeKind.INT) {
                final var rightLiteral = (LiteralExpressionTree) binaryExpression.getRight();
                final var literal = (Integer) rightLiteral.getLiteral();

                rightLiteral.setLiteral(literal.longValue());
                rightLiteral.setLiteralKind(LiteralExpressionTree.Kind.LONG);
                rightLiteral.setType(types.getPrimitiveType(TypeKind.LONG));

                return binaryExpression;
            }
        }

        return binaryExpression;
    }

    @Override
    public Object visitEnhancedForStatement(final EnhancedForStatement enhancedForStatement, final Scope scope) {
        enhancedForStatement.getExpression().accept(this, scope);

        if (enhancedForStatement.getLocalVariable().getType() instanceof VariableTypeTree variableTypeTree) {
            final var symbol = enhancedForStatement.getExpression().getSymbol();
            final var expressionType = (DeclaredType) symbol.asType();
            final var type = expressionType.getTypeArguments().getFirst();
            variableTypeTree.setType(type);
        }

        enhancedForStatement.getLocalVariable().accept(this, scope);
        enhancedForStatement.getStatement().accept(this, scope);
        return enhancedForStatement;
    }

    @Override
    public Object visitVariable(final Variable variable, final Scope scope) {
        variable.getType().accept(this, scope);
        final var resolvedType = variable.getType().getType();

        final var varElement = new VariableBuilder()
                .kind(toElementKind(variable.getKind()))
                .name(variable.getSimpleName())
                .type(resolvedType)
                .build();

        variable.setVarSymbol(varElement);

        scope.define(varElement);

        return super.visitVariable(variable, scope);
    }

    protected ElementKind toElementKind(final Element.Kind kind) {
        return switch (kind) {
            case PARAMETER -> ElementKind.PARAMETER;
            case LOCAL_VARIABLE -> ElementKind.VARIABLE;
            case CONSTRUCTOR -> ElementKind.CONSTRUCTOR;
            case METHOD -> ElementKind.METHOD;
        };
    }

    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Scope scope) {
        final var name = typeIdentifier.getName();
        final var resolver = new Resolver(loader, scope.getCompilationUnit().getImportScope());

        final var resolvedClass = resolver.resolveClass(name);
        TypeMirror type;

        if (resolvedClass == null) {
            type = loader.getTypes().getErrorType(name);
        } else {
            type = resolvedClass.asType();
        }

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> typeParam.accept(this, scope))
                    .map(typeParam -> (ExpressionTree) typeParam)
                    .map(ExpressionTree::getType)
                    .toList();

            final var classType = (CClassType) type;
            type = classType.withTypeArguments(typeParams);
        }

        typeIdentifier.setType(type);

        return typeIdentifier;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final Scope scope) {
        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, scope));
        final var lambdaScope = new LocalScope(scope);
        lambdaExpression.getBody().accept(this, lambdaScope);
        return null;
    }

    @Override
    public Object visitWhileStatement(final WhileStatement whileStatement, final Scope scope) {
        super.visitWhileStatement(whileStatement, scope);
        return whileStatement;
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatement doWhileStatement, final Scope scope) {
        super.visitDoWhileStatement(doWhileStatement, scope);
        return doWhileStatement;
    }
}
