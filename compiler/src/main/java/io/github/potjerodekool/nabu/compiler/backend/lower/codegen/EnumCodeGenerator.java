package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.*;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CExpressionStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CReturnStatementTree;
import io.github.potjerodekool.nabu.tree.statement.impl.CVariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.TypeKind;

import java.util.List;

public class EnumCodeGenerator extends AbstractCodeGenerator {
    public EnumCodeGenerator(final CompilerContextImpl compilerContext) {
        super(compilerContext);
    }

    @Override
    public void generateCode(final ClassDeclaration classDeclaration) {
        final var clazzDeclaration = (CClassDeclaration) classDeclaration;

        generateValuesField(clazzDeclaration);
        generatePrivateValuesMethod(clazzDeclaration);
        generateValuesMethod(clazzDeclaration);
        generateValueOfMethod(clazzDeclaration);
        super.generateCode(classDeclaration);
    }

    protected void afterClientInitMerge(final CFunction mergedClientInitializer,
                                        final ClassDeclaration classDeclaration) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();
        final var valuesMethod = findMethod(clazz, "$values")
                .orElseThrow(() -> new IllegalStateException("Missing $values method"));

        final var methodInvocation = new CMethodInvocationTree(
                new CIdentifierTree("$values"),
                List.of(),
                List.of()
        );

        methodInvocation.getMethodSelector().setType(valuesMethod.asType().getOwner().asType());
        methodInvocation.setMethodType(valuesMethod.asType());

        final var valuesFieldSymbol = findField(clazz, "$VALUES")
                .orElseThrow(() -> new IllegalStateException("Missing $VALUES field"));

        final var left = new CIdentifierTree("$VALUES");
        left.setSymbol(valuesFieldSymbol);

        final var assign = new CBinaryExpressionTree(
                left,
                Tag.ASSIGN,
                methodInvocation
        );

