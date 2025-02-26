package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.StandardElementMetaData;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.ast.element.VariableElement;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IBlockStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IVariableDeclaratorStatement;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.Move;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.ITypeKind;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;
import io.github.potjerodekool.nabu.compiler.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Variable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.type.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.github.potjerodekool.nabu.compiler.backend.ir.expression.Eseq.eseq;

public class Translate extends AbstractTreeVisitor<Exp, TranslateContext> {

    private final TypeResolver typeResolver = new TypeResolver();
    private final ToIType toIType = new ToIType();

    @Override
    public Exp visitCompilationUnit(final CompilationUnit compilationUnit, final TranslateContext context) {
        return super.visitCompilationUnit(compilationUnit, new TranslateContext());
    }

    @Override
    public Exp visitFunction(final Function function, final TranslateContext context) {
        final var frame = new Frame();
        context.function = function;
        context.frame = frame;

        final var clazz = (ClassDeclaration) function.getEnclosingElement();
        final var className = clazz.getQualifiedName();
        final var method = function.methodSymbol;

        if (!method.isStatic() && !method.isAbstract()) {
            frame.allocateLocal("this", IReferenceType.create(ITypeKind.CLASS, className, List.of()), false);
        }

        function.getParameters().forEach(p -> p.accept(this, context));

        Exp exp;

        if (function.getBody() != null) {
            exp = function.getBody().accept(this, context);
        } else {
            exp = new Nx(null);
        }

        var bodyStm = exp.unNx();

        if (bodyStm instanceof IExpressionStatement expressionStatement) {
            final var expr = expressionStatement.getExp();
            bodyStm = new Move(expr, new TempExpr(Frame.RV, frame, null));
        }

        final var returnType = function.getReturnType();
        final IType retType = returnType != null
                ? returnType.accept(typeResolver, frame)
                : null;


        final var name = function.getKind() == Element.Kind.CONSTRUCTOR
                ? "<init>"
                : function.getSimpleName();

        final var procFrag = new ProcFrag(
                Flags.parse(method.getModifiers()),
                name,
                retType,
                frame,
                bodyStm
        );

        method.setMetaData(StandardElementMetaData.FRAG, procFrag);
        return null;
    }

    @Override
    public Exp visitVariable(final Variable variable, final TranslateContext context) {
        if (variable.getKind() == Element.Kind.LOCAL_VARIABLE
                || variable.getKind() == Element.Kind.PARAMETER) {
            final var variableType = variable.getVarSymbol().asType();
            final var type = variableType.accept(typeResolver, context);
            final var frame = context.frame;
            frame.allocateLocal(
                    variable.getSimpleName(),
                    type,
                    variable.getKind() == Element.Kind.PARAMETER);
        }

        return super.visitVariable(variable, context);
    }

    @Override
    public Exp visitIdentifier(final IdentifierTree identifier, final TranslateContext context) {
        final var frame = context.frame;
        final var local = frame.get(identifier.getName());
        return new Ex(new TempExpr(local.index(), frame, local.type()));
    }

    @Override
    public Exp visitTypeNameExpression(final TypeNameExpressioTree cTypeNameExpression, final TranslateContext context) {
        throw new TodoException("");
    }

