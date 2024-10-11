package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.CVariableType;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.CVariableDeclaratorStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementExpression;
import io.github.potjerodekool.nabu.compiler.tree.expression.CAnnotatedType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.TypeVisitor;

import java.util.ArrayList;
import java.util.function.Function;

import static io.github.potjerodekool.nabu.compiler.CollectionUtils.forEarchIndexed;

public class ArgumentBoxer extends AbstractTreeVisitor<CExpression, TypeMirror> {

    private final BoxerSelector boxerSelector;

    public ArgumentBoxer(final ClassElementLoader classElementLoader,
                         final MethodResolver methodResolver) {
        boxerSelector = new BoxerSelector(
                classElementLoader,
                methodResolver
        );
    }

    public void boxArguments(final MethodInvocation methodInvocation) {
        final var methodType = methodInvocation.getMethodType();

        final var arguments = methodInvocation.getArguments();
        final var argTypes = methodType.getArgumentTypes();

        final var newArgs = new ArrayList<CExpression>();

        forEarchIndexed(arguments,
                (i, arg) -> {
                    arg = arg.accept(this, argTypes.get(i));
                    newArgs.add(arg);
                }
        );

        methodInvocation.getArguments().clear();
        newArgs.forEach(methodInvocation::argument);
    }


    @Override
    public CExpression visitCompilationUnit(final CompilationUnit compilationUnit, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitFunction(final CFunction function, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitVariable(final CVariable variable, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitBlockStatement(final BlockStatement blockStatement, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitReturnStatement(final ReturnStatement returnStatement, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitVariableType(final CVariableType variableType, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitIdentifier(final CIdent ident, final TypeMirror param) {
        final var symbol = (VariableElement) ident.getSymbol();
        final var varType = symbol.getVariableType();
        final Function<CExpression, CExpression> boxFunction = varType.accept(boxerSelector, param);

        if (boxFunction != null) {
            return boxFunction.apply(ident);
        }

        return ident;
    }

    @Override
    public CExpression visitLambdaExpression(final CLambdaExpression lambdaExpression, final TypeMirror param) {
        throw new TodoException();
    }

    @Override
    public CExpression visitBinaryExpression(final BinaryExpression binaryExpression, final TypeMirror param) {
        throw new TodoException();
    }

    @Override
    public CExpression visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final TypeMirror param) {
        throw new TodoException();
    }

    @Override
    public CExpression visitClass(final CClassDeclaration classDeclaration, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitMethodInvocation(final MethodInvocation methodInvocation, final TypeMirror param) {
        return methodInvocation;
    }

    @Override
    public CExpression visitLiteralExpression(final LiteralExpression literalExpression, final TypeMirror param) {
        final var boxer = literalExpression.getType().accept(boxerSelector, param);

        if (boxer != null) {
            return boxer.apply(literalExpression);
        }

        return literalExpression;
    }

    @Override
    public CExpression visitStatementExpression(final StatementExpression statementExpression, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitPackageDeclaration(final CPackageDeclaration cPackageDeclaration, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitImportItem(final ImportItem importItem, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitPrimitiveType(final CPrimitiveType cPrimitiveType, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitUnaryExpression(final UnaryExpression unaryExpression, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitTypeIdentifier(final CTypeApply typeIdentifier, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitAnnotatedType(final CAnnotatedType annotatedType, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitTypeNameExpression(final CTypeNameExpression cTypeNameExpression, final TypeMirror param) {
        return null;
    }

    @Override
    public CExpression visitNoTypeExpression(final CNoTypeExpression cNoTypeExpression, final TypeMirror param) {
        return null;
    }
}

class BoxerSelector implements TypeVisitor< Function<CExpression, CExpression>, TypeMirror> {

    private final ClassElementLoader loader;
    private final MethodResolver methodResolver;



    public BoxerSelector(final ClassElementLoader classElementLoader,
                         final MethodResolver methodResolver) {
        this.loader = classElementLoader;
        this.methodResolver = methodResolver;
    }

    @Override
    public Function<CExpression, CExpression> visitArrayType(final ArrayType arrayType, final TypeMirror param) {
        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitClassType(final ClassType classType, final TypeMirror param) {
        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitMethodType(final MethodType methodType, final TypeMirror param) {
        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitVoidType(final VoidType voidType, final TypeMirror param) {
        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitPrimitiveType(final PrimitiveType primitiveType,
                                                                 final TypeMirror otherType) {
        if (otherType instanceof ClassType) {
            return switch (primitiveType.getKind()) {
                case BOOLEAN -> this::boxBoolean;
                case INT -> this::boxInteger;
                default -> throw new TodoException("" + primitiveType.getKind());
            };
        }

        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitNullType(final NullType nullType, final TypeMirror param) {
        return null;
    }

    private CExpression boxBoolean(final CExpression expression) {
        final var methodInvocation = new MethodInvocation();
        final var target = new CIdent("java.lang.Boolean");
        target.setSymbol(loader.resolveClass("java.lang.Boolean"));

        methodInvocation.target(target)
                .name(new CIdent("valueOf"))
                .argument(expression);

        final var methodType = methodResolver.resolveMethod(methodInvocation);
        methodInvocation.setMethodType(methodType);

        return methodInvocation;
    }

    private CExpression boxInteger(final CExpression expression) {
        final var methodInvocation = new MethodInvocation();
        final var target = new CIdent("java.lang.Integer");
        target.setSymbol(loader.resolveClass("java.lang.Integer"));

        methodInvocation.target(target)
                .name(new CIdent("valueOf"))
                .argument(expression);

        final var methodType = methodResolver.resolveMethod(methodInvocation);
        methodInvocation.setMethodType(methodType);

        return methodInvocation;
    }

    @Override
    public Function<CExpression, CExpression> visitVariableType(final io.github.potjerodekool.nabu.compiler.type.VariableType variableType, final TypeMirror param) {
        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitWildcardType(final WildcardType wildcardType,
                                                                final TypeMirror param) {
        return null;
    }

    @Override
    public Function<CExpression, CExpression> visitTypeVariable(final TypeVariable typeVariable, final TypeMirror param) {
        return null;
    }
}

