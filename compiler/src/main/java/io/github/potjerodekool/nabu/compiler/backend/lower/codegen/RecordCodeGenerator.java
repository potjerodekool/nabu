package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda.TypeExpressionCreator;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CBinaryExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CExpressionStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CReturnStatementTree;
import io.github.potjerodekool.nabu.type.ExecutableType;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class RecordCodeGenerator extends AbstractCodeGenerator {

    public RecordCodeGenerator(final CompilerContextImpl compilerContext) {
        super(compilerContext);
    }

    @Override
    public void generateCode(final ClassDeclaration classDeclaration) {
        super.generateCode(classDeclaration);
        processCompactConstructor((CClassDeclaration) classDeclaration);
    }

    private void processCompactConstructor(final CClassDeclaration classDeclaration) {
        final var compactConstructorOptional = TreeFilter.constructorsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(constructor -> constructor.hasFlag(Flags.COMPACT_RECORD_CONSTRUCTOR))
                .map(it -> (CFunction) it)
                .findFirst();

        compactConstructorOptional.ifPresent(compactConstructor -> {
            fillCompactConstructor(compactConstructor, classDeclaration);
            fillComponentAccessMethods(compactConstructor, classDeclaration);
        });
    }

    private void fillCompactConstructor(final CFunction compactConstructor,
                                        final ClassDeclaration classDeclaration) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();
        final var superClass = clazz.getSuperclass().asTypeElement();
        final var recordConstructor = ElementFilter.constructorsIn(superClass.getEnclosedElements())
                .getFirst();

        final var fieldsMap = ElementFilter.fieldsIn(clazz.getEnclosedElements()).stream()
                .collect(Collectors.toMap(
                        Element::getSimpleName,
                        java.util.function.Function.identity()
                ));

        final var parameters = compactConstructor.getParameters();
        final var statements = new ArrayList<StatementTree>();

        final var fieldAccess = new CFieldAccessExpressionTree(
                createThisExpression(clazz),
                new CIdentifierTree(Constants.SUPER)
        );

        fieldAccess.setType(superClass.asType());

        final var constructorInvocation = TreeMaker.methodInvocationTree(
                fieldAccess,
                List.of(),
                List.of(),
                -1,
                -1
        );
        constructorInvocation.setMethodType((ExecutableType) recordConstructor.asType());

        statements.add(new CExpressionStatementTree(constructorInvocation));

        statements.addAll(parameters.stream()
                .map(parameter -> {
                    final var parameterIdentifier = new CIdentifierTree(parameter.getName().getName());
                    parameterIdentifier.setSymbol(parameter.getName().getSymbol());

                    final var fieldSymbol = fieldsMap.get(parameter.getName().getName());
                    final var fieldIdentifier = new CIdentifierTree(fieldSymbol.getSimpleName());
                    fieldIdentifier.setSymbol(fieldSymbol);

                    return new CExpressionStatementTree(
                            new CBinaryExpressionTree(
                                    new CFieldAccessExpressionTree(
                                            createThisExpression(clazz),
                                            fieldIdentifier
                                    ),
                                    Tag.ASSIGN,
                                    parameterIdentifier
                            )
                    );
                }).toList());

        statements.add(new CReturnStatementTree(
                null,
                -1,
                -1
        ));

        compactConstructor.setBody(new CBlockStatementTree(statements));
    }

    private IdentifierTree createThisExpression(final ClassSymbol classSymbol) {
        final var thisExpression = new CIdentifierTree(Constants.THIS);
        thisExpression.setSymbol(classSymbol);
        return thisExpression;
    }

    private void fillComponentAccessMethods(final Function compactConstructor,
                                            final CClassDeclaration classDeclaration) {
        final var clazz = classDeclaration.getClassSymbol();

        final var componentNames = compactConstructor.getParameters().stream()
                .map(VariableDeclaratorTree::getName)
                .map(IdentifierTree::getName)
                .collect(Collectors.toSet());

        final var fields = ElementFilter.fieldsIn(clazz.getEnclosedElements()).stream()
                .collect(Collectors.toMap(
                        Element::getSimpleName,
                        java.util.function.Function.identity()
                ));

        ElementFilter.methodsIn(clazz.getEnclosedElements()).stream()
                .map(it -> (MethodSymbol) it)
                .filter(method -> componentNames.contains(method.getSimpleName()))
                .filter(method -> method.getParameters().isEmpty())
                .forEach(method -> {
                    final var field = fields.get(method.getSimpleName());

                    fillComponentAccessMethod(
                            method,
                            field,
                            classDeclaration
                    );
                });
    }

    private void fillComponentAccessMethod(final MethodSymbol method,
                                           final VariableElement field,
                                           final CClassDeclaration classDeclaration) {
        final var classSymbol = (ClassSymbol) classDeclaration.getClassSymbol();

        final var returnValue = new CIdentifierTree(method.getSimpleName());
        returnValue.setSymbol(field);

        final var returnStatement = new CReturnStatementTree(new CFieldAccessExpressionTree(
                createThisExpression(classSymbol),
                returnValue
        ));

        final var body = new CBlockStatementTree(
                List.of(returnStatement)
        );

        final var returnType = method.getReturnType().accept(new TypeExpressionCreator(), null);

        final var function = new CFunction(
                method.getSimpleName(),
                Kind.METHOD,
                new Modifiers(method.getFlags()),
                List.of(),
                null,
                List.of(),
                returnType,
                List.of(),
                body,
                null,
                -1,
                -1
        );

        function.setMethodSymbol(method);

        classDeclaration.enclosedElement(function);
    }

}
