package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.Flags;
import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclarator;
import io.github.potjerodekool.nabu.compiler.tree.statement.EnhancedForStatement;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.util.Pair;

import java.util.HashSet;
import java.util.stream.Collectors;

public class Phase1Resolver extends AbstractResolver {

    private final ClassElementLoader classElementLoader;

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
            final var resolvedClass = classElementLoader.loadClass(className);

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
        scope.setPackageElement(packageElement);
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {

        final var annotations = classDeclaration.getModifiers().getAnnotations().stream()
                .map(annotationTree -> (AnnotationMirror) annotationTree.accept(this, scope))
                .toList();

        final var superType = classElementLoader.loadClass(Constants.OBJECT).asType();

        final var typeParameters = classDeclaration.getTypeParameters().stream()
                .map(it -> (TypeParameterElement) it.accept(this, scope))
                .toList();

        final var clazz = new ClassBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .modifiers(Flags.createModifiers(classDeclaration.getModifiers().getFlags()))
                .name(classDeclaration.getSimpleName())
                .enclosingElement(scope.getPackageElement())
                .superclass(superType)
                .typeParameters(typeParameters)
                .annotations(annotations)
                .build();

        classDeclaration.setClassSymbol(clazz);

        final var classScope = new SymbolScope((DeclaredType) clazz.asType(), scope);
        return super.visitClass(classDeclaration, classScope);
    }

    @Override
    public Object visitFunction(final Function function,
                                final Scope scope) {
        final var clazz = (ClassSymbol) scope.getCurrentClass();

        function.getReturnType().accept(this, new FunctionScope(scope, null));

        final TypeMirror returnType;

        if (function.getKind() == Kind.CONSTRUCTOR) {
            returnType = clazz.asType();
        } else {
            returnType = function.getReturnType().getType();
        }

        final var modifiers = new HashSet<>(Flags.createModifiers(function.getModifiers().getFlags()));

        if (!function.getModifiers()
                .hasAccessModifier()) {
            modifiers.add(Modifier.PUBLIC);
        }

        final var annotations = function.getModifiers().getAnnotations().stream()
                .map(it -> (AnnotationMirror) it.accept(this, scope))
                .toList();

        final var method = (MethodSymbol) new MethodBuilder()
                .kind(toElementKind(function))
                .name(function.getSimpleName())
                .enclosingElement(clazz)
                .returnType(returnType)
                .modifiers(modifiers)
                .annotations(annotations)
                .build();

        clazz.addEnclosedElement(method);

        function.setMethodSymbol(method);

        final var functionScope = new FunctionScope(scope,
                !function.hasFlag(Flags.STATIC)
                        ? method
                        : null
        );

        function.getParameters().forEach(it -> it.accept(this, functionScope));

        function.getParameters().stream()
                .map(param -> (VariableElement) param.getName().getSymbol())
                .forEach(method::addParameter);

        return null;
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclarator variableDeclaratorStatement, final Scope scope) {
        super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        if (variableDeclaratorStatement.getKind() == Kind.FIELD
                || variableDeclaratorStatement.getKind() == Kind.PARAMETER) {
            final var clazz = (ClassSymbol) scope.getCurrentClass();
            final var symbol = createVariable(variableDeclaratorStatement);
            variableDeclaratorStatement.getName().setSymbol(symbol);

            if (variableDeclaratorStatement.getKind() == Kind.FIELD) {
                clazz.addEnclosedElement(symbol);
            } else {
                scope.define(symbol);
            }
        }

        return null;
    }

    private ElementKind toElementKind(final Function function) {
        return switch (function.getKind()) {
            case CONSTRUCTOR -> ElementKind.CONSTRUCTOR;
            case METHOD -> ElementKind.METHOD;
            default -> null;
        };
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
            case VOID -> TypeKind.VOID;
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
            case 0 -> loader.loadClass(Constants.OBJECT).asType();
            case 1 -> typeBound.getFirst();
            default -> types.getIntersectionType(typeBound);
        };

        return types.getTypeVariable(
                typeParameterTree.getIdentifier().getName(),
                upperBound,
                null
        ).asElement();
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final Scope scope) {
        annotationTree.getName().accept(this, scope);
        final var annotationType = (DeclaredType) annotationTree.getName().getType();

        final var values = annotationTree.getArguments().stream()
                .map(it -> (Pair<ExecutableElement, AnnotationValue>) it.accept(this, scope))
                .collect(Collectors.toMap(
                        Pair::first,
                        Pair::second
                ));

        return AnnotationBuilder.createAnnotation(
                annotationType,
                values
        );
    }

    @Override
    public Object visitAssignment(final AssignmentExpression assignmentExpression, final Scope scope) {
        final var identifier = (IdentifierTree) assignmentExpression.getLeft();
        final var name = identifier.getName();
        assignmentExpression.getRight().accept(this, scope);
        final AnnotationValue annotationValue = toAnnotationValue(assignmentExpression.getRight());

        final var executableElement = new MethodBuilder()
                .name(name)
                .build();

        return new Pair<>(
                executableElement,
                annotationValue
        );
    }

    private AnnotationValue toAnnotationValue(final ExpressionTree expressionTree) {
        return switch (expressionTree) {
            case LiteralExpressionTree literalExpressionTree ->
                    AnnotationBuilder.createConstantValue(literalExpressionTree.getLiteral());
            case FieldAccessExpressionTree fieldAccessExpressionTree -> {
                final var type = (DeclaredType) fieldAccessExpressionTree.getTarget().getType();
                final var value = (VariableElement) fieldAccessExpressionTree.getField().getSymbol();
                yield AnnotationBuilder.createEnumValue(
                        type,
                        value
                );
            }
            case NewArrayExpression newArrayExpression -> {
                final var type = newArrayExpression.getType();
                final var values = newArrayExpression.getElements().stream()
                        .map(this::toAnnotationValue)
                        .toList();

                yield AnnotationBuilder.createArrayValue(type, values);
            }
            default -> throw new TodoException();
        };
    }

    @Override
    public Object visitNewArray(final NewArrayExpression newArrayExpression, final Scope scope) {
        newArrayExpression.getElements().forEach(e -> e.accept(this, scope));
        return null;
    }
}