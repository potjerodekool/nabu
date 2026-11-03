package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.tree.CatchTree;
import io.github.potjerodekool.nabu.tree.PatternCaseLabel;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.impl.CCatchTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.RecordPattern;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.tree.CaseLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Finds the switch expressions with patterns wherever they are in a method (arguments, operands, conditions,
 * lambda bodies, throw and yield, the bodies of other switches) and replaces each one by the tree that the
 * given lowering makes of it. The trees are rewritten from the inside out, so a switch in a case body is
 * lowered before the switch around it. A tree in which nothing is replaced is returned as it is.
 * <p>
 * A record pattern in an instanceof that no condition hook of the lowering handles (an argument, an operand of
 * an arithmetic expression, a lambda body, ...) is replaced in the same way. The variables of the pattern are
 * declared before the statement, so they are in scope for the rest of the statement.
 */
final class PatternSwitchRewriter {

    private final java.util.function.Predicate<SwitchExpressionTree> isPatternSwitch;
    private final Function<SwitchExpressionTree, ExpressionTree> lowering;
    private final RecordPatternLowering recordPatternLowering;

    /** The declarations that belong before the statement that is being rewritten, null if there is none. */
    private List<StatementTree> hoisted;

    /** Lowers {@code x instanceof R(var a)}; the declarations go to {@code hoisted} or, if null, in the result. */
    interface RecordPatternLowering {
        ExpressionTree lower(InstanceOfExpression instanceOf, List<StatementTree> hoisted);

        /** {@code value} evaluated after the declarations, in an expression: for an initializer. */
        ExpressionTree declaringFirst(List<StatementTree> declarations, ExpressionTree value);
    }

    PatternSwitchRewriter(final java.util.function.Predicate<SwitchExpressionTree> isPatternSwitch,
                          final Function<SwitchExpressionTree, ExpressionTree> lowering,
                          final RecordPatternLowering recordPatternLowering) {
        this.isPatternSwitch = isPatternSwitch;
        this.lowering = lowering;
        this.recordPatternLowering = recordPatternLowering;
    }

    BlockStatementTree rewriteBody(final BlockStatementTree body) {
        return body == null ? null : (BlockStatementTree) statement(body);
    }

    /**
     * @param hooked Is true if the lowering handles a record pattern at the top of the expression itself (the
     * initializer of a local variable). The initializer of a field has no statement to declare the variables of a
     * pattern before, so they are declared at the start of an expression that holds the initializer.
     */
    ExpressionTree rewriteExpression(final ExpressionTree expression,
                                     final boolean hooked) {
        if (hooked || recordPatternLowering == null) {
            return expression(expression, hooked);
        }
        final var outer = hoisted;
        final var declarations = new ArrayList<StatementTree>();
        hoisted = declarations;
        final ExpressionTree rewritten;
        try {
            rewritten = expression(expression, false);
        } finally {
            hoisted = outer;
        }
        return declarations.isEmpty() ? rewritten : recordPatternLowering.declaringFirst(declarations, rewritten);
    }

    private ExpressionTree expression(final ExpressionTree tree) {
        return expression(tree, false);
    }

