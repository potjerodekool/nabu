package io.github.potjerodekool.nabu.compiler.backend.ir.impl;

import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.expression.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.temp.ILabel;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IPrimitiveType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IReferenceType;
import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.tree.statement.builder.ReturnStatementTreeBuilder;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static io.github.potjerodekool.nabu.compiler.backend.ir.expression.Eseq.eseq;

public class Translate extends AbstractTreeVisitor<Exp, TranslateContext> {

    private final TypeResolver typeResolver = new TypeResolver();
    private final CompilerContextImpl compilerContext;

    public Translate(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
    }

    @Override
    public Exp visitUnknown(final Tree tree, final TranslateContext Param) {
        throw new UnsupportedOperationException(tree.getClass().getName());
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

        function.getParameters().forEach(p ->
                acceptTree(p, context));

        Exp exp;

        if (function.getBody() != null) {
            final var body = function.getBody();

            if (body.getStatements().isEmpty()) {
                body.addStatement(new ReturnStatementTreeBuilder()
                        .build()
                );
            }
            exp = acceptTree(body, context);
        } else {
            exp = new Nx(new Move(new TempExpr(), new TempExpr(Frame.V0)));
        }

        var bodyStm = exp.unNx();

        if (bodyStm instanceof IExpressionStatement expressionStatement) {
            final var expr = expressionStatement.getExp();
            bodyStm = new Move(expr, new TempExpr(Frame.V0));
        }

        final var procFrag = new ProcFrag(bodyStm, frame);

        method.setFrag(procFrag);
        return null;
    }

    @Override
    public Exp visitIdentifier(final IdentifierTree identifier, final TranslateContext context) {
        final var type = identifier.getType();
        final var symbol = (Symbol) identifier.getSymbol();

        if (Constants.THIS.equals(identifier.getName())) {
            return new Ex(new TempExpr(0, ToIType.toIType(symbol.asType())));
        }

        if (type != null
                && symbol == null
                && type.isDeclaredType()) {
            if ("class".equals(identifier.getName())) {
                return new Ex(new Name("class"));
            } else {
                return new Ex(new Name(type.asTypeElement().getFlatName()));
            }
        }

        if (isNullOrError(symbol)) {
            return new Nx(null);
        }

        if (symbol.getKind() == ElementKind.FIELD
                || symbol.getKind() == ElementKind.ENUM_CONSTANT
                || symbol.getKind() == ElementKind.RECORD_COMPONENT) {
            final var clazz = (ClassSymbol) symbol.getEnclosingElement();

            return new Ex(new IFieldAccess(
                    new Name(clazz.getFlatName()),
                    symbol.getSimpleName(),
                    ToIType.toIType(symbol.asType()),
                    symbol.isStatic()
            ));
        } else if (symbol instanceof TypeElement) {
            return new Nx(null);
        }

        final var frame = context.frame;
        final var local = frame.get(identifier.getName());
        return new Ex(new TempExpr(local.index(), local.type()));
    }

    private boolean isNullOrError(final Symbol symbol) {
        return symbol == null
                || symbol.isError();
    }

    @Override
    public Exp visitTypeNameExpression(final TypeNameExpressionTree cTypeNameExpression, final TranslateContext context) {
        return null;
    }

