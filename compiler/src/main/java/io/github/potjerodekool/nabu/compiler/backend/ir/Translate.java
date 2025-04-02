package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.ReturnStatementTreeBuilder;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static io.github.potjerodekool.nabu.compiler.backend.ir.expression.Eseq.eseq;

public class Translate extends AbstractTreeVisitor<Exp, TranslateContext> {

    private final TypeResolver typeResolver = new TypeResolver();
    private final Types types;

    public Translate(final Types types) {
        this.types = types;
    }

    @Override
    public Exp visitUnknown(final Tree tree, final TranslateContext Param) {
        throw new TodoException(tree.getClass().getName());
    }

    @Override
    public Exp visitCompilationUnit(final CompilationUnit compilationUnit, final TranslateContext context) {
        return super.visitCompilationUnit(compilationUnit, new TranslateContext());
    }

    @Override
    public Exp visitPackageDeclaration(final PackageDeclaration packageDeclaration, final TranslateContext param) {
        return null;
    }

    @Override
    public Exp visitClass(final ClassDeclaration classDeclaration, final TranslateContext context) {
        final var parentClass = context.classDeclaration;
        context.classDeclaration = classDeclaration;
        final var result = super.visitClass(classDeclaration, context);
        final var clientInitOptional = generateClientInit(classDeclaration);

        clientInitOptional.ifPresent(clientInit -> clientInit.accept(this, context));

        context.classDeclaration = parentClass;
        return result;
    }

    @Override
    public Exp visitFunction(final Function function, final TranslateContext context) {
        final var frame = new Frame();
        context.function = function;
        context.frame = frame;

        final var clazz = context.classDeclaration;
        final var className = clazz.getClassSymbol().getQualifiedName();
        final var method = (MethodSymbol) function.getMethodSymbol();

        if (!method.isStatic()
                && !method.isAbstract()
                && !method.isNative()) {
            frame.allocateLocal(Constants.THIS, IReferenceType.createClassType(null, className, List.of()), false);
        }

        function.getParameters().forEach(p -> p.accept(this, context));

        Exp exp;

        if (function.getBody() != null) {
            final var body = function.getBody();

            if (body.getStatements().isEmpty()) {
                body.addStatement(new ReturnStatementTreeBuilder()
                        .build()
                );
            }
            exp = body.accept(this, context);
        } else {
            exp = new Nx(new Move(new TempExpr(), new TempExpr(Frame.V0)));
        }

        var bodyStm = exp.unNx();

        if (bodyStm instanceof IExpressionStatement expressionStatement) {
            final var expr = expressionStatement.getExp();
            bodyStm = new Move(expr, new TempExpr(Frame.V0));
        }

        final var procFrag = new ProcFrag(
                bodyStm
        );

        method.setFrag(procFrag);
        return null;
    }

    private Optional<Function> generateClientInit(final ClassDeclaration classDeclaration) {
        final var className = classDeclaration.getClassSymbol().getQualifiedName();

        final var fields = TreeFilter.fields(classDeclaration).stream()
                .filter(field -> field.hasFlag(Flags.STATIC))
                .filter(field -> field.getValue() != null)
                .toList();

        Function clientInit = null;

        for (final var field : fields) {
            final var fieldValue = (ExpressionTree) field.getValue();

            if (field.hasFlag(Flags.FINAL) != (fieldValue instanceof LiteralExpressionTree)) {
                if (clientInit == null) {
                    clientInit = findOrCreateClientInit(classDeclaration);
                }

                final var body = clientInit.getBody();

                final var expressionTree = TreeMaker.binaryExpressionTree(
                        TreeMaker.fieldAccessExpressionTree(
                                IdentifierTree.create(
                                        className
                                ),
                                field.getName(),
                                -1,
                                -1
                        ),
                        Tag.ASSIGN,
                        fieldValue,
                        -1,
                        -1
                );

                body.addStatement(
                        TreeMaker.expressionStatement(
                                expressionTree,
                                -1,
                                -1
                        )
                );
            }
        }

        if (clientInit != null) {
            clientInit.getBody().addStatement(
                    new ReturnStatementTreeBuilder()
                            .build()
            );
        }

        return Optional.ofNullable(clientInit);
    }

