package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Lexer;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20Parser;
import io.github.potjerodekool.nabu.compiler.lang.support.java.Java20ParserVisitor;
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
        final var lexer = new Java20Lexer(inputSteam);
        final var tokens = new CommonTokenStream(lexer);
        final var parser = new Java20Parser(tokens);
        final var referenceType = parser.referenceType();
        return (T) referenceType.accept(new TypeParser(getCompilerContext()));
    }
}

class TypeParser extends AbstractParseTreeVisitor<Object> implements Java20ParserVisitor<Object> {

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
            case Java20Parser.Identifier, Java20Parser.DOT -> node.getText();
            default -> null;
        };
    }

    @Override
    public Object visitErrorNode(final ErrorNode node) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object visitStart_(final Java20Parser.Start_Context ctx) {
        return null;
    }

    @Override
    public Object visitIdentifier(final Java20Parser.IdentifierContext ctx) {
        return ctx.getText();
    }

    @Override
    public Object visitTypeIdentifier(final Java20Parser.TypeIdentifierContext ctx) {
        if (ctx.Identifier() != null) {
            return ctx.Identifier().accept(this);
        } else {
            throw new UnsupportedOperationException("Non-identifier type not supported in test parser");
        }
    }

    @Override
    public Object visitUnqualifiedMethodIdentifier(final Java20Parser.UnqualifiedMethodIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitContextualKeyword(final Java20Parser.ContextualKeywordContext ctx) {
        return null;
    }

    @Override
    public Object visitContextualKeywordMinusForTypeIdentifier(final Java20Parser.ContextualKeywordMinusForTypeIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitContextualKeywordMinusForUnqualifiedMethodIdentifier(final Java20Parser.ContextualKeywordMinusForUnqualifiedMethodIdentifierContext ctx) {
        return null;
    }

    @Override
    public Object visitLiteral(final Java20Parser.LiteralContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimitiveType(final Java20Parser.PrimitiveTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitNumericType(final Java20Parser.NumericTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitIntegralType(final Java20Parser.IntegralTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitFloatingPointType(final Java20Parser.FloatingPointTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitReferenceType(final Java20Parser.ReferenceTypeContext ctx) {
        return ctx.getChild(0).accept(this);
    }

    @Override
    public Object visitCoit(final Java20Parser.CoitContext ctx) {
        return null;
    }

    @Override
    public Object visitClassOrInterfaceType(final Java20Parser.ClassOrInterfaceTypeContext ctx) {
        final var packageName = (String) accept(ctx.packageName());
        final var typeIdentifier = (String) ctx.typeIdentifier().accept(this);
        final var typeArguments = (List<TypeMirror>) accept(ctx.typeArguments());

        final var className = concatNames(packageName, typeIdentifier);

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
    public Object visitClassType(final Java20Parser.ClassTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceType(final Java20Parser.InterfaceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeVariable(final Java20Parser.TypeVariableContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayType(final Java20Parser.ArrayTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitDims(final Java20Parser.DimsContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameter(final Java20Parser.TypeParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameterModifier(final Java20Parser.TypeParameterModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeBound(final Java20Parser.TypeBoundContext ctx) {
        return null;
    }

    @Override
    public Object visitAdditionalBound(final Java20Parser.AdditionalBoundContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeArguments(final Java20Parser.TypeArgumentsContext ctx) {
        return ctx.typeArgumentList().accept(this);
    }

    @Override
    public Object visitTypeArgumentList(final Java20Parser.TypeArgumentListContext ctx) {
        return ctx.typeArgument().stream()
                .map(typeArg -> typeArg.accept(this))
                .peek(Objects::requireNonNull)
                .toList();
    }

    @Override
    public Object visitTypeArgument(final Java20Parser.TypeArgumentContext ctx) {
        return ctx.getChild(0).accept(this);
    }

    @Override
    public Object visitWildcard(final Java20Parser.WildcardContext ctx) {
        return null;
    }

    @Override
    public Object visitWildcardBounds(final Java20Parser.WildcardBoundsContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleName(final Java20Parser.ModuleNameContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageName(final Java20Parser.PackageNameContext ctx) {
        final var identifier = (String) ctx.identifier().accept(this);

        if (ctx.packageName() != null) {
            final var packageName = (String) ctx.packageName().accept(this);
            return identifier + "." + packageName;
        } else {
            return identifier;
        }
    }

    @Override
    public Object visitTypeName(final Java20Parser.TypeNameContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageOrTypeName(final Java20Parser.PackageOrTypeNameContext ctx) {
        return null;
    }

    @Override
    public Object visitExpressionName(final Java20Parser.ExpressionNameContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodName(final Java20Parser.MethodNameContext ctx) {
        return null;
    }

    @Override
    public Object visitAmbiguousName(final Java20Parser.AmbiguousNameContext ctx) {
        return null;
    }

    @Override
    public Object visitCompilationUnit(final Java20Parser.CompilationUnitContext ctx) {
        return null;
    }

    @Override
    public Object visitOrdinaryCompilationUnit(final Java20Parser.OrdinaryCompilationUnitContext ctx) {
        return null;
    }

    @Override
    public Object visitModularCompilationUnit(final Java20Parser.ModularCompilationUnitContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageDeclaration(final Java20Parser.PackageDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitPackageModifier(final Java20Parser.PackageModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitImportDeclaration(final Java20Parser.ImportDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitSingleTypeImportDeclaration(final Java20Parser.SingleTypeImportDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeImportOnDemandDeclaration(final Java20Parser.TypeImportOnDemandDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitImportStaticBody(final Java20Parser.ImportStaticBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitTopLevelClassOrInterfaceDeclaration(final Java20Parser.TopLevelClassOrInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleDeclaration(final Java20Parser.ModuleDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitModuleDirective(final Java20Parser.ModuleDirectiveContext ctx) {
        return null;
    }

    @Override
    public Object visitRequiresModifier(final Java20Parser.RequiresModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitClassDeclaration(final Java20Parser.ClassDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitNormalClassDeclaration(final Java20Parser.NormalClassDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitClassModifier(final Java20Parser.ClassModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameters(final Java20Parser.TypeParametersContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeParameterList(final Java20Parser.TypeParameterListContext ctx) {
        return null;
    }

    @Override
    public Object visitClassExtends(final Java20Parser.ClassExtendsContext ctx) {
        return null;
    }

    @Override
    public Object visitClassImplements(final Java20Parser.ClassImplementsContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceTypeList(final Java20Parser.InterfaceTypeListContext ctx) {
        return null;
    }

    @Override
    public Object visitClassPermits(final Java20Parser.ClassPermitsContext ctx) {
        return null;
    }

    @Override
    public Object visitClassBody(final Java20Parser.ClassBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitClassBodyDeclaration(final Java20Parser.ClassBodyDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitClassMemberDeclaration(final Java20Parser.ClassMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitFieldDeclaration(final Java20Parser.FieldDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitFieldModifier(final Java20Parser.FieldModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableDeclaratorList(final Java20Parser.VariableDeclaratorListContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableDeclarator(final Java20Parser.VariableDeclaratorContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableDeclaratorId(final Java20Parser.VariableDeclaratorIdContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableInitializer(final Java20Parser.VariableInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannType(final Java20Parser.UnannTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannPrimitiveType(final Java20Parser.UnannPrimitiveTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannReferenceType(final Java20Parser.UnannReferenceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannClassOrInterfaceType(final Java20Parser.UnannClassOrInterfaceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUCOIT(final Java20Parser.UCOITContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannClassType(final Java20Parser.UnannClassTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannInterfaceType(final Java20Parser.UnannInterfaceTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannTypeVariable(final Java20Parser.UnannTypeVariableContext ctx) {
        return null;
    }

    @Override
    public Object visitUnannArrayType(final Java20Parser.UnannArrayTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodDeclaration(final Java20Parser.MethodDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodModifier(final Java20Parser.MethodModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodHeader(final Java20Parser.MethodHeaderContext ctx) {
        return null;
    }

    @Override
    public Object visitResult(final Java20Parser.ResultContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodDeclarator(final Java20Parser.MethodDeclaratorContext ctx) {
        return null;
    }

    @Override
    public Object visitReceiverParameter(final Java20Parser.ReceiverParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitFormalParameterList(final Java20Parser.FormalParameterListContext ctx) {
        return null;
    }

    @Override
    public Object visitFormalParameter(final Java20Parser.FormalParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableArityParameter(final Java20Parser.VariableArityParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableModifier(final Java20Parser.VariableModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitThrowsT(final Java20Parser.ThrowsTContext ctx) {
        return null;
    }

    @Override
    public Object visitExceptionTypeList(final Java20Parser.ExceptionTypeListContext ctx) {
        return null;
    }

    @Override
    public Object visitExceptionType(final Java20Parser.ExceptionTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodBody(final Java20Parser.MethodBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitInstanceInitializer(final Java20Parser.InstanceInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitStaticInitializer(final Java20Parser.StaticInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorDeclaration(final Java20Parser.ConstructorDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorModifier(final Java20Parser.ConstructorModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorDeclarator(final Java20Parser.ConstructorDeclaratorContext ctx) {
        return null;
    }

    @Override
    public Object visitSimpleTypeName(final Java20Parser.SimpleTypeNameContext ctx) {
        return null;
    }

    @Override
    public Object visitConstructorBody(final Java20Parser.ConstructorBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitExplicitConstructorInvocation(final Java20Parser.ExplicitConstructorInvocationContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumDeclaration(final Java20Parser.EnumDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumBody(final Java20Parser.EnumBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumConstantList(final Java20Parser.EnumConstantListContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumConstant(final Java20Parser.EnumConstantContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumConstantModifier(final Java20Parser.EnumConstantModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitEnumBodyDeclarations(final Java20Parser.EnumBodyDeclarationsContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordDeclaration(final Java20Parser.RecordDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordHeader(final Java20Parser.RecordHeaderContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordComponentList(final Java20Parser.RecordComponentListContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordComponent(final Java20Parser.RecordComponentContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableArityRecordComponent(final Java20Parser.VariableArityRecordComponentContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordComponentModifier(final Java20Parser.RecordComponentModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordBody(final Java20Parser.RecordBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitRecordBodyDeclaration(final Java20Parser.RecordBodyDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitCompactConstructorDeclaration(final Java20Parser.CompactConstructorDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceDeclaration(final Java20Parser.InterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitNormalInterfaceDeclaration(final Java20Parser.NormalInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceModifier(final Java20Parser.InterfaceModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceExtends(final Java20Parser.InterfaceExtendsContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfacePermits(final Java20Parser.InterfacePermitsContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceBody(final Java20Parser.InterfaceBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceMemberDeclaration(final Java20Parser.InterfaceMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitConstantDeclaration(final Java20Parser.ConstantDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitConstantModifier(final Java20Parser.ConstantModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceMethodDeclaration(final Java20Parser.InterfaceMethodDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitInterfaceMethodModifier(final Java20Parser.InterfaceMethodModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceDeclaration(final Java20Parser.AnnotationInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceBody(final Java20Parser.AnnotationInterfaceBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceMemberDeclaration(final Java20Parser.AnnotationInterfaceMemberDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceElementDeclaration(final Java20Parser.AnnotationInterfaceElementDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotationInterfaceElementModifier(final Java20Parser.AnnotationInterfaceElementModifierContext ctx) {
        return null;
    }

    @Override
    public Object visitDefaultValue(final Java20Parser.DefaultValueContext ctx) {
        return null;
    }

    @Override
    public Object visitAnnotation(final Java20Parser.AnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitNormalAnnotation(final Java20Parser.NormalAnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValuePairList(final Java20Parser.ElementValuePairListContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValuePair(final Java20Parser.ElementValuePairContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValue(final Java20Parser.ElementValueContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValueArrayInitializer(final Java20Parser.ElementValueArrayInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitElementValueList(final Java20Parser.ElementValueListContext ctx) {
        return null;
    }

    @Override
    public Object visitMarkerAnnotation(final Java20Parser.MarkerAnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitSingleElementAnnotation(final Java20Parser.SingleElementAnnotationContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayInitializer(final Java20Parser.ArrayInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableInitializerList(final Java20Parser.VariableInitializerListContext ctx) {
        return null;
    }

    @Override
    public Object visitBlock(final Java20Parser.BlockContext ctx) {
        return null;
    }

    @Override
    public Object visitBlockStatements(final Java20Parser.BlockStatementsContext ctx) {
        return null;
    }

    @Override
    public Object visitBlockStatement(final Java20Parser.BlockStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalClassOrInterfaceDeclaration(final Java20Parser.LocalClassOrInterfaceDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalVariableDeclaration(final Java20Parser.LocalVariableDeclarationContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalVariableType(final Java20Parser.LocalVariableTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitLocalVariableDeclarationStatement(final Java20Parser.LocalVariableDeclarationStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitStatement(final Java20Parser.StatementContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementNoShortIf(final Java20Parser.StatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementWithoutTrailingSubstatement(final Java20Parser.StatementWithoutTrailingSubstatementContext ctx) {
        return null;
    }

    @Override
    public Object visitEmptyStatement_(final Java20Parser.EmptyStatement_Context ctx) {
        return null;
    }

    @Override
    public Object visitLabeledStatement(final Java20Parser.LabeledStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitLabeledStatementNoShortIf(final Java20Parser.LabeledStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitExpressionStatement(final Java20Parser.ExpressionStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementExpression(final Java20Parser.StatementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitIfThenStatement(final Java20Parser.IfThenStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitIfThenElseStatement(final Java20Parser.IfThenElseStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitIfThenElseStatementNoShortIf(final Java20Parser.IfThenElseStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitAssertStatement(final Java20Parser.AssertStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchStatement(final Java20Parser.SwitchStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchBlock(final Java20Parser.SwitchBlockContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchRule(final Java20Parser.SwitchRuleContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchBlockStatementGroup(final Java20Parser.SwitchBlockStatementGroupContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchLabel(final Java20Parser.SwitchLabelContext ctx) {
        return null;
    }

    @Override
    public Object visitCaseConstant(final Java20Parser.CaseConstantContext ctx) {
        return null;
    }

    @Override
    public Object visitWhileStatement(final Java20Parser.WhileStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitWhileStatementNoShortIf(final Java20Parser.WhileStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitDoStatement(final Java20Parser.DoStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitForStatement(final Java20Parser.ForStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitForStatementNoShortIf(final Java20Parser.ForStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitBasicForStatement(final Java20Parser.BasicForStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitBasicForStatementNoShortIf(final Java20Parser.BasicForStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitForInit(final Java20Parser.ForInitContext ctx) {
        return null;
    }

    @Override
    public Object visitForUpdate(final Java20Parser.ForUpdateContext ctx) {
        return null;
    }

    @Override
    public Object visitStatementExpressionList(final Java20Parser.StatementExpressionListContext ctx) {
        return null;
    }

    @Override
    public Object visitEnhancedForStatement(final Java20Parser.EnhancedForStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitEnhancedForStatementNoShortIf(final Java20Parser.EnhancedForStatementNoShortIfContext ctx) {
        return null;
    }

    @Override
    public Object visitBreakStatement(final Java20Parser.BreakStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitContinueStatement(final Java20Parser.ContinueStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitReturnStatement(final Java20Parser.ReturnStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitThrowStatement(final Java20Parser.ThrowStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitSynchronizedStatement(final Java20Parser.SynchronizedStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitTryStatement(final Java20Parser.TryStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitCatches(final Java20Parser.CatchesContext ctx) {
        return null;
    }

    @Override
    public Object visitCatchClause(final Java20Parser.CatchClauseContext ctx) {
        return null;
    }

    @Override
    public Object visitCatchFormalParameter(final Java20Parser.CatchFormalParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitCatchType(final Java20Parser.CatchTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitFinallyBlock(final Java20Parser.FinallyBlockContext ctx) {
        return null;
    }

    @Override
    public Object visitTryWithResourcesStatement(final Java20Parser.TryWithResourcesStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitResourceSpecification(final Java20Parser.ResourceSpecificationContext ctx) {
        return null;
    }

    @Override
    public Object visitResourceList(final Java20Parser.ResourceListContext ctx) {
        return null;
    }

    @Override
    public Object visitResource(final Java20Parser.ResourceContext ctx) {
        return null;
    }

    @Override
    public Object visitVariableAccess(final Java20Parser.VariableAccessContext ctx) {
        return null;
    }

    @Override
    public Object visitYieldStatement(final Java20Parser.YieldStatementContext ctx) {
        return null;
    }

    @Override
    public Object visitPattern(final Java20Parser.PatternContext ctx) {
        return null;
    }

    @Override
    public Object visitTypePattern(final Java20Parser.TypePatternContext ctx) {
        return null;
    }

    @Override
    public Object visitExpression(final Java20Parser.ExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimary(final Java20Parser.PrimaryContext ctx) {
        return null;
    }

    @Override
    public Object visitPrimaryNoNewArray(final Java20Parser.PrimaryNoNewArrayContext ctx) {
        return null;
    }

    @Override
    public Object visitPNNA(final Java20Parser.PNNAContext ctx) {
        return null;
    }

    @Override
    public Object visitClassLiteral(final Java20Parser.ClassLiteralContext ctx) {
        return null;
    }

    @Override
    public Object visitClassInstanceCreationExpression(final Java20Parser.ClassInstanceCreationExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitUnqualifiedClassInstanceCreationExpression(final Java20Parser.UnqualifiedClassInstanceCreationExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitClassOrInterfaceTypeToInstantiate(final Java20Parser.ClassOrInterfaceTypeToInstantiateContext ctx) {
        return null;
    }

    @Override
    public Object visitTypeArgumentsOrDiamond(final Java20Parser.TypeArgumentsOrDiamondContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayCreationExpression(final Java20Parser.ArrayCreationExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayCreationExpressionWithoutInitializer(final Java20Parser.ArrayCreationExpressionWithoutInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayCreationExpressionWithInitializer(final Java20Parser.ArrayCreationExpressionWithInitializerContext ctx) {
        return null;
    }

    @Override
    public Object visitDimExprs(final Java20Parser.DimExprsContext ctx) {
        return null;
    }

    @Override
    public Object visitDimExpr(final Java20Parser.DimExprContext ctx) {
        return null;
    }

    @Override
    public Object visitArrayAccess(final Java20Parser.ArrayAccessContext ctx) {
        return null;
    }

    @Override
    public Object visitFieldAccess(final Java20Parser.FieldAccessContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodInvocation(final Java20Parser.MethodInvocationContext ctx) {
        return null;
    }

    @Override
    public Object visitArgumentList(final Java20Parser.ArgumentListContext ctx) {
        return null;
    }

    @Override
    public Object visitMethodReference(final Java20Parser.MethodReferenceContext ctx) {
        return null;
    }

    @Override
    public Object visitPostfixExpression(final Java20Parser.PostfixExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPfE(final Java20Parser.PfEContext ctx) {
        return null;
    }

    @Override
    public Object visitPostIncrementExpression(final Java20Parser.PostIncrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPostDecrementExpression(final Java20Parser.PostDecrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitUnaryExpression(final Java20Parser.UnaryExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPreIncrementExpression(final Java20Parser.PreIncrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitPreDecrementExpression(final Java20Parser.PreDecrementExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitUnaryExpressionNotPlusMinus(final Java20Parser.UnaryExpressionNotPlusMinusContext ctx) {
        return null;
    }

    @Override
    public Object visitCastExpression(final Java20Parser.CastExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitMultiplicativeExpression(final Java20Parser.MultiplicativeExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitAdditiveExpression(final Java20Parser.AdditiveExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitShiftExpression(final Java20Parser.ShiftExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitRelationalExpression(final Java20Parser.RelationalExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitEqualityExpression(final Java20Parser.EqualityExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitAndExpression(final Java20Parser.AndExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitExclusiveOrExpression(final Java20Parser.ExclusiveOrExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitInclusiveOrExpression(final Java20Parser.InclusiveOrExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConditionalAndExpression(final Java20Parser.ConditionalAndExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConditionalOrExpression(final Java20Parser.ConditionalOrExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConditionalExpression(final Java20Parser.ConditionalExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitAssignmentExpressionTree(final Java20Parser.AssignmentExpressionTreeContext ctx) {
        return null;
    }

    @Override
    public Object visitAssignment(final Java20Parser.AssignmentContext ctx) {
        return null;
    }

    @Override
    public Object visitLeftHandSide(final Java20Parser.LeftHandSideContext ctx) {
        return null;
    }

    @Override
    public Object visitAssignmentOperator(final Java20Parser.AssignmentOperatorContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaExpression(final Java20Parser.LambdaExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameters(final Java20Parser.LambdaParametersContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameterList(final Java20Parser.LambdaParameterListContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameter(final Java20Parser.LambdaParameterContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaParameterType(final Java20Parser.LambdaParameterTypeContext ctx) {
        return null;
    }

    @Override
    public Object visitLambdaBody(final Java20Parser.LambdaBodyContext ctx) {
        return null;
    }

    @Override
    public Object visitSwitchExpression(final Java20Parser.SwitchExpressionContext ctx) {
        return null;
    }

    @Override
    public Object visitConstantExpression(final Java20Parser.ConstantExpressionContext ctx) {
        return null;
    }
}