    @Override
    public Exp visitMethodInvocation(final MethodInvocationTree methodInvocation, final TranslateContext context) {
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
                                new TempExpr()
                        )
                );
            }
        }

        final var expArgs = methodInvocation.getArguments().stream()
                .map(a -> a.accept(this, context))
                .toList();

        expArgs.forEach(Objects::requireNonNull);

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

        final var name = ((IdentifierTree) methodInvocation.getName()).getName();

        final var invokedMethod = methodType.getMethodSymbol();
        final InvocationType invocationType = resolveInvocationType(methodType);
        final String owner;

        if (invokedMethod.getKind() == ElementKind.CONSTRUCTOR) {
            final var ownerClass = (TypeElement) invokedMethod.getEnclosingElement();
            owner = ownerClass.getQualifiedName();
        } else {
            final var ownerClass = methodType.getOwner();
            owner = ownerClass.getQualifiedName();
        }

        final var originalMethodType = (ExecutableType) methodType.getMethodSymbol().asType();

        final var returnType = toIType(originalMethodType.getReturnType());
        final var argumentTypes = originalMethodType.getParameterTypes();

        final List<IType> parameterTypes = argumentTypes.stream()
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

        return new Ex(call);
    }

    private InvocationType resolveInvocationType(final ExecutableType methodType) {
        final InvocationType invocationType;
        final var ownerClass = methodType.getOwner();
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
        final var expression = returnStatement.getExpression();
        final IExpression expr;

        if (expression == null) {
            expr = new TempExpr();
        } else {
            final var exp = returnStatement.getExpression().accept(this, context);
            expr = exp.unEx();
        }

        return new Nx(
                new Move(
                        expr,
                        new TempExpr(Frame.RV, context.frame, null)
                )
        );
    }

    @Override
    public Exp visitLiteralExpression(final LiteralExpressionTree literalExpression, final TranslateContext context) {
        final var constExpression = new Const(literalExpression.getLiteral());
        constExpression.setType(toIType(literalExpression.getType()));
        return new Ex(constExpression);
    }

    @Override
    public Exp visitBlockStatement(final BlockStatement blockStatement, final TranslateContext context) {
        Frame parentFrame = context.frame;
        context.frame = parentFrame.subFrame();

        final var irBlockStatement = new IBlockStatement();

        final var statements = blockStatement.getStatements().iterator();

        Exp exp;

        while (statements.hasNext()) {
            final var next = statements.next();
            exp = next.accept(this, context);
            irBlockStatement.addStatement(exp.unNx());
        }

        context.frame = parentFrame;

        return new Nx(irBlockStatement);
    }

    @Override
    public Exp visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                     final TranslateContext context) {
        final var collector = new UsedVarsCollector();
        lambdaExpression.accept(collector, context);

        final var frame = context.frame;

        final var locals = collector.getUsedIdentifiers().stream()
                .map(identifier -> frame.get(identifier.getName()))
                .toList();

        final var arguments = new ArrayList<IExpression>();

        for (Local local : locals) {
            arguments.add(new TempExpr(local.index(), frame, local.type()));
        }

        final var lambdaType = (DeclaredType) lambdaExpression.getType();
        final var lambdaClass = (TypeElement) lambdaType.asElement();
        final var lambdaFunction = lambdaClass.getEnclosedElements().getFirst();
        final var name = lambdaFunction.getSimpleName();

        final var paramTypes = collector.getUsedIdentifiers().stream()
                .map(identifier -> (VariableElement) identifier.getSymbol())
                .map(VariableElement::asType)
                .map(this::toIType)
                .toList();

        final var lambdaMethodType = lambdaExpression.getLambdaMethodType();
        final var lambdaMth = lambdaMethodType.getMethodSymbol();
        final var lambdaFunctionName = lambdaMth.getSimpleName();
        final var lambdaParamTypes = lambdaMth.getParameters().stream()
                .map(VariableElement::asType)
                .map(this::toIType)
                .toList();

        final var lambdaFunctionType = (ExecutableType) lambdaFunction.asType();

        final var lambdaFunctionCall = new DefaultCall(
                InvocationType.STATIC,
                null,
                new Name(lambdaFunction.getSimpleName()),
                toIType(lambdaFunctionType.getReturnType()),
                lambdaFunctionType.getParameterTypes().stream()
                        .map(this::toIType)
                        .toList(),
                List.of()
        );

        final var lambdaReturnType = toIType(lambdaMethodType.getReturnType());

        return new Ex(
                new DynamicCall(
                        new Name(name),
                        IReferenceType.create(
                                ITypeKind.CLASS,
                                lambdaClass.getQualifiedName(),
                                List.of()
                        ),
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
                        )
                )
        );
    }

    @Override
    public Exp visitBinaryExpression(final BinaryExpressionTree binaryExpression, final TranslateContext context) {
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
                    new TempExpr()
            );
        }

        if (rightEx == null) {
            rightEx = eseq(
                    right.unNx(),
                    new TempExpr()
            );
        }

        return new Ex(
                new BinOp(
                        leftEx,
                        binaryExpression.getTag(),
                        rightEx
                )
        );
    }

    private IType toIType(final TypeMirror typeMirror) {
        if (typeMirror == null) {
            return null;
        }

        return typeMirror.accept(toIType, null);
    }

    @Override
    public Exp visitVariableDeclaratorStatement(final CVariableDeclaratorStatement variableDeclaratorStatement, final TranslateContext context) {
        if (variableDeclaratorStatement.getValue() != null) {
            final var value = variableDeclaratorStatement.getValue().accept(this, context);
            final var identifier = variableDeclaratorStatement.getIdent();
            final var varType = toIType(variableDeclaratorStatement.getType().getType());

            var src = value.unEx();

            if (src == null) {
                src = eseq(value.unNx(), new TempExpr());
            }

            final var index = context.frame.allocateLocal(
                    identifier.getName(),
                    varType,
                    false
            );

            final var vds = new IVariableDeclaratorStatement(
                    (VariableElement) identifier.getSymbol(),
                    varType,
                    src,
                    new TempExpr(index, context.frame, varType)
            );
            vds.setLineNumber(variableDeclaratorStatement.getLineNumber());

            return new Nx(vds);
        } else {
            return new Nx(null);
        }
    }

    @Override
    public Exp visitUnaryExpression(final UnaryExpressionTree unaryExpression, final TranslateContext context) {
        final var exp = unaryExpression.getExpression().accept(this, context);
        var expression = exp.unEx();

        if (expression == null) {
            expression = eseq(exp.unNx(), new TempExpr());
        }

        return new Ex(
                new Unop(
                        unaryExpression.getTag(),
                        expression
                )
        );
    }

    @Override
    public Exp visitEmptyStatement(final EmptyStatementTree emptyStatementTree,
                                   final TranslateContext context) {
        return new Nx(new IExpressionStatement(new TempExpr()));
    }

    @Override
    public Exp visitIfStatement(final IfStatementTree ifStatementTree, final TranslateContext context) {
        final Exp condition = ifStatementTree.getExpression().accept(this, context);
        final Exp trueExpression = ifStatementTree.getThenStatement().accept(this, context);
        final Exp falseExpression = ifStatementTree.getElseStatement() != null
                ? ifStatementTree.getElseStatement().accept(this, context)
                : new Nx(null);

        return new IfThenElseExp(
                condition,
                trueExpression,
                falseExpression
        );
    }

    @Override
    public Exp visitFieldAccessExpression(final FieldAccessExpressioTree fieldAccessExpression, final TranslateContext context) {
        final var target = (IdentifierTree) fieldAccessExpression.getTarget();
        final var field = (IdentifierTree) fieldAccessExpression.getField();
        final var fieldType = toIType(TreeUtils.resolveType(field));
        return new Ex(new IFieldAccess(
                target.getName(),
                field.getName(),
                fieldType,
                true
        ));
    }

    @Override
    public Exp visitForStatement(final ForStatement forStatement, final TranslateContext context) {
        final var init = forStatement.getForInit() != null
                ? forStatement.getForInit().accept(this, context)
                : new Ex(new TempExpr());

        final var condition = forStatement.getExpression() != null
                ? forStatement.getExpression().accept(this, context)
                : new Ex(new TempExpr());

        final var update = forStatement.getForUpdate() != null
                ? forStatement.getForUpdate().accept(this, context)
                : new Ex(new TempExpr());

        final var body = forStatement.getStatement().accept(this, context);

        return new ForExpr(
                init,
                condition,
                update,
                body
        );
    }

    @Override
    public Exp visitStatementExpression(final StatementExpression statementExpression, final TranslateContext context) {
        final var expr = statementExpression.getExpression().accept(this, context).unEx();
        final var es = new IExpressionStatement(expr);
        es.setLineNumber(statementExpression.getLineNumber());
        return new Nx(es);
    }

    @Override
    public Exp visitCastExpression(final CastExpressionTree castExpression, final TranslateContext param) {
        final var expression = castExpression.getExpression().accept(this, param).unEx();
        final var targetType = (IdentifierTree) castExpression.getTargetType();
        return new Ex(new CastExpression(
                targetType.getName(),
                expression
        ));
    }

    @Override
    public Exp visitWhileStatement(final WhileStatement whileStatement, final TranslateContext context) {
        final var condition = whileStatement.getCondition().accept(this, context);
        final var body = whileStatement.getBody().accept(this, context);

        return new WhileExp(
                condition,
                body
        );
    }

    @Override
    public Exp visitDoWhileStatement(final DoWhileStatement doWhileStatement, final TranslateContext context) {
        final var body = doWhileStatement.getBody().accept(this, context);
        final var condition = doWhileStatement.getCondition().accept(this, context);

        return new DoWhileExp(
                body,
                condition
        );
    }

}
