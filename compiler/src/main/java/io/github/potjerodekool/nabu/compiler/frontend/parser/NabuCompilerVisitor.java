package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.NabuParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class NabuCompilerVisitor extends NabuParserBaseVisitor<Object> {

    private final FileObject fileObject;

    public NabuCompilerVisitor(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final NabuParser.OrdinaryCompilationUnitContext ctx) {
        var name = fileObject.getFileName();
        final var end = name.lastIndexOf(".");
        name = name.substring(0, end);

        final var startToken = ctx.getStart();

        final var cu = new CompilationUnit(
                ctx.start.getLine(),
                ctx.start.getCharPositionInLine()
        );
        cu.setFileObject(fileObject);

        if (ctx.packageDeclaration() != null) {
            final PackageDeclaration packageDeclaration = (PackageDeclaration)
                    ctx.packageDeclaration().accept(this);
            cu.add(packageDeclaration);
        }

        ctx.importDeclaration().stream()
                .map(importDeclaration -> ((ImportItem) importDeclaration.accept(this)))
                .forEach(cu::addImport);

        if (!ctx.topLevelClassOrInterfaceDeclaration().isEmpty()) {
            final var declarations = ctx.topLevelClassOrInterfaceDeclaration()
                    .stream()
                    .map(it -> (Element<?>) it.accept(this))
                            .toList();
            declarations.forEach(cu::add);
        } else {
            final var clazz = new ClassDeclaration.ClassDeclarationBuilder()
                    .lineNumber(startToken.getLine())
                    .columnNumber(startToken.getCharPositionInLine())
                    .modifier(CModifier.PUBLIC)
                    .simpleName(name)
                    .build();

            final var declarations = ctx.functionDeclaration()
                    .stream()
                    .map(it -> (Element<?>) it.accept(this))
                    .toList();
            declarations
                    .forEach(clazz::enclosedElement);
            cu.add(clazz);
        }

        return cu;
    }

    @Override
    public Tree visitPackageDeclaration(final NabuParser.PackageDeclarationContext ctx) {
        final var annotations = ctx.packageModifier().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var packageName = ctx.identifier().stream()
                .map(it -> (IdentifierTree) it.accept(this))
                .reduce((left, right) -> new IdentifierTree(left.getName() + "." + right.getName()))
                .orElse(null);

        return new PackageDeclaration(
                annotations,
                packageName.getName(),
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
    }

    @Override
    public Tree visitSingleTypeImportDeclaration(final NabuParser.SingleTypeImportDeclarationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);

        return new SingleImportItem(typeName.getName());
    }

    @Override
    public Tree visitTypeName(final NabuParser.TypeNameContext ctx) {
        final IdentifierTree pckName = (IdentifierTree) ctx.packageName().accept(this);

        if (ctx.typeIdentifier() == null) {
            return pckName;
        } else {
            final IdentifierTree identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);
            return new IdentifierTree(
                    pckName.getName()
                            + "."
                            + identifier.getName()
            );
        }
    }

    @Override
    public Tree visitPackageOrTypeName(final NabuParser.PackageOrTypeNameContext ctx) {
        final var ident = (Identifier) ctx.identifier().accept(this);
        final var packageOrTypeName = (Identifier) accept(ctx.packageOrTypeName());

        if (packageOrTypeName == null) {
            return new IdentifierTree(ident.getName());
        } else {
            return new IdentifierTree(
                    packageOrTypeName.getName()
                            + "."
                            + ident.getName()
            );
        }
    }

    @Override
    public Object visitFunctionDeclaration(final NabuParser.FunctionDeclarationContext ctx) {
        final var function = new Function(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );

        final var modifiers = ctx.functionModifier().stream()
                .map(it -> (CModifier) it.accept(this))
                .toList();
        function.modifiers(modifiers);

        final var functionHeader = (FunctionHeader) ctx.functionHeader().accept(this);
        //TODO

        final var functionName = functionHeader.functionDeclarator().name();
        final var returnType = functionHeader.result();
        function.returnType(returnType);

        final List<Variable> params = functionHeader.functionDeclarator().parameters();
        function.simpleName(functionName);
        params.forEach(function::parameter);

        if (ctx.methodBody() != null) {
            final BlockStatement body = (BlockStatement) ctx.methodBody().accept(this);
            function.body(body);
        }

        return function;
    }

    @Override
    public Object visitFunctionHeader(final NabuParser.FunctionHeaderContext ctx) {
        if (ctx.typeParameters() != null) {
            throw new TodoException();
        }

        final var annotations = ctx.annotation().stream()
                .map(it -> it.accept(this))
                .toList();

        if (!annotations.isEmpty()) {
            throw new TodoException();
        }

        final var result = (ExpressionTree) ctx.result().accept(this);
        final var functionDeclarator = (FunctionDeclarator) ctx.functionDeclarator().accept(this);

        if (ctx.throwsT() != null) {
            throw new TodoException();
        }

        return new FunctionHeader(
                functionDeclarator,
                result
        );
    }

    @Override
    public Object visitFunctionDeclarator(final NabuParser.FunctionDeclaratorContext ctx) {
        final var name = (IdentifierTree) ctx.identifier().accept(this);

        if (ctx.receiverParameter() != null) {
            throw new TodoException();
        }

        // CVariable
        final List<Variable> parameters =acceptList(ctx.formalParameterList());

        return new FunctionDeclarator(
                name.getName(),
                parameters
        );
    }

    @Override
    public Object visitFunctionModifier(final NabuParser.FunctionModifierContext ctx) {
        if (ctx.annotation() != null) {
            return ctx.annotation().accept(this);
        } else {
            final var text = ctx.getText();
            return CModifier.parse(text);
        }
    }

    @Override
    public Object visitFormalParameterList(final NabuParser.FormalParameterListContext ctx) {
        return ctx.formalParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitFormalParameter(final NabuParser.FormalParameterContext ctx) {
        if (!ctx.variableModifier().isEmpty()) {
            throw new TodoException();
        }

        final var name = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var type = (ExpressionTree) ctx.unannType().accept(this);

        final var parameter =new Variable(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );

        parameter.kind(Element.Kind.PARAMETER);
        parameter.simpleName(name.getName());
        parameter.type(type);

        return parameter;
    }

    @Override
    public Tree visitReturnStatement(final NabuParser.ReturnStatementContext ctx) {
        final ExpressionTree expression = accept(ctx.expression());
        final var returnStm = new ReturnStatement(expression);
        returnStm.setLineNumber(ctx.getStart().getLine());
        returnStm.setColumnNumber(ctx.getStart().getCharPositionInLine());
        return returnStm;
    }

    @Override
    public Tree visitLambdaExpression(final NabuParser.LambdaExpressionContext ctx) {
        final var lambda = new LambdaExpressionTree.LambdaExpressionTreeBuilder()
                .lineNumber(ctx.start.getLine())
                .columnNumber(ctx.start.getCharPositionInLine())
                .build();

        if (ctx.lambdaParameters() != null) {
            final var list = (List<Variable>) ctx.lambdaParameters().accept(this);
            list.forEach(lambda::variable);
        }

        final var body = ctx.lambdaBody().accept(this);

        if (body instanceof ExpressionTree expressionBody) {
            lambda.body(expressionBody);
        } else {
            final var statement = (Statement) body;
            lambda.body(statement);
        }

        return lambda;
    }

    @Override
    public Object visitLambdaParameters(final NabuParser.LambdaParametersContext ctx) {
        if (ctx.lambdaParameterList() != null) {
            return acceptList(ctx.lambdaParameterList());
        } else {
            throw new TodoException();
        }
    }

    @Override
    public Object visitLambdaParameterList(final NabuParser.LambdaParameterListContext ctx) {
        if (!ctx.lambdaParameter().isEmpty()) {
            return ctx.lambdaParameter().stream()
                    .map(it -> it.accept(this))
                    .toList();
        } else {
            return ctx.identifier().stream()
                    .map(it -> it.accept(this))
                    .toList();
        }
    }

    @Override
    public Object visitLambdaParameter(final NabuParser.LambdaParameterContext ctx) {
        final var name = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var type = (ExpressionTree) ctx.lambdaParameterType().accept(this);

        final var variable = new Variable(
                ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine()
        );
        variable.kind(Element.Kind.PARAMETER);

        variable.simpleName(name.getName());
        variable.type(type);

        return variable;
    }

    @Override
    public Tree visitEqualityExpression(final NabuParser.EqualityExpressionContext ctx) {
        final var right = (ExpressionTree) ctx.relationalExpression().accept(this);

        if (ctx.equalityExpression() != null) {
            final ExpressionTree left = (ExpressionTree) ctx.equalityExpression().accept(this);
            final var operatorText = ctx.oper.getText();
            final var binExpr = new BinaryExpressionTree(
                    left,
                    Tag.fromText(operatorText),
                    right
            );
            binExpr.setLineNumber(ctx.getStart().getLine());
            binExpr.setColumnNumber(ctx.getStart().getCharPositionInLine());
            return binExpr;
        } else {
            return right;
        }
    }

    @Override
    public Tree visitRelationalExpression(final NabuParser.RelationalExpressionContext ctx) {
        final var shiftExpression = (ExpressionTree) accept(ctx.shiftExpression());
        final ExpressionTree relationalExpression = accept(ctx.relationalExpression());
        final ExpressionTree referenceType = accept(ctx.referenceType());

        if (referenceType != null) {
            return new InstanceOfExpression(
                    relationalExpression,
                    referenceType
            );
        } else if (relationalExpression != null) {
            final var tag = Tag.fromText(ctx.oper.getText());

            final var binExpr = new BinaryExpressionTree(
                    relationalExpression,
                    tag,
                    shiftExpression
            );

            binExpr.setLineNumber(ctx.getStart().getLine());
            binExpr.setColumnNumber(ctx.getStart().getCharPositionInLine());
            return binExpr;
        } else {
            return shiftExpression;
        }
    }

    @Override
    public Object visitShiftExpression(final NabuParser.ShiftExpressionContext ctx) {
        return ctx.additiveExpression().accept(this);
    }

    @Override
    public Object visitAdditiveExpression(final NabuParser.AdditiveExpressionContext ctx) {
        return ctx.multiplicativeExpression().accept(this);
    }

    @Override
    public Object visitMultiplicativeExpression(final NabuParser.MultiplicativeExpressionContext ctx) {
        return ctx.unaryExpression().accept(this);
    }

    @Override
    public Object visitUnaryExpression(final NabuParser.UnaryExpressionContext ctx) {
        if (ctx.unaryExpression() != null) {
            final var prefix = ctx.prefix.getText();
            final var expr = (ExpressionTree) ctx.unaryExpression().accept(this);

            if ("-".equals(prefix) && expr instanceof LiteralExpressionTree literalExpressionTree) {
                return literalExpressionTree.negate();
            }

            final var tag = "-".equals(prefix) ? Tag.SUB : Tag.ADD;

            return new UnaryExpressionTree(
                    tag,
                    expr
            );
        } else {
            return ctx.unaryExpressionNotPlusMinus().accept(this);
        }
    }

    @Override
    public Object visitUnaryExpressionNotPlusMinus(final NabuParser.UnaryExpressionNotPlusMinusContext ctx) {
        if (ctx.postfixExpression() != null) {
            return ctx.postfixExpression().accept(this);
        } else if (ctx.castExpression() != null) {
            return ctx.castExpression().accept(this);
        } else {
            final var expression = (ExpressionTree) ctx.unaryExpression().accept(this);
            return addLineInfo(new UnaryExpressionTree(
                    Tag.NOT,
                    expression
            ), ctx.getStart());
        }
    }

    @Override
    public Object visitCastExpression(final NabuParser.CastExpressionContext ctx) {
        final ExpressionTree expressionTree;
        final ExpressionTree type;

        if (ctx.primitiveType() != null) {
            throw new TodoException();
        } else {
            type = (ExpressionTree) ctx.referenceType().accept(this);

            if (!ctx.additionalBound().isEmpty()) {
                throw new TodoException();
            }

            if (ctx.unaryExpressionNotPlusMinus() != null) {
                expressionTree = (ExpressionTree) ctx.unaryExpressionNotPlusMinus().accept(this);
            } else {
                expressionTree = (ExpressionTree) ctx.lambdaExpression().accept(this);
            }
        }

        return new CastExpressionTree()
                .targetType(type)
                .expression(expressionTree);
    }

    @Override
    public Object visitPrimary(final NabuParser.PrimaryContext ctx) {
        return ctx.primaryNoNewArray().accept(this);
    }

    @Override
    public Object visitPrimaryNoNewArray(final NabuParser.PrimaryNoNewArrayContext ctx) {
        if (ctx.literal() != null) {
            return ctx.literal().accept(this);
        } else if (ctx.THIS() != null) {
            final var thisExpression = new IdentifierTree("this");

            if (ctx.pNNA() == null) {
                return thisExpression;
            } else {
                final var expression = (ExpressionTree) ctx.pNNA().accept(this);
                return new FieldAccessExpressioTree(
                        thisExpression,
                        expression
                );
            }
        } else {
            // typeName '.' typeArguments? identifier '(' argumentList? ')' pNNA?
            final var typeName = (ExpressionTree) ctx.typeName().accept(this);
            final var identifier = (ExpressionTree) ctx.identifier().accept(this);
            final List<ExpressionTree> arguments =
                    ctx.argumentList() != null
                            ? (List<ExpressionTree>) ctx.argumentList().accept(this)
                            : List.of();

            return new MethodInvocationTree()
                    .target(typeName)
                    .name(identifier)
                    .arguments(arguments);
        }
    }

    @Override
    public Tree visitExpressionName(final NabuParser.ExpressionNameContext ctx) {
        final var identifier = (ExpressionTree) ctx.identifier().accept(this);

        if (ctx.ambiguousName() != null) {
            final var ambiguousName = (ExpressionTree) ctx.ambiguousName().accept(this);
            return new FieldAccessExpressioTree(
                    ambiguousName,
                    identifier
            );
        } else {
            return identifier;
        }
    }

    @Override
    public Tree visitAmbiguousName(final NabuParser.AmbiguousNameContext ctx) {
        final var identifier = ctx.identifier();
        final var identifierTree = (ExpressionTree) identifier.accept(this);
        identifierTree.setLineNumber(identifier.getStart().getLine());
        identifierTree.setColumnNumber(identifier.getStart().getCharPositionInLine());

        if (ctx.ambiguousName() != null) {
            final var ambiguousName = (ExpressionTree) ctx.ambiguousName().accept(this);
            return new FieldAccessExpressioTree(
                    identifierTree,
                    ambiguousName
            );
        } else {
            return identifierTree;
        }
    }

    @Override
    public Object visitFieldAccess(final NabuParser.FieldAccessContext ctx) {
        final ExpressionTree target;
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);

        if (ctx.primary() != null) {
            target = (ExpressionTree) ctx.primary().accept(this);

            return new FieldAccessExpressioTree(
                    target,
                    identifier
            );
        } else {
            throw new TodoException();
        }
    }

    private IdentifierTree identifier(final Token token) {
        final var identifier = new IdentifierTree(token.getText());
        identifier.setLineNumber(token.getLine());
        identifier.setColumnNumber(token.getCharPositionInLine() + 1);
        return identifier;
    }

    @Override
    public Tree visitTerminal(final TerminalNode node) {
        final var type = node.getSymbol().getType();
        node.getSymbol().getLine();

        return switch (type) {
            case NabuLexer.Identifier,
                 NabuLexer.DOT -> identifier(node.getSymbol());
            case NabuLexer.INT -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.INT);
            case NabuLexer.BYTE -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.BYTE);
            case NabuLexer.SHORT -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.SHORT);
            case NabuLexer.LONG -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.LONG);
            case NabuLexer.CHAR -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.CHAR);
            case NabuLexer.FLOAT -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.FLOAT);
            case NabuLexer.DOUBLE -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.DOUBLE);
            case NabuLexer.BOOLEAN -> new PrimitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN);
            default -> throw new UnsupportedOperationException("" + type);
        };
    }

    @Override
    public Object visitUnannPrimitiveType(final NabuParser.UnannPrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            return new PrimitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN);
        }
    }

    @Override
    public Tree visitUnannClassOrInterfaceType(final NabuParser.UnannClassOrInterfaceTypeContext ctx) {
        final TypeApplyTree packageName = accept(ctx.packageName());
        final var type = (ExpressionTree) ctx.typeIdentifier().accept(this);
        final var typeArguments = (List<ExpressionTree>) accept(ctx.typeArguments());

        if (packageName == null) {
            if (typeArguments != null) {
                final var ident = (IdentifierTree) type;

                final var typeApply = new TypeApplyTree(
                        ident
                );
                typeArguments.forEach(
                        typeApply::addTypeParameter
                );
                return typeApply;
            } else {
                return type;
            }
        } else {
            final var ident = (IdentifierTree) type;

            if (typeArguments == null) {

                return new IdentifierTree(
                        packageName.getName()
                                + "."
                                + ident.getName()
                );
            }


            return new TypeApplyTree(
                    new IdentifierTree(
                            packageName.getName()
                                    + "."
                                    + ident.getName()
                    ),
                    typeArguments
            );
        }
    }

    @Override
    public Object visitClassOrInterfaceType(final NabuParser.ClassOrInterfaceTypeContext ctx) {
        if (ctx.packageName() != null) {
            throw new TodoException();
        }

        final var annotations = ctx.annotation().stream()
                .map(it -> it.accept(this))
                .toList();

        if (!annotations.isEmpty()) {
            throw new TodoException();
        }

        final var ident = (IdentifierTree) ctx.typeIdentifier().accept(this);

        final List<ExpressionTree> typeArguments;

        if (ctx.typeArguments() != null) {
            typeArguments = (List<ExpressionTree>) ctx.typeArguments().accept(this);
        } else {
            typeArguments = List.of();
        }

        if (ctx.coit() != null) {
            throw new TodoException();
        }

        if (typeArguments.isEmpty()) {
            return ident;
        } else {
            final var typeApply = new TypeApplyTree(
                    ident
            );
            typeArguments.forEach(
                    typeApply::addTypeParameter
            );
            return typeApply;
        }
    }

    @Override
    public Object visitTypeArguments(final NabuParser.TypeArgumentsContext ctx) {
        return ctx.typeArgumentList().accept(this);
    }

    @Override
    public Object visitTypeArgumentList(final NabuParser.TypeArgumentListContext ctx) {
        return ctx.typeArgument().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Tree visitPackageName(final NabuParser.PackageNameContext ctx) {
        final IdentifierTree identifier = (IdentifierTree) ctx.identifier().accept(this);
        final IdentifierTree packageName = accept(ctx.packageName());

        if (packageName == null) {
            return new IdentifierTree(identifier.getName());
        } else {
            final var first = identifier.getName();
            final var second = packageName.getName();
            return new IdentifierTree(first + "." + second);
        }
    }

    @Override
    public Tree visitTypeIdentifier(final NabuParser.TypeIdentifierContext ctx) {
        final var name = ctx.Identifier().getText();
        return new IdentifierTree(name);
    }

    @Override
    public Tree visitLocalVariableDeclaration(final NabuParser.LocalVariableDeclarationContext ctx) {
        final var modifiers = ctx.variableModifier().stream()
                .map(it -> it.accept(this))
                .toList();

        final ExpressionTree type = (ExpressionTree) ctx.localVariableType().accept(this);

        final var variableDeclarators = ctx.variableDeclaratorList().accept(this);

        if (variableDeclarators instanceof VariableDeclarator(IdentifierTree id,
                                                              ExpressionTree initializer)) {
            final var statement = new CVariableDeclaratorStatement(
                    type,
                    id,
                    initializer
            );

            statement.setLineNumber(ctx.getStart().getLine());
            statement.setColumnNumber(ctx.getStart().getCharPositionInLine());
            return statement;
        }

        throw new TodoException();
    }

    @Override
    public Object visitVariableDeclarator(final NabuParser.VariableDeclaratorContext ctx) {
        final var variableDeclaratorId = (IdentifierTree) ctx.variableDeclaratorId().accept(this);
        final var init = (ExpressionTree) accept(ctx.variableInitializer());

        return new VariableDeclarator(
                variableDeclaratorId,
                init
        );
    }

    @Override
    public Object visitLocalVariableType(final NabuParser.LocalVariableTypeContext ctx) {
        if (ctx.unannType() != null) {
            return ctx.unannType().accept(this);
        } else {
            return new VariableTypeTree();
        }
    }

    @Override
    public Object visitLocalVariableDeclarationStatement(final NabuParser.LocalVariableDeclarationStatementContext ctx) {
        return ctx.localVariableDeclaration().accept(this);
    }

    @Override
    public Tree visitLiteral(final NabuParser.LiteralContext ctx) {
        final Object value;
        final TerminalNode node;

        if (ctx.IntegerLiteral() != null) {
            node = ctx.IntegerLiteral();
            final var text = node.getText();

            if (text.toLowerCase().endsWith("l")) {
                value = Long.parseLong(text.substring(0, text.length() - 1));
            } else {
                value = Integer.parseInt(text);
            }
        } else if (ctx.BooleanLiteral() != null) {
            node = ctx.BooleanLiteral();
            value = Boolean.valueOf(node.getText());
        } else if (ctx.StringLiteral() != null) {
            node = ctx.StringLiteral();
            var text = node.getText();
            value = text.substring(1, text.length() - 1);
        } else if (ctx.NullLiteral() != null) {
            node = ctx.NullLiteral();
            value = null;
        } else {
            return null;
        }

        final var literalExpression = new LiteralExpressionTree(value);
        literalExpression.setLineNumber(node.getSymbol().getLine());
        literalExpression.setColumnNumber(node.getSymbol().getCharPositionInLine());
        return literalExpression;
    }

    private <T> T accept(final ParserRuleContext parserRuleContext) {
        if (parserRuleContext == null) {
            return null;
        } else {
            return (T) parserRuleContext.accept(this);
        }
    }

    private <E> List<E> acceptList(final ParserRuleContext context) {
        if (context == null) {
            return List.of();
        } else {
            return (List<E>) context.accept(this);
        }
    }

    @Override
    public Tree visitExpressionStatement(final NabuParser.ExpressionStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.statementExpression().accept(this);
        return addLineInfo(new StatementExpression(expression), ctx.getStart());
    }

    @Override
    public Object visitExpression(final NabuParser.ExpressionContext ctx) {
        if (ctx.lambdaExpression() != null) {
            return ctx.lambdaExpression().accept(this);
        } else {
            return ctx.assignmentExpression().accept(this);
        }
    }

    @Override
    public Object visitAssignmentExpression(final NabuParser.AssignmentExpressionContext ctx) {
        return ctx.conditionalExpression().accept(this);
    }

    @Override
    public Object visitAssignment(final NabuParser.AssignmentContext ctx) {
        final var leftHandSide = (ExpressionTree) ctx.leftHandSide().accept(this);
        final var assignmentOperator = (Tag) ctx.assignmentOperator().accept(this);
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        return addLineInfo(new BinaryExpressionTree(
                leftHandSide,
                assignmentOperator,
                expression
        ), ctx.getStart());
    }

    private <E extends Tree> E addLineInfo(final E e,
                                           final Token token) {
        e.setLineNumber(token.getLine());
        e.setColumnNumber(token.getCharPositionInLine());
        return e;
    }

    @Override
    public Object visitAssignmentOperator(final NabuParser.AssignmentOperatorContext ctx) {
        return Tag.fromText(ctx.start.getText());
    }

    @Override
    public Object visitConditionalExpression(final NabuParser.ConditionalExpressionContext ctx) {
        return ctx.conditionalOrExpression().accept(this);
    }

    @Override
    public Object visitConditionalOrExpression(final NabuParser.ConditionalOrExpressionContext ctx) {
        final var andExpression = (ExpressionTree) ctx.conditionalAndExpression().accept(this);

        if (ctx.conditionalOrExpression() == null) {
            return andExpression;
        } else {
            final var orExpression = (ExpressionTree) ctx.conditionalOrExpression().accept(this);
            final var binExpr = new BinaryExpressionTree(
                    orExpression,
                    Tag.OR,
                    andExpression
            );

            binExpr.setLineNumber(ctx.getStart().getLine());
            binExpr.setColumnNumber(ctx.getStart().getCharPositionInLine());
            return binExpr;
        }
    }

    @Override
    public Tree visitConditionalAndExpression(final NabuParser.ConditionalAndExpressionContext ctx) {
        final var inclusiveOrExpression = (ExpressionTree) ctx.inclusiveOrExpression().accept(this);

        if (ctx.conditionalAndExpression() == null) {
            return inclusiveOrExpression;
        }

        final var andExpression = (ExpressionTree) ctx.conditionalAndExpression().accept(this);

        final var binExpr = new BinaryExpressionTree(
                andExpression,
                Tag.AND,
                inclusiveOrExpression
        );

        binExpr.setLineNumber(ctx.getStart().getLine());
        binExpr.setColumnNumber(ctx.getStart().getCharPositionInLine());
        return binExpr;
    }

    @Override
    public Object visitInclusiveOrExpression(final NabuParser.InclusiveOrExpressionContext ctx) {
        return ctx.exclusiveOrExpression().accept(this);
    }

    @Override
    public Object visitExclusiveOrExpression(final NabuParser.ExclusiveOrExpressionContext ctx) {
        return ctx.andExpression().accept(this);
    }

    @Override
    public Object visitAndExpression(final NabuParser.AndExpressionContext ctx) {
        return ctx.equalityExpression().accept(this);
    }

    @Override
    public Object visitPrimitiveType(final NabuParser.PrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            return new PrimitiveTypeTree(PrimitiveTypeTree.Kind.BOOLEAN);
        }
    }

    @Override
    public Object visitWildcard(final NabuParser.WildcardContext ctx) {
        final var annotations = ctx.annotation().stream()
                .map(a -> a.accept(this))
                .toList();

        if (!annotations.isEmpty()) {
            throw new TodoException();
        }

        if (ctx.wildcardBounds() != null) {
            final var bounds = ctx.accept(this);
            throw new TodoException();
        }

        return new WildCardExpressionTree(
                null,
                null
        );
    }

    @Override
    public Object visitIfThenElseStatement(final NabuParser.IfThenElseStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var thenStatement = (Statement) ctx.statementNoShortIf().accept(this);
        final var elseStatement = (Statement) ctx.statement().accept(this);
        return new IfStatementTree(expression, thenStatement, elseStatement);
    }

    @Override
    public Object visitBlock(final NabuParser.BlockContext ctx) {
        final List<Statement> statements;

        if (ctx.blockStatements() != null) {
            final var result = ctx.blockStatements().accept(this);

            if (result instanceof List<?>) {
                statements = (List<Statement>) result;
            } else {
                statements = List.of((Statement) result);
            }
        } else {
            statements = List.of();
        }

        return new BlockStatement()
                .statement(statements);
    }

    @Override
    public Object visitBlockStatements(final NabuParser.BlockStatementsContext ctx) {
        return ctx.blockStatement().stream()
                .map(bs -> {
                    final var statement = (Statement) bs.accept(this);
                    Objects.requireNonNull(statement);
                    return statement;
                })
                .toList();
    }

    @Override
    public Object visitEmptyStatement_(final NabuParser.EmptyStatement_Context ctx) {
        return new EmptyStatementTree();
    }

    @Override
    public Object visitPostIncrementExpression(final NabuParser.PostIncrementExpressionContext ctx) {
        final var expression = (ExpressionTree) ctx.postfixExpression().accept(this);
        return addLineInfo(new UnaryExpressionTree(
                Tag.POST_INC,
                expression
        ), ctx.start);
    }

    @Override
    public Object visitBasicForStatement(final NabuParser.BasicForStatementContext ctx) {
        final var forInit = (Statement) accept(ctx.forInit());
        final var expression = (ExpressionTree) accept(ctx.expression());
        final var forUpdate = (ExpressionTree) accept(ctx.forUpdate());
        final var statement = (Statement) ctx.statement().accept(this);

        final var forStatement = new ForStatement(
                forInit,
                expression,
                forUpdate,
                statement
        );

        forStatement.setLineNumber(ctx.getStart().getLine());
        forStatement.setColumnNumber(ctx.getStart().getCharPositionInLine());

        return forStatement;
    }

    @Override
    public Object visitNormalClassDeclaration(final NabuParser.NormalClassDeclarationContext ctx) {
        final var builder = new ClassDeclaration.ClassDeclarationBuilder();

        final var modifiers = ctx.classModifier().stream()
                .map(m -> (CModifier) m.accept(this))
                .collect(Collectors.toList());

        if (modifiers.stream()
                .noneMatch(CModifier::isAccessModifier)) {
            modifiers.add(CModifier.PUBLIC);
        }

        builder.modifiers(modifiers);

        final var name = (IdentifierTree) ctx.typeIdentifier().accept(this);
        builder.simpleName(name.getName());

        if (ctx.typeParameters() != null) {
            final List<TypeParameterTree> typeParameters = accept(ctx.typeParameters());
            builder.typeParameters(typeParameters);
        }

        if (ctx.classExtends() != null) {
            final ExpressionTree extendion = accept(ctx.classExtends());
            builder.extendion(extendion);
        }

        if (ctx.classImplements() != null) {
            final List<ExpressionTree> classImplements = accept(ctx.classImplements());
            builder.implementing(classImplements);
        }

        if (ctx.classPermits() != null) {
            ctx.classPermits().accept(this);
            throw new TodoException();
        }

        final var body = (List<Element<?>>) ctx.classBody().accept(this);

        builder.enclosedElements(body);
        return builder.build();
    }

    @Override
    public Object visitClassExtends(final NabuParser.ClassExtendsContext ctx) {
        return ctx.classType().accept(this);
    }

    @Override
    public Object visitClassImplements(final NabuParser.ClassImplementsContext ctx) {
        return ctx.interfaceTypeList().accept(this);
    }

    @Override
    public Object visitInterfaceTypeList(final NabuParser.InterfaceTypeListContext ctx) {
        return ctx.interfaceType().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitTypeParameters(final NabuParser.TypeParametersContext ctx) {
        return ctx.typeParameterList().accept(this);
    }

    @Override
    public Object visitClassType(final NabuParser.ClassTypeContext ctx) {
        final var annotations = ctx.annotation().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        if (ctx.typeArguments() != null) {
            throw new TodoException();
        }

        IdentifierTree prefix = null;

        if (ctx.packageName() != null) {
            prefix = (IdentifierTree) ctx.packageName().accept(this);
        } else if (ctx.classOrInterfaceType() != null) {
            prefix = (IdentifierTree) ctx.classOrInterfaceType().accept(this);
        }

        var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);

        if (prefix != null) {
            identifier = new IdentifierTree(prefix.getName() + "." + identifier.getName());
        }

        if (annotations.isEmpty()) {
            return identifier;
        } else {
            return new AnnotatedTypeTree(
                    annotations,
                    identifier,
                    List.of()
            );
        }
    }

    @Override
    public Object visitTypeParameterList(final NabuParser.TypeParameterListContext ctx) {
        return ctx.typeParameter().stream()
                .map(it -> it .accept(this))
                .toList();
    }

    @Override
    public Object visitClassBody(final NabuParser.ClassBodyContext ctx) {
        return ctx.classBodyDeclaration().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitClassModifier(final NabuParser.ClassModifierContext ctx) {
        if (ctx.annotation() != null) {
            throw new TodoException();
        } else {
            final var text = ctx.getChild(0).getText();
            return CModifier.parse(text);
        }
    }

    @Override
    public Object visitConstructorModifier(final NabuParser.ConstructorModifierContext ctx) {
        if (ctx.annotation() != null) {
            throw new TodoException();
        } else {
            final var text = ctx.getChild(0).getText();
            return CModifier.parse(text);
        }
    }

    @Override
    public Object visitConstructorDeclarator(final NabuParser.ConstructorDeclaratorContext ctx) {
        final var builder = new Function.CFunctionBuilder();
        builder.kind(Element.Kind.CONSTRUCTOR);
        builder.returnType(new PrimitiveTypeTree(PrimitiveTypeTree.Kind.VOID));

        if (ctx.typeParameters() != null) {
            throw new TodoException();
        }

        final var name = (IdentifierTree) ctx.simpleTypeName().accept(this);

        if (ctx.receiverParameter() != null) {
            throw new TodoException();
        }

        if (ctx.formalParameterList() != null) {
            throw new TodoException();
        }

        builder.simpleName(name.getName());

        return builder.build();
    }

    @Override
    public Object visitConstructorBody(final NabuParser.ConstructorBodyContext ctx) {
        final var body = new BlockStatement();

        if (ctx.explicitConstructorInvocation() != null) {
            final var statement = (Statement) ctx.explicitConstructorInvocation().accept(this);
            body.statement(statement);
        }

        if (ctx.blockStatements() != null) {
            final var blockStatements = (List<Statement>) ctx.blockStatements().accept(this);
            body.statement(blockStatements);
        }

        return body;
    }

    @Override
    public Object visitConstructorDeclaration(final NabuParser.ConstructorDeclarationContext ctx) {
        final var modifiers = ctx.constructorModifier().stream()
                .map(it -> (CModifier) it.accept(this))
                .toList();

        final var constructor = (Function) ctx.constructorDeclarator().accept(this);

        if (ctx.throwsT() != null) {
            throw new TodoException();
        }

        final var body = (BlockStatement) ctx.constructorBody().accept(this);

        constructor.modifiers(modifiers);
        constructor.body(body);

        return constructor;
    }

    @Override
    public Object visitForStatement(final NabuParser.ForStatementContext ctx) {
        return super.visitForStatement(ctx);
    }

    @Override
    public Object visitEnhancedForStatement(final NabuParser.EnhancedForStatementContext ctx) {
        final var localVariableDeclaration = (CVariableDeclaratorStatement) ctx.localVariableDeclaration().accept(this);
        final var expression =  (ExpressionTree) ctx.expression().accept(this);
        final var statement = (Statement) ctx.statement().accept(this);

        return new EnhancedForStatement(
                localVariableDeclaration,
                expression,
                statement
        );
    }

    @Override
    public Object visitEnhancedForStatementNoShortIf(final NabuParser.EnhancedForStatementNoShortIfContext ctx) {
        throw new TodoException();
    }

    @Override
    public Object visitVariableModifier(final NabuParser.VariableModifierContext ctx) {
        if (ctx.annotation() != null) {
            return ctx.annotation().accept(this);
        } else {
            final var type = ctx.getStart().getType();

            if (type == NabuParser.VAR) {
                return new VariableTypeTree();
            } else {
                return CModifier.FINAL;
            }
        }
    }

    @Override
    public Object visitExplicitConstructorInvocation(final NabuParser.ExplicitConstructorInvocationContext ctx) {
        final List<IdentifierTree> typeArguments;
        final List<ExpressionTree> arguments;
        ExpressionTree target = null;

        if (ctx.typeArguments() != null) {
            typeArguments = (List<IdentifierTree>) ctx.typeArguments().accept(this);
        } else {
            typeArguments = List.of();
        }

        if (ctx.argumentList() != null) {
            arguments = (List<ExpressionTree>) ctx.argumentList().accept(this);
        } else {
            arguments = List.of();
        }

        if (ctx.expressionName() != null) {
            target = (ExpressionTree) ctx.expressionName().accept(this);
        } else if (ctx.primary() != null) {
            target = (ExpressionTree) ctx.primary().accept(this);
        }

        final var methodName = new IdentifierTree(ctx.name.getText());

        return new StatementExpression(new MethodInvocationTree()
                .typeArguments(typeArguments)
                .target(target)
                .name(methodName)
                .arguments(arguments));
    }

    @Override
    public Object visitArgumentList(final NabuParser.ArgumentListContext ctx) {
        return ctx.expression().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitMarkerAnnotation(final NabuParser.MarkerAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        return new AnnotationTree(typeName);
    }

    @Override
    public Object visitUnqualifiedClassInstanceCreationExpression(final NabuParser.UnqualifiedClassInstanceCreationExpressionContext ctx) {
        final var typeArgs = acceptList(ctx.typeArguments());

        if (!typeArgs.isEmpty()) {
           throw new TodoException();
        }

        final var clazz = (ExpressionTree) ctx.classOrInterfaceTypeToInstantiate().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        final List<Statement> classBody = accept(ctx.classBody());

        return new NewClassExpression(
                clazz,
                arguments,
                classBody
        );
    }

    @Override
    public Object visitClassOrInterfaceTypeToInstantiate(final NabuParser.ClassOrInterfaceTypeToInstantiateContext ctx) {
        // AnnotatedTypeTree
        ExpressionTree expressionTree = null;

        for (final var child : ctx.children) {
            final var expr = child.accept(this);

            if (expressionTree == null) {
                expressionTree = (ExpressionTree) expr;
            } else if (expr instanceof List<?>) {
                final var typeParameters = (List<ExpressionTree>) expr;
                expressionTree = new TypeApplyTree(
                        expressionTree,
                        typeParameters
                );
            }
        }

        return expressionTree;
    }

    @Override
    public Object visitWhileStatement(final NabuParser.WhileStatementContext ctx) {
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var body = (Statement) ctx.statement().accept(this);

        return new WhileStatement(
                expression,
                body
        );
    }

    @Override
    public Object visitWhileStatementNoShortIf(final NabuParser.WhileStatementNoShortIfContext ctx) {
        final var expression = (ExpressionTree) ctx.expression().accept(this);
        final var body = (Statement) ctx.statementNoShortIf().accept(this);

        return new WhileStatement(
                expression,
                body
        );
    }

    @Override
    public Object visitMethodInvocation(final NabuParser.MethodInvocationContext ctx) {
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());
        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());

        if (ctx.methodName() != null) {
            final var methodName = (IdentifierTree) ctx.methodName().accept(this);
            return new MethodInvocationTree()
                    .name(methodName)
                    .arguments(arguments);
        } else if (ctx.typeName() != null) {
            final var typeName = (IdentifierTree) ctx.typeName().accept(this);
            final var identifier = (ExpressionTree) ctx.identifier().accept(this);

            return new MethodInvocationTree()
                    .target(typeName)
                    .typeArguments(typeArguments)
                    .name(identifier)
                    .arguments(arguments);
        } else if (ctx.expressionName() != null) {
            final var expressionName = (ExpressionTree) ctx.expressionName().accept(this);
            final var identifier = ctx.identifier().accept(this);
            throw new TodoException();
        } else if (ctx.primary() != null) {
            final var primary = (ExpressionTree) ctx.primary().accept(this);
            final var identifier = ctx.identifier().accept(this);
            throw new TodoException();
        } else {
            throw new TodoException();
        }
    }

    @Override
    public Object visitPNNA(final NabuParser.PNNAContext ctx) {
        final ExpressionTree pnna = accept(ctx.pNNA());
        final List<IdentifierTree> typeArguments = acceptList(ctx.typeArguments());
        final List<ExpressionTree> arguments = acceptList(ctx.argumentList());

        if (ctx.unqualifiedClassInstanceCreationExpression() != null) {
            return ctx.unqualifiedClassInstanceCreationExpression().accept(this);
        } else if (ctx.identifier() != null) {
            final var identifier = (ExpressionTree) ctx.identifier().accept(this);

            if (ctx.argumentList() != null) {
                return new MethodInvocationTree()
                        .typeArguments(typeArguments)
                        .name(identifier)
                        .arguments(arguments);
            } else if (pnna != null) {
                return new FieldAccessExpressioTree(
                        identifier,
                        pnna
                );
            } else {
                return identifier;
            }
        } else if (ctx.expression() != null) {
            throw new TodoException();
        }

        throw new TodoException();
    }

    @Override
    public Object visitDoStatement(final NabuParser.DoStatementContext ctx) {
        final var body = (Statement) ctx.statement().accept(this);
        final var condition = (ExpressionTree) ctx.expression().accept(this);

        return new DoWhileStatement(
                body,
                condition
        );
    }

    @Override
    public Object visitNormalAnnotation(final NabuParser.NormalAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        final List<ExpressionTree> arguments = acceptList(ctx.elementValuePairList());

        return new AnnotationTree(
                typeName,
                arguments
        );
    }

    @Override
    public Object visitElementValuePairList(final NabuParser.ElementValuePairListContext ctx) {
        return ctx.elementValuePair().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitElementValuePair(final NabuParser.ElementValuePairContext ctx) {
        final var identifier = (IdentifierTree) ctx.identifier().accept(this);
        final var elementValue = (ExpressionTree) ctx.elementValue().accept(this);

        return new Function.CFunctionBuilder()
                .simpleName(identifier.getName())
                .defaultValue(elementValue)
                .build();
    }

    @Override
    public Object visitSingleElementAnnotation(final NabuParser.SingleElementAnnotationContext ctx) {
        final var typeName = (IdentifierTree) ctx.typeName().accept(this);
        final var elementValue = (ExpressionTree) ctx.elementValue().accept(this);

        return new AnnotationTree(
                typeName,
                List.of(elementValue)
        );
    }

    @Override
    public Object visitTypeParameter(final NabuParser.TypeParameterContext ctx) {
        final var annotations = ctx.typeParameterModifier().stream()
                .map(it -> (AnnotationTree) it.accept(this))
                .toList();

        final var identifier = (IdentifierTree) ctx.typeIdentifier().accept(this);

        final List<ExpressionTree> typeBound = acceptList(ctx.typeBound());

        return new TypeParameterTree(
                annotations,
                identifier,
                typeBound
        );
    }

    @Override
    public Object visitTypeBound(final NabuParser.TypeBoundContext ctx) {
        if (ctx.typeVariable() != null) {
            final var expression = (ExpressionTree) ctx.typeVariable().accept(this);
            return List.of(expression);
        } else if (ctx.classOrInterfaceType() != null) {
            final var list = new ArrayList<ExpressionTree>();
            final var classOrInterfaceType = (ExpressionTree) ctx.classOrInterfaceType().accept(this);
            list.add(classOrInterfaceType);

            for (final var additionalBoundContext : ctx.additionalBound()) {
                final var additionalBound = (ExpressionTree) additionalBoundContext.accept(this);
                list.add(additionalBound);
            }

            return list;
        } else {
            return List.of();
        }
    }

    @Override
    public Object visitAdditionalBound(final NabuParser.AdditionalBoundContext ctx) {
        return ctx.interfaceType().accept(this);
    }

    @Override
    public Object visitPostDecrementExpression(final NabuParser.PostDecrementExpressionContext ctx) {
        final var expression = (ExpressionTree) ctx.postfixExpression().accept(this);
        return new UnaryExpressionTree(
                Tag.POST_DEC,
                expression
        );
    }
}

record VariableDeclarator(IdentifierTree id,
                          ExpressionTree initializer) {

}

record FunctionDeclarator(String name, List<Variable> parameters) {

}

record FunctionHeader(FunctionDeclarator functionDeclarator, ExpressionTree result) {

}