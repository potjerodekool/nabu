package io.github.potjerodekool.nabu.compiler.impl;

import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tools.JavaVersion;
import io.github.potjerodekool.nabu.tools.diagnostic.DefaultDiagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.Diagnostic;
import io.github.potjerodekool.nabu.tools.diagnostic.DiagnosticListener;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;

import java.util.List;

/**
 * Reports an error for a language construct of a newer Java version than the release ({@code --release}).
 * <p>
 * Java and Nabu sources both become the same trees, so one check serves both: a Nabu construct has the version of its
 * Java counterpart, and a construct without a counterpart is never reported. Only constructs that became final
 * after Java 17 are checked, because 17 is the oldest release.
 */
public final class LanguageLevelChecker extends AbstractTreeVisitor<Object, Void> {

    /**
     * A language feature and the version in which it became final.
     */
    public enum Feature {
        PATTERN_SWITCH("pattern matching for switch", JavaVersion.V21),
        CASE_NULL("'case null'", JavaVersion.V21),
        RECORD_PATTERN("record patterns", JavaVersion.V21),
        UNNAMED_VARIABLE("unnamed variables and patterns ('_')", JavaVersion.V22),
        FLEXIBLE_CONSTRUCTOR("statements before 'this(...)' or 'super(...)' in a constructor", JavaVersion.V25),
        MODULE_IMPORT("module import declarations", JavaVersion.V25),
        COMPACT_SOURCE_FILE("compact source files", JavaVersion.V25);

        private final String description;
        private final JavaVersion since;

        Feature(final String description,
                final JavaVersion since) {
            this.description = description;
            this.since = since;
        }

        public String description() {
            return description;
        }

        public JavaVersion since() {
            return since;
        }
    }

    private final JavaVersion release;
    private final DiagnosticListener listener;
    private CompilationUnit unit;

    private LanguageLevelChecker(final JavaVersion release,
                                 final DiagnosticListener listener) {
        this.release = release;
        this.listener = listener;
    }

    /**
     * Checks a compilation unit.
     *
     * @param compilationUnit A compilation unit that was just parsed.
     * @param release The release, the language level.
     * @param listener Receives an error for every construct that needs a newer version.
     */
    public static void check(final CompilationUnit compilationUnit,
                             final JavaVersion release,
                             final DiagnosticListener listener) {
        final var checker = new LanguageLevelChecker(release, listener);
        checker.unit = compilationUnit;
        checker.scan(compilationUnit);
    }

    private void require(final Feature feature,
                         final Tree tree) {
        if (release.isAtLeast(feature.since())) {
            return;
        }

        listener.report(new DefaultDiagnostic(
                Diagnostic.Kind.ERROR,
                String.format("%s requires Java %s, but the release is %s (line %d)",
                        capitalize(feature.description()), feature.since(), release, tree.getLineNumber()),
                unit.getFileObject(),
                tree.getLineNumber(),
                tree.getColumnNumber()
        ));
    }

