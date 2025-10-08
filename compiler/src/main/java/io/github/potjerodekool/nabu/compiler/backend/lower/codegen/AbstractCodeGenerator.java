package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementFilter;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.backend.lower.SymbolCreator;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.ast.Flags;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.tree.Modifiers;
import io.github.potjerodekool.nabu.compiler.tree.Tag;
import io.github.potjerodekool.nabu.compiler.tree.TreeFilter;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CExpressionStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CReturnStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CVariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public abstract class AbstractCodeGenerator implements CodeGenerator {

    protected final ClassSymbolLoader loader;
    protected final Types types;
    protected final SymbolCreator symbolCreator = new SymbolCreator();

    public AbstractCodeGenerator(final ClassSymbolLoader loader) {
        this.loader = loader;
        this.types = loader.getTypes();
    }

    @Override
    public void generateCode(final ClassDeclaration classDeclaration) {
        generateClientInit(classDeclaration);
    }

    private void generateClientInit(final ClassDeclaration classDeclaration) {
        final var clazzDeclaration = (CClassDeclaration) classDeclaration;

        final var clientInitializers = TreeFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(it -> it.getKind() == Kind.STATIC_INIT)
                .map(it -> (CFunction) it)
                .toList();

        final var mergedClientInit = createClientInit();

        initStaticFields(clazzDeclaration, mergedClientInit);
        mergeClientInitializers(
                clientInitializers,
                mergedClientInit,
                classDeclaration
        );

        if (!mergedClientInit.getBody().getStatements().isEmpty()) {
            clazzDeclaration.removeEnclosedElements(clientInitializers);
            classDeclaration.enclosedElement(mergedClientInit);
            final var clazz = clazzDeclaration.getClassSymbol();
            clazz.addEnclosedElement((MethodSymbol) mergedClientInit.getMethodSymbol());
        }
    }

    private CFunction createClientInit() {
        final var returnType = new CIdentifierTree(
                Constants.VOID,
                -1,
                -10
        );
        returnType.setType(types.getNoType(TypeKind.VOID));

        final var clientInit = new CFunction(
                Constants.CLINIT,
                Kind.STATIC_INIT,
                new Modifiers(
                        List.of(),
                        Flags.STATIC
                ),
                List.of(),
                null,
                List.of(),
                returnType,
                List.of(),
                new CBlockStatementTree(
                        List.of(),
                        -1,
                        -1
                ),
                null,
                -1,
                -1
        );

        final var method = symbolCreator.createMethod(clientInit);
        clientInit.setMethodSymbol(method);
        return clientInit;
    }

    private void initStaticFields(final CClassDeclaration clazzDeclaration,
                                  final CFunction mergedClientInit) {
        final var body = mergedClientInit.getBody();
        final var clazz = clazzDeclaration.getClassSymbol();

        /* Init fields that are static that are non-final
           or are not initialized by a literal expression
         */
        final var staticFields = TreeFilter.fieldsIn(clazzDeclaration.getEnclosedElements()).stream()
                .filter(it -> it.hasFlag(Flags.STATIC))
                .filter(it ->
                        !it.hasFlag(Flags.FINAL)
                                || isValueIsNotLiteral(it)
                )
                .toList();
        initStaticField(staticFields, clazz, body);

        //Init enum constants.
        initEnumConstantField(TreeFilter.enumConstantsIn(clazzDeclaration.getEnclosedElements()), clazz, body);
    }

    private boolean isValueIsNotLiteral(final VariableDeclaratorTree variableDeclaratorTree) {
        return variableDeclaratorTree.getValue() != null
                && !(variableDeclaratorTree.getValue() instanceof LiteralExpressionTree);
    }

    private void initStaticField(final List<VariableDeclaratorTree> staticFields,
                                 final ClassSymbol clazz,
                                 final BlockStatementTree body) {
        staticFields.forEach(staticField ->
                body.addStatement(createAssignmentExpression(clazz, staticField)));
    }

    private void initEnumConstantField(final List<VariableDeclaratorTree> enumConstants,
                                       final ClassSymbol clazz,
                                       final BlockStatementTree body) {
        for (var i = 0; i < enumConstants.size(); i++) {
            final var enumConstant = enumConstants.get(i);
            body.addStatement(createAssignmentExpressionForEnumConstant(clazz, i, (CVariableDeclaratorTree) enumConstant));
        }
    }

    private StatementTree createAssignmentExpression(final ClassSymbol clazz,
                                                     final VariableDeclaratorTree staticField) {
        final var target = new CIdentifierTree(
                clazz.getQualifiedName(),
                -1,
                -1
        );

        target.setType(clazz.asType());

        final var fieldAccess = new CFieldAccessExpressionTree(
                target,
                staticField.getName(),
                staticField.getLineNumber(),
                staticField.getColumnNumber()
        );

        final var assignField = new CBinaryExpressionTree(
                fieldAccess,
                Tag.ASSIGN,
                (ExpressionTree) staticField.getValue(),
                staticField.getLineNumber(),
                staticField.getColumnNumber()
        );

        return new CExpressionStatementTree(
                assignField,
                staticField.getLineNumber(),
                staticField.getColumnNumber()
        );
    }

    private StatementTree createAssignmentExpressionForEnumConstant(final ClassSymbol clazz,
                                                                    final int index,
                                                                    final CVariableDeclaratorTree enumConstant) {
        final var target = new CIdentifierTree(
                clazz.getQualifiedName(),
                -1,
                -1
        );
        target.setType(clazz.asType());

        final var fieldAccess = new CFieldAccessExpressionTree(
                target,
                enumConstant.getName(),
                enumConstant.getLineNumber(),
                enumConstant.getColumnNumber()
        );


        final var nameLiteral = new CLiteralExpressionTree(
                enumConstant.getName().getName(),
                -1,
                -1
        );

        nameLiteral.setType(
                loader.loadClass(
                        null,
                        Constants.STRING
                ).asType()
        );

        final var indexLiteral = new CLiteralExpressionTree(
                index,
                -1,
                -1
        );
        indexLiteral.setType(types.getPrimitiveType(TypeKind.INT));

        var newClassExpression = (NewClassExpression) enumConstant.getValue();
        final var arguments = new ArrayList<ExpressionTree>(newClassExpression.getArguments().size() + 2);
        arguments.add(nameLiteral);
        arguments.add(indexLiteral);
        arguments.addAll(newClassExpression.getArguments());

        newClassExpression = newClassExpression.builder()
                .arguments(arguments)
                .build();

        enumConstant.setValue(newClassExpression);

        final var assignField = new CBinaryExpressionTree(
                fieldAccess,
                Tag.ASSIGN,
                newClassExpression,
                enumConstant.getLineNumber(),
                enumConstant.getColumnNumber()
        );

        return new CExpressionStatementTree(
                assignField,
                enumConstant.getLineNumber(),
                enumConstant.getColumnNumber()
        );
    }

    private void mergeClientInitializers(final List<CFunction> clientInitializers,
                                         final CFunction mergedClientInitializer,
                                         final ClassDeclaration classDeclaration) {
        clientInitializers.forEach(clientInit ->
                mergedClientInitializer.getBody().addStatements(clientInit.getBody().getStatements())
        );

        afterClientInitMerge(mergedClientInitializer, classDeclaration);

        if (!mergedClientInitializer.getBody().getStatements().isEmpty()) {
            mergedClientInitializer.getBody().addStatement(new CReturnStatementTree(
                    null,
                    -1,
                    -1
            ));
        }
    }

    protected void afterClientInitMerge(final CFunction mergedClientInitializer,
                                        final ClassDeclaration classDeclaration) {
    }

    protected Optional<MethodSymbol> findMethod(final ClassSymbol classSymbol,
                                                final String name) {
        final var methodOptional = ElementFilter.methodsIn(classSymbol.getMembers().elements()).stream()
                .filter(method -> name.equals(method.getSimpleName()))
                .map(it -> (MethodSymbol) it)
                .findFirst();
        if (methodOptional.isPresent()) {
            return methodOptional;
        } else {
            final var superclass = classSymbol.getSuperclass();

            if (superclass != null) {
                return findMethod((ClassSymbol) superclass.asTypeElement(), name);
            } else {
                return Optional.empty();
            }
        }
    }

    protected Optional<VariableSymbol> findField(final ClassSymbol classSymbol,
                                                 final String name) {
        final var fieldOptional = ElementFilter.fieldsIn(classSymbol.getMembers().elements()).stream()
                .filter(field -> name.equals(field.getSimpleName()))
                .map(it -> (VariableSymbol) it)
                .findFirst();

        if (fieldOptional.isPresent()) {
            return fieldOptional;
        } else {
            final var superclass = classSymbol.getSuperclass();

            if (superclass != null) {
                return findField((ClassSymbol) superclass.asTypeElement(), name);
            } else {
                return Optional.empty();
            }
        }
    }
}

