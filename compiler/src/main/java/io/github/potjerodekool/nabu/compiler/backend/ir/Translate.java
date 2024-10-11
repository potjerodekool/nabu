package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.Temp;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CElement;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.Operator;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.CVariableDeclaratorStatement;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatement;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;

import static io.github.potjerodekool.nabu.compiler.backend.ir.expression.Eseq.eseq;
import static io.github.potjerodekool.nabu.compiler.backend.ir.statement.IStatement.seq;

public class Translate extends AbstractTreeVisitor<Exp, TranslateContext> {

    private final TypeResolver typeResolver = new TypeResolver();

    private final ToIType toIType = new ToIType();

    @Override
    public Exp visitCompilationUnit(final CompilationUnit compilationUnit, final TranslateContext context) {
        return super.visitCompilationUnit(compilationUnit, new TranslateContext());
    }

    @Override
    public Exp visitFunction(final CFunction function, final TranslateContext context) {
        final var frame = new Frame();
        context.function = function;
        context.frame = frame;

        final var clazz = (CClassDeclaration) function.getEnclosingElement();
        final var className = clazz.getQualifiedName();
        final var method = function.methodSymbol;

        if (!method.isStatic()) {
            frame.allocateLocal("this", IReferenceType.create(className));
        }

        function.getParameters().forEach(p -> p.accept(this, context));

        final var params = method.getParameters().stream()
                .map(param -> new Param(param.getSimpleName(), toIType(param.getVariableType())))
                .toList();

        Exp exp = null;

        if (function.getBody() != null) {
            exp = function.getBody().accept(this, context);
        }

        var bodyStm = exp.unNx();

        if (bodyStm instanceof IExpressionStatement expressionStatement) {
            final var expr = expressionStatement.getExp();
            bodyStm = new Move(expr, new TempExpr(frame.rv()));
        }

        final var returnType = function.getReturnType();
        final IType retType = returnType != null
                ? returnType.accept(typeResolver, frame)
                : null;


        final var name = function.getKind() == CElement.Kind.CONSTRUCTOR
                ? "<init>"
                : function.getSimpleName();

        final var procFrag = new ProcFrag(
                Flags.parse(method.getModifiers()),
                name,
                params,
                retType,
                frame,
                bodyStm
        );

        method.setFrag(procFrag);
        return null;
    }

    @Override
    public Exp visitVariable(final CVariable variable, final TranslateContext context) {
        if (variable.getKind() == CElement.Kind.LOCAL_VARIABLE
            || variable.getKind() == CElement.Kind.PARAMETER) {
            final IType type = variable.getType().accept(typeResolver, context);
            final var frame = context.frame;
            frame.allocateLocal(variable.getSimpleName(), type);
        }

        return super.visitVariable(variable, context);
    }

    @Override
    public Exp visitIdentifier(final CIdent ident, final TranslateContext context) {
        final var frame = context.frame;
        final var index = frame.indexOf(ident.getName());
        final var temp = new Temp(index);
        return new Ex(new TempExpr(temp));
    }

    @Override
    public Exp visitTypeNameExpression(final CTypeNameExpression cTypeNameExpression, final TranslateContext context) {
        throw new TodoException("");
    }

    @Override
    public Exp visitMethodInvocation(final MethodInvocation methodInvocation, final TranslateContext context) {
        final var arguments = new ArrayList<IExpression>();

        final var methodType = methodInvocation.getMethodType();
        final var isStatic = methodType.getMethodSymbol().isStatic();

        if (methodInvocation.getTarget() != null
            && !isStatic) {

            final var exp = methodInvocation.getTarget().accept(this, context);
            final var target = exp.unEx();

            if (target != null) {
                arguments.add(target);
            } else {
                final var stm = exp.unNx();
                arguments.add(
                        new Eseq(
                                stm,
                                new TempExpr(-1)
                        )
                );
            }
        }

        final var expArgs = methodInvocation.getArguments().stream()
                .map(a -> a.accept(this, context))
                        .toList();

        expArgs.forEach(exp -> {
            IExpression expression = exp.unEx();

            if (expression == null) {
                final var statement = (IExpressionStatement) exp.unNx();
                expression = statement.getExp();
            }

            if (expression == null) {
                throw new NullPointerException();
            }

            arguments.add(expression);
        });

        final var name = ((CIdent) methodInvocation.getName()).getName();

        final var invokedMethodType = methodInvocation.getMethodType();
        final var invokedMethod = invokedMethodType.getMethodSymbol();
        final InvocationType invocationType = resolveInvocationType(invokedMethodType);
        final String owner;

        if (invokedMethod.getKind() == ElementKind.CONSTRUCTOR) {
            final var ownerClass = (ClassSymbol) invokedMethod.getEnclosingElement();
            owner = ownerClass.getQualifiedName();
        } else {
            final var ownerClass = invokedMethodType.getOwner();
            owner = ownerClass.getQualifiedName();
        }

        final var returnType = toIType(methodInvocation.getType());

        final List<IType> parameterTypes = methodType.getArgumentTypes().stream()
                    .map(this::toIType)
                    .toList();

        final var call = new DefaultCall(
                invocationType,
                new Name(owner),
                new Name(name),
                returnType,
                parameterTypes,
                arguments
        );
        return new Nx(new IExpressionStatement(call, methodInvocation.getLineNumber()));
    }

