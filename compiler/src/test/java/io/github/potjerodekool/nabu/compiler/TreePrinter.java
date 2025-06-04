package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;

import java.io.StringWriter;
import java.util.List;
import java.util.StringJoiner;

public class TreePrinter extends AbstractTreeVisitor<Object, Object> {

    private final StringWriter writer = new StringWriter();
    private boolean atStartOfLine = true;
    private final StringBuilder tabs = new StringBuilder();
    private static final String TAB = "    ";
    private static final int TAB_SIZE = TAB.length();

    public static String print(final Tree tree) {
        final var printer = new TreePrinter();
        tree.accept(printer, null);
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
            list.get(i).accept(this, param);
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
        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, param);
            newLine();
        }

        if (!compilationUnit.getImportItems().isEmpty()) {
            writeList(compilationUnit.getImportItems(), param, "\n");
            newLine();
        }

        if (compilationUnit.getModuleDeclaration() != null) {
            newLine();
            compilationUnit.getModuleDeclaration().accept(this, param);
        } else {
            newLine();
            compilationUnit.getClasses().forEach(c -> c.accept(this, param));
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
        moduleDeclaration.getIdentifier().accept(this, param);

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
        requiresTree.getModuleName().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitExports(final ExportsTree exportsTree,
                               final Object param) {
        write("exports ");
        exportsTree.getPackageName().accept(this, param);

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
        opensTree.getPackageName().accept(this, param);

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
        usesTree.getServiceName().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitProvides(final ProvidesTree providesTree,
                                final Object param) {
        write("provides ");
        providesTree.getServiceName().accept(this, param);
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

        importItem.getQualified().accept(this, param);

        write(";");
        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Object param) {
        if (function.getDefaultValue() == null) {
            write("fun ").write(function.getSimpleName());
            write("(");

            if (function.getReceiverParameter() != null) {
                function.getReceiverParameter().accept(this, param);
                write(", ");
            }

            writeList(function.getParameters(), param);
            write("): ");

            if (function.getReturnType() != null) {
                function.getReturnType().accept(this, param);
                write(" ");
            }

            if (!function.getThrownTypes().isEmpty()) {
                write("throws ");
                writeList(function.getThrownTypes(), param, ", ");
                write(" ");
            }

            if (function.getBody() != null) {
                function.getBody().accept(this, param);
            }
        } else {
            write(function.getSimpleName());
            write(" = ");
            function.getDefaultValue().accept(this, param);
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
            expression.accept(this, param);
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

        lambdaExpression.getBody().accept(this, param);
        return null;
    }

    @Override
    public Object visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                        final Object param) {
        binaryExpression.getLeft().accept(this, param);
        write(" ");

        write(binaryExpression.getTag().getText());

        write(" ");
        binaryExpression.getRight().accept(this, param);

        return null;
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Object param) {
        if (fieldAccessExpression.getSelected() != null) {
            fieldAccessExpression.getSelected().accept(this, param);
            write(".");
        }

        fieldAccessExpression.getField().accept(this, param);
        return null;
    }


    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final Object param) {
        typeIdentifier.getClazz().accept(this, param);

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
            fieldAccessExpressionTree.getSelected().accept(this, param);
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

        methodName.accept(this, param);

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
        expressionStatement.getExpression().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Object param) {
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
                        : "";


        final var className = classDeclaration.getSimpleName();
        write("public " + kindName + " ");
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
            classDeclaration.getExtending().accept(this, param);
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
        annotationTree.getName().accept(this, param);

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
        instanceOfExpression.getExpression().accept(this, param);
        write(" instanceof ");
        instanceOfExpression.getTypeExpression().accept(this, param);
        return null;
    }

    @Override
    public Object visitForStatement(final ForStatementTree forStatement,
                                    final Object param) {
        write("for (");
        writeList(forStatement.getForInit(), param, ", ");
        forStatement.getExpression().accept(this, param);
        write(";");

        if (!forStatement.getForUpdate().isEmpty()) {
            final var updates = forStatement.getForUpdate().stream()
                    .map(it -> (ExpressionStatementTree) it)
                    .map(ExpressionStatementTree::getExpression)
                            .toList();

            writeList(updates, param, ", ");
        }
        write(")");

        forStatement.getStatement().accept(this, param);
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final Object param) {
        if (variableDeclaratorStatement.getKind() == Kind.PARAMETER) {
            if (variableDeclaratorStatement.getNameExpression() == null) {
                write(variableDeclaratorStatement.getName().getName()).write(" : ");
            } else {
                variableDeclaratorStatement.getNameExpression().accept(this, param);
                write(" : ");
            }

            variableDeclaratorStatement.getType().accept(this, param);

            if (Flags.hasFlag(variableDeclaratorStatement.getFlags(), Flags.VARARGS)) {
                write("...");
            }

            return null;
        }

        if (variableDeclaratorStatement.getKind() == Kind.RESOURCE_VARIABLE
            || variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE) {
            write("var ");
        }

        if (variableDeclaratorStatement.getKind()
                != Kind.ENUM_CONSTANT &&
                printModifierFlags(variableDeclaratorStatement.getFlags())) {
            write(" ");
        }

        variableDeclaratorStatement.getName().accept(this, param);

        if (variableDeclaratorStatement.getKind() != Kind.ENUM_CONSTANT
            && variableDeclaratorStatement.getType() != null
            && !(variableDeclaratorStatement.getType() instanceof VariableTypeTree)) {
            write(" : ");
            variableDeclaratorStatement.getType().accept(this, param);
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
                value.accept(this, param);
            }

        } else if (variableDeclaratorStatement.getValue() != null) {
            write(" = ");
            variableDeclaratorStatement.getValue().accept(this, param);
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
        castExpressionTree.getTargetType().accept(this, param);
        write(") ");

        castExpressionTree.getExpression().accept(this, param);
        return null;
    }

    @Override
    public Object visitNewClass(final NewClassExpression newClassExpression,
                                final Object param) {
        if (newClassExpression.getName() != null) {
            write("new ");
            newClassExpression.getName().accept(this, param);
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
        whileStatement.getCondition().accept(this, param);
        writeLine(")");
        whileStatement.getBody().accept(this, param);
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
                bound.accept(this, param);
            }
            case SUPER -> {
                write("? super ");
                bound.accept(this, param);
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
        enhancedForStatement.getExpression().accept(this, param);
        writeLine(")");
        enhancedForStatement.getStatement().accept(this, param);
        return null;
    }

    private void visitLocalVariable(final VariableDeclaratorTree localVariable,
                                    final Object param) {
        localVariable.getType().accept(this, param);
        write(" ");
        localVariable.getName().accept(this, param);
    }

    @Override
    public Object visitDoWhileStatement(final DoWhileStatementTree doWhileStatement,
                                        final Object param) {
        writeLine("do");
        doWhileStatement.getBody().accept(this, param);
        write("while (");
        doWhileStatement.getCondition().accept(this, param);
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
        typeParameterTree.getIdentifier().accept(this, param);

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
            arrayTypeTree.getComponentType().accept(this, param);

            write(" ");
            writeList(annotatedType.getAnnotations(), param, " ");

            write("[]");
            return null;
        } else {
            writeList(annotatedType.getAnnotations(), param, " ");
            write(" ");
            annotatedType.getClazz().accept(this, param);

            return null;
        }
    }

    @Override
    public Object visitArrayType(final ArrayTypeTree arrayTypeTree,
                                 final Object param) {
        final var componentType = arrayTypeTree.getComponentType();
        componentType.accept(this, param);
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
        assignmentExpressionTree.getLeft().accept(this, param);
        write(" = ");
        assignmentExpressionTree.getRight().accept(this, param);
        return null;
    }

    @Override
    public Object visitNewArray(final NewArrayExpression newArrayExpression,
                                final Object param) {
        if (newArrayExpression.getElementType() != null) {
            write("new ");
            newArrayExpression.getElementType().accept(this, param);
        }

        newArrayExpression.getDimensions().forEach(dimension -> {
            write("[");
            dimension.accept(this, param);
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
            arrayAccessExpressionTree.getExpression().accept(this, param);
        }
        write("[");
        arrayAccessExpressionTree.getIndex().accept(this, param);
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

        memberReference.getExpression().accept(this, param);

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

        tryStatementTree.getBody().accept(this, param);

        writeList(tryStatementTree.getCatchers(), param, "");

        if (tryStatementTree.getFinalizer() != null) {
            write("finally ");
            tryStatementTree.getFinalizer().accept(this, param);
        }

        return null;
    }

    @Override
    public Object visitCatch(final CatchTree catchTree,
                             final Object param) {
        write("catch (");
        catchTree.getVariable().accept(this, param);
        write(")");
        catchTree.getBody().accept(this, param);
        return null;
    }

    @Override
    public Object visitLabeledStatement(final LabeledStatement labeledStatement,
                                        final Object param) {
        write(labeledStatement.getLabel());
        write(" : ");
        labeledStatement.getStatement().accept(this, param);
        return null;
    }

    @Override
    public Object visitBreakStatement(final BreakStatement breakStatement,
                                      final Object param) {
        write("break");

        if (breakStatement.getTarget() != null) {
            write(" ");
            breakStatement.getTarget().accept(this, param);
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
            continueStatement.getTarget().accept(this, param);
        }

        write(";");
        return null;
    }

    @Override
    public Object visitSynchronizedStatement(final SynchronizedStatement synchronizedStatement,
                                             final Object param) {
        write("synchronized(");
        synchronizedStatement.getExpression().accept(this, param);
        write(")");
        synchronizedStatement.getBody().accept(this, param);
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Object param) {
        write("if(");
        ifStatementTree.getExpression().accept(this, param);
        write(")");
        ifStatementTree.getThenStatement().accept(this, param);

        if (ifStatementTree.getElseStatement() != null) {
            write(" else ");
            ifStatementTree.getElseStatement().accept(this, param);
        }
        return null;
    }

    @Override
    public Object visitThrowStatement(final ThrowStatement throwStatement, final Object param) {
        write("throw ");
        throwStatement.getExpression().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitYieldStatement(final YieldStatement yieldStatement, final Object param) {
        write("yield ");
        yieldStatement.getExpression().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitAssertStatement(final AssertStatement assertStatement, final Object param) {
        write("assert ");
        assertStatement.getCondition().accept(this, param);

        if (assertStatement.getDetail() != null) {
            write(": ");
            assertStatement.getDetail().accept(this, param);
        }

        write(";");
        return null;
    }

    @Override
    public Object visitSwitchStatement(final SwitchStatement switchStatement, final Object param) {
        write("switch(");
        switchStatement.getSelector().accept(this, param);
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
        body.accept(this, param);

        if (body instanceof ExpressionTree) {
            write(";");
        }

        return null;
    }

    @Override
    public Object visitConstantCaseLabel(final ConstantCaseLabel constantCaseLabel, final Object param) {
        final var expression = constantCaseLabel.getExpression();
        expression.accept(this, param);
        return null;
    }

    @Override
    public Object visitPatternCaseLabel(final PatternCaseLabel patternCaseLabel, final Object param) {
        patternCaseLabel.getPattern().accept(this, param);
        return null;
    }

    @Override
    public Object visitBindingPattern(final BindingPattern bindingPattern, final Object param) {
        final var variableDeclarator = bindingPattern.getVariableDeclarator();
        variableDeclarator.getType().accept(this, param);
        write(" ");
        variableDeclarator.getName().accept(this, param);
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
        unaryExpression.getExpression().accept(this, param);

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
