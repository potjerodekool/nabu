package io.github.potjerodekool.nabu.testing;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.*;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.util.CollectionUtils;

import java.io.StringWriter;
import java.util.List;
import java.util.StringJoiner;

/**
 * Test utility that creates a string representation of tree.
 */
public class TreePrinter extends AbstractTreeVisitor<Object, Object> {

    private final StringWriter writer = new StringWriter();
    private boolean atStartOfLine = true;
    private final StringBuilder tabs = new StringBuilder();
    private static final String TAB = "    ";
    private static final int TAB_SIZE = TAB.length();

    public static String print(final Tree tree) {
        final var printer = new TreePrinter();
        printer.acceptTree(tree, null);
        return printer.getText();
    }

    private TreePrinter write(final String text) {
        if (!"\n".equals(text)) {
            insertTabsIfNeeded();
        }
        writer.write(text);
        writer.flush();
        detectNewLine(text);
        return this;
    }

    private void detectNewLine(final String text) {
        if (text.contains("\n")) {
            atStartOfLine = true;
        }
    }

    private void insertTabsIfNeeded() {
        if (atStartOfLine) {
            if (!tabs.isEmpty()) {
                writer.write(tabs.toString());
            }
            atStartOfLine = false;
        }
    }

    private void incrementTabs() {
        this.tabs.append(TAB);
    }

    private void decrementTabs() {
        final int length = tabs.length();
        this.tabs.delete(length - TAB_SIZE, length);
    }

    private TreePrinter writeLine(final String text) {
        insertTabsIfNeeded();
        write(text);
        newLine();
        return this;
    }

    private TreePrinter newLine() {
        writer.write("\n");
        writer.flush();
        this.atStartOfLine = true;
        return this;
    }

