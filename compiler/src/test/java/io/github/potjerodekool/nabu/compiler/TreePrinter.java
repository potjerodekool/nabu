package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.*;

import java.io.StringWriter;
import java.util.List;

public class TreePrinter extends AbstractTreeVisitor<Object, Object> {

    private final StringWriter writer = new StringWriter();

    public static String print(final Tree tree) {
        final var printer = new TreePrinter();
        tree.accept(printer, null);
        return printer.getText();
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit, final Object param) {
        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, param);
            writeLine("");
        }

        if (!compilationUnit.getImportItems().isEmpty()) {
            writeList(compilationUnit.getImportItems(), param, "\n");
            writeLine("");
        }

        if (compilationUnit.getModuleDeclaration() != null) {
            writeLine("");
            compilationUnit.getModuleDeclaration().accept(this, param);
        } else {
            writeLine("");
            compilationUnit.getClasses().forEach(c -> c.accept(this, param));
        }

        return null;
    }

    @Override
    public Object visitModuleDeclaration(final ModuleDeclaration moduleDeclaration, final Object param) {
        final var annotations = moduleDeclaration.getAnnotations();

        if (!annotations.isEmpty()) {
            writeList(annotations, param, "\n");
            writeLine("");
        }

        if (moduleDeclaration.getKind() == ModuleDeclaration.ModuleKind.OPEN) {
            write("open ");
        }

        write("module ");
        moduleDeclaration.getIdentifier().accept(this, param);

        writeLine(" {");

        if (!moduleDeclaration.getDirectives().isEmpty()) {
            writeList(moduleDeclaration.getDirectives(), param, "\n");
            writeLine("");
        }

        write("}");
        return null;
    }

    @Override
    public Object visitRequires(final RequiresTree requiresTree, final Object param) {
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
    public Object visitExports(final ExportsTree exportsTree, final Object param) {
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
    public Object visitOpens(final OpensTree opensTree, final Object param) {
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
    public Object visitUses(final UsesTree usesTree, final Object param) {
        write("uses ");
        usesTree.getServiceName().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitProvides(final ProvidesTree providesTree, final Object param) {
        write("provides ");
        providesTree.getServiceName().accept(this, param);
        write(" with ");
        writeList(providesTree.getImplementationNames(), param, ", ");
        write(";");
        return null;
    }

    @Override
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration, final Object param) {
        writeList(packageDeclaration.getAnnotations(), param, "\n");
        write("package ")
                .write(packageDeclaration.getQualifiedName())
                .writeLine(";");
        return null;
    }

    @Override
    public Object visitImportItem(final ImportItem importItem, final Object param) {
        write("import ");

        if (importItem.isStatic()) {
            write("static ");
        }

        importItem.getQualified().accept(this, param);

        write(";");
        return null;
    }

    @Override
    public Object visitFunction(final Function function, final Object param) {
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
        blockStatement.getStatements().forEach(statement -> statement.accept(this, param));
        writeLine("}");

        return null;
    }

    @Override
    public Object visitReturnStatement(final ReturnStatementTree returnStatement,
                                       final Object param) {
        write("return ");
        returnStatement.getExpression().accept(this, param);
        write(";");

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier, final Object param) {
        write(identifier.getName());
        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression, final Object param) {
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
    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression, final Object param) {
        if (fieldAccessExpression.getTarget() != null) {
            fieldAccessExpression.getTarget().accept(this, param);
            write(".");
        }

        fieldAccessExpression.getField().accept(this, param);
        return null;
    }

    private TreePrinter write(final String text) {
        writer.write(text);
        writer.flush();
        return this;
    }

    private TreePrinter writeLine(final String text) {
        write(text);
        newLine();
        return this;
    }

    private TreePrinter newLine() {
        writer.write("\n");
        writer.flush();
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
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier, final Object param) {
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
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation, final Object param) {
        if (methodInvocation.getTarget() != null) {
            methodInvocation.getTarget().accept(this, param);
            write(".");
        }

        if (methodInvocation.getTypeArguments() != null
                && !methodInvocation.getTypeArguments().isEmpty()) {
            write("<");
            writeList(methodInvocation.getTypeArguments(), param);
            write(">");
        }

        methodInvocation.getName().accept(this, param);

        write("(");
        writeList(methodInvocation.getArguments(), param);
        write(")");

        return null;
    }

    @Override
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression, final Object param) {
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
    public Object visiExpressionStatement(final ExpressionStatementTree expressionStatement, final Object param) {
        expressionStatement.getExpression().accept(this, param);
        write(";");
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration, final Object param) {
        final var className = classDeclaration.getSimpleName();
        write("public class ");
        write(className);

        if (!classDeclaration.getTypeParameters().isEmpty()) {
            write("<");
            writeList(classDeclaration.getTypeParameters(), param);
            write(">");
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
        classDeclaration.getEnclosedElements().forEach(e -> e.accept(this, param));
        writeLine("}");
        return null;
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final Object param) {
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
    public Object visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression, final Object param) {
        instanceOfExpression.getExpression().accept(this, param);
        write(" instanceof ");
        instanceOfExpression.getTypeExpression().accept(this, param);
        return null;
    }

    @Override
    public Object visitForStatement(final ForStatementTree forStatement, final Object param) {
        write("for (");
        writeList(forStatement.getForInit(), param, ", ");
        forStatement.getExpression().accept(this, param);
        write(";");

        if (!forStatement.getForUpdate().isEmpty()) {
            writeList(forStatement.getForUpdate(), param, ", ");
            write(";");
        }
        write(")");

        forStatement.getStatement().accept(this, param);
        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Object param) {
        if (variableDeclaratorStatement.getKind() == Kind.PARAMETER) {
            if (variableDeclaratorStatement.getNameExpression() == null) {
                write(variableDeclaratorStatement.getName().getName()).write(" : ");
            } else {
                variableDeclaratorStatement.getNameExpression().accept(this, param);
                write(" : ");
            }

            variableDeclaratorStatement.getType().accept(this, param);
            return null;
        }

        variableDeclaratorStatement.getType().accept(this, param);
        write(" ");
        variableDeclaratorStatement.getName().accept(this, param);

        if (variableDeclaratorStatement.getValue() != null) {
            write(" = ");
            variableDeclaratorStatement.getValue().accept(this, param);
        }

        if (variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE
                || variableDeclaratorStatement.getKind() == Kind.FIELD) {
            writeLine(";");
        }

        return null;
    }

    @Override
    public Object visitCastExpression(final CastExpressionTree castExpressionTree, final Object param) {
        write("(");
        castExpressionTree.getTargetType().accept(this, param);
        write(") ");

        castExpressionTree.getExpression().accept(this, param);
        return null;
    }

    @Override
    public Object visitNewClass(final NewClassExpression newClassExpression, final Object param) {
        write("new ");
        newClassExpression.getName().accept(this, param);
        write("(");
        writeList(newClassExpression.getArguments(), param);
        write(")");

        if (newClassExpression.getClassDeclaration() != null) {
            write("{");
            writeList(newClassExpression.getClassDeclaration().getEnclosedElements(), "\n");
            write("}");
        }

        return null;
    }

    @Override
    public Object visitWhileStatement(final WhileStatementTree whileStatement, final Object param) {
        write("while(");
        whileStatement.getCondition().accept(this, param);
        writeLine(")");
        whileStatement.getBody().accept(this, param);
        return null;
    }

    @Override
    public Object visitVariableType(final VariableTypeTree variableType, final Object param) {
        write("var");
        return null;
    }

    @Override
    public Object visitWildCardExpression(final WildcardExpressionTree wildCardExpression, final Object param) {
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
    public Object visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final Object param) {
        write("for (");
        visitLocalVariable(enhancedForStatement.getLocalVariable(), param);
        write(" : ");
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
    public Object visitDoWhileStatement(final DoWhileStatementTree doWhileStatement, final Object param) {
        writeLine("do");
        doWhileStatement.getBody().accept(this, param);
        write("while (");
        doWhileStatement.getCondition().accept(this, param);
        writeLine(")");
        return null;
    }

    @Override
    public Object visitTypeParameter(final TypeParameterTree typeParameterTree, final Object param) {
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
    public Object visitAnnotatedType(final AnnotatedTypeTree annotatedType, final Object param) {
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

    private void printArrayTypeComponent(final ArrayTypeTree arrayTypeTree,
                                         final Object param) {
        final var componentType = arrayTypeTree.getComponentType();

        if (!(componentType instanceof ArrayTypeTree)) {
            componentType.accept(this, param);
        }
    }

    private void printArrayTypeAnnotations(final Tree tree,
                                           final Object param) {
        if (tree instanceof AnnotatedTypeTree annotatedType) {
            writeList(annotatedType.getAnnotations(), param, " ");
            printArrayTypeAnnotations(annotatedType.getClazz(), param);
        } else if (tree instanceof ArrayTypeTree arrayTypeTree) {
            //printArrayTypeAnnotations(arrayTypeTree.getComponentType(), param);
        }
    }

    @Override
    public Object visitArrayType(final ArrayTypeTree arrayTypeTree, final Object param) {
        final var componentType = arrayTypeTree.getComponentType();
        componentType.accept(this, param);
        write("[]");
        return null;
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveTypeTree primitiveType, final Object param) {
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
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree, final Object param) {
        assignmentExpressionTree.getLeft().accept(this, param);
        write(" = ");
        assignmentExpressionTree.getRight().accept(this, param);
        return null;
    }

    @Override
    public Object visitNewArray(final NewArrayExpression newArrayExpression,
                                final Object param) {
        write("{ ");
        writeList(newArrayExpression.getElements(), param, ", ");
        write("}");
        return null;
    }

    @Override
    public Object visitArrayAccess(final ArrayAccessExpressionTree arrayAccessExpressionTree, final Object param) {
        if (arrayAccessExpressionTree.getExpression() != null) {
            arrayAccessExpressionTree.getExpression().accept(this, param);
        }
        write("[");
        arrayAccessExpressionTree.getIndex().accept(this, param);
        write("]");
        return null;
    }

    @Override
    public Object visitMemberReference(final MemberReference memberReference, final Object param) {
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
    public Object visitTryStatement(final TryStatementTree tryStatementTree, final Object param) {
        write("try ");

        if (!tryStatementTree.getResources().isEmpty()) {
            write("(");
            writeList(tryStatementTree.getResources(), param, "; ");
            write(") ");
        }

        tryStatementTree.getBody().accept(this, param);

        writeList(tryStatementTree.getCatchers(), param);

        if (tryStatementTree.getFinalizer() != null) {
            tryStatementTree.getFinalizer().accept(this, param);
        }

        return null;
    }

    @Override
    public Object visitCatch(final CatchTree catchTree, final Object param) {
        write("catch (");
        catchTree.getVariable().accept(this, param);
        write(")");
        catchTree.getBody().accept(this, param);
        return null;
    }

    public String getText() {
        return writer.toString();
    }
}
