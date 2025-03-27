package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.AbstractResolver;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.EnhancedForStatementTree;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.util.Pair;

import java.util.stream.Collectors;

public class Phase1Resolver extends AbstractResolver {

    private final ClassSymbolLoader classElementLoader;
    private final PhaseUtils phaseUtils;

    public Phase1Resolver(final CompilerContextImpl compilerContext) {
        super(compilerContext);
        this.classElementLoader = compilerContext.getClassElementLoader();
        this.phaseUtils = new PhaseUtils(classElementLoader.getTypes());
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit, final Scope scope) {
        final var globalScope = (GlobalScope) createScope(compilationUnit);

        if (compilationUnit.getModuleDeclaration() != null) {
            compilationUnit.getModuleDeclaration().accept(this, globalScope);
            final var module = compilationUnit.getModuleDeclaration().getModuleSymbol();
            ((CCompilationTreeUnit) compilationUnit).setModuleSymbol((ModuleSymbol) module);
        } else {
            final var module = classElementLoader.getSymbolTable().getUnnamedModule();
            ((CCompilationTreeUnit) compilationUnit).setModuleSymbol(module);
        }

        if (compilationUnit.getPackageDeclaration() != null) {
            compilationUnit.getPackageDeclaration().accept(this, globalScope);
        }

        compilationUnit.getImportItems().forEach(importItem -> importItem.accept(this, globalScope));
        classElementLoader.importJavaLang(compilationUnit.getNamedImportScope());
        compilationUnit.getClasses().forEach(classDeclaration -> classDeclaration.accept(this, globalScope));

        return null;
    }

    @Override
    public Object visitImportItem(final ImportItem importItem,
                                  final Scope scope) {
        final var isStatic = importItem.isStatic();
        final var isStarImport = importItem.isStarImport();
        final var classOrPackageName = importItem.getClassOrPackageName();

        if (isStatic) {
            if (isStarImport) {
                throw new TodoException();
            } else {
                throw new TodoException();
            }
        } else {
            if (isStarImport) {
                throw new TodoException();
            } else {
                //Single type import
                final var loader = compilerContext.getClassElementLoader();
                final var clazz = loader.loadClass(scope.findModuleElement(), classOrPackageName);

                if (clazz != null) {
                    scope.getCompilationUnit().getNamedImportScope().define(clazz);
                }
            }
        }

        return null;
    }

    @Override
    public Object visitPackageDeclaration(final PackageDeclaration packageDeclaration,
                                          final Scope scope) {
        final var packageElement = classElementLoader.findOrCreatePackage(
                scope.findModuleElement(),
                packageDeclaration.getQualifiedName()
        );
        scope.setPackageElement(packageElement);
        packageDeclaration.setPackageElement(packageElement);
        return null;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {

        final var annotations = classDeclaration.getModifiers().getAnnotations().stream()
                .map(annotationTree -> (AnnotationMirror) annotationTree.accept(this, scope))
                .toList();

        final var typeParameters = classDeclaration.getTypeParameters().stream()
                .map(it -> (TypeParameterElement) it.accept(this, scope))
                .toList();

        final var packageElement = (PackageSymbol) scope.getPackageElement();

        final var className = packageElement + "." + classDeclaration.getSimpleName();

        final var module = (ModuleSymbol) scope.findModuleElement();

        final var clazz = classElementLoader.getSymbolTable()
                .enterClass(
                        module,
                        className,
                        packageElement
                );
        clazz.setKind(toElementKind(classDeclaration.getKind()));
        clazz.setNestingKind(NestingKind.TOP_LEVEL);
        clazz.setFlags(classDeclaration.getModifiers().getFlags());
        clazz.setSimpleName(classDeclaration.getSimpleName());
        clazz.setEnclosingElement(packageElement);
        clazz.setAnnotations(annotations);

        final var typeArguments = typeParameters.stream()
                .map(it -> (AbstractType) it.asType())
                .toList();

        final var type = new CClassType(
                null,
                clazz,
                typeArguments);

        clazz.setType(type);

        classDeclaration.setClassSymbol(clazz);

        packageElement.getMembers().define(clazz);

        final var classScope = new SymbolScope(
                (DeclaredType) clazz.asType(),
                scope
        );
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

        var flags = function.getModifiers().getFlags();

        if (!Flags.hasAccessModifier(flags)) {
            flags += Flags.PUBLIC;
        }

        final var annotations = function.getModifiers().getAnnotations().stream()
                .map(it -> (AnnotationMirror) it.accept(this, scope))
                .toList();

        final TypeMirror receiverType;

        if (function.getReceiverParameter() != null) {
            function.getReceiverParameter().accept(this, scope);
            receiverType = function.getReceiverParameter().getType()
                    .getType();
        } else {
            receiverType = null;
        }

        final var method = new MethodSymbolBuilderImpl()
                .kind(toElementKind(function.getKind()))
                .name(function.getSimpleName())
                .enclosingElement(clazz)
                .returnType(returnType)
                .receiverType(receiverType)
                .flags(flags)
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
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement, final Scope scope) {
        super.visitVariableDeclaratorStatement(variableDeclaratorStatement, scope);

        final var clazz = (ClassSymbol) scope.getCurrentClass();
        final var symbol = phaseUtils.createVariable(variableDeclaratorStatement);
        variableDeclaratorStatement.getName().setSymbol(symbol);

        if (variableDeclaratorStatement.getKind() == Kind.FIELD) {
            clazz.addEnclosedElement(symbol);
        } else {
            scope.define(symbol);
        }

        return null;
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
    public Object visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement, final Scope scope) {
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
            case 0 -> loader.loadClass(
                    scope.findModuleElement(),
                    Constants.OBJECT
            ).asType();
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
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree, final Scope scope) {
        final var identifier = (IdentifierTree) assignmentExpressionTree.getLeft();
        final var name = identifier.getName();
        assignmentExpressionTree.getRight().accept(this, scope);
        final AnnotationValue annotationValue = toAnnotationValue(assignmentExpressionTree.getRight());

        final var executableElement = new MethodSymbolBuilderImpl()
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
            default -> null;
        };
    }

    @Override
    public Object visitNewArray(final NewArrayExpression newArrayExpression, final Scope scope) {
        newArrayExpression.getElements().forEach(e -> e.accept(this, scope));
        return null;
    }
}