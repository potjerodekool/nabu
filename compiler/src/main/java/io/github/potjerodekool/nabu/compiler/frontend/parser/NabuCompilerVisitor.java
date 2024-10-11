package io.github.potjerodekool.nabu.compiler.frontend.parser;

import io.github.potjerodekool.nabu.NabuLexer;
import io.github.potjerodekool.nabu.NabuParser;
import io.github.potjerodekool.nabu.NabuParserBaseVisitor;
import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.backend.ir.statement.IExpressionStatement;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.CElement;
import io.github.potjerodekool.nabu.compiler.tree.element.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.element.CVariable;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.List;

public class NabuCompilerVisitor extends NabuParserBaseVisitor<Object> {

    private final FileObject fileObject;

    public NabuCompilerVisitor(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    @Override
    public Tree visitCompilationUnit(final NabuParser.CompilationUnitContext ctx) {
        var name = fileObject.getFileName();
        final var end = name.lastIndexOf(".");
        name = name.substring(0, end);

        final var startToken = ctx.getStart();

        final var cu = new CompilationUnit();
        cu.setFileObject(fileObject);
        final var clazz = new CClassDeclaration();
        clazz.setLineNumber(startToken.getLine());
        clazz.setColumnNumber(startToken.getCharPositionInLine());

        clazz.simpleName(name);

        if (ctx.packageDeclaration() != null) {
            final CPackageDeclaration packageDeclaration = (CPackageDeclaration)
                    ctx.packageDeclaration().accept(this);
            cu.add(packageDeclaration);
        }

        ctx.importDeclaration().stream()
                .map(importDeclaration -> ((ImportItem) importDeclaration.accept(this)))
                .forEach(cu::addImport);

        final var functions = ctx.function().stream()
                .map(function -> (CElement<?>) function.accept(this))
                .toList();

        functions.forEach(clazz::enclosedElement);

        cu.add(clazz);
        return cu;
    }

    @Override
    public Tree visitPackageDeclaration(final NabuParser.PackageDeclarationContext ctx) {
        final var packageName = (CIdent) ctx.packageName().accept(this);

        return new CPackageDeclaration(
                packageName.getName()
        );
    }

    @Override
    public Tree visitSingleTypeImportDeclaration(final NabuParser.SingleTypeImportDeclarationContext ctx) {
        final var typeName = (CIdent) ctx.typeName().accept(this);

        return new SingleImportItem(typeName.getName());
    }

    @Override
    public Tree visitTypeName(final NabuParser.TypeNameContext ctx) {
        final var ident = (Identifier) ctx.Identifier().accept(this);
        final var packageOrTypeName = (Identifier) accept(ctx.packageOrTypeName());

        if (packageOrTypeName == null) {
            return new CIdent(ident.getName());
        } else {
            return new CIdent(
                    packageOrTypeName.getName()
                    + "."
                    + ident.getName()
            );
        }
    }

    @Override
    public Tree visitPackageOrTypeName(final NabuParser.PackageOrTypeNameContext ctx) {
        final var ident = (Identifier) ctx.Identifier().accept(this);
        final var packageOrTypeName = (Identifier) accept(ctx.packageOrTypeName());

        if (packageOrTypeName == null) {
            return new CIdent(ident.getName());
        } else {
            return new CIdent(
                    packageOrTypeName.getName()
                            + "."
                            + ident.getName()
            );
        }
    }

    @Override
    public Tree visitFunction(final NabuParser.FunctionContext ctx) {
        final var function = new CFunction();
        final var functionName = ctx.name.getText();
        final var returnType = (CExpression) ctx.returnType.accept(this);
        function.returnType(returnType);

        final List<CVariable> params = (List<CVariable>) ctx.params.accept(this);
        function.simpleName(functionName);
        params.forEach(function::parameter);

        if (ctx.body != null) {
            final BlockStatement body = (BlockStatement) ctx.body.accept(this);
            function.body(body);
        }

        return function;
    }

    @Override
    public Tree visitFunctionParameter(final NabuParser.FunctionParameterContext ctx) {
        final var parameter = new CVariable();
        parameter.kind(CElement.Kind.PARAMETER);

        final var name = ctx.name.getText();
        final var type = (CExpression) ctx.type.accept(this);

        parameter.simpleName(name);
        parameter.type(type);

        return parameter;
    }

    @Override
    public Tree visitFunctionBody(final NabuParser.FunctionBodyContext ctx) {
        final var body = new BlockStatement();

        if (ctx.statements != null) {
            final var result = ctx.statements.accept(this);

            if (result instanceof Statement statement) {
                body.statement(statement);
            } else if (result instanceof List) {
                final var statements = (List<Statement>) result;
                statements.forEach(body::statement);
            }
        }

        return body;
    }


    @Override
    public Tree visitReturnStatement(final NabuParser.ReturnStatementContext ctx) {
        final var returnStatement = new ReturnStatement();
        final CExpression expression = (CExpression) ctx.exp.accept(this);
        returnStatement.expression(expression);
        return returnStatement;
    }

    @Override
    public Tree visitLambdaExpression(final NabuParser.LambdaExpressionContext ctx) {
        final var lambda = new CLambdaExpression();
        lambda.setLineNumber(ctx.getStart().getLine());
        lambda.setColumnNumber(ctx.getStart().getCharPositionInLine());

        final var name = ctx.name.getText();
        final var type = (CExpression) ctx.type.accept(this);

        final var variable = new CVariable();
        variable.kind(CElement.Kind.LOCAL_VARIABLE);

        variable.simpleName(name);
        variable.type(type);
        lambda.variable(variable);

        final var body = ctx.exp.accept(this);

        if (body instanceof CExpression expressionBody) {
            lambda.body(expressionBody);
        } else {
            final var statement = (Statement) body;
            lambda.body(statement);
        }

        return lambda;
    }

    @Override
    public Tree visitLambdaBody(final NabuParser.LambdaBodyContext ctx) {
        final var statements = ctx.statement().stream()
                .map(stm -> (Statement) stm.accept(this))
                .toList();

        final var body = new BlockStatement();
        statements.forEach(body::statement);

        if (ctx.expression() != null) {
            final var exp = (CExpression) ctx.accept(this);
            body.statement(exp);
        }

        final var bodyStatements = body.getStatements();

        if (!bodyStatements.isEmpty()) {
           final var lastStatement = bodyStatements.getLast();

           if (lastStatement instanceof StatementExpression statementExpression) {
               final var expression = statementExpression.getExpression();
               final var returnStatement = new ReturnStatement();
               returnStatement.expression(expression);
               bodyStatements.removeLast();
               bodyStatements.add(returnStatement);
           }
        }

        return body;
    }

    @Override
    public Tree visitEqualityExpression(final NabuParser.EqualityExpressionContext ctx) {
        final var right = (CExpression) ctx.relationalExpression().accept(this);

        if (ctx.left != null) {
            final CExpression left = (CExpression) ctx.left.accept(this);
            final var operatorText = ctx.oper.getText();
            return new BinaryExpression(
                    left,
                    Operator.fromText(operatorText),
                    right
            );
        } else {
            return right;
        }
    }

    @Override
    public Tree visitRelationalExpression(final NabuParser.RelationalExpressionContext ctx) {
        final var shiftExpression = (CExpression) accept(ctx.shiftExpression());
        final var relationalExpression = (CExpression) accept(ctx.relationalExpression());
        final var referenceType = accept(ctx.referenceType());
        final var operator = parseOperator(ctx.oper);

        if (referenceType != null) {
            throw new TodoException();
        } else if (relationalExpression != null) {
            return new BinaryExpression(
                    relationalExpression,
                    operator,
                    shiftExpression
            );
        } else {
            return shiftExpression;
        }
    }

    private Operator parseOperator(final Token token) {
        if (token == null) {
            return null;
        } else {
            final var text = token.getText();
            return Operator.fromText(text);
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
        return ctx.unaryExpressionNotPlusMinus().accept(this);
    }

    @Override
    public Object visitUnaryExpressionNotPlusMinus(final NabuParser.UnaryExpressionNotPlusMinusContext ctx) {
        if (ctx.postfixExpression() != null) {
            return ctx.postfixExpression().accept(this);
        } else {
            final var expression = (CExpression) ctx.unaryExpression().accept(this);
            return new UnaryExpression(
                    Operator.BANG,
                    expression
            );
        }
    }

    @Override
    public Object visitPrimary(final NabuParser.PrimaryContext ctx) {
        return ctx.primaryNoNewArray().accept(this);
    }

    @Override
    public Object visitPrimaryNoNewArray(final NabuParser.PrimaryNoNewArrayContext ctx) {
        return ctx.literal().accept(this);
    }

    @Override
    public Tree visitExpressionName(final NabuParser.ExpressionNameContext ctx) {
        final var identifier = (CExpression) ctx.identifier().accept(this);

        if (ctx.ambiguousName() != null) {
            final var ambiguousName = (CExpression) ctx.ambiguousName().accept(this);
            final var fieldAccess = new CFieldAccessExpression();
            fieldAccess.setTarget(ambiguousName);
            fieldAccess.setField(identifier);
            return fieldAccess;
        } else {
            return identifier;
        }
    }

    @Override
    public Tree visitAmbiguousName(final NabuParser.AmbiguousNameContext ctx) {
        final var identifier = ctx.identifier();
        final var identifierTree = (CExpression) identifier.accept(this);
        identifierTree.setLineNumber(identifier.getStart().getLine());
        identifierTree.setColumnNumber(identifier.getStart().getCharPositionInLine());

        if (ctx.ambiguousName() != null) {
            final var ambiguousName = (CExpression) ctx.ambiguousName().accept(this);
            final var fieldAccess = new CFieldAccessExpression();
            fieldAccess.setTarget(identifierTree);
            fieldAccess.setField(ambiguousName);
            return fieldAccess;
        } else {
            return identifierTree;
        }
    }

    @Override
    public Tree visitFieldAccessExpression(final NabuParser.FieldAccessExpressionContext ctx) {
        final var fieldAccess = new CFieldAccessExpression();
        fieldAccess.setTarget(identifier(ctx.target));
        fieldAccess.setField(identifier(ctx.field));

        return fieldAccess;
    }

    private CIdent identifier(final Token token) {
        final var identifier = new CIdent(token.getText());
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
            case NabuLexer.INT -> new CPrimitiveType(CPrimitiveType.Kind.INT);
            case NabuLexer.BYTE -> new CPrimitiveType(CPrimitiveType.Kind.BYTE);
            case NabuLexer.SHORT -> new CPrimitiveType(CPrimitiveType.Kind.SHORT);
            case NabuLexer.LONG -> new CPrimitiveType(CPrimitiveType.Kind.LONG);
            case NabuLexer.CHAR -> new CPrimitiveType(CPrimitiveType.Kind.CHAR);
            case NabuLexer.FLOAT -> new CPrimitiveType(CPrimitiveType.Kind.FLOAT);
            case NabuLexer.DOUBLE -> new CPrimitiveType(CPrimitiveType.Kind.DOUBLE);
            case NabuLexer.BOOLEAN -> new CPrimitiveType(CPrimitiveType.Kind.BOOLEAN);
            default -> throw new UnsupportedOperationException("" + type);
        };
    }

    @Override
    public Object visitUnannPrimitiveType(final NabuParser.UnannPrimitiveTypeContext ctx) {
        if (ctx.numericType() != null) {
            return ctx.numericType().accept(this);
        } else {
            return new CPrimitiveType(CPrimitiveType.Kind.BOOLEAN);
        }
    }

    @Override
    public Tree visitUnannClassOrInterfaceType(final NabuParser.UnannClassOrInterfaceTypeContext ctx) {
        final CTypeApply packageName = accept(ctx.packageName());
        final var type = (CExpression) ctx.typeIdentifier().accept(this);
        final var typeArguments = (List<CExpression>) accept(ctx.typeArguments());

        if (packageName == null) {
            if (typeArguments != null) {
                final var ident = (CIdent) type;

                final var typeApply = new CTypeApply(
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
            final var ident = (CIdent) type;

            if (typeArguments == null) {

                return new CIdent(
                        packageName.getName()
                                + "."
                                + ident.getName()
                );
            }



            return new CTypeApply(
                    new CIdent(
                            packageName.getName()
                                    + "."
                                    + ident.getName()
                    ),
                    typeArguments
            );
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
        final CIdent identifier = (CIdent) ctx.Identifier().accept(this);
        final CIdent packageName = accept(ctx.packageName());

        if (packageName == null) {
            return new CIdent(identifier.getName());
        } else {
            final var first = identifier.getName();
            final var second = packageName.getName();
            return new CIdent(first + "." + second);
        }
    }

    @Override
    public Tree visitTypeIdentifier(final NabuParser.TypeIdentifierContext ctx) {
        final var name = ctx.Identifier().getText();
        return new CIdent(name);
    }

    @Override
    public Object visitStatement(final NabuParser.StatementContext ctx) {
        if (ctx.returnStatement() != null) {
            return ctx.returnStatement().accept(this);
        } else if (ctx.localVariableDecleratorStatement() != null) {
            return ctx.localVariableDecleratorStatement().accept(this);
        } else {
            return ctx.expressionStatement().accept(this);
        }
    }

    @Override
    public Object visitLocalVariableDecleratorStatement(final NabuParser.LocalVariableDecleratorStatementContext ctx) {
        return ctx.localVariableDeclaration().accept(this);
    }

    @Override
    public Tree visitLocalVariableDeclaration(final NabuParser.LocalVariableDeclarationContext ctx) {
        final var type = (CExpression) ctx.localVariableType().accept(this);
        final var ident = (CIdent) ctx.variableDeclarator().accept(this);
        final var value = (Tree) ctx.variableInitializer().accept(this);

        final var statement = new CVariableDeclaratorStatement(
                type,
                ident,
                value
        );

        statement.setLineNumber(ctx.getStart().getLine());
        statement.setColumnNumber(ctx.getStart().getCharPositionInLine());
        return statement;
    }

    @Override
    public Object visitVariableInitializer(final NabuParser.VariableInitializerContext ctx) {
        final var asExpression = (AsExpression) accept(ctx.asExpression());
        final var expression = (CExpression) ctx.expression().accept(this);

        if (asExpression == null) {
            return expression;
        } else {
            asExpression.setExpression(expression);
            return asExpression;
        }
    }

    @Override
    public Object visitLocalVariableType(final NabuParser.LocalVariableTypeContext ctx) {
        if (ctx.unannType() != null) {
            return ctx.unannType().accept(this);
        } else {
            return new CVariableType();
        }
    }

    @Override
    public Tree visitLiteral(final NabuParser.LiteralContext ctx) {
        final LiteralExpression literalExpression;


        if (ctx.StringLiteral() != null) {
            var text = ctx.StringLiteral().getText();
            text = text.substring(1, text.length() - 1);
            literalExpression = new LiteralExpression(text);
            literalExpression.setLineNumber(ctx.StringLiteral().getSymbol().getLine());
            literalExpression.setColumnNumber(ctx.StringLiteral().getSymbol().getCharPositionInLine());
        } else if (ctx.BooleanLiteral() != null) {
            final var value = Boolean.valueOf(ctx.BooleanLiteral().getText());
            literalExpression = new LiteralExpression(value);
            literalExpression.setLineNumber(ctx.BooleanLiteral().getSymbol().getLine());
            literalExpression.setColumnNumber(ctx.BooleanLiteral().getSymbol().getCharPositionInLine());
        } else if (ctx.NullLiteral() != null) {
            literalExpression = new LiteralExpression(null);
            literalExpression.setLineNumber(ctx.NullLiteral().getSymbol().getLine());
            literalExpression.setColumnNumber(ctx.NullLiteral().getSymbol().getCharPositionInLine());
        } else {
            throw new TodoException();
        }
        return literalExpression;
    }


    private <T> T accept(final ParserRuleContext parserRuleContext) {
        if (parserRuleContext == null) {
            return null;
        } else {
            return (T) parserRuleContext.accept(this);
        }
    }

    @Override
    public Tree visitExpressionStatement(final NabuParser.ExpressionStatementContext ctx) {
        final var expression = (CExpression) ctx.expression().accept(this);
        return new StatementExpression(expression);
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
    public Object visitConditionalExpression(final NabuParser.ConditionalExpressionContext ctx) {
        return ctx.conditionalOrExpression().accept(this);
    }

    @Override
    public Object visitConditionalOrExpression(final NabuParser.ConditionalOrExpressionContext ctx) {
        final var andExpression = (CExpression) ctx.conditionalAndExpression().accept(this);

        if (ctx.conditionalOrExpression() == null) {
            return andExpression;
        } else {
            final var orExpression = (CExpression) ctx.conditionalOrExpression().accept(this);
            return new BinaryExpression(
                    orExpression,
                    Operator.OR,
                    andExpression
            );
        }
    }

    @Override
    public Tree visitConditionalAndExpression(final NabuParser.ConditionalAndExpressionContext ctx) {
        final var inclusiveOrExpression = (CExpression) ctx.inclusiveOrExpression().accept(this);

        if (ctx.conditionalAndExpression() == null) {
            return inclusiveOrExpression;
        }

        final var andExpression = (CExpression) ctx.conditionalAndExpression().accept(this);

        return new BinaryExpression(
                andExpression,
                Operator.AND,
                inclusiveOrExpression
        );
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
            return new CPrimitiveType(CPrimitiveType.Kind.BOOLEAN);
        }
    }

    @Override
    public Object visitFunctionParams(final NabuParser.FunctionParamsContext ctx) {
        return ctx.functionParameter().stream()
                .map(it -> it.accept(this))
                .toList();
    }

    @Override
    public Object visitAsExpression(final NabuParser.AsExpressionContext ctx) {
        final var type = (CExpression) ctx.unannClassOrInterfaceType().accept(this);
        final var asExpression = new AsExpression();
        asExpression.setTargetType(type);
        return asExpression;
    }
}
