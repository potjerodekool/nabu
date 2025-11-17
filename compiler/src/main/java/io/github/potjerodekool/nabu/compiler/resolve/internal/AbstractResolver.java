package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.*;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;

import java.util.stream.Collectors;

/**
 * Base class for resolver.
 */
public abstract class AbstractResolver extends AbstractTreeVisitor<Object, Scope> {

    private final CompilerContext compilerContext;
    private final ClassElementLoader loader;
    private final Types types;

    protected AbstractResolver(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
    }

    /**
     * @return Returns the compiler context.
     */
    public CompilerContext getCompilerContext() {
        return compilerContext;
    }

    @Override
    public Object defaultAnswer(final Tree tree, final Scope param) {
        return tree;
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        return super.visitCompilationUnit(compilationUnit, createScope(compilationUnit));
    }

    private Scope createScope(final CompilationUnit compilationUnit) {
        return new GlobalScope(compilationUnit, compilerContext);
    }

    @Override
    public Object visitVariableType(final VariableTypeTree variableType, final Scope scope) {
        if (variableType.getType() == null) {
            variableType.setType(types.getVariableType(null));
        }
        return defaultAnswer(variableType, scope);
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final Scope scope) {
        if (variableDeclaratorStatement.getValue() != null) {
            variableDeclaratorStatement.getValue().accept(this, scope);
        }

        variableDeclaratorStatement.getVariableType().accept(this, scope);

        var type = variableDeclaratorStatement.getVariableType().getType();

        if (type instanceof VariableType) {
            if (variableDeclaratorStatement.getValue() == null) {
                type = types.getErrorType("error");
            } else {
                final var interferedType = compilerContext.getTreeUtils().typeOf(variableDeclaratorStatement.getValue());
                type = types.getVariableType(interferedType);
            }
            variableDeclaratorStatement.getVariableType().setType(type);
        }

        return defaultAnswer(variableDeclaratorStatement, scope);
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Scope scope) {
        castExpressionTree.getExpression().accept(this, scope);
        castExpressionTree.getTargetType().accept(this, scope);
        return defaultAnswer(castExpressionTree, scope);
    }

    private TypeMirror resolveType(final String name,
                                   final Scope scope) {
        TypeMirror type = scope.resolveType(name);

        if (type == null) {
            final var resolvedClass = loader.loadClass(
                    scope.findModuleElement(),
                    name
            );

            if (resolvedClass != null) {
                type = resolvedClass.asType();
            }
        }

        return type;
    }