    /**
     * @param hooked Is true if the lowering handles a record pattern here itself: the condition of an if or while,
     * a return, the initializer of a local variable, the value of an assignment and the operands of {@code &&},
     * {@code ||}, {@code !} and {@code ?:} in those.
     */
    private ExpressionTree expression(final ExpressionTree tree,
                                      final boolean hooked) {
        if (tree == null) {
            return null;
        }
        if (tree instanceof SwitchExpressionTree switchExpression) {
            final var selector = expression(switchExpression.getSelector());
            final var cases = cases(switchExpression.getCases());
            final var rebuilt = selector == switchExpression.getSelector() && cases == switchExpression.getCases()
                    ? switchExpression
                    : switchExpression.builder().selector(selector).cases(cases).type(switchExpression.getType()).build();
            return isPatternSwitch.test(rebuilt) ? lowering.apply(rebuilt) : rebuilt;
        } else if (tree instanceof MethodInvocationTree call) {
            final var selector = expression(call.getMethodSelector());
            final var arguments = expressions(call.getArguments());
            return selector == call.getMethodSelector() && arguments == call.getArguments()
                    ? call
                    : call.builder().methodSelector(selector).arguments(arguments).build();
        } else if (tree instanceof BinaryExpressionTree binary) {
            final var chained = hooked && (binary.getTag() == Tag.AND || binary.getTag() == Tag.OR);
            final var left = expression(binary.getLeft(), chained);
            final var right = expression(binary.getRight(), chained);
            return left == binary.getLeft() && right == binary.getRight()
                    ? binary
                    : binary.builder().left(left).right(right).build();
        } else if (tree instanceof UnaryExpressionTree unary) {
            final var operand = expression(unary.getExpression(), hooked && unary.getTag() == Tag.NOT);
            if (operand == unary.getExpression()) {
                return unary;
            }
            final var rebuilt = TreeMaker.unaryExpressionTree(unary.getTag(), operand, unary.getLineNumber(),
                    unary.getColumnNumber());
            rebuilt.setType(unary.getType());
            return rebuilt;
        } else if (tree instanceof CastExpressionTree cast) {
            final var operand = expression(cast.getExpression());
            if (operand == cast.getExpression()) {
                return cast;
            }
            final var rebuilt = TreeMaker.castExpressionTree(cast.getTargetType(), operand, cast.getLineNumber(),
                    cast.getColumnNumber());
            rebuilt.setType(cast.getType());
            return rebuilt;
        } else if (tree instanceof ParenthesizedExpression parenthesized) {
            final var inner = expression(parenthesized.getExpression(), hooked);
            if (inner == parenthesized.getExpression()) {
                return parenthesized;
            }
            final var rebuilt = TreeMaker.parenthesizedExpression(inner, parenthesized.getLineNumber(),
                    parenthesized.getColumnNumber());
            rebuilt.setType(parenthesized.getType());
            return rebuilt;
        } else if (tree instanceof ConditionalExpressionTree conditional) {
            final var condition = expression(conditional.getCondition(), hooked);
            final var whenTrue = expression(conditional.getTrueExpression(), hooked);
            final var whenFalse = expression(conditional.getFalseExpression(), hooked);
            return condition == conditional.getCondition()
                    && whenTrue == conditional.getTrueExpression()
                    && whenFalse == conditional.getFalseExpression()
                    ? conditional
                    : conditional.builder().condition(condition).trueExpression(whenTrue)
                    .falseExpression(whenFalse).type(conditional.getType()).build();
        } else if (tree instanceof FieldAccessExpressionTree fieldAccess) {
            final var selected = expression(fieldAccess.getSelected());
            return selected == fieldAccess.getSelected()
                    ? fieldAccess
                    : fieldAccess.builder().selected(selected).field(fieldAccess.getField()).build();
        } else if (tree instanceof ArrayAccessExpressionTree access) {
            final var array = expression(access.getExpression());
            final var index = expression(access.getIndex());
            return array == access.getExpression() && index == access.getIndex()
                    ? access
                    : access.builder().expression(array).index(index).build();
        } else if (tree instanceof AssignmentExpressionTree assignment) {
            final var left = expression(assignment.getLeft());
            final var right = expression(assignment.getRight());
            if (left == assignment.getLeft() && right == assignment.getRight()) {
                return assignment;
            }
            final var rebuilt = TreeMaker.assignmentExpression(left, right, assignment.getLineNumber(),
                    assignment.getColumnNumber());
            rebuilt.setType(assignment.getType());
            return rebuilt;
        } else if (tree instanceof NewClassExpression newClass) {
            final var arguments = expressions(newClass.getArguments());
            return arguments == newClass.getArguments()
                    ? newClass
                    : newClass.builder().arguments(arguments).build();
        } else if (tree instanceof NewArrayExpression newArray) {
            final var dimensions = expressions(newArray.getDimensions());
            final var elements = expressions(newArray.getElements());
            if (dimensions == newArray.getDimensions() && elements == newArray.getElements()) {
                return newArray;
            }
            final var rebuilt = NewArrayExpression.create(newArray.getElementType(), dimensions, elements,
                    newArray.getLineNumber(), newArray.getColumnNumber());
            rebuilt.setType(newArray.getType());
            return rebuilt;
        } else if (tree instanceof InstanceOfExpression instanceOf) {
            final var operand = expression(instanceOf.getExpression());
            var rebuilt = instanceOf;
            if (operand != instanceOf.getExpression()) {
                rebuilt = TreeMaker.instanceOfExpression(operand, instanceOf.getTypeExpression(),
                        instanceOf.getLineNumber(), instanceOf.getColumnNumber());
                rebuilt.setType(instanceOf.getType());
            }
            if (!hooked && recordPatternLowering != null && rebuilt.getTypeExpression() instanceof RecordPattern) {
                return recordPatternLowering.lower(rebuilt, hoisted);
            }
            return rebuilt;
        } else if (tree instanceof LambdaExpressionTree lambda) {
            final var body = lambda.getBody();
            if (body instanceof ExpressionTree expressionBody) {
                final var outer = hoisted;
                final var declarations = new ArrayList<StatementTree>();
                hoisted = declarations;
                final ExpressionTree rewritten;
                try {
                    rewritten = expression(expressionBody);
                } finally {
                    hoisted = outer;
                }
                if (declarations.isEmpty()) {
                    return rewritten == body ? lambda : lambda.builder().body(rewritten).build();
                }
                final var line = lambda.getLineNumber();
                final var column = lambda.getColumnNumber();
                final var methodType = lambda.getLambdaMethodType();
                final var returnsNothing = methodType != null && methodType.getReturnType() != null
                        && methodType.getReturnType().getKind() == TypeKind.VOID;
                final var statements = new ArrayList<>(declarations);
                statements.add(returnsNothing
                        ? TreeMaker.expressionStatement(rewritten, line, column)
                        : TreeMaker.returnStatement(rewritten, line, column));
                return lambda.builder().body(TreeMaker.blockStatement(statements, line, column)).build();
            } else if (body instanceof StatementTree statementBody) {
                final var rewritten = statement(statementBody);
                return rewritten == body ? lambda : lambda.builder().body(rewritten).build();
            }
        }
        return tree;
    }