        mergedClientInitializer.getBody().addStatement(new CExpressionStatementTree(assign));
    }

    private void generateValuesField(final CClassDeclaration classDeclaration) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();
        final var type = clazz.asType();
        final var arrayType = types.getArrayType(type);

        final var componentType = new CIdentifierTree(clazz.getQualifiedName());
        componentType.setType(type);

        final var arrayTypeTree = new CArrayTypeTree(
                componentType,
                List.of()
        );
        arrayTypeTree.setType(arrayType);

        final var field = new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .variableType(arrayTypeTree)
                .name(new CIdentifierTree("$VALUES"))
                .modifiers(new Modifiers(Flags.PRIVATE + Flags.STATIC + Flags.FINAL))
                .build();

        final var symbol = symbolCreator.createSymbol(field);
        classDeclaration.enclosedElement(field);

        clazz.addEnclosedElement(symbol);
    }

    private void generatePrivateValuesMethod(final CClassDeclaration classDeclaration) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();

        final var enumConstants = TreeFilter.enumConstantsIn(classDeclaration.getEnclosedElements());

        final var componentType = new CIdentifierTree(clazz.getSimpleName());
        componentType.setType(clazz.asType());

        final var arrayType = types.getArrayType(clazz.asType());
        final var arrayTypeTree = new CArrayTypeTree(componentType, List.of());
        arrayTypeTree.setType(arrayType);

        final var function = new CFunction(
                "$values",
                Kind.METHOD,
                new Modifiers(
                        Flags.PRIVATE + Flags.STATIC + Flags.SYNTHETIC
                ),
                List.of(),
                null,
                List.of(),
                arrayTypeTree,
                List.of(),
                null,
                null
        );

        final var type = new CIdentifierTree(classDeclaration.getSimpleName());

        type.setType(clazz.asType());

        final var dimension = new CLiteralExpressionTree(enumConstants.size());

        dimension.setType(types.getPrimitiveType(TypeKind.INT));

        final var arrayElements = enumConstants.stream()
                .map(enumConstant -> {
                    final var target = new CIdentifierTree(clazz.getQualifiedName());
                    target.setType(clazz.asType());

                    final var field = new CIdentifierTree(enumConstant.getName().getName());
                    field.setSymbol(
                            enumConstant.getName()
                                    .getSymbol()
                    );

                    return (ExpressionTree) new CFieldAccessExpressionTree(
                            target,
                            field
                    );
                }).toList();

        final var body = new CBlockStatementTree(
                List.of(
                        new CReturnStatementTree(
                                new CNewArrayExpression(
                                        type,
                                        List.of(dimension),
                                        arrayElements
                                )
                        )
                )
        );

        function.setBody(body);
        final var method = symbolCreator.createMethod(function);
        function.setMethodSymbol(method);

        classDeclaration.enclosedElement(function);
        clazz.addEnclosedElement(method);
    }

    private void generateValuesMethod(final CClassDeclaration classDeclaration) {
        final var clazz = (ClassSymbol) classDeclaration.getClassSymbol();
        final var componentType = new CIdentifierTree(clazz.getSimpleName());
        componentType.setType(clazz.asType());

        final var arrayTypeTree = new CArrayTypeTree(componentType, List.of());
        arrayTypeTree.setType(types.getArrayType(componentType.getType()));

        final var valuesFieldIdentifier = new CIdentifierTree("$VALUES");
        valuesFieldIdentifier.setSymbol(
                findField(clazz, "$VALUES")
                        .orElseThrow(() -> new IllegalStateException("Missing $VALUES field"))
        );

        final var owner = new CIdentifierTree(clazz.getQualifiedName());
        owner.setType(clazz.asType());

        final var methodSelector = new CFieldAccessExpressionTree(
                new CFieldAccessExpressionTree(
                        owner,
                        valuesFieldIdentifier
                ),
                new CIdentifierTree("clone")
        );

        methodSelector.setType(arrayTypeTree.getType());

        final var methodInvocation = new CMethodInvocationTree(
                methodSelector,
                List.of(),
                List.of()
        );

        final var cloneMethod = withOwner(
                findMethod(clazz, "clone").orElseThrow(() -> new IllegalStateException("Missing clone method")),
                clazz
        );

        methodInvocation.setMethodType(cloneMethod.asType());

        final var statements = List.<StatementTree>of(
                new CReturnStatementTree(
                        new CCastExpressionTree(
                                arrayTypeTree,
                                methodInvocation
                        )
                )
        );

        final var body = new CBlockStatementTree(statements);

        final var function = new CFunction(
                "values",
                Kind.METHOD,
                new Modifiers(
                        Flags.PUBLIC + Flags.STATIC
                ),
                List.of(),
                null,
                List.of(),
                arrayTypeTree,
                List.of(),
                body,
                null
        );

        final var method = symbolCreator.createMethod(function);
        function.setMethodSymbol(method);
        classDeclaration.enclosedElement(function);

        clazz.addEnclosedElement(method);
    }

    private MethodSymbol withOwner(final MethodSymbol methodSymbol,
                                   final ClassSymbol classSymbol) {
        return methodSymbol.builder()
                .enclosingElement(classSymbol)
                .build();
    }

    private void generateValueOfMethod(final CClassDeclaration clazzDeclaration) {
        final var clazz = (ClassSymbol) clazzDeclaration.getClassSymbol();

        final var parameterType = new CIdentifierTree("String");
        parameterType.setType(SymbolTable.getInstance(compilerContext).getStringType());

        final var parameter = new CVariableDeclaratorTree(
                Kind.PARAMETER,
                new Modifiers(0),
                parameterType,
                new CIdentifierTree("name"),
                null,
                null
        );

        //parameter.getName().setSymbol(createSymbol(parameter));

        final var type = new CIdentifierTree(clazzDeclaration.getSimpleName());
        type.setType(clazz.asType());

        final var classLiteral = TreeMaker.classLiteralTree(type, -1, -1);

        classLiteral.getField()
                .setType(
                        loader.loadClass(
                                findModule(clazz),
                                Constants.CLAZZ
                        ).asType()
                );

        final var name = new CIdentifierTree("name");

        final var methodInvocation = new CMethodInvocationTree(
                new CIdentifierTree("valueOf"),
                List.of(),
                List.of(
                        classLiteral,
                        name
                )
        );

        final var enumClazz = SymbolTable.getInstance(compilerContext).getEnumType().asTypeElement();

        final var enumValueOfMethod = findMethod((ClassSymbol) enumClazz, "valueOf")
                .orElseThrow(() -> new IllegalStateException("Missing valueOf method"));


        methodInvocation.getMethodSelector().setType(enumValueOfMethod.asType().getOwner().asType());
        methodInvocation.setMethodType(enumValueOfMethod.asType());

        final var targetType = new CIdentifierTree(clazzDeclaration.getSimpleName());
        targetType.setType(clazz.asType());

        final var statements = List.<StatementTree>of(
                new CReturnStatementTree(
                        new CCastExpressionTree(
                                targetType,
                                methodInvocation
                        )
                )
        );

        final var body = new CBlockStatementTree(statements);

        final var returnType = new CIdentifierTree(clazzDeclaration.getSimpleName());
        returnType.setType(clazz.asType());

        final var function = new CFunction(
                "valueOf",
                Kind.METHOD,
                new Modifiers(
                        Flags.PUBLIC + Flags.STATIC
                ),
                List.of(),
                null,
                List.of(parameter),
                returnType,
                List.of(),
                body,
                null
        );

        final var method = symbolCreator.createMethod(function);
        function.setMethodSymbol(method);
        clazzDeclaration.enclosedElement(function);

        clazz.addEnclosedElement(method);
    }

    private ModuleElement findModule(final Element element) {
        if (element == null) {
            return null;
        } else if (element instanceof PackageSymbol packageSymbol) {
            return packageSymbol.getModuleSymbol();
        } else {
            return findModule(element.getEnclosingElement());
        }
    }
}