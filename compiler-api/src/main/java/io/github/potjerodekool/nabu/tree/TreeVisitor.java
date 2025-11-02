package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.tree.element.*;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;

/**
 * A visitor for visiting trees.
 * @param <R> The return type.
 * @param <P> THe parameter type.
 */
public interface TreeVisitor<R, P> {

    /**
     * Called for unknown or unhandled types.
     * @param tree A tree.
     * @param param A parameter.
     * @return Returns a result.
     */
    R visitUnknown(Tree tree,
                   P param);

    /**
     * Visit compilation unit.
     *
     * @param compilationUnit A compilation unit.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitCompilationUnit(CompilationUnit compilationUnit,
                                   P param) {
        return visitUnknown(compilationUnit, param);
    }

    /**
     * Visit function.
     *
     * @param function A function.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitFunction(Function function,
                            P param) {
        return visitUnknown(function, param);
    }

    /**
     * Visit block statement.
     *
     * @param blockStatement A block statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitBlockStatement(BlockStatementTree blockStatement,
                                  P param) {
        return visitUnknown(blockStatement, param);
    }

    /**
     * Visit return statement.
     *
     * @param returnStatement A return statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitReturnStatement(ReturnStatementTree returnStatement,
                                   P param) {
        return visitUnknown(returnStatement, param);
    }

    /**
     * Visit identifier.
     *
     * @param identifier An identifier.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitIdentifier(IdentifierTree identifier,
                              P param) {
        return visitUnknown(identifier, param);
    }

    /**
     * Visit lambda expression.
     *
     * @param lambdaExpression A lambda expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitLambdaExpression(LambdaExpressionTree lambdaExpression,
                                    P param) {
        return visitUnknown(lambdaExpression, param);
    }

    /**
     * Visit binary expression.
     *
     * @param binaryExpression A binary expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitBinaryExpression(BinaryExpressionTree binaryExpression,
                                    P param) {
        return visitUnknown(binaryExpression, param);
    }

    /**
     * Visit field access expression.
     *
     * @param fieldAccessExpression A field access expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitFieldAccessExpression(FieldAccessExpressionTree fieldAccessExpression,
                                         P param) {
        return visitUnknown(fieldAccessExpression, param);
    }

    /**
     * Visit class declaration.
     *
     * @param classDeclaration A class declaration.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitClass(ClassDeclaration classDeclaration,
                         P param) {
        return visitUnknown(classDeclaration, param);
    }

    /**
     * Visit method invocation.
     *
     * @param methodInvocation A method invocation.
     * @param param A paraameter.
     * @return Returns a result.
     */
    default R visitMethodInvocation(MethodInvocationTree methodInvocation,
                                    P param) {
        return visitUnknown(methodInvocation, param);
    }

    /**
     * Visit literal expression.
     *
     * @param literalExpression A literal expresion.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitLiteralExpression(LiteralExpressionTree literalExpression,
                                     P param) {
        return visitUnknown(literalExpression, param);
    }

    /**
     * Visit expression statement.
     *
     * @param expressionStatement An expression statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitExpressionStatement(ExpressionStatementTree expressionStatement,
                                       P param) {
        return visitUnknown(expressionStatement, param);
    }

    /**
     * Visit variable declarator statement.
     *
     * @param variableDeclaratorStatement A variable declarator statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitVariableDeclaratorStatement(VariableDeclaratorTree variableDeclaratorStatement,
                                               P param) {
        return visitUnknown(variableDeclaratorStatement, param);
    }

    /**
     * Visit package declaration.
     *
     * @param packageDeclaration A package declaration.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitPackageDeclaration(PackageDeclaration packageDeclaration,
                                      P param) {
        return visitUnknown(packageDeclaration, param);
    }

    /**
     * Visit import item.
     * @param importItem An import item.
     * @param param A parameter.
     * @return Returns a resulot.
     */
    default R visitImportItem(ImportItem importItem,
                              P param) {
        return visitUnknown(importItem, param);
    }

    /**
     * Visit primitive type.
     *
     * @param primitiveType a primitive type.
     * @param param a parameter.
     * @return Returns a result.
     */
    default R visitPrimitiveType(PrimitiveTypeTree primitiveType,
                                 P param) {
        return visitUnknown(primitiveType, param);
    }