    private TreePrinter writeList(final List<? extends Tree> list,
                                  final Object param,
                                  final String sep) {
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                write(sep);
            }
            acceptTree(list.get(i), param);
        }

        return this;
    }

    private TreePrinter writeList(final List<? extends Tree> list,
                                  final Object param) {
        return writeList(list, param, ", ");
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Object param) {
        boolean addNewLine = false;

        if (compilationUnit.getPackageDeclaration() != null) {
            acceptTree(compilationUnit.getPackageDeclaration(), param);
            addNewLine = true;
        }

        if (!compilationUnit.getImportItems().isEmpty()) {
            if (addNewLine) {
                newLine();
            }
            writeList(compilationUnit.getImportItems(), param, "\n");
            newLine();
            addNewLine = true;
        }

        if (compilationUnit.getModuleDeclaration() != null) {
            if (addNewLine) {
                newLine();
            }
            acceptTree(compilationUnit.getModuleDeclaration(), param);
        } else {
            if (addNewLine) {
                newLine();
            }
            compilationUnit.getClasses().forEach(c ->
                    acceptTree(c, param));
        }

        return null;
    }

    @Override
    public Object visitModuleDeclaration(final ModuleDeclaration moduleDeclaration,
                                         final Object param) {
        final var annotations = moduleDeclaration.getAnnotations();

        if (!annotations.isEmpty()) {
            writeList(annotations, param, "\n");
            newLine();
        }

        if (moduleDeclaration.getKind() == ModuleDeclaration.ModuleKind.OPEN) {
            write("open ");
        }

        write("module ");
        acceptTree(moduleDeclaration.getIdentifier(), param);

        writeLine(" {");
        incrementTabs();

        if (!moduleDeclaration.getDirectives().isEmpty()) {
            writeList(moduleDeclaration.getDirectives(), param, "\n");
            newLine();
        }

        decrementTabs();
        write("}");
        return null;
    }

    @Override
    public Object visitRequires(final RequiresTree requiresTree,
                                final Object param) {
        write("requires");

        if (requiresTree.isTransitive()) {
            write(" transitive");
        }

        if (requiresTree.isStatic()) {
            write(" static");
        }

        write(" ");
        acceptTree(requiresTree.getModuleName(), param);
        write(";");
        return null;
    }

    @Override
    public Object visitExports(final ExportsTree exportsTree,
                               final Object param) {
        write("exports ");
        acceptTree(exportsTree.getPackageName(), param);

        if (!exportsTree.getModuleNames().isEmpty()) {
            write(" to ");
            writeList(exportsTree.getModuleNames(), ", ");
        }

        write(";");
        return null;
    }

    @Override
    public Object visitOpens(final OpensTree opensTree,
                             final Object param) {
        write("opens ");
        acceptTree(opensTree.getPackageName(), param);

        if (!opensTree.getModuleNames().isEmpty()) {
            write(" to ");
            writeList(opensTree.getModuleNames(), param, ", ");
        }

        write(";");
        return null;
    }

    @Override
    public Object visitUses(final UsesTree usesTree,
                            final Object param) {
        write("uses ");
        acceptTree(usesTree.getServiceName(), param);
        write(";");
        return null;
    }

    @Override
    public Object visitProvides(final ProvidesTree providesTree,
                                final Object param) {
        write("provides ");
        acceptTree(providesTree.getServiceName(), param);
        write(" with ");
        writeList(providesTree.getImplementationNames(), param, ", ");
        write(";");
        return null;
    }

    @Override
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration,
                                          final Object param) {
        writeList(packageDeclaration.getAnnotations(), param, "\n");
        write("package ")
                .write(packageDeclaration.getQualifiedName())
                .writeLine(";");
        return null;
    }

    @Override
    public Object visitImportItem(final ImportItem importItem,
                                  final Object param) {
        write("import ");

        if (importItem.isStatic()) {
            write("static ");
        }

        acceptTree(importItem.getQualified(), param);

        write(";");
        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Object param) {
        if (function.getModifiers().getFlags() != 0) {
            printModifierFlags(function.getModifiers().getFlags());
            write(" ");
        }

        if (function.getDefaultValue() == null) {
            write("fun ").write(function.getSimpleName());
            write("(");

            if (function.getReceiverParameter() != null) {
                acceptTree(function.getReceiverParameter(), param);
                write(", ");
            }

            writeList(function.getParameters(), param);
            write("): ");

            if (function.getReturnType() != null) {
                acceptTree(function.getReturnType(), param);
                write(" ");
            }

            if (!function.getThrownTypes().isEmpty()) {
                write("throws ");
                writeList(function.getThrownTypes(), param, ", ");
                write(" ");
            }

            if (function.getBody() != null) {
                acceptTree(function.getBody(), param);
            }
        } else {
            write(function.getSimpleName());
            write(" = ");
            acceptTree(function.getDefaultValue(), param);
        }

        return null;
    }

    @Override
    public Object visitBlockStatement(final BlockStatementTree blockStatement,
                                      final Object param) {
        writeLine("{");
        incrementTabs();

        writeList(
                blockStatement.getStatements(),
                param,
                "\n"
        );

        decrementTabs();
        newLine();
        writeLine("}");

        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement,
                                       final Object param) {
        final var expression = returnStatement.getExpression();

        if (expression != null) {
            write("return ");
            acceptTree(expression, param);
            write(";");
        } else {
            write("return;");
        }

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Object param) {
        write(identifier.getName());
        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final Object param) {
        write("(");
        writeList(lambdaExpression.getVariables(), param);
        write(")");
        write(" -> ");

        acceptTree(lambdaExpression.getBody(), param);
        return null;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                        final Object param) {
        acceptTree(binaryExpression.getLeft(), param);
        write(" ");

        write(binaryExpression.getTag().getText());

        write(" ");
        acceptTree(binaryExpression.getRight(), param);

        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Object param) {
        if (fieldAccessExpression.getSelected() != null) {
            acceptTree(fieldAccessExpression.getSelected(), param);
            write(".");
        }

        acceptTree(fieldAccessExpression.getField(), param);
        return null;
    }


    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Object param) {
        acceptTree(typeIdentifier.getClazz(), param);

        if (typeIdentifier.getTypeParameters() != null
                && !typeIdentifier.getTypeParameters().isEmpty()) {
            write("<");
            writeList(typeIdentifier.getTypeParameters(), param);
            write(">");
        }

        return null;
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Object param) {
        final var methodSelector = methodInvocation.getMethodSelector();
        final ExpressionTree methodName;

        if (methodSelector instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            acceptTree(fieldAccessExpressionTree.getSelected(), param);
            write(".");
            methodName = fieldAccessExpressionTree.getField();
        } else {
            methodName = methodSelector;
        }

        if (methodInvocation.getTypeArguments() != null
                && !methodInvocation.getTypeArguments().isEmpty()) {
            write("<");
            writeList(methodInvocation.getTypeArguments(), param);
            write(">");
        }

        acceptTree(methodName, param);

        write("(");
        writeList(methodInvocation.getArguments(), param);
        write(")");

        return null;
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                         final Object param) {
        final var literal = literalExpression.getLiteral();

        switch (literalExpression.getLiteralKind()) {
            case STRING -> write("\"" + literal + "\"");
            case NULL -> write("null");
            case CHAR -> write("'" + literal + "'");
            default -> write(literal.toString());
        }
        return null;
    }

    @Override
    public Object visitExpressionStatement(final ExpressionStatementTree expressionStatement,
                                           final Object param) {
        acceptTree(expressionStatement.getExpression(), param);
        write(";");
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Object param) {

        if (classDeclaration.getModifiers().getFlags() != 0) {
            printModifierFlags(classDeclaration.getModifiers().getFlags());
            writer.write(" ");
        }

        final var kindName = switch (classDeclaration.getKind()) {
            case CLASS -> "class";
            case INTERFACE -> "interface";
            case RECORD -> "record";
            case ENUM -> "enum";
            default -> "";
        };

        final var enclosingSeparator =
                classDeclaration.getKind() == Kind.ENUM
                        ? ",\n"
                        : "\n";


        final var className = classDeclaration.getSimpleName();
        write(kindName + " ");
        write(className);

        if (!classDeclaration.getTypeParameters().isEmpty()) {
            write("<");
            writeList(classDeclaration.getTypeParameters(), param);
            write(">");
        }

        if (classDeclaration.getKind() == Kind.RECORD) {
            final var primaryConstructor = (Function) classDeclaration.getEnclosedElements().getFirst();

            write("(");
            writeList(primaryConstructor.getParameters(), param, ", ");
            write(")");
        }

        if (classDeclaration.getExtending() != null) {
            write(" extends ");
            acceptTree(classDeclaration.getExtending(), param);
        }

        if (!classDeclaration.getImplementing().isEmpty()) {
            write(" implements ");
            writeList(classDeclaration.getImplementing(), param);
        }

        writeLine(" {");

        List<Tree> enclosingElements;

        if (classDeclaration.getKind() == Kind.RECORD) {
            enclosingElements = classDeclaration.getEnclosedElements()
                    .subList(1, classDeclaration.getEnclosedElements().size());
        } else {
            enclosingElements = classDeclaration.getEnclosedElements();
        }

        if (classDeclaration.getKind() == Kind.ENUM) {
            final var constants = enclosingElements.stream()
                    .flatMap(CollectionUtils.mapOnly(VariableDeclaratorTree.class))
                    .toList();

            final var others = enclosingElements.stream()
                    .filter(it -> !(it instanceof VariableDeclaratorTree))
                    .toList();

            incrementTabs();

            writeList(
                    constants,
                    param,
                    ",\n"
            );

            if (!constants.isEmpty()
                    && !others.isEmpty()) {
                writeLine(";");
            }

            writeList(
                    others,
                    param
            );

            decrementTabs();
        } else if (!enclosingElements.isEmpty()) {
            incrementTabs();

            writeList(
                    enclosingElements,
                    param,
                    enclosingSeparator
            );

            decrementTabs();
        }

        if (!enclosingElements.isEmpty()) {
            newLine();
        }

        writeLine("}");

        return null;
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree,
                                  final Object param) {
        write("@");
        acceptTree(annotationTree.getName(), param);

        if (!annotationTree.getArguments().isEmpty()) {
            write("(");
            writeList(annotationTree.getArguments(), param);
            write(")");
        }

        return null;
    }

    @Override
    public Object visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression,
                                            final Object param) {
        acceptTree(instanceOfExpression.getExpression(), param);
        write(" instanceof ");
        acceptTree(instanceOfExpression.getTypeExpression(), param);
        return null;
    }

    @Override
    public Object visitForStatement(final ForStatementTree forStatement,
                                    final Object param) {
        write("for (");
        writeList(forStatement.getForInit(), param, ", ");
        acceptTree(forStatement.getCondition(), param);
        write(";");

        if (!forStatement.getForUpdate().isEmpty()) {
            final var updates = forStatement.getForUpdate().stream()
                    .map(it -> (ExpressionStatementTree) it)
                    .map(ExpressionStatementTree::getExpression)
                            .toList();

            writeList(updates, param, ", ");
        }
        write(")");

        acceptTree(forStatement.getStatement(), param);
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final Object param) {
        if (variableDeclaratorStatement.getKind() == Kind.PARAMETER) {
            if (variableDeclaratorStatement.getNameExpression() == null) {
                write(variableDeclaratorStatement.getName().getName()).write(" : ");
            } else {
                acceptTree(variableDeclaratorStatement.getNameExpression(), param);
                write(" : ");
            }

            acceptTree(variableDeclaratorStatement.getVariableType(), param);

            if (Flags.hasFlag(variableDeclaratorStatement.getFlags(), Flags.VARARGS)) {
                write("...");
            }

            return null;
        }

        if (variableDeclaratorStatement.getKind()
                != Kind.ENUM_CONSTANT &&
                printModifierFlags(variableDeclaratorStatement.getFlags())) {
            write(" ");
        }

        if (variableDeclaratorStatement.getKind() == Kind.RESOURCE_VARIABLE
            || variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE) {
            write("var ");
        }

        acceptTree(variableDeclaratorStatement.getName(), param);

        if (variableDeclaratorStatement.getKind() != Kind.ENUM_CONSTANT
            && variableDeclaratorStatement.getVariableType() != null
            && !(variableDeclaratorStatement.getVariableType() instanceof VariableTypeTree)) {
            write(" : ");
            acceptTree(variableDeclaratorStatement.getVariableType(), param);
        }

        if (variableDeclaratorStatement.getKind() == Kind.ENUM_CONSTANT) {
            final var value = variableDeclaratorStatement.getValue();

            if (value instanceof MethodInvocationTree methodInvocationTree) {
                write("(");
                writeList(methodInvocationTree.getArguments(), param, ", ");
                write(")");
            } else if (value instanceof NewClassExpression newClassExpression) {
                write("(");
                writeList(newClassExpression.getArguments(), param, ", ");
                write(")");
            } else if (value != null) {
                acceptTree(value, param);
            }

        } else if ( (variableDeclaratorStatement.getKind() != Kind.FIELD
            && variableDeclaratorStatement.getValue() != null)
            ||
                (variableDeclaratorStatement.getKind() == Kind.FIELD
                && variableDeclaratorStatement.getValue() instanceof LiteralExpressionTree)) {
            write(" = ");
            acceptTree(variableDeclaratorStatement.getValue(), param);
        }

        if (variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE
                || variableDeclaratorStatement.getKind() == Kind.FIELD) {
            write(";");
        }

        return null;
    }

    private boolean printModifierFlags(final long flags) {
        final var modifiers = new StringJoiner(" ");

        if (Flags.hasFlag(flags, Flags.PUBLIC)) {
            modifiers.add("public");
        }

        if (Flags.hasFlag(flags, Flags.ABSTRACT)) {
            modifiers.add("abstract");
        }

        if (Flags.hasFlag(flags, Flags.PRIVATE)) {
            modifiers.add("private");
        }

        if (Flags.hasFlag(flags, Flags.PROTECTED)) {
            modifiers.add("protected");
        }

        if (Flags.hasFlag(flags, Flags.STATIC)) {
            modifiers.add("static");
        }

        if (Flags.hasFlag(flags, Flags.FINAL)) {
            modifiers.add("final");
        }

        write(modifiers.toString());

        return modifiers.length() > 0;
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree,
                                      final Object param) {
        write("(");
        acceptTree(castExpressionTree.getTargetType(), param);
        write(") ");

        acceptTree(castExpressionTree.getExpression(), param);
        return null;
    }

    @Override
    public Object visitNewClass(final NewClassExpression newClassExpression,
                                final Object param) {
        if (newClassExpression.getName() != null) {
            write("new ");
            acceptTree(newClassExpression.getName(), param);
        }

        write("(");
        writeList(newClassExpression.getArguments(), param);
        write(")");

        if (newClassExpression.getClassDeclaration() != null) {
            final var enclosedElements = newClassExpression.getClassDeclaration().getEnclosedElements();
            write("{");
            writeList(enclosedElements, "\n");
            write("}");
        }

        return null;
    }

    @Override
    public Object visitWhileStatement(final WhileStatementTree whileStatement,
                                      final Object param) {
        write("while(");
        acceptTree(whileStatement.getCondition(), param);
        writeLine(")");
        acceptTree(whileStatement.getBody(), param);
        return null;
    }

    @Override
    public Object visitVariableType(final VariableTypeTree variableType,
                                    final Object param) {
        write("var");
        return null;
    }

    @Override
    public Object visitWildCardExpression(final WildcardExpressionTree wildCardExpression,
                                          final Object param) {
        final var bound = wildCardExpression.getBound();

        switch (wildCardExpression.getBoundKind()) {
            case EXTENDS -> {
                write("? extends ");
                acceptTree(bound, param);
            }
            case SUPER -> {
                write("? super ");
                acceptTree(bound, param);
            }
            case UNBOUND -> write("?");
        }
        return null;
    }

    @Override
    public Object visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement,
                                            final Object param) {
        write("for (");
        visitLocalVariable(enhancedForStatement.getLocalVariable(), param);
        write(" in ");
        acceptTree(enhancedForStatement.getExpression(), param);
        writeLine(")");
        acceptTree(enhancedForStatement.getStatement(), param);
        return null;
    }

    private void visitLocalVariable(final VariableDeclaratorTree localVariable,
                                    final Object param) {
        acceptTree(localVariable.getVariableType(), param);
        write(" ");
        acceptTree(localVariable.getName(), param);
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatementTree doWhileStatement,
                                        final Object param) {
        writeLine("do");
        acceptTree(doWhileStatement.getBody(), param);
        write("while (");
        acceptTree(doWhileStatement.getCondition(), param);
        write(")");
        return null;
    }

    @Override
    public Object visitTypeParameter(final TypeParameterTree typeParameterTree,
                                     final Object param) {
        if (!typeParameterTree.getAnnotations().isEmpty()) {
            writeList(typeParameterTree.getAnnotations(), param);
            write(" ");
        }

        acceptTree(typeParameterTree.getIdentifier(), param);

        if (!typeParameterTree.getTypeBound().isEmpty()) {
            write(" extends ");
            writeList(typeParameterTree.getTypeBound(), param, " & ");
        }

        return null;
    }

    @Override
    public Object visitAnnotatedType(final AnnotatedTypeTree annotatedType,
                                     final Object param) {
        final var clazz = annotatedType.getClazz();

        if (clazz instanceof ArrayTypeTree arrayTypeTree) {
            acceptTree(arrayTypeTree.getComponentType(), param);

            write(" ");
            writeList(annotatedType.getAnnotations(), param, " ");

            write("[]");
            return null;
        } else {
            if (clazz instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
                acceptTree(fieldAccessExpressionTree.getSelected(), param);
                write(".");
                writeList(annotatedType.getAnnotations(), param, " ");write(" ");
                acceptTree(fieldAccessExpressionTree.getField(), param);
            } else {
                writeList(annotatedType.getAnnotations(), param, " ");
                write(" ");
                acceptTree(annotatedType.getClazz(), param);
            }

            return null;
        }
    }

    @Override
    public Object visitArrayType(final ArrayTypeTree arrayTypeTree,
                                 final Object param) {
        final var componentType = arrayTypeTree.getComponentType();
        acceptTree(componentType, param);
        write("[]");
        return null;
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveTypeTree primitiveType,
                                     final Object param) {
        switch (primitiveType.getKind()) {
            case BOOLEAN -> write("boolean");
            case INT -> write("int");
            case BYTE -> write("byte");
            case SHORT -> write("short");
            case LONG -> write("long");
            case CHAR -> write("char");
            case FLOAT -> write("float");
            case DOUBLE -> write("double");
            case VOID -> write("void");
        }

        return null;
    }

    @Override
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree,
                                  final Object param) {
        acceptTree(assignmentExpressionTree.getLeft(), param);
        write(" = ");
        acceptTree(assignmentExpressionTree.getRight(), param);
        return null;
    }

    @Override
    public Object visitNewArray(final NewArrayExpression newArrayExpression,
                                final Object param) {
        if (newArrayExpression.getElementType() != null) {
            write("new ");
            acceptTree(newArrayExpression.getElementType(), param);
        }

        newArrayExpression.getDimensions().forEach(dimension -> {
            write("[");
            acceptTree(dimension, param);
            write("]");
        });

        if (newArrayExpression.getElements() != null) {
            write("{");
            writeList(newArrayExpression.getElements(), param, ", ");
            write("}");
        }

        return null;
    }

    @Override
    public Object visitArrayAccess(final ArrayAccessExpressionTree arrayAccessExpressionTree,
                                   final Object param) {
        if (arrayAccessExpressionTree.getExpression() != null) {
            acceptTree(arrayAccessExpressionTree.getExpression(), param);
        }
        write("[");
        acceptTree(arrayAccessExpressionTree.getIndex(), param);
        write("]");
        return null;
    }

    @Override
    public Object visitMemberReference(final MemberReference memberReference,
                                       final Object param) {
        write("::");

        if (!memberReference.getTypeArguments().isEmpty()) {
            write("<");
            writeList(memberReference.getTypeArguments(), param, ", ");
            write(">");
        }

        acceptTree(memberReference.getExpression(), param);

        return null;
    }

    @Override
    public Object visitTryStatement(final TryStatementTree tryStatementTree,
                                    final Object param) {
        write("try ");

        if (!tryStatementTree.getResources().isEmpty()) {
            write("(");
            writeList(tryStatementTree.getResources(), param, "; ");
            write(") ");
        }

        acceptTree(tryStatementTree.getBody(), param);

        writeList(tryStatementTree.getCatchers(), param, "");

        if (tryStatementTree.getFinalizer() != null) {
            write("finally ");
            acceptTree(tryStatementTree.getFinalizer(), param);
        }

        return null;
    }

    @Override
    public Object visitCatch(final CatchTree catchTree,
                             final Object param) {
        write("catch (");
        acceptTree(catchTree.getVariable(), param);
        write(")");
        acceptTree(catchTree.getBody(), param);
        return null;
    }

    @Override
    public Object visitLabeledStatement(final LabeledStatement labeledStatement,
                                        final Object param) {
        write(labeledStatement.getLabel());
        write(" : ");
        acceptTree(labeledStatement.getStatement(), param);
        return null;
    }

    @Override
    public Object visitBreakStatement(final BreakStatement breakStatement,
                                      final Object param) {
        write("break");

        if (breakStatement.getTarget() != null) {
            write(" ");
            acceptTree(breakStatement.getTarget(), param);
        }

        write(";");
        return null;
    }

    @Override
    public Object visitContinueStatement(final ContinueStatement continueStatement,
                                         final Object param) {
        write("continue");

        if (continueStatement.getTarget() != null) {
            write(" ");
            acceptTree(continueStatement.getTarget(), param);
        }

        write(";");
        return null;
    }

    @Override
    public Object visitSynchronizedStatement(final SynchronizedStatement synchronizedStatement,
                                             final Object param) {
        write("synchronized(");
        acceptTree(synchronizedStatement.getExpression(), param);
        write(")");
        acceptTree(synchronizedStatement.getBody(), param);
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Object param) {
        write("if(");
        acceptTree(ifStatementTree.getExpression(), param);
        write(")");
        acceptTree(ifStatementTree.getThenStatement(), param);

        if (ifStatementTree.getElseStatement() != null) {
            write(" else ");
            acceptTree(ifStatementTree.getElseStatement(), param);
        }
        return null;
    }

    @Override
    public Object visitThrowStatement(final ThrowStatement throwStatement, final Object param) {
        write("throw ");
        acceptTree(throwStatement.getExpression(), param);
        write(";");
        return null;
    }

    @Override
    public Object visitYieldStatement(final YieldStatement yieldStatement, final Object param) {
        write("yield ");
        acceptTree(yieldStatement.getExpression(), param);
        write(";");
        return null;
    }

    @Override
    public Object visitAssertStatement(final AssertStatement assertStatement, final Object param) {
        write("assert ");
        acceptTree(assertStatement.getCondition(), param);

        if (assertStatement.getDetail() != null) {
            write(": ");
            acceptTree(assertStatement.getDetail(), param);
        }

        write(";");
        return null;
    }

    @Override
    public Object visitSwitchStatement(final SwitchStatement switchStatement, final Object param) {
        write("switch(");
        acceptTree(switchStatement.getSelector(), param);
        writeLine(")");
        writeLine("{");
        incrementTabs();
        writeList(switchStatement.getCases(), param, "\n");
        decrementTabs();
        newLine();
        writeLine("}");
        return null;
    }

    @Override
    public Object visitCaseStatement(final CaseStatement caseStatement, final Object param) {
        final var labels = caseStatement.getLabels();

        if (labels.size() == 1
                && labels.getFirst() instanceof DefaultCaseLabel) {
            write("default");
        } else {
            write("case ");
            final var separator = switch (caseStatement.getCaseKind()) {
                case RULE -> ", ";
                case STATEMENT -> " : case ";
            };

            writeList(labels, param, separator);
        }

        switch (caseStatement.getCaseKind()) {
            case RULE -> write(" -> ");
            case STATEMENT -> write(" : ");
        }

        final var body = caseStatement.getBody();
        acceptTree(body, param);

        if (body instanceof ExpressionTree) {
            write(";");
        }

        return null;
    }

    @Override
    public Object visitConstantCaseLabel(final ConstantCaseLabel constantCaseLabel, final Object param) {
        final var expression = constantCaseLabel.getExpression();
        acceptTree(expression, param);
        return null;
    }

    @Override
    public Object visitPatternCaseLabel(final PatternCaseLabel patternCaseLabel, final Object param) {
        acceptTree(patternCaseLabel.getPattern(), param);
        return null;
    }

    @Override
    public Object visitTypePattern(final TypePattern typePattern, final Object param) {
        final var variableDeclarator = typePattern.getVariableDeclarator();
        acceptTree(variableDeclarator.getVariableType(), param);
        write(" ");
        acceptTree(variableDeclarator.getName(), param);
        return null;
    }

    @Override
    public Object visitEmptyStatement(final EmptyStatementTree emptyStatementTree, final Object param) {
        write(";");
        return null;
    }

    @Override
    public Object visitUnaryExpression(final UnaryExpressionTree unaryExpression, final Object param) {
        final var tag = unaryExpression.getTag();
        acceptTree(unaryExpression.getExpression(), param);

        if (tag == Tag.POST_INC) {
            write("++");
        } else if (tag == Tag.POST_DEC) {
            write("--");
        }
        return null;
    }

    public String getText() {
        return writer.toString();
    }

}