    private Function findOrCreateClientInit(final ClassDeclaration classDeclaration) {
        return classDeclaration.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(Function.class))
                .filter(function -> Constants.CLINIT.equals(function.getSimpleName()))
                .findFirst()
                .orElseGet(() -> {
                    final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();

                    final var method = new MethodSymbolBuilderImpl()
                            .kind(ElementKind.STATIC_INIT)
                            .flags(Flags.STATIC)
                            .name(Constants.CLINIT)
                            .returnType(types.getNoType(TypeKind.VOID))
                            .build();


                    final var function = TreeMaker.function(
                            Constants.CLINIT,
                            Kind.STATIC_INIT,
                            new CModifiers(
                                    List.of(),
                                    Flags.STATIC
                            ),
                            List.of(),
                            null,
                            List.of(),
                            TreeMaker.primitiveTypeTree(
                                    PrimitiveTypeTree.Kind.VOID,
                                    -1,
                                    -1
                            ),
                            List.of(),
                            TreeMaker.blockStatement(List.of(), -1, -1),
                            null,
                            -1,
                            -1
                    );
                    function.setMethodSymbol(method);
                    clazz.addEnclosedElement(method);

                    return function;
                });
    }

    @Override
    public Exp visitIdentifier(final IdentifierTree identifier, final TranslateContext context) {
        final var frame = context.frame;
        final var local = frame.get(identifier.getName());
        return new Ex(new TempExpr(local.index(), local.type()));
    }

    @Override
    public Exp visitTypeNameExpression(final TypeNameExpressionTree cTypeNameExpression, final TranslateContext context) {
        return null;
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

        final IType returnType =
                invokedMethod.getKind() == ElementKind.CONSTRUCTOR
                        ? IPrimitiveType.VOID
                        : toIType(originalMethodType.getReturnType());

        final var argumentTypes = originalMethodType.getParameterTypes();

        final List<IType> parameterTypes = argumentTypes.stream()
                .map(this::toIType)
                .toList();

        final var call = new Call(
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
    public Exp visitReturnStatement(final ReturnStatementTree returnStatement, final TranslateContext context) {
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
                        new TempExpr(Frame.V0)
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
    public Exp visitBlockStatement(final BlockStatementTree blockStatement, final TranslateContext context) {
        Frame parentFrame = context.frame;
        context.frame = parentFrame.subFrame();

        final var iStatements = new ArrayList<IStatement>();

        final var statements = blockStatement.getStatements().iterator();

        Exp exp;

        while (statements.hasNext()) {
            final var next = statements.next();
            exp = next.accept(this, context);
            iStatements.add(exp.unNx());
        }

        context.frame = parentFrame;

        return new Nx(new IBlockStatement(iStatements));
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
            arguments.add(new TempExpr(local.index(), local.type()));
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

        final var lambdaFunctionCall = new Call(
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
                new Call(
                        new Name(name),
                        IReferenceType.createClassType(
                                null,
                                lambdaClass.getQualifiedName(),
                                List.of()
                        ),
                        paramTypes,
                        arguments,
                        lambdaFunctionCall,
                        new Call(
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

        return typeMirror.accept(ToIType.INSTANCE, null);
    }

    @Override
    public Exp visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                final TranslateContext context) {
        if (variableDeclaratorStatement.getKind() == Kind.PARAMETER) {
            final var variableType = variableDeclaratorStatement.getName().getSymbol().asType();
            final var type = variableType.accept(typeResolver, context);
            final var frame = context.frame;
            frame.allocateLocal(
                    variableDeclaratorStatement.getName().getName(),
                    type,
                    variableDeclaratorStatement.getKind() == Kind.PARAMETER);
            return null;
        } else if (variableDeclaratorStatement.getKind() == Kind.FIELD
            || variableDeclaratorStatement.getKind() == Kind.RECORD_COMPONENT) {
            return null;
        }

        if (variableDeclaratorStatement.getValue() != null) {
            final var value = variableDeclaratorStatement.getValue().accept(this, context);
            final var identifier = variableDeclaratorStatement.getName();
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
                    new TempExpr(index, varType)
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
    public Exp visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final TranslateContext context) {
        final var target = (IdentifierTree) fieldAccessExpression.getTarget();
        final var field = (IdentifierTree) fieldAccessExpression.getField();
        final var fieldType = toIType(TreeUtils.typeOf(field));
        return new Ex(new IFieldAccess(
                target.getName(),
                field.getName(),
                fieldType,
                true
        ));
    }

    @Override
    public Exp visitForStatement(final ForStatementTree forStatement, final TranslateContext context) {
        final var init = forStatement.getForInit().stream()
                .map(it -> it.accept(this, context))
                .reduce((first, second) -> new Ex(new ExpList(first.unEx(), second.unEx())))
                .orElse(new Ex(new TempExpr()));

        final var condition = forStatement.getExpression() != null
                ? forStatement.getExpression().accept(this, context)
                : new Ex(new TempExpr());

        final var update = forStatement.getForUpdate().stream()
                .map(it -> it.accept(this, context))
                .reduce((first, second) -> new Ex(new ExpList(first.unEx(), second.unEx())))
                .orElse(new Ex(new TempExpr()));

        final var body = forStatement.getStatement().accept(this, context);

        return new ForExpr(
                init,
                condition,
                update,
                body
        );
    }

    @Override
    public Exp visitExpressionStatement(final ExpressionStatementTree expressionStatement, final TranslateContext context) {
        final var expr = expressionStatement.getExpression().accept(this, context).unEx();
        final var es = new IExpressionStatement(expr);
        es.setLineNumber(expressionStatement.getLineNumber());
        return new Nx(es);
    }

    @Override
    public Exp visitCastExpression(final CastExpressionTree castExpression, final TranslateContext param) {
        final var expression = castExpression.getExpression().accept(this, param).unEx();
        final var targetType = (IdentifierTree) castExpression.getTargetType();
        final var className = targetType.getType().getClassName();

        return new Ex(new ITypeExpression(
                ITypeExpression.Kind.CAST,
                className,
                expression
        ));
    }

    @Override
    public Exp visitWhileStatement(final WhileStatementTree whileStatement, final TranslateContext context) {
        final var condition = whileStatement.getCondition().accept(this, context);
        final var body = whileStatement.getBody().accept(this, context);

        return new WhileExp(
                condition,
                body
        );
    }

    @Override
    public Exp visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final TranslateContext context) {
        final var body = doWhileStatement.getBody().accept(this, context);
        final var condition = doWhileStatement.getCondition().accept(this, context);

        return new DoWhileExp(
                body,
                condition
        );
    }

    @Override
    public Exp visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final TranslateContext param) {
        final var expression = instanceOfExpression.getExpression().accept(this, param).unEx();
        final var targetType = (IdentifierTree) instanceOfExpression.getTypeExpression();
        final var className = targetType.getType().getClassName();

        return new Ex(new ITypeExpression(
                ITypeExpression.Kind.INSTANCEOF,
                className,
                expression
        ));
    }
}
