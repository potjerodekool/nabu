package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java25Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java25Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java25ParserVisitor;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeMirror;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class JavaCompilerTest extends AbstractCompilerTest {

    public <T extends TypeMirror> T parseType(final String type) throws IOException {
        final var inputSteam = CharStreams.fromStream(new ByteArrayInputStream(type.getBytes()));
        final var lexer = new Java25Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java25Parser(tokens);
        final var referenceType = parser.referenceType();
        return (T) referenceType.accept(new TypeParser(getCompilerContext()));
    }
}

class TypeParser extends AbstractParseTreeVisitor<Object> implements Java25ParserVisitor<Object> {

    private final CompilerContext context;

    TypeParser(final CompilerContext context) {
        this.context = context;
    }

    @Override
    public Object visit(final ParseTree tree) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitTerminal(final TerminalNode node) {
        final var type = node.getSymbol().getType();

        return switch (type) {
            case Java25Parser.Identifier, Java25Parser.DOT -> node.getText();
            default -> null;
        };
    }

    @Override
    public Object visitErrorNode(final ErrorNode node) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitStart_(final Java25Parser.Start_Context ctx) {
        return null;
    }

    @Override
    public Object visitIdentifier(final Java25Parser.IdentifierContext ctx) {
        return ctx.getText();
    }

    @Override
    public Object visitTypeIdentifier(final Java25Parser.TypeIdentifierContext ctx) {
        if (ctx.Identifier() != null) {
            return ctx.Identifier().accept(this);
        } else {
            throw new UnsupportedOperationException("Non-identifier type not supported in test parser");
        }
    }

