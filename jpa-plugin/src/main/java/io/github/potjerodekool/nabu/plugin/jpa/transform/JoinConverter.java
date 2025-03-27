package io.github.potjerodekool.nabu.plugin.jpa.transform;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.SymbolScope;
import io.github.potjerodekool.nabu.compiler.tree.TreeMaker;
import io.github.potjerodekool.nabu.compiler.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.util.Types;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

import java.util.List;

import static io.github.potjerodekool.nabu.compiler.resolve.TreeUtils.getSymbol;

public class JoinConverter extends AbstractJpaTransformer {

    private final ClassElementLoader loader;
    private final Types types;
    private final String joinType;

    protected JoinConverter(final CompilerContext compilerContext,
                            final String joinType) {
        super(compilerContext);
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
        this.joinType = joinType;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Scope scope) {
        final var module = scope.findModuleElement();

        final var target = (IdentifierTree) fieldAccessExpression.getTarget();
        final var field = (IdentifierTree) fieldAccessExpression.getField();
        final var targetScope = visitFieldAccessExpressionTarget(
                fieldAccessExpression,
                scope
        );

        field.accept(this, targetScope);

        final var literalExpression = TreeMaker.literalExpressionTree(
                field.getName(),
                -1,
                -1
        );
        final var stringType = loader.loadClass(module, ClassNames.STRING_CLASS_NAME).asType();
        literalExpression.setType(stringType);

        final var targetType = (DeclaredType) TreeUtils.typeOf(target);
        final var fieldType = (DeclaredType) TreeUtils.typeOf(field);

        final var fromType = (DeclaredType) targetType.getTypeArguments().getFirst();
        final DeclaredType toType;

        if (fieldType.getTypeArguments() != null
                && fieldType.getTypeArguments().size() == 1) {
            toType = (DeclaredType) fieldType.getTypeArguments().getFirst();
        } else {
            toType = types.getErrorType("error");
        }

        final var joinTypeType = (DeclaredType) loader.loadClass(module, "jakarta.persistence.criteria.JoinType").asType();
        final var joinTypeIdentifier = IdentifierTree.create("jakarta.persistence.criteria.JoinType");
        joinTypeIdentifier.setType(joinTypeType);

        final var innerIdentifier = IdentifierTree.create(joinType);
        innerIdentifier.setSymbol(
                ElementFilter.enumConstantByName((TypeElement) joinTypeType.asElement(), joinType)
                        .orElse(null)
        );

        final var joinTypeArg = TreeMaker.fieldAccessExpressionTree(
                joinTypeIdentifier,
                innerIdentifier,
                -1,
                -1
        );

        final var typeArguments = List.of(
                createTypeArgument(fromType),
                createTypeArgument(toType)
        );

        final var call = createBuilderCall(
                typeArguments,
                target,
                "join",
                literalExpression,
                joinTypeArg
        );

        final var methodType = call.getMethodType();
        var returnType = (DeclaredType) methodType.getReturnType();

        returnType = types.getDeclaredType(
                (TypeElement) returnType.asElement(),
                fromType,
                toType
        );

        call.setMethodType(types.getExecutableType(
                methodType.getMethodSymbol(),
                methodType.getTypeVariables(),
                returnType,
                methodType.getParameterTypes(),
                methodType.getThrownTypes()
        ));

        return call;
    }

    private IdentifierTree createTypeArgument(final DeclaredType declaredType) {
        final var typeElement = (TypeElement) declaredType.asElement();
        final var identifier = IdentifierTree.create(typeElement.getQualifiedName());
        identifier.setType(declaredType);
        return identifier;
    }

    private Scope visitFieldAccessExpressionTarget(final FieldAccessExpressionTree fieldAccessExpression,
                                                   final Scope scope) {
        final var target = fieldAccessExpression.getTarget();
        target.accept(this, scope);

        final var targetSymbol = getSymbol(fieldAccessExpression.getTarget());
        final DeclaredType declaredType = switch (targetSymbol) {
            case VariableElement variableElement -> {
                var dt = asDeclaredType(variableElement.asType());

                final var pathType = resolvePathType(scope);

                if (types.isSubType(dt, pathType)) {
                    yield (DeclaredType) dt.getTypeArguments().getFirst();
                } else {
                    yield (DeclaredType) pathType;
                }
            }
            case TypeElement typeElement -> asDeclaredType(typeElement.asType());
            default -> (DeclaredType) targetSymbol.asType();
        };

        return new SymbolScope(
                declaredType,
                scope.getGlobalScope()
        );
    }

    private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType dt) {
            return dt;
        } else {
            return (DeclaredType) ((VariableType) typeMirror).getInterferedType();
        }
    }
}