    private StatementTree statement(final StatementTree tree) {
        if (tree == null) {
            return null;
        }
        if (tree instanceof BlockStatementTree block) {
            final var statements = statements(block.getStatements());
            return statements == block.getStatements() ? block : block.builder().statements(statements).build();
        } else if (tree instanceof ExpressionStatementTree expressionStatement) {
            final var expression = assignmentOrExpression(expressionStatement.getExpression());
            return expression == expressionStatement.getExpression()
                    ? expressionStatement
                    : expressionStatement.builder().expression(expression).build();
        } else if (tree instanceof ReturnStatementTree returnStatement) {
            final var expression = expression(returnStatement.getExpression(), true);
            return expression == returnStatement.getExpression()
                    ? returnStatement
                    : returnStatement.builder().expression(expression).build();
        } else if (tree instanceof ThrowStatement throwStatement) {
            final var expression = expression(throwStatement.getExpression());
            return expression == throwStatement.getExpression()
                    ? throwStatement
                    : ThrowStatement.create(expression, throwStatement.getLineNumber(),
                    throwStatement.getColumnNumber());
        } else if (tree instanceof YieldStatement yieldStatement) {
            final var expression = expression(yieldStatement.getExpression());
            return expression == yieldStatement.getExpression()
                    ? yieldStatement
                    : YieldStatement.create(expression, yieldStatement.getLineNumber(),
                    yieldStatement.getColumnNumber());
        } else if (tree instanceof VariableDeclaratorTree declarator) {
            if (declarator.getValue() instanceof ExpressionTree value) {
                final var rewritten = expression(value, declarator.getKind() == Kind.LOCAL_VARIABLE);
                return rewritten == value ? declarator : declarator.builder().value(rewritten).build();
            }
        } else if (tree instanceof IfStatementTree ifStatement) {
            final var condition = expression(ifStatement.getExpression(), true);
            final var thenStatement = nested(ifStatement.getThenStatement());
            final var elseStatement = nested(ifStatement.getElseStatement());
            return condition == ifStatement.getExpression()
                    && thenStatement == ifStatement.getThenStatement()
                    && elseStatement == ifStatement.getElseStatement()
                    ? ifStatement
                    : ifStatement.builder().expression(condition).thenStatement(thenStatement)
                    .elseStatement(elseStatement).build();
        } else if (tree instanceof WhileStatementTree whileStatement) {
            final var condition = expression(whileStatement.getCondition(), true);
            final var body = nested(whileStatement.getBody());
            return condition == whileStatement.getCondition() && body == whileStatement.getBody()
                    ? whileStatement
                    : whileStatement.builder().condition(condition).body(body).build();
        } else if (tree instanceof DoWhileStatementTree doWhile) {
            final var body = nested(doWhile.getBody());
            final var condition = expression(doWhile.getCondition());
            return condition == doWhile.getCondition() && body == doWhile.getBody()
                    ? doWhile
                    : doWhile.builder().body(body).condition(condition).build();
        } else if (tree instanceof ForStatementTree forStatement) {
            final var init = statements(forStatement.getForInit());
            final var condition = expression(forStatement.getCondition());
            final var update = statements(forStatement.getForUpdate());
            final var body = nested(forStatement.getStatement());
            return init == forStatement.getForInit()
                    && condition == forStatement.getCondition()
                    && update == forStatement.getForUpdate()
                    && body == forStatement.getStatement()
                    ? forStatement
                    : forStatement.builder().forInit(init).expression(condition).forUpdate(update)
                    .statement(body).build();
        } else if (tree instanceof EnhancedForStatementTree enhancedFor) {
            final var expression = expression(enhancedFor.getExpression());
            final var body = nested(enhancedFor.getStatement());
            return expression == enhancedFor.getExpression() && body == enhancedFor.getStatement()
                    ? enhancedFor
                    : enhancedFor.builder().expression(expression).statement(body).build();
        } else if (tree instanceof SwitchStatement switchStatement) {
            final var selector = expression(switchStatement.getSelector());
            final var cases = cases(switchStatement.getCases());
            return selector == switchStatement.getSelector() && cases == switchStatement.getCases()
                    ? switchStatement
                    : switchStatement.builder().selector(selector).cases(cases).build();
        } else if (tree instanceof TryStatementTree tryStatement) {
            return tryStatement(tryStatement);
        } else if (tree instanceof LabeledStatement labeled) {
            final var inner = nested(labeled.getStatement());
            return inner == labeled.getStatement()
                    ? labeled
                    : LabeledStatement.create(labeled.getLabel(), inner, labeled.getLineNumber(),
                    labeled.getColumnNumber());
        } else if (tree instanceof SynchronizedStatement synchronizedStatement) {
            final var expression = expression(synchronizedStatement.getExpression());
            final var body = (BlockStatementTree) statement(synchronizedStatement.getBody());
            return expression == synchronizedStatement.getExpression() && body == synchronizedStatement.getBody()
                    ? synchronizedStatement
                    : SynchronizedStatement.create(expression, body, synchronizedStatement.getLineNumber(),
                    synchronizedStatement.getColumnNumber());
        }
        return tree;
    }