    @Override
    public Object visitUnqualifiedMethodIdentifier(final Java25Parser.UnqualifiedMethodIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitContextualKeyword(final Java25Parser.ContextualKeywordContext ctx) {
        return null;
    }

    @Override
    public Object visitContextualKeywordMinusForTypeIdentifier(final Java25Parser.ContextualKeywordMinusForTypeIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitContextualKeywordMinusForUnqualifiedMethodIdentifier(final Java25Parser.ContextualKeywordMinusForUnqualifiedMethodIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitLiteral(final Java25Parser.LiteralContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimitiveType(final Java25Parser.PrimitiveTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitNumericType(final Java25Parser.NumericTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitIntegralType(final Java25Parser.IntegralTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitFloatingPointType(final Java25Parser.FloatingPointTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitReferenceType(final Java25Parser.ReferenceTypeContext ctx) {
        return ctx.getChild(0).accept(this);
    }

    @Override
    public Object visitCoit(final Java25Parser.CoitContext ctx) {
        return null;
    }

    @Override
    public Object visitClassOrInterfaceType(final Java25Parser.ClassOrInterfaceTypeContext ctx) {
        final var qualified = new StringBuilder((String) ctx.typeIdentifier().accept(this));
        var typeArgumentsContext = ctx.typeArguments();

        for (var next = ctx.coit(); next != null; next = next.coit()) {
            qualified.append('.').append(next.typeIdentifier().accept(this));
            typeArgumentsContext = next.typeArguments();
        }

        final var typeArguments = (List<TypeMirror>) accept(typeArgumentsContext);

        final var className = qualified.toString();

        final var element = context.getClassElementLoader().loadClass(
                context.getModules().getJavaBase(),
                className
        );

        if (typeArguments == null) {
            return element.asType();
        }

        return context.getTypes()
                .getDeclaredType(
                        element,
                        typeArguments.toArray(TypeMirror[]::new)
                );
    }

    private String concatNames(final String first,
                               final String second) {
        if (first == null) {
            return second;
        } else if (second == null) {
            return first;
        } else {
            return first + "." + second;
        }
    }

    private <T> T accept(final ParserRuleContext ctx) {
        return ctx != null ? (T) ctx.accept(this) : null;
    }

    @Override
    public Object visitClassType(final Java25Parser.ClassTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceType(final Java25Parser.InterfaceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeVariable(final Java25Parser.TypeVariableContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayType(final Java25Parser.ArrayTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitDims(final Java25Parser.DimsContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameter(final Java25Parser.TypeParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameterModifier(final Java25Parser.TypeParameterModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeBound(final Java25Parser.TypeBoundContext ctx) {
        return null;
    }

    @Override
    public Object visitAdditionalBound(final Java25Parser.AdditionalBoundContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeArguments(final Java25Parser.TypeArgumentsContext ctx) {
        return ctx.typeArgumentList().accept(this);
    }

    @Override
    public Object visitTypeArgumentList(final Java25Parser.TypeArgumentListContext ctx) {
        return ctx.typeArgument().stream()
                .map(typeArg -> typeArg.accept(this))
                .peek(Objects::requireNonNull)
                .toList();
    }

    @Override
    public Object visitTypeArgument(final Java25Parser.TypeArgumentContext ctx) {
        return ctx.getChild(0).accept(this);
    }

    @Override
    public Object visitWildcard(final Java25Parser.WildcardContext ctx) {
        return null;
    }

    @Override
    public Object visitWildcardBounds(final Java25Parser.WildcardBoundsContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleName(final Java25Parser.ModuleNameContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageName(final Java25Parser.PackageNameContext ctx) {
        final var identifier = (String) ctx.identifier().accept(this);

        if (ctx.packageName() != null) {
            final var packageName = (String) ctx.packageName().accept(this);
            return identifier + "." + packageName;
        } else {
            return identifier;
        }
    }

    @Override
    public Object visitTypeName(final Java25Parser.TypeNameContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageOrTypeName(final Java25Parser.PackageOrTypeNameContext ctx) {
        return null;
    }

    @Override
    public Object visitExpressionName(final Java25Parser.ExpressionNameContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodName(final Java25Parser.MethodNameContext ctx) {
        return null;
    }

    @Override
    public Object visitAmbiguousName(final Java25Parser.AmbiguousNameContext ctx) {
        return null;
    }

    @Override
    public Object visitCompilationUnit(final Java25Parser.CompilationUnitContext ctx) {
        return null;
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final Java25Parser.OrdinaryCompilationUnitContext ctx) {
        return null;
    }

    @Override
    public Object visitModularCompilationUnit(final Java25Parser.ModularCompilationUnitContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageDeclaration(final Java25Parser.PackageDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageModifier(final Java25Parser.PackageModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitImportDeclaration(final Java25Parser.ImportDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitSingleTypeImportDeclaration(final Java25Parser.SingleTypeImportDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeImportOnDemandDeclaration(final Java25Parser.TypeImportOnDemandDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitImportStaticBody(final Java25Parser.ImportStaticBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitTopLevelClassOrInterfaceDeclaration(final Java25Parser.TopLevelClassOrInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleDeclaration(final Java25Parser.ModuleDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleDirective(final Java25Parser.ModuleDirectiveContext ctx) {
        return null;
    }

    @Override
    public Object visitRequiresModifier(final Java25Parser.RequiresModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitClassDeclaration(final Java25Parser.ClassDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitNormalClassDeclaration(final Java25Parser.NormalClassDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitClassModifier(final Java25Parser.ClassModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameters(final Java25Parser.TypeParametersContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameterList(final Java25Parser.TypeParameterListContext ctx) {
        return null;
    }

    @Override
    public Object visitClassExtends(final Java25Parser.ClassExtendsContext ctx) {
        return null;
    }

    @Override
    public Object visitClassImplements(final Java25Parser.ClassImplementsContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceTypeList(final Java25Parser.InterfaceTypeListContext ctx) {
        return null;
    }

    @Override
    public Object visitClassPermits(final Java25Parser.ClassPermitsContext ctx) {
        return null;
    }

    @Override
    public Object visitClassBody(final Java25Parser.ClassBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitClassBodyDeclaration(final Java25Parser.ClassBodyDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitClassMemberDeclaration(final Java25Parser.ClassMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitFieldDeclaration(final Java25Parser.FieldDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitFieldModifier(final Java25Parser.FieldModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableDeclaratorList(final Java25Parser.VariableDeclaratorListContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableDeclarator(final Java25Parser.VariableDeclaratorContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableDeclaratorId(final Java25Parser.VariableDeclaratorIdContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableInitializer(final Java25Parser.VariableInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannType(final Java25Parser.UnannTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannPrimitiveType(final Java25Parser.UnannPrimitiveTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannReferenceType(final Java25Parser.UnannReferenceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannClassOrInterfaceType(final Java25Parser.UnannClassOrInterfaceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUCOIT(final Java25Parser.UCOITContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannClassType(final Java25Parser.UnannClassTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannInterfaceType(final Java25Parser.UnannInterfaceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannTypeVariable(final Java25Parser.UnannTypeVariableContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannArrayType(final Java25Parser.UnannArrayTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodDeclaration(final Java25Parser.MethodDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodModifier(final Java25Parser.MethodModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodHeader(final Java25Parser.MethodHeaderContext ctx) {
        return null;
    }

    @Override
    public Object visitResult(final Java25Parser.ResultContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodDeclarator(final Java25Parser.MethodDeclaratorContext ctx) {
        return null;
    }

    @Override
    public Object visitReceiverParameter(final Java25Parser.ReceiverParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitFormalParameterList(final Java25Parser.FormalParameterListContext ctx) {
        return null;
    }

    @Override
    public Object visitFormalParameter(final Java25Parser.FormalParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableArityParameter(final Java25Parser.VariableArityParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableModifier(final Java25Parser.VariableModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitThrowsT(final Java25Parser.ThrowsTContext ctx) {
        return null;
    }

    @Override
    public Object visitExceptionTypeList(final Java25Parser.ExceptionTypeListContext ctx) {
        return null;
    }

    @Override
    public Object visitExceptionType(final Java25Parser.ExceptionTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodBody(final Java25Parser.MethodBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitInstanceInitializer(final Java25Parser.InstanceInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitStaticInitializer(final Java25Parser.StaticInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorDeclaration(final Java25Parser.ConstructorDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorModifier(final Java25Parser.ConstructorModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorDeclarator(final Java25Parser.ConstructorDeclaratorContext ctx) {
        return null;
    }

    @Override
    public Object visitSimpleTypeName(final Java25Parser.SimpleTypeNameContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorBody(final Java25Parser.ConstructorBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitExplicitConstructorInvocation(final Java25Parser.ExplicitConstructorInvocationContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumDeclaration(final Java25Parser.EnumDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumBody(final Java25Parser.EnumBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumConstantList(final Java25Parser.EnumConstantListContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumConstant(final Java25Parser.EnumConstantContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumConstantModifier(final Java25Parser.EnumConstantModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumBodyDeclarations(final Java25Parser.EnumBodyDeclarationsContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordDeclaration(final Java25Parser.RecordDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordHeader(final Java25Parser.RecordHeaderContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordComponentList(final Java25Parser.RecordComponentListContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordComponent(final Java25Parser.RecordComponentContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableArityRecordComponent(final Java25Parser.VariableArityRecordComponentContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordComponentModifier(final Java25Parser.RecordComponentModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordBody(final Java25Parser.RecordBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordBodyDeclaration(final Java25Parser.RecordBodyDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitCompactConstructorDeclaration(final Java25Parser.CompactConstructorDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceDeclaration(final Java25Parser.InterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final Java25Parser.NormalInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceModifier(final Java25Parser.InterfaceModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceExtends(final Java25Parser.InterfaceExtendsContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfacePermits(final Java25Parser.InterfacePermitsContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceBody(final Java25Parser.InterfaceBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceMemberDeclaration(final Java25Parser.InterfaceMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitConstantDeclaration(final Java25Parser.ConstantDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitConstantModifier(final Java25Parser.ConstantModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceMethodDeclaration(final Java25Parser.InterfaceMethodDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceMethodModifier(final Java25Parser.InterfaceMethodModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceDeclaration(final Java25Parser.AnnotationInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceBody(final Java25Parser.AnnotationInterfaceBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceMemberDeclaration(final Java25Parser.AnnotationInterfaceMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceElementDeclaration(final Java25Parser.AnnotationInterfaceElementDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceElementModifier(final Java25Parser.AnnotationInterfaceElementModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitDefaultValue(final Java25Parser.DefaultValueContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotation(final Java25Parser.AnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitNormalAnnotation(final Java25Parser.NormalAnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValuePairList(final Java25Parser.ElementValuePairListContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValuePair(final Java25Parser.ElementValuePairContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValue(final Java25Parser.ElementValueContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValueArrayInitializer(final Java25Parser.ElementValueArrayInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValueList(final Java25Parser.ElementValueListContext ctx) {
        return null;
    }

    @Override
    public Object visitMarkerAnnotation(final Java25Parser.MarkerAnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitSingleElementAnnotation(final Java25Parser.SingleElementAnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayInitializer(final Java25Parser.ArrayInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableInitializerList(final Java25Parser.VariableInitializerListContext ctx) {
        return null;
    }

    @Override
    public Object visitBlock(final Java25Parser.BlockContext ctx) {
        return null;
    }

    @Override
    public Object visitBlockStatements(final Java25Parser.BlockStatementsContext ctx) {
        return null;
    }

    @Override
    public Object visitBlockStatement(final Java25Parser.BlockStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalClassOrInterfaceDeclaration(final Java25Parser.LocalClassOrInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalVariableDeclaration(final Java25Parser.LocalVariableDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalVariableType(final Java25Parser.LocalVariableTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalVariableDeclarationStatement(final Java25Parser.LocalVariableDeclarationStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitStatement(final Java25Parser.StatementContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementNoShortIf(final Java25Parser.StatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementWithoutTrailingSubstatement(final Java25Parser.StatementWithoutTrailingSubstatementContext ctx) {
        return null;
    }

    @Override
    public Object visitEmptyStatement_(final Java25Parser.EmptyStatement_Context ctx) {
        return null;
    }

    @Override
    public Object visitLabeledStatement(final Java25Parser.LabeledStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitLabeledStatementNoShortIf(final Java25Parser.LabeledStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitExpressionStatement(final Java25Parser.ExpressionStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementExpression(final Java25Parser.StatementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitIfThenStatement(final Java25Parser.IfThenStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitIfThenElseStatement(final Java25Parser.IfThenElseStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitIfThenElseStatementNoShortIf(final Java25Parser.IfThenElseStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitAssertStatement(final Java25Parser.AssertStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchStatement(final Java25Parser.SwitchStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchBlock(final Java25Parser.SwitchBlockContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchRule(final Java25Parser.SwitchRuleContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchBlockStatementGroup(final Java25Parser.SwitchBlockStatementGroupContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchLabel(final Java25Parser.SwitchLabelContext ctx) {
        return null;
    }

    @Override
    public Object visitCaseConstant(final Java25Parser.CaseConstantContext ctx) {
        return null;
    }

    @Override
    public Object visitWhileStatement(final Java25Parser.WhileStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitWhileStatementNoShortIf(final Java25Parser.WhileStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitDoStatement(final Java25Parser.DoStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitForStatement(final Java25Parser.ForStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitForStatementNoShortIf(final Java25Parser.ForStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitBasicForStatement(final Java25Parser.BasicForStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitBasicForStatementNoShortIf(final Java25Parser.BasicForStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitForInit(final Java25Parser.ForInitContext ctx) {
        return null;
    }

    @Override
    public Object visitForUpdate(final Java25Parser.ForUpdateContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementExpressionList(final Java25Parser.StatementExpressionListContext ctx) {
        return null;
    }

    @Override
    public Object visitEnhancedForStatement(final Java25Parser.EnhancedForStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitEnhancedForStatementNoShortIf(final Java25Parser.EnhancedForStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitBreakStatement(final Java25Parser.BreakStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitContinueStatement(final Java25Parser.ContinueStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitReturnStatement(final Java25Parser.ReturnStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitThrowStatement(final Java25Parser.ThrowStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitSynchronizedStatement(final Java25Parser.SynchronizedStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitTryStatement(final Java25Parser.TryStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitCatches(final Java25Parser.CatchesContext ctx) {
        return null;
    }

    @Override
    public Object visitCatchClause(final Java25Parser.CatchClauseContext ctx) {
        return null;
    }

    @Override
    public Object visitCatchFormalParameter(final Java25Parser.CatchFormalParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitCatchType(final Java25Parser.CatchTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitFinallyBlock(final Java25Parser.FinallyBlockContext ctx) {
        return null;
    }

    @Override
    public Object visitTryWithResourcesStatement(final Java25Parser.TryWithResourcesStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitResourceSpecification(final Java25Parser.ResourceSpecificationContext ctx) {
        return null;
    }

    @Override
    public Object visitResourceList(final Java25Parser.ResourceListContext ctx) {
        return null;
    }

    @Override
    public Object visitResource(final Java25Parser.ResourceContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableAccess(final Java25Parser.VariableAccessContext ctx) {
        return null;
    }

    @Override
    public Object visitYieldStatement(final Java25Parser.YieldStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitPattern(final Java25Parser.PatternContext ctx) {
        return null;
    }

    @Override
    public Object visitGuardedPattern(final Java25Parser.GuardedPatternContext ctx) {
        return null;
    }

    @Override
    public Object visitGuard(final Java25Parser.GuardContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimaryPattern(final Java25Parser.PrimaryPatternContext ctx) {
        return null;
    }

    @Override
    public Object visitTypePattern(final Java25Parser.TypePatternContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordPattern(final Java25Parser.RecordPatternContext ctx) {
        return null;
    }

    @Override
    public Object visitPatternList(final Java25Parser.PatternListContext ctx) {
        return null;
    }

    @Override
    public Object visitCaseLabelElement(final Java25Parser.CaseLabelElementContext ctx) {
        return null;
    }

    @Override
    public Object visitExpression(final Java25Parser.ExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimary(final Java25Parser.PrimaryContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimaryNoNewArray(final Java25Parser.PrimaryNoNewArrayContext ctx) {
        return null;
    }

    @Override
    public Object visitPNNA(final Java25Parser.PNNAContext ctx) {
        return null;
    }

    @Override
    public Object visitClassLiteral(final Java25Parser.ClassLiteralContext ctx) {
        return null;
    }

    @Override
    public Object visitClassInstanceCreationExpression(final Java25Parser.ClassInstanceCreationExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitUnqualifiedClassInstanceCreationExpression(final Java25Parser.UnqualifiedClassInstanceCreationExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitClassOrInterfaceTypeToInstantiate(final Java25Parser.ClassOrInterfaceTypeToInstantiateContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeArgumentsOrDiamond(final Java25Parser.TypeArgumentsOrDiamondContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayCreationExpression(final Java25Parser.ArrayCreationExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayCreationExpressionWithoutInitializer(final Java25Parser.ArrayCreationExpressionWithoutInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayCreationExpressionWithInitializer(final Java25Parser.ArrayCreationExpressionWithInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitDimExprs(final Java25Parser.DimExprsContext ctx) {
        return null;
    }

    @Override
    public Object visitDimExpr(final Java25Parser.DimExprContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayAccess(final Java25Parser.ArrayAccessContext ctx) {
        return null;
    }

    @Override
    public Object visitFieldAccess(final Java25Parser.FieldAccessContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodInvocation(final Java25Parser.MethodInvocationContext ctx) {
        return null;
    }

    @Override
    public Object visitArgumentList(final Java25Parser.ArgumentListContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodReference(final Java25Parser.MethodReferenceContext ctx) {
        return null;
    }

    @Override
    public Object visitPostfixExpression(final Java25Parser.PostfixExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPfE(final Java25Parser.PfEContext ctx) {
        return null;
    }

    @Override
    public Object visitPostIncrementExpression(final Java25Parser.PostIncrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPostDecrementExpression(final Java25Parser.PostDecrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitUnaryExpression(final Java25Parser.UnaryExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPreIncrementExpression(final Java25Parser.PreIncrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPreDecrementExpression(final Java25Parser.PreDecrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitUnaryExpressionNotPlusMinus(final Java25Parser.UnaryExpressionNotPlusMinusContext ctx) {
        return null;
    }

    @Override
    public Object visitCastExpression(final Java25Parser.CastExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitMultiplicativeExpression(final Java25Parser.MultiplicativeExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitAdditiveExpression(final Java25Parser.AdditiveExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitShiftExpression(final Java25Parser.ShiftExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitRelationalExpression(final Java25Parser.RelationalExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitEqualityExpression(final Java25Parser.EqualityExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitAndExpression(final Java25Parser.AndExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitExclusiveOrExpression(final Java25Parser.ExclusiveOrExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitInclusiveOrExpression(final Java25Parser.InclusiveOrExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConditionalAndExpression(final Java25Parser.ConditionalAndExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConditionalOrExpression(final Java25Parser.ConditionalOrExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConditionalExpression(final Java25Parser.ConditionalExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitAssignmentExpressionTree(final Java25Parser.AssignmentExpressionTreeContext ctx) {
        return null;
    }

    @Override
    public Object visitAssignment(final Java25Parser.AssignmentContext ctx) {
        return null;
    }

    @Override
    public Object visitLeftHandSide(final Java25Parser.LeftHandSideContext ctx) {
        return null;
    }

    @Override
    public Object visitAssignmentOperator(final Java25Parser.AssignmentOperatorContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaExpression(final Java25Parser.LambdaExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameters(final Java25Parser.LambdaParametersContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaIdentifier(final Java25Parser.LambdaIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleImportName(final Java25Parser.ModuleImportNameContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorBodyStatement(final Java25Parser.ConstructorBodyStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitUnnamedPattern(final Java25Parser.UnnamedPatternContext ctx) {
        return null;
    }

    @Override
    public Object visitCompactMemberDeclaration(final Java25Parser.CompactMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameterList(final Java25Parser.LambdaParameterListContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameter(final Java25Parser.LambdaParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameterType(final Java25Parser.LambdaParameterTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaBody(final Java25Parser.LambdaBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchExpression(final Java25Parser.SwitchExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConstantExpression(final Java25Parser.ConstantExpressionContext ctx) {
        return null;
    }
}