    private InvocationType resolveInvocationType(final MethodType methodType) {
        final InvocationType invocationType;
        final var ownerClass = (ClassSymbol) methodType.getOwner();
        final var invokedMethod = methodType.getMethodSymbol();

        if (invokedMethod.getKind() == ElementKind.CONSTRUCTOR || invokedMethod.isPrivate()) {
            invocationType = InvocationType.SPECIAL;
        } else if (ownerClass.getKind() == ElementKind.INTERFACE) {
            invocationType = InvocationType.INTERFACE;
        } else if (invokedMethod.isStatic()) {
            invocationType = InvocationType.STATIC;
        } else {
            invocationType = InvocationType.VIRTUAL;
        }

        return invocationType;
    }

    @Override
    public Exp visitReturnStatement(final ReturnStatement returnStatement, final TranslateContext context) {
        final var exp = returnStatement.getExpression() != null
                ? returnStatement.getExpression().accept(this, context)
                : new Nx(null);

        final var expression = exp.unEx();

        if (expression != null) {
            return new Nx(new Move(
                    expression,
                    new TempExpr(context.frame.rv())
            ));
        } else {
            final var statement = exp.unNx();
            return new Nx(new Move(
                    eseq(statement, new TempExpr(-1)),
                    new TempExpr(context.frame.rv())
            ));
        }
    }

    @Override
    public Exp visitLiteralExpression(final LiteralExpression literalExpression, final TranslateContext context) {
        final var constExpression = new Const(literalExpression.getLiteral());
        constExpression.setType(toIType(literalExpression.getType()));
        return new Ex(constExpression);
    }

    @Override
    public Exp visitBlockStatement(final BlockStatement blockStatement, final TranslateContext context) {
        final var start = new ILabelStatement();
        final var end = new ILabelStatement();

        final var statements = blockStatement.getStatements().iterator();

        if (!statements.hasNext()) {
            return new Nx(
                    seq(
                            new IExpressionStatement(
                                    eseq(start, new TempExpr(new Temp(-1))),
                                    blockStatement.getLineNumber()
                            ),
                            end
                    )
            );
        }

        Exp exp = statements.next().accept(this, context);

        if (!statements.hasNext()) {
            final var stm = exp.unNx();

            if (stm != null) {
                return new Nx(seq(seq(start, stm), end));
            } else {
                return new Nx(
                        new Seq(
                                new IExpressionStatement(
                                        new Eseq(start, exp.unEx()),
                                        blockStatement.getLineNumber()
                                ),
                                end
                        )
                );
            }
        }

        IStatement stm = new Seq(start, exp.unNx());

        while (statements.hasNext()) {
            exp = statements.next().accept(this, context);
            stm = seq(stm, exp.unNx());
        }

        stm = seq(stm, end);

        return new Nx(stm);
    }