    @Override
    public Object visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final Scope scope) {
        final TypeMirror type;

        type = switch (wildCardExpression.getBoundKind()) {
            case UNBOUND -> types.getWildcardType(null, null);
            case EXTENDS -> {
                final var extendsBound = (TypeMirror) wildCardExpression.getBound().accept(this, scope);
                yield types.getWildcardType(extendsBound, null);

            }
            case SUPER -> {
                final var superBound = (TypeMirror) wildCardExpression.getBound().accept(this, scope);
                yield types.getWildcardType(null, superBound);
            }
        };

        wildCardExpression.setType(type);
        return defaultAnswer(wildCardExpression, scope);
    }


    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Scope scope) {
        final var builder = ifStatementTree.builder();
        final var expression = (ExpressionTree) ifStatementTree.getExpression().accept(this, scope);
        builder.expression(expression);

        ifStatementTree.getThenStatement().accept(this, scope);

        if (ifStatementTree.getElseStatement() != null) {
            ifStatementTree.getElseStatement().accept(this, scope);
        }

        return builder.build();
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression, final Scope scope) {
        super.visitBinaryExpression(binaryExpression, scope);
        final var leftType = compilerContext.getTreeUtils().typeOf(binaryExpression.getLeft());
        final var rightType = compilerContext.getTreeUtils().typeOf(binaryExpression.getRight());

        if (leftType != null
                && rightType != null) {

            if (!types.isSameType(leftType, rightType)) {
                final var transformed = transformBinaryExpression(binaryExpression, leftType, rightType);
                final var module = scope.findModuleElement();
                final var stringType = loader.loadClass(module, Constants.STRING).asType();

                if (types.isSameType(stringType, leftType)
                        || types.isSameType(stringType, rightType)) {
                    transformed.setType(stringType);
                }
            } else {
                binaryExpression.setType(leftType);
            }
        }

        return defaultAnswer(binaryExpression, scope);
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
    public Object visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final Scope scope) {
        enhancedForStatement.getExpression().accept(this, scope);

        if (enhancedForStatement.getLocalVariable().getVariableType() instanceof VariableTypeTree variableTypeTree) {
            final DeclaredType expressionType;

            final var expression = enhancedForStatement.getExpression();
            final var symbol = expression.getSymbol();

            if (symbol != null) {
                expressionType = (DeclaredType) symbol.asType();
            } else {
                expressionType = (DeclaredType) expression.getType();
            }

            final var type = expressionType.getTypeArguments().getFirst();
            variableTypeTree.setType(type);
        }

        enhancedForStatement.getLocalVariable().accept(this, scope);
        enhancedForStatement.getStatement().accept(this, scope);
        return defaultAnswer(enhancedForStatement, scope);
    }

    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Scope scope) {
        var type = typeIdentifier.getType();
        final var clazz = typeIdentifier.getClazz();
        final var name = TreeUtils.getClassName(clazz);

        if (type == null) {
            type = resolveType(name, scope);
        }

        if (type == null) {
            type = loader.getTypes().getErrorType(name);
        }

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> typeParam.accept(this, scope))
                    .map(typeParam -> (ExpressionTree) typeParam)
                    .map(ExpressionTree::getType)
                    .toArray(TypeMirror[]::new);

            type = types.getDeclaredType(
                    type.asTypeElement(),
                    typeParams
            );
        }

        typeIdentifier.setType(type);

        return defaultAnswer(typeIdentifier, scope);
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final Scope scope) {
        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, scope));
        final var lambdaScope = new LocalScope(scope);
        lambdaExpression.getBody().accept(this, lambdaScope);
        return defaultAnswer(lambdaExpression, scope);
    }

    @Override
    public Object visitWhileStatement(final WhileStatementTree whileStatement, final Scope scope) {
        super.visitWhileStatement(whileStatement, scope);
        return defaultAnswer(whileStatement, scope);
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final Scope scope) {
        super.visitDoWhileStatement(doWhileStatement, scope);
        return defaultAnswer(doWhileStatement, scope);
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Scope scope) {
        final var selected = fieldAccessExpression.getSelected();
        selected.accept(this, scope);

        final var varElement = TreeUtils.getSymbol(selected);

        if (varElement != null) {
            final var varType = varElement.asType();
            final DeclaredType declaredType = asDeclaredType(varType);
            final var symbolScope = new SymbolScope(
                    declaredType,
                    scope.getGlobalScope()
            );
            fieldAccessExpression.getField().accept(this, symbolScope);
        } else if (selected.getType() != null) {
            final DeclaredType declaredType = asDeclaredType(selected.getType());
            final var classScope = new ClassScope(
                    declaredType,
                    null,
                    scope.getCompilationUnit(),
                    loader
            );
            fieldAccessExpression.getField().accept(this, classScope);
        }

        fieldAccessExpression.setType(fieldAccessExpression.getField().getType());
        return defaultAnswer(fieldAccessExpression, scope);
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Scope scope) {
        var type = identifier.getType();

        if (type == null) {
            type = resolveType(identifier.getName(), scope);
        }

        if (type != null) {
            identifier.setType(type);
            identifier.setSymbol(null);
        } else {
            final var symbol = scope.resolve(identifier.getName());

            if (symbol != null) {
                identifier.setSymbol(symbol);
            } else if (identifier.getSymbol() == null) {
                identifier.setSymbol(
                        compilerContext.getElementBuilders()
                                .createErrorSymbol(identifier.getName())
                );
                identifier.setType(types.getErrorType(identifier.getName()));
            }
        }

        return defaultAnswer(identifier, scope);
    }

    private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType;
        } else {
            final var variableType = (VariableType) typeMirror;
            return (DeclaredType) variableType.getInterferedType();
        }
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final Scope scope) {
        annotationTree.getName().accept(this, scope);
        var annotationType = (DeclaredType) annotationTree.getName().getType();

        if (annotationType == null) {
            annotationType = getCompilerContext().getClassElementLoader()
                    .getTypes()
                    .getErrorType(annotationTree.getName().getName());
            annotationTree.getName().setType(annotationType);
        }

        final var annotationScope = new SymbolScope(annotationType, scope);

        final var values = annotationTree.getArguments().stream()
                .map(it -> (Pair<ExecutableElement, AnnotationValue>) it.accept(this, annotationScope))
                .filter(it -> it.first() != null)
                .collect(Collectors.toMap(
                        Pair::first,
                        Pair::second
                ));

        return AnnotationBuilder.createAnnotation(
                annotationType,
                values
        );
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveTypeTree primitiveType,
                                     final Scope scope) {
        final var type  = switch (primitiveType.getKind()) {
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case INT -> types.getPrimitiveType(TypeKind.INT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case VOID -> types.getNoType(TypeKind.VOID);
        };

        primitiveType.setType(type);
        return defaultAnswer(primitiveType, scope);
    }


    @Override
    public Object visitSwitchStatement(final SwitchStatement switchStatement, final Scope scope) {
        switchStatement.getSelector().accept(this, scope);
        final var switchScope = new SwitchScope(
                switchStatement.getSelector().getSymbol(),
                scope
        );
        switchStatement.getCases().forEach(caseStatement -> caseStatement.accept(this, switchScope));
        return defaultAnswer(switchStatement, switchScope);
    }

    @Override
    public Object visitConstantCaseLabel(final ConstantCaseLabel constantCaseLabel, final Scope scope) {
        return super.visitConstantCaseLabel(constantCaseLabel, scope);
    }

    @Override
    public Object visitArrayAccess(final ArrayAccessExpressionTree arrayAccessExpressionTree, final Scope scope) {
        arrayAccessExpressionTree.getExpression().accept(this, scope);
        arrayAccessExpressionTree.getIndex().accept(this, scope);
        return defaultAnswer(arrayAccessExpressionTree, scope);
    }

    @Override
    public Object visitTypeParameter(final TypeParameterTree typeParameterTree,
                                     final Scope scope) {
        final var currentClass = scope.getCurrentClass();

        var typeBound = typeParameterTree.getTypeBound().stream()
                .map(it -> (TypeMirror) it.accept(this, scope))
                .toList();

        final var upperBound = switch (typeBound.size()) {
            case 0 -> loader.loadClass(
                    findModuleElement(currentClass),
                    Constants.OBJECT
            ).asType();
            case 1 -> typeBound.getFirst();
            default -> types.getIntersectionType(typeBound);
        };

        return types.getTypeVariable(
                typeParameterTree.getIdentifier().getName(),
                upperBound,
                null
        ).asElement();
    }

    private ModuleElement findModuleElement(final Element element) {
        if (element == null) {
            return null;
        }
        if (element instanceof PackageElement packageElement) {
            return packageElement.getModuleSymbol();
        } else {
            return findModuleElement(element.getEnclosingElement());
        }
    }

}