    @Override
    public Exp visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                     final TranslateContext context) {

        final var arguments = methodInvocationArguments(
                methodInvocation,
                context
        );

        final var methodType = methodInvocation.getMethodType();
        final var methodSelector = methodInvocation.getMethodSelector();
        final String methodName;

        if (methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            methodName = fieldAccessExpressionTree.getField().getName();
        } else {
            methodName = ((IdentifierTree) methodSelector).getName();
        }

        final var invokedMethod = methodType.getMethodSymbol();
        final InvocationType invocationType = resolveInvocationType(methodType);
        final IType owner = getType(methodSelector);

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
                owner,
                new Name(methodName),
                returnType,
                parameterTypes,
                arguments
        );

        return new Ex(call);
    }

    private IType getType(final ExpressionTree expressionTree) {
        if (expressionTree.getType() != null) {
            return ToIType.toIType(expressionTree.getType());
        }

        if (expressionTree instanceof FieldAccessExpressionTree fieldAccess) {
            final var type = getType(fieldAccess.getField());

            if (type != null) {
                return type;
            }

            final var selected = fieldAccess.getSelected();
            if (selected.getSymbol() != null) {
                return ToIType.toIType(selected.getSymbol().asType());
            } else if (selected.getType() != null) {
                return ToIType.toIType(selected.getType());
            } else {
                return getType(selected);
            }
        } else {
            final var type = expressionTree.getType();

            if (type != null) {
                return ToIType.toIType(type);
            } else if (expressionTree.getSymbol() != null) {
                return ToIType.toIType(expressionTree.getSymbol().asType());
            } else {
                return null;
            }
        }
    }

    private List<IExpression> methodInvocationArguments(final MethodInvocationTree methodInvocation,
                                                        final TranslateContext context) {
        final var arguments = new ArrayList<IExpression>();
        final var methodType = methodInvocation.getMethodType();
        final var isStatic = methodType.getMethodSymbol().isStatic();

        final var methodSelector = methodInvocation.getMethodSelector();
        final ExpressionTree selected;

        if (methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            selected = fieldAccessExpressionTree.getSelected();
        } else {
            selected = null;
        }

        if (selected != null
                && !isStatic) {
            final var exp = acceptTree(selected, context);
            final var targetExpression = exp.unEx();

            if (targetExpression != null) {
                if (targetExpression instanceof ExpList expList) {
                    arguments.addAll(expList.getList());
                } else {
                    arguments.add(targetExpression);
                }
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
                .map(a -> acceptTree(a, context))
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

        return arguments;
    }

    private InvocationType resolveInvocationType(final ExecutableType methodType) {
        final InvocationType invocationType;
        final var ownerClass = methodType.getOwner();
        final var invokedMethod = methodType.getMethodSymbol();

        if (invokedMethod.isStatic()) {
            invocationType = InvocationType.STATIC;
        } else if (invokedMethod.getKind() == ElementKind.CONSTRUCTOR || invokedMethod.isPrivate()) {
            invocationType = InvocationType.SPECIAL;
        } else if (ownerClass.getKind() == ElementKind.INTERFACE) {
            invocationType = InvocationType.INTERFACE;
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
            final var exp = acceptTree(returnStatement.getExpression(), context);
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
            exp = acceptTree(next, context);
            iStatements.add(exp.unNx());
        }

        context.frame = parentFrame;
        return new Nx(Seq.seq(iStatements));
    }

    @Override
    public Exp visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                     final TranslateContext context) {
        final var collector = new UsedVarsCollector();
        collector.acceptTree(lambdaExpression, context);

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
                .map(
                        this::toIType)
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
        final var left = acceptTree(binaryExpression.getLeft(), context);
        final var right = acceptTree(binaryExpression.getRight(), context);

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
            //TODO Should not be null.
            return null;
        }

        return ToIType.toIType(typeMirror);
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
        } else if (variableDeclaratorStatement.getKind() != Kind.LOCAL_VARIABLE) {
            return null;
        }

        if (variableDeclaratorStatement.getValue() != null) {
            final var value = acceptTree(variableDeclaratorStatement.getValue(), context);
            final var identifier = variableDeclaratorStatement.getName();
            final var varType = toIType(variableDeclaratorStatement.getVariableType().getType());

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
        final var exp = acceptTree(unaryExpression.getExpression(), context);
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
        final Exp condition = acceptTree(ifStatementTree.getExpression(), context);
        final Exp trueExpression = acceptTree(ifStatementTree.getThenStatement(), context);

        final Exp falseExpression = ifStatementTree.getElseStatement() != null
                ? acceptTree(ifStatementTree.getElseStatement(), context)
                : new Nx(null);

        return new IfThenElseExp(
                condition,
                trueExpression,
                falseExpression
        );
    }

    @Override
    public Exp visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final TranslateContext context) {
        final var selected = (IdentifierTree) fieldAccessExpression.getSelected();
        final var field = fieldAccessExpression.getField();
        final var fieldType = toIType(compilerContext.getTreeUtils().typeOf(field));

        final var selectedExpr = acceptTree(selected, context).unEx();
        final var fieldExpr = acceptTree(field, context).unEx();

        if (selectedExpr == null) {
            return new Ex(fieldExpr);
        }

        if (selectedExpr instanceof IFieldAccess
                || selectedExpr instanceof TempExpr) {
            return new Ex(new ExpList(
                    selectedExpr,
                    fieldExpr
            ));
        } else if (fieldExpr instanceof Name name) {
            return new Ex(new IFieldAccess(
                    selectedExpr,
                    name.getLabel().getName(),
                    fieldType,
                    true
            ));
        } else {
            return new Ex(fieldExpr);
        }
    }

    @Override
    public Exp visitForStatement(final ForStatementTree forStatement, final TranslateContext context) {
        final var init = forStatement.getForInit().stream()
                .map(it ->
                        acceptTree(it, context))
                .reduce((first, second) -> new Ex(new ExpList(first.unEx(), second.unEx())))
                .orElse(new Ex(new TempExpr()));

        final var condition = forStatement.getCondition() != null
                ? acceptTree(forStatement.getCondition(), context)
                : new Ex(new TempExpr());

        final var update = forStatement.getForUpdate().stream()
                .map(it -> acceptTree(it, context))
                .reduce((first, second) -> new Ex(new ExpList(first.unEx(), second.unEx())))
                .orElse(new Ex(new TempExpr()));

        final var body = acceptTree(forStatement.getStatement(), context);

        return new ForExpr(
                init,
                condition,
                update,
                body
        );
    }

    @Override
    public Exp visitExpressionStatement(final ExpressionStatementTree expressionStatement, final TranslateContext context) {
        final var expression = expressionStatement.getExpression();
        final var expr = acceptTree(expression, context).unEx();

        if (expression instanceof MethodInvocationTree methodInvocationTree) {
            if (methodInvocationTree.getMethodType().getReturnType().getKind() != TypeKind.VOID) {
                final var move = new Move(
                        expr,
                        new TempExpr()
                );
                move.setLineNumber(expressionStatement.getLineNumber());
                return new Nx(move);
            }
        }

        final var es = new IExpressionStatement(expr);
        es.setLineNumber(expressionStatement.getLineNumber());
        return new Nx(es);
    }

    @Override
    public Exp visitCastExpression(final CastExpressionTree castExpression, final TranslateContext param) {
        final var expression = acceptTree(castExpression.getExpression(), param).unEx();
        final var targetType = castExpression.getTargetType();
        final var castType = ToIType.toIType(targetType.getType());
        return new Ex(new ITypeExpression(
                ITypeExpression.Kind.CAST,
                castType,
                expression
        ));
    }

    @Override
    public Exp visitWhileStatement(final WhileStatementTree whileStatement, final TranslateContext context) {
        final var condition = acceptTree(whileStatement.getCondition(), context);
        final var body = acceptTree(whileStatement.getBody(), context);

        return new WhileExp(
                condition,
                body
        );
    }

    @Override
    public Exp visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final TranslateContext context) {
        final var body = acceptTree(doWhileStatement.getBody(), context);
        final var condition = acceptTree(doWhileStatement.getCondition(), context);

        return new DoWhileExp(
                body,
                condition
        );
    }

    @Override
    public Exp visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final TranslateContext param) {
        final var expression = acceptTree(instanceOfExpression.getExpression(), param).unEx();
        final var targetType = (IdentifierTree) instanceOfExpression.getTypeExpression();
        final var clazz = ToIType.toIType(targetType.getType());

        return new Ex(new ITypeExpression(
                ITypeExpression.Kind.INSTANCEOF,
                clazz,
                expression
        ));
    }

    @Override
    public Exp visitNewClass(final NewClassExpression newClassExpression,
                             final TranslateContext param) {
        final var owner = ToIType.toIType(
                newClassExpression.getName().getType()
        );

        final var clazz = ToIType.toIType(newClassExpression.getName().getType());

        final var parameterTypes = newClassExpression.getArguments().stream()
                .map(ExpressionTree::getType)
                .map(ToIType::toIType)
                .toList();

        final var arguments = newClassExpression.getArguments().stream()
                .map(arg ->
                        acceptTree(arg, param))
                .map(Exp::unEx)
                .toList();

        final var call = new Call(
                InvocationType.SPECIAL,
                owner,
                new Name(Constants.INIT),
                IPrimitiveType.VOID,
                parameterTypes,
                arguments
        );

        return new Ex(new ITypeExpression(
                ITypeExpression.Kind.NEW,
                clazz,
                call
        ));
    }

    @Override
    public Exp visitNewArray(final NewArrayExpression newArrayExpression, final TranslateContext param) {
        final var dimensions = newArrayExpression.getDimensions();

        if (dimensions.size() != 1) {
            throw new TodoException();
        }

        final var dimension = dimensions.getFirst();
        final var dimExpr = acceptTree(dimension, param);

        final var type = (IdentifierTree) newArrayExpression.getElementType();
        final var clazz = ToIType.toIType(type.getType());

        final var newArrayExpr = new ITypeExpression(
                ITypeExpression.Kind.NEWARRAY,
                clazz,
                dimExpr.unEx()
        );

        if (newArrayExpression.getElements() == null) {
            return new Ex(newArrayExpr);
        } else {
            final var elements = newArrayExpression.getElements();
            final var expressions = new ArrayList<IExpression>();

            for (var index = 0; index < elements.size(); index++) {
                final var exp = acceptTree(elements.get(index), param).unEx();

                expressions.add(new ExpList(
                        InstExpression.dup(),
                        new Const(index),
                        exp,
                        InstExpression.arrayStore()
                ));
            }

            return new Ex(new ExpList(
                    newArrayExpr,
                    new ExpList(expressions)
            ));
        }
    }

    @Override
    public Exp visitArrayAccess(final ArrayAccessExpressionTree arrayAccessExpressionTree,
                                final TranslateContext context) {
        final var exp = acceptTree(arrayAccessExpressionTree.getExpression(), context);
        final var index = acceptTree(arrayAccessExpressionTree.getIndex(), context);
        return new Ex(new ArrayLoad(exp.unEx(), index.unEx()));
    }

    @Override
    public Exp visitSwitchStatement(final SwitchStatement switchStatement,
                                    final TranslateContext context) {
        final var selector = acceptTree(switchStatement.getSelector(), context);
        final var endLabel = new ILabelStatement(new ILabel());
        var defaultLabel = endLabel.getLabel();
        final var keys = new ArrayList<Integer>();
        final var switchLabels = new ArrayList<ILabel>();
        final var statements = new ArrayList<IStatement>();

        for (final var caseStatement : switchStatement.getCases()) {
            final var labels = caseStatement.getLabels();
            final var label = new ILabel();

            for (final CaseLabel caseLabel : labels) {
                if (caseLabel instanceof ConstantCaseLabel constantCaseLabel) {
                    if (constantCaseLabel.getExpression() instanceof LiteralExpressionTree literal){
                        keys.add(toSwitchKeyValue(literal.getLiteral()));
                        switchLabels.add(label);
                    } else if (constantCaseLabel.getExpression() instanceof IdentifierTree identifierTree) {
                        final var enumConstantName = identifierTree.getName();

                        final var map = compilerContext.getEnumUsageMap().getEnumSwitchMap(
                                context.classDeclaration,
                                (ClassSymbol) identifierTree.getSymbol().asType().asTypeElement()
                        );
                        final var literal = map.get(enumConstantName);
                        keys.add(toSwitchKeyValue(literal));
                        switchLabels.add(label);
                    }
                } else if (caseLabel instanceof DefaultCaseLabel) {
                    defaultLabel = label;
                }
            }

            statements.add(new ILabelStatement(label));

            final var body = caseStatement.getBody();
            final var statement = acceptTree(body, context).unNx();

            if (statement != null) {
                statements.add(statement);
            }
        }

        final var keyArray = keys.stream()
                .mapToInt(it -> it)
                .toArray();

        final var result = new ArrayList<IStatement>();

        result.add(
                new ISwitchStatement(
                        selector,
                        defaultLabel,
                        keyArray,
                        switchLabels.toArray(new ILabel[0])
                )
        );

        result.addAll(statements);
        result.add(endLabel);

        return new Nx(Seq.seq(result));
    }

    private int toSwitchKeyValue(final Object literal) {
        return switch (literal) {
            case Integer i -> i;
            case Character c -> c;
            case Byte b -> b;
            case Short s -> s;
            case null, default -> throw new IllegalArgumentException("Not supported");
        };
    }

    @Override
    public Exp visitThrowStatement(final ThrowStatement throwStatement, final TranslateContext param) {
        final var expr = acceptTree(throwStatement.getExpression(), param);
        return new Nx(Seq.seq(
                new IThrowStatement(expr.unEx()),
                new ILabelStatement()
        ));
    }

    @Override
    public Exp visitAnnotation(final AnnotationTree annotationTree, final TranslateContext param) {
        return null;
    }
}