package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.tree.expression.CTypeApply;
import io.github.potjerodekool.nabu.compiler.type.ClassType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

public class Phase1Resolver extends AbstractResolver {

    private final ClassElementLoader classElementLoader;

    public Phase1Resolver(final CompilerContext compilerContext) {
        super(compilerContext);
        this.classElementLoader = compilerContext.getClassElementLoader();
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        final var globalScope = new GlobalScope(compilationUnit);

        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, globalScope);
        }

        compilationUnit.getImportItems().forEach(importItem -> importItem.accept(this, globalScope));

        classElementLoader.importJavaLang(compilationUnit.getImportScope());

        compilationUnit.getClasses().forEach(classDeclaration -> classDeclaration.accept(this, globalScope));

        if (compilationUnit.getPackageDeclaration() != null) {
            final var packageDeclaration = compilationUnit.getPackageDeclaration();
            final var packageElement = packageDeclaration.getPackageElement();
            compilationUnit.setPackageElement(packageElement);

            compilationUnit.getClasses().forEach(classDeclaration -> {
                classDeclaration.enclosingElement(packageDeclaration);
                final var classSymbol = classDeclaration.classSymbol;
                classSymbol.setEnclosingElement(packageElement);
            });
        }

        return null;
    }

    @Override
    public Object visitImportItem(final ImportItem importItem,
                                  final Scope scope) {
        if (importItem instanceof SingleImportItem singleImportItem) {
            final var className = singleImportItem.getClassName();
            final var resolvedClass = classElementLoader.resolveClass(className);

            if (resolvedClass != null) {
                scope.getCompilationUnit().getImportScope().define(resolvedClass);
            }
        }

        return super.visitImportItem(importItem, scope);
    }

    @Override
    public Object visitPackageDeclaration(final CPackageDeclaration packageDeclaration,
                                          final Scope scope) {
        final var packageElement = classElementLoader.findOrCreatePackage(packageDeclaration.getPackageName());
        packageDeclaration.setPackageElement(packageElement);
        return null;
    }

    @Override
    public Object visitClass(final CClassDeclaration classDeclaration,
                             final Scope scope) {
        final var clazz = new ClassSymbol(
                ElementKind.CLASS,
                NestingKind.TOP_LEVEL,
                classDeclaration.getSimpleName(),
                null
        );
        clazz.setType(new MutableClassType(clazz));
        final var superType = classElementLoader.resolveType("java.lang.Object");
        clazz.setSuperType(superType);

        classDeclaration.classSymbol = clazz;

        final var classScope = new ClassScope(clazz, scope);
        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Object visitFunction(final CFunction function,
                                final Scope scope) {
        final var clazz = getClassSymbol(function);

        final var method = new MethodSymbol(
                toElementKind(function),
                function.getSimpleName(),
                clazz
        );
        //TODO For now all are public
        method.addModifier(Modifier.PUBLIC);
        clazz.addEnclosedElement(method);

        function.methodSymbol = method;

        final var functionScope = new FunctionScope(scope,
                !function.hasModifier(CModifier.STATIC)
                        ? method
                        : null
        );

        final var result = super.visitFunction(function, functionScope);

        method.getMethodType().setReturnType(function.getReturnType().getType());

        function.getParameters().stream()
                .map(CVariable::getVarSymbol)
                .forEach(method::addParameter);

        return result;
    }

    private ClassSymbol getClassSymbol(final CFunction function) {
        final var enclosingTree = (CClassDeclaration) function.getEnclosingElement();
        return enclosingTree.classSymbol;
    }

    private ElementKind toElementKind(final CFunction function) {
        return switch (function.getKind()) {
            case CONSTRUCTOR -> ElementKind.CONSTRUCTOR;
            case METHOD -> ElementKind.METHOD;
            default -> null;
        };
    }

    @Override
    public Object visitLambdaExpression(final CLambdaExpression lambdaExpression,
                                        final Scope scope) {
        lambdaExpression.getVariables().forEach(variable -> variable.accept(this, scope));
        final var lambdaScope = new LocalScope(scope);
        lambdaExpression.getBody().accept(this, lambdaScope);
        return null;
    }

    @Override
    public Object visitVariable(final CVariable variable,
                                final Scope scope) {
        final var varElement = new VariableElement(
                toElementKind(variable.getKind()),
                variable.getSimpleName(),
                null
        );
        scope.define(varElement);

        variable.getType().accept(this, scope);
        final var resolvedType = variable.getType().getType();

        varElement.setVariableType(resolvedType);
        variable.setVarSymbol(varElement);

        return null;
    }

    private ElementKind toElementKind(final CElement.Kind kind) {
        return switch (kind) {
            case PARAMETER -> ElementKind.PARAMETER;
            case LOCAL_VARIABLE -> ElementKind.VARIABLE;
            case CONSTRUCTOR -> ElementKind.CONSTRUCTOR;
            case METHOD -> ElementKind.METHOD;
        };
    }

    @Override
    public Object visitTypeIdentifier(final CTypeApply typeIdentifier,
                                      final Scope scope) {
        final var name = typeIdentifier.getName();
        final var resolver = new Resolver(classElementLoader, scope.getCompilationUnit().getImportScope());

        var type = resolver.resolveType(name);

        if (type == null) {
            type = classElementLoader.getTypes().getErrorType(name);
        }

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> typeParam.accept(this, scope))
                    .map(typeParam -> (CExpression) typeParam)
                    .map(CExpression::getType)
                    .toList();

            final var classType = (MutableClassType) type;
            typeParams.forEach(classType::addParameterType);
        }

        typeIdentifier.setType(type);

        return typeIdentifier;
    }

    @Override
    public Object visitFieldAccessExpression(final CFieldAccessExpression fieldAccessExpression,
                                             final Scope scope) {
        final var target = fieldAccessExpression.getTarget();
        target.accept(this, scope);

        final var varElement = getVariableElement(target);
        final var varType = varElement.getVariableType();
        final ClassType classType;

        if (varType instanceof ClassType ct) {
            classType = ct;
        } else {
            final var variableType = (VariableType) varType;
            classType = (ClassType) variableType.getInterferedType();
        }

        final var symbolScope = new ClassScope(
                (ClassSymbol) classType.asElement(),
                scope.getGlobalScope()
        );
        fieldAccessExpression.getField().accept(this, symbolScope);
        return null;
    }

    private VariableElement getVariableElement(final CExpression expression) {
        if (expression instanceof CFieldAccessExpression fieldAccessExpression) {
            return getVariableElement(fieldAccessExpression.getField());
        }

        return (VariableElement) expression.getSymbol();
    }

    @Override
    public Object visitPrimitiveType(final CPrimitiveType primitiveType,
                                     final Scope scope) {
        final var kind = toTypeMirrorKind(primitiveType.getKind());
        final var type = classElementLoader.getTypes().getPrimitiveType(kind);
        primitiveType.setType(type);
        return null;
    }

    private TypeKind toTypeMirrorKind(final CPrimitiveType.Kind kind) {
        return switch (kind) {
            case BOOLEAN -> TypeKind.BOOLEAN;
            case INT -> TypeKind.INT;
            case BYTE -> TypeKind.BYTE;
            case SHORT -> TypeKind.SHORT;
            case LONG -> TypeKind.LONG;
            case CHAR -> TypeKind.CHAR;
            case FLOAT -> TypeKind.FLOAT;
            case DOUBLE -> TypeKind.DOUBLE;
        };
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocation methodInvocation,
                                        final Scope scope) {
        final var target = methodInvocation.getTarget();

        if (target != null) {
            target.accept(this, scope);
        }

        methodInvocation.getArguments()
                .forEach(arg -> arg.accept(this, scope));

        return null;
    }

}