    /** The value of an assignment is a place where the lowering handles a record pattern itself. */
    private ExpressionTree assignmentOrExpression(final ExpressionTree tree) {
        if (tree instanceof BinaryExpressionTree binary && binary.getTag() == Tag.ASSIGN) {
            final var left = expression(binary.getLeft());
            final var right = expression(binary.getRight(), true);
            return left == binary.getLeft() && right == binary.getRight()
                    ? binary
                    : binary.builder().left(left).right(right).build();
        } else if (tree instanceof AssignmentExpressionTree assignment) {
            final var left = expression(assignment.getLeft());
            final var right = expression(assignment.getRight(), true);
            if (left == assignment.getLeft() && right == assignment.getRight()) {
                return assignment;
            }
            final var rebuilt = TreeMaker.assignmentExpression(left, right, assignment.getLineNumber(),
                    assignment.getColumnNumber());
            rebuilt.setType(assignment.getType());
            return rebuilt;
        }
        return expression(tree);
    }

    /**
     * A statement that is not in a list of statements (the branch of an if, the body of a loop): the declarations
     * that it needs go in a block around it.
     */
    private StatementTree nested(final StatementTree tree) {
        if (tree == null || tree instanceof BlockStatementTree) {
            return statement(tree);
        }
        final var outer = hoisted;
        final var declarations = new ArrayList<StatementTree>();
        hoisted = declarations;
        final StatementTree rewritten;
        try {
            rewritten = statement(tree);
        } finally {
            hoisted = outer;
        }
        if (declarations.isEmpty()) {
            return rewritten;
        }
        declarations.add(rewritten);
        return TreeMaker.blockStatement(declarations, tree.getLineNumber(), tree.getColumnNumber());
    }