    @Override
    public Exp visitLambdaExpression(final CLambdaExpression lambdaExpression,
                                     final TranslateContext context) {
        final var collector = new UsedVarsCollector();
        lambdaExpression.accept(collector, context);

        final var frame = context.frame;

        final var temps = collector.getUsedIdentifiers().stream()
                        .map(ident -> frame.indexOf(ident.getName()))
                .toList();

        IStatement stm;

        final var arguments = new ArrayList<IExpression>();

        for (Integer temp : temps) {
            arguments.add(new TempExpr(temp));
        }

        final var lambdaType = (ClassType) lambdaExpression.getType();
        final var lamdaClass = (ClassSymbol) lambdaType.asElement();
        final var lambdaFunction = (MethodSymbol) lamdaClass.getEnclosedElements().getFirst();
        final var name = lambdaFunction.getSimpleName();

        final var paramTypes = collector.getUsedIdentifiers().stream()
                .map(ident -> (VariableElement) ident.getSymbol())
                .map(VariableElement::getVariableType)
                .map(this::toIType)
                .toList();

        final var lambdaMethodType = lambdaExpression.getLambdaMethodType();
        final var lambdaMth = lambdaMethodType.getMethodSymbol();
        final var lambdaFunctionName = lambdaMth.getSimpleName();
        final var lambdaParamTypes = lambdaMth.getParameters().stream()
                .map(VariableElement::getVariableType)
                .map(this::toIType)
                .toList();

        final var lambdaFunctionType = lambdaFunction.getMethodType();

        final var lambdaFunctionCall = new DefaultCall(
                InvocationType.STATIC,
                null,
                new Name(lambdaFunction.getSimpleName()),
                toIType(lambdaFunctionType.getReturnType()),
                lambdaFunctionType.getArgumentTypes().stream()
                        .map(this::toIType)
                        .toList(),
                List.of()
        );

        final var lambdaReturnType = toIType(lambdaMethodType.getReturnType());

        stm = new IExpressionStatement(Eseq.eseq(
                null,
                new DynamicCall(
                        new Name(name),
                        IReferenceType.create(lamdaClass.getQualifiedName()),
                        paramTypes,
                        arguments,
                        lambdaFunctionCall,
                        new DefaultCall(
                                InvocationType.STATIC,
                                null,
                                new Name(lambdaFunctionName),
                                lambdaReturnType,
                                lambdaParamTypes,
                                List.of()
                        ),
                        lambdaExpression.getLineNumber()
                )
        ),
                lambdaExpression.getLineNumber()
        );

        return new Nx(stm);
    }

    @Override
    public Exp visitBinaryExpression(final BinaryExpression binaryExpression, final TranslateContext context) {
        final var left = binaryExpression.getLeft().accept(this, context);
        final var right = binaryExpression.getRight().accept(this, context);

        if (left == null) {
            throw new NullPointerException();
        }

        if (right == null) {
            throw new NullPointerException();
        }

        IExpression leftEx = left.unEx();
        IExpression rightEx = right.unEx();

        if (leftEx == null) {
            leftEx = eseq(
                    left.unNx(),
                    new TempExpr(-1)
            );
        }

        if (rightEx == null) {
            rightEx = eseq(
                    right.unNx(),
                    new TempExpr(-1)
            );
        }

        if (leftEx == null || rightEx == null) {
            throw new NullPointerException();
        }

        return new Ex(
                new BinOp(
                        leftEx,
                        null,
                        rightEx
                )
        );
    }

    @Override
    public Exp visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression, final TranslateContext param) {
        final var target = fieldAccessExpression.getTarget() != null
                ? fieldAccessExpression.getTarget().accept(this, param)
                : null;

        final var field = fieldAccessExpression.getField().accept(this, param);

        return super.visitFieldAccessExpression(fieldAccessExpression, param);
    }

    private IType toIType(final TypeMirror typeMirror) {
        if (typeMirror == null) {
            return null;
        }

        return typeMirror.accept(toIType, null);
    }

    @Override
    public Exp visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement, final TranslateContext context) {
        final var value = variableDeclaratorStatement.getValue().accept(this, context);
        final var ident = variableDeclaratorStatement.getIdent();
        final var varType = toIType(variableDeclaratorStatement.getType().getType());

        final var index = context.frame.allocateLocal(
                ident.getName(),
                varType
        );

        var src = value.unEx();

        if (src == null) {
            src = eseq(value.unNx(), new TempExpr(-1));
        }

        return new Ex(eseq(
                new Move(
                        src,
                        new TempExpr(index),
                        variableDeclaratorStatement.getLineNumber()
                ),
                new TempExpr(-1)
        ));
    }

    @Override
    public Exp visitUnaryExpression(final UnaryExpression unaryExpression, final TranslateContext context) {
        final var exp = unaryExpression.getExpression().accept(this, context);

        if (exp == null) {
            throw new NullPointerException();
        }

        var expression = exp.unEx();

        if (expression == null) {
            expression = eseq(exp.unNx(), new TempExpr(-1));
        }

        if (unaryExpression.getOperator() == Operator.BANG) {
            return new Ex(new Unop(
                    Unop.Oper.BANG,
                    expression,
                    true
            ));
        } else {
            throw new TodoException();
        }
    }
}

