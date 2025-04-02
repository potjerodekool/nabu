package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.internal.Flags;
import io.github.potjerodekool.nabu.compiler.resolve.TreeUtils;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.Function;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CPrimitiveTypeTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.PrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.util.CollectionUtils;
import io.github.potjerodekool.nabu.compiler.util.Elements;
import io.github.potjerodekool.nabu.compiler.util.Pair;
import io.github.potjerodekool.nabu.compiler.util.Types;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TypeEnter implements Completer, TreeVisitor<Object, ClassSymbol> {

    private final Map<ClassSymbol, ClassDeclaration> symbolToTreeMap = new HashMap<>();
    private final Map<Tree, CompilationUnit> treeToCompilationUnitMap = new HashMap<>();

    private final PhaseUtils phaseUtils;
    private final ClassSymbolLoader loader;
    private final Types types;
    private final Elements elements;
    private final SymbolTable symbolTable;

    public TypeEnter(final CompilerContextImpl compilerContext) {
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
        this.elements = compilerContext.getElements();
        this.symbolTable = loader.getSymbolTable();
        this.phaseUtils = new PhaseUtils(loader.getTypes());
    }

    public void put(final ClassSymbol symbol,
                    final ClassDeclaration tree,
                    final CompilationUnit compilationUnit) {
        this.symbolToTreeMap.put(symbol, tree);
        this.treeToCompilationUnitMap.put(tree, compilationUnit);
    }

    @Override
    public void complete(final Symbol symbol) throws CompleteException {
        symbol.setCompleter(Completer.NULL_COMPLETER);

        if (symbol instanceof ClassSymbol currentClass) {
            if (currentClass.getNestingKind() == NestingKind.TOP_LEVEL) {
                final var moduleElement = findModuleElement(currentClass);
                final var compilationUnit = findCompilationUnit(currentClass);
                fillImports(moduleElement, compilationUnit);
            }

            final var classDeclaration = this.symbolToTreeMap.get(symbol);

            if (currentClass.getMembers() == null) {
                currentClass.setMembers(new WritableScope());
            }

            classDeclaration.accept(this, currentClass);
            fillRecord(currentClass);

            completeClass(currentClass, classDeclaration);
        }
    }

    private void completeClass(final ClassSymbol currentClass,
                               final ClassDeclaration classDeclaration) {
        completeOwner(currentClass.getEnclosingElement());
        fillInSuper(currentClass, classDeclaration);
        generateConstructor(currentClass, classDeclaration);
    }

    private void completeOwner(final Symbol symbol) {
        if (symbol.getKind() != ElementKind.PACKAGE) {
            completeOwner(symbol.getEnclosingElement());
        }
        symbol.complete();
    }

    private void fillInSuper(final ClassSymbol currentClass,
                             final ClassDeclaration classDeclaration) {
        final TypeMirror superType;

        if (classDeclaration.getExtending() != null) {
            classDeclaration.getExtending().accept(this, currentClass);
            superType = classDeclaration.getExtending().getType();
        } else {
            final var superClassName = switch (currentClass.getKind()) {
                case RECORD -> Constants.RECORD;
                case ENUM -> Constants.ENUM;
                default -> Constants.OBJECT;
            };

            superType = loader.loadClass(findModuleElement(currentClass), superClassName).asType();
        }

        currentClass.setSuperClass(superType);
    }

    @Override
    public Void visitUnknown(final Tree tree,
                             final ClassSymbol currentClass) {
        return null;
    }

    private void fillImports(final ModuleElement moduleElement,
                             final CompilationUnit compilationUnit) {
        compilationUnit.getImportItems().stream()
                .filter(it -> !it.isStarImport())
                .forEach(importItem -> visitImportItem(importItem, moduleElement, compilationUnit));

        compilationUnit.getImportItems().stream()
                .filter(ImportItem::isStarImport)
                .forEach(importItem -> visitImportItem(importItem, moduleElement, compilationUnit));

        loader.importJavaLang(compilationUnit.getNamedImportScope());
    }

    private void visitImportItem(final ImportItem importItem,
                                 final ModuleElement moduleElement,
                                 final CompilationUnit compilationUnit) {
        final var isStatic = importItem.isStatic();
        final var isStarImport = importItem.isStarImport();
        final var classOrPackageName = importItem.getClassOrPackageName();

        if (isStatic) {
            if (isStarImport) {
                throw new TodoException("Implement static start import");
            } else {
                throw new TodoException("Implement static import");
            }
        } else {
            if (isStarImport) {
                importStarImport(moduleElement, classOrPackageName, compilationUnit);
            } else {
                importSingleImport(moduleElement, classOrPackageName, compilationUnit);
            }
        }
    }

    private void importSingleImport(final ModuleElement moduleElement,
                                    final String classOrPackageName,
                                    final CompilationUnit compilationUnit) {
        final var clazz = loader.loadClass(moduleElement, classOrPackageName);

        if (clazz != null) {
            compilationUnit.getNamedImportScope().define(clazz);
        }
    }

    private void importStarImport(final ModuleElement moduleElement,
                                  final String classOrPackageName,
                                  final CompilationUnit compilationUnit) {
        final var packageSymbol = loader.getSymbolTable().lookupPackage(
                (ModuleSymbol) moduleElement,
                classOrPackageName
        );

        if (packageSymbol != null) {
            final var scope = compilationUnit.getNamedImportScope();

            packageSymbol.getMembers().elements().stream()
                    .filter(Element::isType)
                    .forEach(scope::define);

        }
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final ClassSymbol currentClass) {
        final var annotations = classDeclaration.getModifiers().getAnnotations().stream()
                .map(annotationTree -> (AnnotationMirror) annotationTree.accept(this, currentClass))
                .toList();

        final var typeArguments = classDeclaration.getTypeParameters().stream()
                .map(it -> (TypeParameterElement) it.accept(this, currentClass))
                .map(it -> (AbstractType) it.asType())
                .toList();

        currentClass.setAnnotations(annotations);

        final var type = (CClassType) currentClass.asType();
        type.setTypeArguments(typeArguments);

        classDeclaration.getEnclosedElements().forEach(enclosedElement -> enclosedElement.accept(this, currentClass));
        return classDeclaration;
    }

    @Override
    public Object visitFunction(final Function function, final ClassSymbol currentClass) {
        function.getReturnType().accept(this, currentClass);

        final TypeMirror returnType;

        if (function.getKind() == Kind.CONSTRUCTOR) {
            returnType = currentClass.asType();
        } else {
            returnType = function.getReturnType().getType();
        }

        var flags = function.getModifiers().getFlags();

        if (!Flags.hasAccessModifier(flags)) {
            flags += Flags.PUBLIC;
        }

        final var annotations = function.getModifiers().getAnnotations().stream()
                .map(it -> (AnnotationMirror) it.accept(this, currentClass))
                .toList();

        final TypeMirror receiverType;

        if (function.getReceiverParameter() != null) {
            function.getReceiverParameter().accept(this, null);
            receiverType = function.getReceiverParameter().getType()
                    .getType();
        } else {
            receiverType = null;
        }

        final var method = new MethodSymbolBuilderImpl()
                .kind(toElementKind(function.getKind()))
                .name(function.getSimpleName())
                .enclosingElement(currentClass)
                .returnType(returnType)
                .receiverType(receiverType)
                .flags(flags)
                .annotations(annotations)
                .build();

        currentClass.addEnclosedElement(method);

        function.setMethodSymbol(method);

        function.getParameters().forEach(it ->
                it.accept(this, currentClass));

        function.getParameters().stream()
                .map(param -> (VariableSymbol) param.getName().getSymbol())
                .forEach(method::addParameter);

        return function;
    }

    @Override
    public Void visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                 final ClassSymbol currentClass) {
        variableDeclaratorStatement.getType().accept(this, currentClass);

        final var symbol = phaseUtils.createVariable(variableDeclaratorStatement);
        variableDeclaratorStatement.getName().setSymbol(symbol);

        if (symbol.getKind() == ElementKind.FIELD
                || symbol.getKind() == ElementKind.ENUM_CONSTANT) {
            currentClass.addEnclosedElement(symbol);
        }

        return null;
    }

    @Override
    public Void visitPrimitiveType(final PrimitiveTypeTree primitiveType, final ClassSymbol param) {
        if (primitiveType.getKind() == PrimitiveTypeTree.Kind.VOID) {
            primitiveType.setType(loader.getTypes().getNoType(TypeKind.VOID));
        } else {
            final var kind = toTypeMirrorKind(primitiveType.getKind());
            final var type = loader.getTypes().getPrimitiveType(kind);
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

    protected ElementKind toElementKind(final Kind kind) {
        return ElementKind.valueOf(kind.name());
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final ClassSymbol currentClass) {
        annotationTree.getName().accept(this, currentClass);
        final var annotationType = (DeclaredType) annotationTree.getName().getType();

        final var values = annotationTree.getArguments().stream()
                .map(it -> (Pair<ExecutableElement, AnnotationValue>) it.accept(this, currentClass))
                .collect(Collectors.toMap(
                        Pair::first,
                        Pair::second
                ));

        return AnnotationBuilder.createAnnotation(
                annotationType,
                values
        );
    }

    private CompilationUnit findCompilationUnit(final ClassSymbol classSymbol) {
        final var classTree = this.symbolToTreeMap.get(classSymbol);
        return this.treeToCompilationUnitMap.get(classTree);
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final ClassSymbol currentClass) {
        final var compilationUnit = findCompilationUnit(currentClass);
        final var type = compilationUnit.getScope()
                .resolveType(identifier.getName());

        resolveType(identifier.getName(), currentClass);

        if (type != null) {
            identifier.setType(type);
        }
        return identifier;
    }

    private TypeMirror resolveType(final String name,
                                   final ClassSymbol currentClass) {
        final var compilationUnit = findCompilationUnit(currentClass);
        var type = compilationUnit.getCompositeImportScope()
                .resolveType(name);

        if (type == null) {
            final var moduleElement = findModuleElement(currentClass);
            final var resolvedClass = loader.loadClass(
                    moduleElement,
                    name
            );

            if (resolvedClass != null) {
                type = resolvedClass.asType();
            }
        }

        return type;
    }

    private ModuleElement findModuleElement(final Element element) {
        if (element == null) {
            return null;
        }
        if (element instanceof PackageElement packageElement) {
            return packageElement.getModuleSymbol();
        } else {
            return findModuleElement(element.getEnclosingElement());
        }
    }

    @Override
    public Object visitTypeIdentifier(final TypeApplyTree typeIdentifier,
                                      final ClassSymbol currentClass) {
        final var clazz = typeIdentifier.getClazz();
        final var name = TreeUtils.getClassName(clazz);
        TypeMirror type = resolveType(name, currentClass);

        if (type == null) {
            type = loader.getTypes().getErrorType(name);
        }

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> typeParam.accept(this, currentClass))
                    .map(typeParam -> (ExpressionTree) typeParam)
                    .map(ExpressionTree::getType)
                    .toArray(TypeMirror[]::new);

            type = types.getDeclaredType(
                    type.asTypeElement(),
                    typeParams
            );
        }

        typeIdentifier.setType(type);

        return typeIdentifier;
    }

    @Override
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree,
                                  final ClassSymbol currentClass) {
        final var identifier = (IdentifierTree) assignmentExpressionTree.getLeft();
        final var name = identifier.getName();
        assignmentExpressionTree.getRight().accept(this, currentClass);
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
    public Object visitLiteralExpression(final LiteralExpressionTree literalExpression,
                                         final ClassSymbol classSymbol) {
        final var moduleElement = findModuleElement(classSymbol);

        final TypeMirror type = switch (literalExpression.getLiteralKind()) {
            case INTEGER -> types.getPrimitiveType(TypeKind.INT);
            case LONG -> types.getPrimitiveType(TypeKind.LONG);
            case BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case STRING -> loader.loadClass(moduleElement, Constants.STRING).asType();
            case NULL -> types.getNullType();
            case CLASS -> loader.loadClass(moduleElement, Constants.CLAZZ).asType();
            case BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);
            case CHAR -> types.getPrimitiveType(TypeKind.CHAR);
        };

        literalExpression.setType(type);

        return null;
    }

    @Override
    public Object visitTypeParameter(final TypeParameterTree typeParameterTree,
                                     final ClassSymbol currentClass) {
        var typeBound = typeParameterTree.getTypeBound().stream()
                .map(it -> (TypeMirror) it.accept(this, currentClass))
                .toList();

        final var upperBound = switch (typeBound.size()) {
            case 0 -> loader.loadClass(
                    findModuleElement(currentClass),
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

    private void fillRecord(final ClassSymbol currentClass) {
        if (currentClass.getKind() != ElementKind.RECORD) {
            return;
        }

        final var compactConstructorOptional = ElementFilter.constructorsIn(currentClass.getEnclosedElements()).stream()
                .filter(elements::isCompactConstructor)
                .findFirst();

        compactConstructorOptional.ifPresent(compactConstructor -> {
            addRecordComponents(compactConstructor, currentClass);
            addRecordFields(compactConstructor, currentClass);
            addRecordComponentAccessMethods(compactConstructor, currentClass);
        });
    }

    private void addRecordComponentAccessMethods(final ExecutableElement compactConstructor,
                                                 final ClassSymbol currentClass) {
        compactConstructor.getParameters().forEach(parameter -> {
            final var method = new MethodSymbolBuilderImpl()
                    .flags(Flags.PUBLIC)
                    .kind(ElementKind.METHOD)
                    .name(parameter.getSimpleName())
                    .returnType(parameter.asType())
                    .build();
            currentClass.addEnclosedElement(method);
        });
    }

    private void addRecordComponents(final ExecutableElement compactConstructor,
                                     final ClassSymbol currentClass) {
        final var recordComponents = compactConstructor.getParameters().stream()
                .map(param -> createRecordComponent(param, ElementKind.RECORD_COMPONENT))
                .toList();

        CollectionUtils.forEachIndexed(recordComponents, currentClass::addEnclosedElement);
    }

    private void addRecordFields(final ExecutableElement compactConstructor,
                                 final ClassSymbol currentClass) {
        final var fields = compactConstructor.getParameters().stream()
                .map(param -> createRecordComponent(
                        param,
                        ElementKind.FIELD
                ))
                .toList();
        //Place the fields after record components.
        final var offset = fields.size();

        CollectionUtils.forEachIndexed(fields, (index, field) ->
                currentClass.addEnclosedElement(offset + index, field));
    }

    private VariableSymbol createRecordComponent(final VariableElement parameter,
                                                 final ElementKind kind) {
        return new VariableSymbolBuilderImpl()
                .kind(kind)
                .type(parameter.asType())
                .name(parameter.getSimpleName())
                .build();
    }

    private void generateConstructor(final ClassSymbol classSymbol,
                                     final ClassDeclaration classDeclaration) {
        if (classSymbol.getKind() == ElementKind.INTERFACE) {
            return;
        }

        final var constructors = ElementFilter.constructorsIn(classSymbol.getMembers().elements());

        if (constructors.isEmpty()) {
            addConstructor(classSymbol, classDeclaration);
        }

        classDeclaration.getEnclosedElements().stream()
                .flatMap(CollectionUtils.mapOnly(Function.class))
                .filter(function -> function.getKind() == Kind.CONSTRUCTOR)
                .forEach(this::conditionalInvokeSuper);
    }

    private void addConstructor(final ClassSymbol classSymbol,
                                final ClassDeclaration classDeclaration) {
        final var superclass = classSymbol.getSuperclass();
        final List<ExpressionTree> arguments;
        final var superConstructor = findConstructor(superclass.asTypeElement());

        if (superclass == symbolTable.getObjectType()) {
            arguments = List.of();
        } else if (superConstructor != null) {
            arguments = superConstructor.getParameters().stream()
                    .map(parameter -> {
                        final var identifier = IdentifierTree.create(parameter.getSimpleName());
                        identifier.setType(parameter.asType());
                        return (ExpressionTree) identifier;
                    })
                    .toList();
        } else {
            return;
        }

        final var superCall = TreeMaker.methodInvocationTree(
                IdentifierTree.create(Constants.THIS),
                IdentifierTree.create(Constants.SUPER),
                List.of(),
                arguments,
                classDeclaration.getLineNumber(),
                classDeclaration.getColumnNumber()
        );

        final var statements = new ArrayList<StatementTree>();

        statements.add(TreeMaker.expressionStatement(superCall, superCall.getLineNumber(), superCall.getColumnNumber()));
        statements.add(TreeMaker.returnStatement(null, -1, -1));

        final var accessFlag = Flags.PUBLIC;

        final var body = TreeMaker.blockStatement(
                statements,
                -1,
                -1
        );

        final var parameterTrees = arguments.stream()
                .map(argument -> {
                    final var identifier = (IdentifierTree) argument;
                    final var param = new VariableDeclaratorTreeBuilder()
                            .type(createTypeTree(identifier.getType()))
                            .kind(Kind.PARAMETER)
                            .name(identifier)
                            .build();
                    param.accept(this, classSymbol);
                    return param;
                }).toList();

        final var parameters = parameterTrees.stream()
                .map(param -> (VariableSymbol) param.getName().getSymbol()).toList();


        final var constructor = new FunctionBuilder()
                .simpleName(Constants.INIT)
                .kind(Kind.CONSTRUCTOR)
                .returnType(TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.VOID, -1, -1))
                .modifiers(new CModifiers(List.of(), accessFlag))
                .parameters(parameterTrees)
                .body(body)
                .build();

        final var accessFlags =
                Constants.ENUM.equals(superclass.asTypeElement().getQualifiedName())
                        ? Flags.PRIVATE
                        : Flags.PUBLIC;

        final var constructorSymbol = new MethodSymbolBuilderImpl()
                .kind(ElementKind.CONSTRUCTOR)
                .name(Constants.INIT)
                .returnType(classSymbol.asType())
                .parameters(parameters)
                //.flags(accessFlags + Flags.SYNTHETIC)
                .flags(accessFlags)
                .enclosingElement(classSymbol)
                .build();

        constructor.setMethodSymbol(constructorSymbol);
        classDeclaration.enclosedElement(constructor);

        classSymbol.addEnclosedElement(constructorSymbol);
    }

    private ExecutableElement findConstructor(final TypeElement typeElement) {
        final var classSymbol = (ClassSymbol) typeElement;
        classSymbol.complete();
        final var constructors = ElementFilter.constructorsIn(classSymbol.getMembers().elements());

        if (Constants.OBJECT.equals(classSymbol.getQualifiedName())
                || Constants.ENUM.equals(classSymbol.getQualifiedName())) {
            return constructors.stream().findFirst()
                    .orElse(null);
        } else {
            return constructors.stream()
                    .filter(it -> it.asType().getParameterTypes().isEmpty())
                    .findFirst()
                    .orElse(null);
        }
    }

    private ExpressionTree createTypeTree(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            final var qualifiedName = declaredType.asTypeElement().getQualifiedName();
            final var names = qualifiedName.split("\\.");
            ExpressionTree expressionTree = IdentifierTree.create(names[0]);

            for (var index = 1; index < names.length; index++) {
                final var identifier = IdentifierTree.create(names[index]);
                expressionTree = new FieldAccessExpressionBuilder()
                        .target(expressionTree)
                        .field(identifier)
                        .build();
            }

            expressionTree.setType(declaredType);
            return expressionTree;
        } else if (typeMirror instanceof PrimitiveType primitiveType) {
            final var kind = switch (primitiveType.getKind()) {
                case VOID -> PrimitiveTypeTree.Kind.VOID;
                case BOOLEAN -> PrimitiveTypeTree.Kind.BOOLEAN;
                case CHAR -> PrimitiveTypeTree.Kind.CHAR;
                case BYTE -> PrimitiveTypeTree.Kind.BYTE;
                case SHORT -> PrimitiveTypeTree.Kind.SHORT;
                case INT -> PrimitiveTypeTree.Kind.INT;
                case FLOAT -> PrimitiveTypeTree.Kind.FLOAT;
                case LONG -> PrimitiveTypeTree.Kind.LONG;
                case DOUBLE -> PrimitiveTypeTree.Kind.DOUBLE;
                default -> null;
            };

            final var primitiveTypeTree = new CPrimitiveTypeTree(
                    kind,
                    -1,
                    -1
            );
            primitiveTypeTree.setType(primitiveType);
            return primitiveTypeTree;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private void conditionalInvokeSuper(final Function constructor) {
        final var body = constructor.getBody();
        final var newStatements = new ArrayList<StatementTree>();
        boolean hasConstructorInvocation = false;

        for (final var statement : body.getStatements()) {
            if (hasConstructorInvocation) {
                newStatements.add(statement);
            } else {
                if (!isConstructorInvocation(statement)) {
                    final var constructorInvocation = TreeMaker.methodInvocationTree(
                            null,
                            IdentifierTree.create(Constants.SUPER),
                            List.of(),
                            List.of(),
                            -1,
                            -1
                    );

                    newStatements.add(TreeMaker.expressionStatement(constructorInvocation, -1, -1));

                }
                hasConstructorInvocation = true;
                newStatements.add(statement);
            }
        }

        if (newStatements.size() > body.getStatements().size()) {
            final var newBody = body.builder()
                    .statements(newStatements)
                    .build();

            constructor.setBody(newBody);
        }
    }

    private boolean isConstructorInvocation(final StatementTree statement) {
        return statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree methodInvocationTree
                && isThisOrSuper(methodInvocationTree.getName());
    }

    private boolean isThisOrSuper(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            return Constants.THIS.equals(identifierTree.getName())
                    || Constants.SUPER.equals(identifierTree.getName());
        } else if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var target = fieldAccessExpressionTree.getTarget();

            if (!(target instanceof IdentifierTree identifierTree)) {
                return false;
            }

            if (!Constants.THIS.equals(identifierTree.getName())) {
                return false;
            }

            if (!(fieldAccessExpressionTree.getField() instanceof IdentifierTree field)) {
                return false;
            }

            return Constants.SUPER.equals(field.getName());
        }

        return false;
    }
}