    /**
     * Visit unary expression.
     *
     * @param unaryExpression an unary expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitUnaryExpression(UnaryExpressionTree unaryExpression,
                                   P param) {
        return visitUnknown(unaryExpression, param);
    }

    /**
     * Visit type identifier.
     *
     * @param typeIdentifier A type identifier.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTypeIdentifier(TypeApplyTree typeIdentifier,
                                  P param) {
        return visitUnknown(typeIdentifier, param);
    }

    /**
     * Visit annotation type.
     *
     * @param annotatedType an annotated type.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitAnnotatedType(AnnotatedTypeTree annotatedType,
                                 P param) {
        return visitUnknown(annotatedType, param);
    }

    /**
     * Visit type name expression.
     *
     * @param typeNameExpression A type name expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTypeNameExpression(TypeNameExpressionTree typeNameExpression,
                                      P param) {
        return visitUnknown(typeNameExpression, param);
    }

    /**
     * Visit variable type.
     * @param variableType A variable type.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitVariableType(VariableTypeTree variableType,
                                P param) {
        return visitUnknown(variableType, param);
    }

    /**
     * Visit cast expression.
     *
     * @param castExpressionTree A cast expression tree.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitCastExpression(CastExpressionTree castExpressionTree,
                                  P param) {
        return visitUnknown(castExpressionTree, param);
    }

    /**
     * Visit wildcard expression.
     *
     * @param wildcardExpression A wildcard
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitWildCardExpression(WildcardExpressionTree wildcardExpression,
                                      P param) {
        return visitUnknown(wildcardExpression, param);
    }

    /**
     * Visit if statement.
     *
     * @param ifStatementTree An if statement tree.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitIfStatement(IfStatementTree ifStatementTree,
                               P param) {
        return visitUnknown(ifStatementTree, param);
    }

    /**
     * Visit empty statement.
     *
     * @param emptyStatementTree An empty statement tree.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitEmptyStatement(EmptyStatementTree emptyStatementTree,
                                  P param) {
        return visitUnknown(emptyStatementTree, param);
    }

    /**
     * Visit for statement.
     *
     * @param forStatement A for statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitForStatement(ForStatementTree forStatement,
                                P param) {
        return visitUnknown(forStatement, param);
    }

    /**
     * Visit enchanged for statement.
     *
     * @param enhancedForStatement An enhanced for statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitEnhancedForStatement(EnhancedForStatementTree enhancedForStatement,
                                        P param) {
        return visitUnknown(enhancedForStatement, param);
    }


    /**
     * Visit annotation.
     *
     * @param annotationTree An annotation tree.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitAnnotation(AnnotationTree annotationTree,
                              P param) {
        return visitUnknown(annotationTree, param);
    }

    /**
     * Visit instance of expression.
     *
     * @param instanceOfExpression An instance of expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitInstanceOfExpression(InstanceOfExpression instanceOfExpression,
                                        P param) {
        return visitUnknown(instanceOfExpression, param);
    }

    /**
     * Visit new class expression.
     *
     * @param newClassExpression A new class expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitNewClass(NewClassExpression newClassExpression,
                            P param) {
        return visitUnknown(newClassExpression, param);
    }

    /**
     * Visit while statement.
     *
     * @param whileStatement A while statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitWhileStatement(WhileStatementTree whileStatement,
                                  P param) {
        return visitUnknown(whileStatement, param);
    }

    /**
     * Visit do while statement.
     *
     * @param doWhileStatement A do while statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitDoWhileStatement(DoWhileStatementTree doWhileStatement,
                                    P param) {
        return visitUnknown(doWhileStatement, param);
    }

    /**
     * Visit type parameter.
     *
     * @param typeParameterTree a type parameter.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTypeParameter(TypeParameterTree typeParameterTree,
                                 P param) {
        return visitUnknown(typeParameterTree, param);
    }

    /**
     * @param typeVariableTree A type variable.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTypeVariable(TypeVariableTree typeVariableTree,
                                P param) {
        return visitUnknown(typeVariableTree, param);
    }

    /**
     * Visit array type.
     *
     * @param arrayTypeTree Am array type
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitArrayType(ArrayTypeTree arrayTypeTree,
                             P param) {
        return visitUnknown(arrayTypeTree, param);
    }

    /**
     * Visit assignment expression.
     *
     * @param assignmentExpressionTree An assignment expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitAssignment(AssignmentExpressionTree assignmentExpressionTree,
                              P param) {
        return visitUnknown(assignmentExpressionTree, param);
    }

    /**
     * Visit new array expression.
     *
     * @param newArrayExpression A new array expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitNewArray(NewArrayExpression newArrayExpression,
                            P param) {
        return visitUnknown(newArrayExpression, param);
    }

    /**
     * Visit error.
     *
     * @param errorTree An error
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitErroneous(ErrorTree errorTree,
                             P param) {
        return visitUnknown(errorTree, param);
    }

    /**
     * Visit intersection type.
     *
     * @param intersectionTypeTree An intersection type
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitIntersectionType(IntersectionTypeTree intersectionTypeTree,
                                    P param) {
        return visitUnknown(intersectionTypeTree, param);
    }

    /**
     * Visit array access expression.
     *
     * @param arrayAccessExpressionTree An array access expression.
     * @param param A parameter.
     * @return Return a result.
     */
    default R visitArrayAccess(ArrayAccessExpressionTree arrayAccessExpressionTree,
                               P param) {
        return visitUnknown(arrayAccessExpressionTree, param);
    }

