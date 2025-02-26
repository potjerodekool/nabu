package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.element.Element;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.EnhancedForStatement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.VariableType;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class Phase1Resolver extends AbstractResolver {

    private final ClassElementLoader classElementLoader;
    private final Set<CModifier> accessModifiers = Set.of(CModifier.PUBLIC, CModifier.PROTECTED, CModifier.PRIVATE);

    public Phase1Resolver(final CompilerContext compilerContext) {
        super(compilerContext);
        this.classElementLoader = compilerContext.getClassElementLoader();
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit,
                                       final Scope scope) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);

        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, globalScope);
        }

        compilationUnit.getImportItems().forEach(importItem -> importItem.accept(this, globalScope));

        classElementLoader.importJavaLang(compilationUnit.getImportScope());

        compilationUnit.getClasses().forEach(classDeclaration -> classDeclaration.accept(this, globalScope));

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
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration,
                                          final Scope scope) {
        final var packageElement = classElementLoader.findOrCreatePackage(packageDeclaration.getPackageName());
        packageDeclaration.setPackageElement(packageElement);
        scope.setPackageElement(packageElement);
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var superType = classElementLoader.resolveClass(Constants.OBJECT).asType();

        final var typeParameters = classDeclaration.getTypeParameters().stream()
                .map(it -> (TypeParameterElement) it.accept(this, scope))
                .toList();

        final var clazz = new ClassBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .name(classDeclaration.getSimpleName())
                .enclosingElement(scope.getPackageElement())
                .superclass(superType)
                .typeParameters(typeParameters)
                .build();

        classDeclaration.classSymbol = clazz;

        final var classScope = new ClassScope(clazz.asType(), scope);
        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Object visitFunction(final Function function,
                                final Scope scope) {
        final var clazz = getClassSymbol(function);

        Objects.requireNonNull(function.getReturnType());

        function.getReturnType().accept(this, new FunctionScope(scope, null));

        final TypeMirror returnType;

        if (function.getKind() == Element.Kind.CONSTRUCTOR) {
            returnType = clazz.asType();
        } else {
            returnType = function.getReturnType().getType();
        }

        final var modifiers = new HashSet<>(toModifiers(function.getModifiers()));
        if (!hasAccessModifier(function)) {
            modifiers.add(Modifier.PUBLIC);
        }

        final var method = (MethodSymbol) new MethodBuilder()
                .kind(toElementKind(function))
                .name(function.getSimpleName())
                .enclosingElement(clazz)
                .returnType(returnType)
                .modifiers(modifiers)
                .build();

        clazz.addEnclosedElement(method);

        function.methodSymbol = method;

        final var functionScope = new FunctionScope(scope,
                !function.hasModifier(CModifier.STATIC)
                        ? method
                        : null
        );

        function.getParameters().forEach(it -> it.accept(this, functionScope));

        function.getParameters().stream()
                .map(Variable::getVarSymbol)
                .forEach(method::addParameter);

        //return result;
        return null;
    }

    private Set<Modifier> toModifiers(final Set<CModifier> modifiers) {
        return modifiers.stream()
                .map(this::toModifier)
                .collect(Collectors.toSet());
    }

    private Modifier toModifier(final CModifier modifier) {
        return switch (modifier) {
            case ABSTRACT -> Modifier.ABSTRACT;
            case PUBLIC -> Modifier.PUBLIC;
            case PROTECTED -> Modifier.PROTECTED;
            case PRIVATE -> Modifier.PRIVATE;
            case STATIC -> Modifier.STATIC;
            case FINAL -> Modifier.FINAL;
        };
    }

    private boolean hasAccessModifier(final Function function) {
        return function.getModifiers().stream()
                .anyMatch(accessModifiers::contains);
    }

    private TypeElement getClassSymbol(final Function function) {
        final var enclosingTree = (ClassDeclaration) function.getEnclosingElement();
        return enclosingTree.classSymbol;
    }

    private ElementKind toElementKind(final Function function) {
        return switch (function.getKind()) {
            case CONSTRUCTOR -> ElementKind.CONSTRUCTOR;
            case METHOD -> ElementKind.METHOD;
            default -> null;
        };
    }

    @Override
    public Object visitFieldAccessExpression(final FieldAccessExpressioTree fieldAccessExpression,
                                             final Scope scope) {
        final var target = fieldAccessExpression.getTarget();
        target.accept(this, scope);

        final var varElement = getVariableElement(target);

        if (varElement != null) {
            final var varType = varElement.asType();
            final DeclaredType classType;

            if (varType instanceof DeclaredType ct) {
                classType = ct;
            } else {
                final var variableType = (VariableType) varType;
                classType = (DeclaredType) variableType.getInterferedType();
            }

            final var symbolScope = new ClassScope(
                    classType,
                    scope.getGlobalScope()
            );
            fieldAccessExpression.getField().accept(this, symbolScope);
        }

        return null;
    }

    private VariableElement getVariableElement(final ExpressionTree expression) {
        if (expression instanceof FieldAccessExpressioTree fieldAccessExpression) {
            return getVariableElement(fieldAccessExpression.getField());
        }

        return (VariableElement) expression.getSymbol();
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveTypeTree primitiveType,
                                     final Scope scope) {
        if (primitiveType.getKind() == PrimitiveTypeTree.Kind.VOID) {
            primitiveType.setType(classElementLoader.getTypes().getNoType(TypeKind.VOID));
        } else {
            final var kind = toTypeMirrorKind(primitiveType.getKind());
            final var type = classElementLoader.getTypes().getPrimitiveType(kind);
            primitiveType.setType(type);
        }

        return null;
    }

    private TypeKind toTypeMirrorKind(final PrimitiveTypeTree.Kind kind) {
        return switch (kind) {
            case BOOLEAN -> TypeKind.BOOLEAN;
            case INT -> TypeKind.INT;
            case BYTE -> TypeKind.BYTE;
            case SHORT -> TypeKind.SHORT;
            case LONG -> TypeKind.LONG;
            case CHAR -> TypeKind.CHAR;
            case FLOAT -> TypeKind.FLOAT;
            case DOUBLE -> TypeKind.DOUBLE;
            case VOID ->  TypeKind.VOID;
        };
    }

    @Override
    public Object visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                        final Scope scope) {
        final var target = methodInvocation.getTarget();

        if (target != null) {
            target.accept(this, scope);
        }

        methodInvocation.getArguments()
                .forEach(arg -> arg.accept(this, scope));

        return null;
    }

    @Override
    public Object visitEnhancedForStatement(final EnhancedForStatement enhancedForStatement, final Scope scope) {
        enhancedForStatement.getExpression().accept(this, scope);

        if (enhancedForStatement.getLocalVariable().getType() instanceof VariableTypeTree variableTypeTree) {
            final var symbol = enhancedForStatement.getExpression().getSymbol();
            final var expressionType = (DeclaredType) symbol.asType();
            final var type = expressionType.getTypeArguments().getFirst();
            variableTypeTree.setType(type);
        }

        enhancedForStatement.getLocalVariable().accept(this, scope);
        enhancedForStatement.getStatement().accept(this, scope);
        return null;
    }

    @Override
    public Object visitTypeParameter(final TypeParameterTree typeParameterTree, final Scope scope) {
        var typeBound = typeParameterTree.getTypeBound().stream()
                .map(it -> (TypeMirror) it.accept(this, scope))
                .toList();

        final var upperBound = switch (typeBound.size()) {
            case 0 -> loader.resolveClass(Constants.OBJECT).asType();
            case 1 -> typeBound.getFirst();
            default -> types.getIntersectionType(typeBound);
        };

        return types.getTypeVariable(
                typeParameterTree.getIdentifier().getName(),
                upperBound,
                null
        ).asElement();
    }
}