    private StatementTree tryStatement(final TryStatementTree tryStatement) {
        final var body = (BlockStatementTree) statement(tryStatement.getBody());
        var catchesChanged = false;
        final var catchers = new ArrayList<CatchTree>();
        for (final var catcher : tryStatement.getCatchers()) {
            final var catchBody = (BlockStatementTree) statement(catcher.getBody());
            if (catchBody != catcher.getBody()) {
                catchesChanged = true;
                catchers.add(new CCatchTree(catcher.getVariable(), catchBody, catcher.getLineNumber(),
                        catcher.getColumnNumber()));
            } else {
                catchers.add(catcher);
            }
        }
        final var finalizer = (BlockStatementTree) statement(tryStatement.getFinalizer());
        var resourcesChanged = false;
        final var resources = new ArrayList<Tree>();
        for (final var resource : tryStatement.getResources()) {
            final Tree rewritten;
            if (resource instanceof StatementTree resourceStatement) {
                rewritten = statement(resourceStatement);
            } else if (resource instanceof ExpressionTree resourceExpression) {
                rewritten = expression(resourceExpression);
            } else {
                rewritten = resource;
            }
            resourcesChanged |= rewritten != resource;
            resources.add(rewritten);
        }
        if (body == tryStatement.getBody() && !catchesChanged && finalizer == tryStatement.getFinalizer()
                && !resourcesChanged) {
            return tryStatement;
        }
        return TryStatementTree.create(body, catchers, finalizer, resources, tryStatement.getLineNumber(),
                tryStatement.getColumnNumber());
    }

    private List<CaseStatement> cases(final List<CaseStatement> cases) {
        var changed = false;
        final var result = new ArrayList<CaseStatement>();
        for (final var caseStatement : cases) {
            var labelsChanged = false;
            final var labels = new ArrayList<CaseLabel>();
            for (final var label : caseStatement.getLabels()) {
                if (label instanceof PatternCaseLabel patternLabel && patternLabel.getWhen() != null) {
                    final var guard = expression(patternLabel.getWhen());
                    if (guard != patternLabel.getWhen()) {
                        labelsChanged = true;
                        labels.add(PatternCaseLabel.create(patternLabel.getPattern(), guard,
                                patternLabel.getLineNumber(), patternLabel.getColumnNumber()));
                        continue;
                    }
                }
                labels.add(label);
            }
            final var body = caseStatement.getBody();
            final Tree newBody;
            if (body instanceof StatementTree bodyStatement) {
                newBody = nested(bodyStatement);
            } else if (body instanceof ExpressionTree bodyExpression) {
                newBody = expression(bodyExpression);
            } else {
                newBody = body;
            }
            if (labelsChanged || newBody != body) {
                changed = true;
                result.add(CaseStatement.create(caseStatement.getCaseKind(), labelsChanged ? labels : caseStatement.getLabels(),
                        newBody, caseStatement.getLineNumber(), caseStatement.getColumnNumber()));
            } else {
                result.add(caseStatement);
            }
        }
        return changed ? result : cases;
    }

    private List<ExpressionTree> expressions(final List<? extends ExpressionTree> trees) {
        var changed = false;
        final var result = new ArrayList<ExpressionTree>();
        for (final var tree : trees) {
            final var rewritten = expression(tree);
            changed |= rewritten != tree;
            result.add(rewritten);
        }
        @SuppressWarnings("unchecked")
        final var original = (List<ExpressionTree>) trees;
        return changed ? result : original;
    }

    private List<StatementTree> statements(final List<StatementTree> trees) {
        var changed = false;
        final var result = new ArrayList<StatementTree>();
        for (final var tree : trees) {
            final var outer = hoisted;
            final var declarations = new ArrayList<StatementTree>();
            hoisted = declarations;
            final StatementTree rewritten;
            try {
                rewritten = statement(tree);
            } finally {
                hoisted = outer;
            }
            changed |= rewritten != tree || !declarations.isEmpty();
            result.addAll(declarations);
            result.add(rewritten);
        }
        return changed ? result : trees;
    }
}