    /**
     * Visit member reference.
     *
     * @param memberReference A member reference.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitMemberReference(MemberReference memberReference,
                                   P param) {
        return visitUnknown(memberReference, param);
    }

    /**
     * Visit try statement.
     *
     * @param tryStatementTree A try statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTryStatement(TryStatementTree tryStatementTree,
                                P param) {
        return visitUnknown(tryStatementTree, param);
    }

    /**
     * Visit catch.
     *
     * @param catchTree A catch.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitCatch(CatchTree catchTree,
                         P param) {
        return visitUnknown(catchTree, param);
    }

    /**
     * Visit type union expression.
     *
     * @param typeUnionTreeExpression A type union expression.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTypeUnion(TypeUnionExpressionTree typeUnionTreeExpression,
                             P param) {
        return visitUnknown(typeUnionTreeExpression, param);
    }

    /**
     * Visit module declaration.
     *
     * @param moduleDeclaration A module declaration.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitModuleDeclaration(ModuleDeclaration moduleDeclaration,
                                     P param) {
        return visitUnknown(moduleDeclaration, param);
    }

    /**
     * Visit requires.
     *
     * @param requiresTree A requires
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitRequires(RequiresTree requiresTree,
                            P param) {
        return visitUnknown(requiresTree, param);
    }

    /**
     * Visit providddes.
     *
     * @param providesTree A provides
     * @param param A parameter
     * @return Returns a result.
     */
    default R visitProvides(ProvidesTree providesTree,
                            P param) {
        return visitUnknown(providesTree, param);
    }

    /**
     * Visit exports.
     *
     * @param exportsTree An export
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitExports(ExportsTree exportsTree,
                           P param) {
        return visitUnknown(exportsTree, param);
    }

    /**
     * Visit uses.
     *
     * @param usesTree An uses.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitUses(UsesTree usesTree,
                        P param) {
        return visitUnknown(usesTree, param);
    }

    /**
     * Visit opens.
     *
     * @param opensTree An opens
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitOpens(OpensTree opensTree,
                         P param) {
        return visitUnknown(opensTree, param);
    }

    /**
     * Visit labeled statement.
     *
     * @param labeledStatement A label statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitLabeledStatement(LabeledStatement labeledStatement,
                                    P param) {
        return visitUnknown(labeledStatement, param);
    }

    /**
     * Visit break statement.
     *
     * @param breakStatement A break statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitBreakStatement(BreakStatement breakStatement,
                                  P param) {
        return visitUnknown(breakStatement, param);
    }

    /**
     * Visit continue statement.
     *
     * @param continueStatement A continue statement
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitContinueStatement(ContinueStatement continueStatement,
                                     P param) {
        return visitUnknown(continueStatement, param);
    }

    /**
     * Visit synchronized statement.
     *
     * @param synchronizedStatement A synchronized statement
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitSynchronizedStatement(SynchronizedStatement synchronizedStatement,
                                         P param) {
        return visitUnknown(synchronizedStatement, param);
    }

    /**
     * Visit throw statement.
     *
     * @param throwStatement A throw statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitThrowStatement(ThrowStatement throwStatement,
                                  P param) {
        return visitUnknown(throwStatement, param);
    }

    /**
     * Visit yield statement.
     *
     * @param yieldStatement A yield statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitYieldStatement(YieldStatement yieldStatement,
                                  P param) {
        return visitUnknown(yieldStatement, param);
    }

    /**
     * Visit assert statement.
     *
     * @param assertStatement A assert statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitAssertStatement(AssertStatement assertStatement,
                                   P param) {
        return visitUnknown(assertStatement, param);
    }

    /**
     * Visit case statement.
     *
     * @param caseStatement A case statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitCaseStatement(CaseStatement caseStatement,
                                 P param) {
        return visitUnknown(caseStatement, param);
    }

    /**
     * Visit constant case label.
     *
     * @param constantCaseLabel A constant case label.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitConstantCaseLabel(ConstantCaseLabel constantCaseLabel,
                                     P param) {
        return visitUnknown(constantCaseLabel, param);
    }

    /**
     * Visit default case label.
     *
     * @param defaultCaseLabel A default case label.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitDefaultCaseLabel(DefaultCaseLabel defaultCaseLabel,
                                    P param) {
        return visitUnknown(defaultCaseLabel, param);
    }

    /**
     * Visit switch statement.
     *
     * @param switchStatement A switch statement.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitSwitchStatement(SwitchStatement switchStatement,
                                   P param) {
        return visitUnknown(switchStatement, param);
    }

    /**
     * Visit pattern case label.
     *
     * @param patternCaseLabel A pattern case label.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitPatternCaseLabel(PatternCaseLabel patternCaseLabel,
                                    P param) {
        return visitUnknown(patternCaseLabel, param);
    }

    /**
     * Visit type pattern.
     *
     * @param typePattern A type pattern.
     * @param param A parameter.
     * @return Returns a result.
     */
    default R visitTypePattern(TypePattern typePattern,
                               P param) {
        return visitUnknown(typePattern, param);
    }
}