    private static String capitalize(final String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private void scan(final Tree tree) {
        if (tree != null) {
            acceptTree(tree, null);
        }
    }

    private void scanAll(final List<? extends Tree> trees) {
        if (trees != null) {
            trees.forEach(this::scan);
        }
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit, final Void param) {
        // A compact source file imports module java.base implicitly; that is not a module import of its own.
        final var compact = compilationUnit.getClasses().stream()
                .anyMatch(it -> it.getModifiers().hasFlag(Flags.IMPLICIT_CLASS));
        compilationUnit.getImportItems().stream()
                .filter(it -> it.isModule() && !compact)
                .forEach(importItem -> require(Feature.MODULE_IMPORT, importItem));
        scanAll(compilationUnit.getClasses());
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Void param) {
        if (classDeclaration.getModifiers().hasFlag(Flags.IMPLICIT_CLASS)) {
            require(Feature.COMPACT_SOURCE_FILE, classDeclaration);
        }
        scanAll(classDeclaration.getEnclosedElements());
        return null;
    }

    @Override
    public Object visitFunction(final Function function, final Void param) {
        scanAll(function.getParameters());
        final var body = function.getBody();

        if (body != null) {
            if (function.getKind() == Kind.CONSTRUCTOR) {
                checkConstructorBody(body);
            }
            scan(body);
        }
        return null;
    }

    private void checkConstructorBody(final BlockStatementTree body) {
        final var statements = body.getStatements();

        for (int i = 1; i < statements.size(); i++) {
            if (isExplicitConstructorInvocation(statements.get(i))) {
                require(Feature.FLEXIBLE_CONSTRUCTOR, statements.get(0));
                return;
            }
        }
    }

    private boolean isExplicitConstructorInvocation(final StatementTree statement) {
        if (statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree invocation) {
            final var selector = invocation.getMethodSelector();
            final var name = selector instanceof FieldAccessExpressionTree fieldAccess
                    ? fieldAccess.getField()
                    : selector;
            return name instanceof IdentifierTree identifier
                    && (Constants.THIS.equals(identifier.getName()) || Constants.SUPER.equals(identifier.getName()));
        }
        return false;
    }

    @Override
    public Object visitBlockStatement(final BlockStatementTree blockStatement, final Void param) {
        scanAll(blockStatement.getStatements());
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variable, final Void param) {
        if (variable.getName() != null
                && variable.getName().getName().startsWith(Constants.UNNAMED_PREFIX)) {
            require(Feature.UNNAMED_VARIABLE, variable);
        }
        scan(variable.getValue());
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatement, final Void param) {
        scan(ifStatement.getExpression());
        scan(ifStatement.getThenStatement());
        scan(ifStatement.getElseStatement());
        return null;
    }

    @Override
    public Object visitForStatement(final ForStatementTree forStatement, final Void param) {
        scanAll(forStatement.getForInit());
        scan(forStatement.getCondition());
        scanAll(forStatement.getForUpdate());
        scan(forStatement.getStatement());
        return null;
    }

    @Override
    public Object visitEnhancedForStatement(final EnhancedForStatementTree forStatement, final Void param) {
        scan(forStatement.getLocalVariable());
        scan(forStatement.getExpression());
        scan(forStatement.getStatement());
        return null;
    }

    @Override
    public Object visitWhileStatement(final WhileStatementTree whileStatement, final Void param) {
        scan(whileStatement.getCondition());
        scan(whileStatement.getBody());
        return null;
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatementTree doWhile, final Void param) {
        scan(doWhile.getBody());
        scan(doWhile.getCondition());
        return null;
    }

    @Override
    public Object visitLabeledStatement(final LabeledStatement labeled, final Void param) {
        scan(labeled.getStatement());
        return null;
    }

    @Override
    public Object visitSynchronizedStatement(final SynchronizedStatement synchronizedStatement, final Void param) {
        scan(synchronizedStatement.getExpression());
        scan(synchronizedStatement.getBody());
        return null;
    }

    @Override
    public Object visitTryStatement(final TryStatementTree tryStatement, final Void param) {
        scanAll(tryStatement.getResources());
        scan(tryStatement.getBody());
        scanAll(tryStatement.getCatchers());
        scan(tryStatement.getFinalizer());
        return null;
    }

    @Override
    public Object visitCatch(final CatchTree catchTree, final Void param) {
        scan(catchTree.getVariable());
        scan(catchTree.getBody());
        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement, final Void param) {
        scan(returnStatement.getExpression());
        return null;
    }

    @Override
    public Object visitThrowStatement(final ThrowStatement throwStatement, final Void param) {
        scan(throwStatement.getExpression());
        return null;
    }

    @Override
    public Object visitYieldStatement(final YieldStatement yieldStatement, final Void param) {
        scan(yieldStatement.getExpression());
        return null;
    }

    @Override
    public Object visitAssertStatement(final AssertStatement assertStatement, final Void param) {
        scan(assertStatement.getCondition());
        scan(assertStatement.getDetail());
        return null;
    }

    @Override
    public Object visitExpressionStatement(final ExpressionStatementTree expressionStatement, final Void param) {
        scan(expressionStatement.getExpression());
        return null;
    }

    @Override
    public Object visitSwitchStatement(final SwitchStatement switchStatement, final Void param) {
        scan(switchStatement.getSelector());
        scanAll(switchStatement.getCases());
        return null;
    }

    @Override
    public Object visitSwitchExpression(final SwitchExpressionTree switchExpression, final Void param) {
        scan(switchExpression.getSelector());
        scanAll(switchExpression.getCases());
        return null;
    }

    @Override
    public Object visitCaseStatement(final CaseStatement caseStatement, final Void param) {
        scanAll(caseStatement.getLabels());
        scan(caseStatement.getBody());
        return null;
    }

    @Override
    public Object visitConstantCaseLabel(final ConstantCaseLabel label, final Void param) {
        if (label.getExpression() instanceof LiteralExpressionTree literal
                && literal.getLiteralKind() == LiteralExpressionTree.Kind.NULL) {
            require(Feature.CASE_NULL, label);
        }
        scan(label.getExpression());
        return null;
    }

    @Override
    public Object visitPatternCaseLabel(final PatternCaseLabel label, final Void param) {
        require(Feature.PATTERN_SWITCH, label);
        scan(label.getPattern());
        scan(label.getWhen());
        return null;
    }

    @Override
    public Object visitTypePattern(final TypePattern typePattern, final Void param) {
        scan(typePattern.getVariableDeclarator());
        return null;
    }

    @Override
    public Object visitRecordPattern(final RecordPattern recordPattern, final Void param) {
        require(Feature.RECORD_PATTERN, recordPattern);
        scanAll(recordPattern.getPatterns());
        return null;
    }

    @Override
    public Object visitInstanceOfExpression(final InstanceOfExpression instanceOf, final Void param) {
        scan(instanceOf.getExpression());
        scan(instanceOf.getTypeExpression());
        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambda, final Void param) {
        scanAll(lambda.getVariables());
        scan(lambda.getBody());
        return null;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binary, final Void param) {
        scan(binary.getLeft());
        scan(binary.getRight());
        return null;
    }

    @Override
    public Object visitUnaryExpression(final UnaryExpressionTree unary, final Void param) {
        scan(unary.getExpression());
        return null;
    }

    @Override
    public Object visitConditionalExpression(final ConditionalExpressionTree conditional, final Void param) {
        scan(conditional.getCondition());
        scan(conditional.getTrueExpression());
        scan(conditional.getFalseExpression());
        return null;
    }

    @Override
    public Object visitAssignment(final AssignmentExpressionTree assignment, final Void param) {
        scan(assignment.getLeft());
        scan(assignment.getRight());
        return null;
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree cast, final Void param) {
        scan(cast.getExpression());
        return null;
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree invocation, final Void param) {
        scan(invocation.getMethodSelector());
        scanAll(invocation.getArguments());
        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccess, final Void param) {
        scan(fieldAccess.getSelected());
        return null;
    }

    @Override
    public Object visitMemberReference(final MemberReference memberReference, final Void param) {
        scan(memberReference.getExpression());
        return null;
    }

    @Override
    public Object visitNewClass(final NewClassExpression newClass, final Void param) {
        scanAll(newClass.getArguments());
        scan(newClass.getClassDeclaration());
        return null;
    }

    @Override
    public Object visitNewArray(final NewArrayExpression newArray, final Void param) {
        scanAll(newArray.getDimensions());
        scanAll(newArray.getElements());
        return null;
    }

    @Override
    public Object visitArrayAccess(final ArrayAccessExpressionTree arrayAccess, final Void param) {
        scan(arrayAccess.getExpression());
        scan(arrayAccess.getIndex());
        return null;
    }

    @Override
    public Object visitParenthesizedExpression(final ParenthesizedExpression parenthesized, final Void param) {
        scan(parenthesized.getExpression());
        return null;
    }
}
