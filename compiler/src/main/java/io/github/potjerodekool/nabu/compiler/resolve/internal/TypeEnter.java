package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.*;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.compiler.backend.lower.SymbolCreator;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.builder.BinaryExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CPrimitiveTypeTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Elements;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;
import java.util.stream.Collectors;

public class TypeEnter implements Completer, TreeVisitor<Object, Scope> {

    private final Map<TypeElement, ClassDeclaration> symbolToTreeMap = new HashMap<>();
    private final Map<ClassDeclaration, CompilationUnit> treeToCompilationUnitMap = new HashMap<>();

    private final CompilerContext compilerContext;
    private final ClassElementLoader loader;
    private final Types types;
    private final SymbolTable symbolTable;
    private final SymbolCreator symbolCreator = new SymbolCreator();
    private final Map<ElementKind, Completer> typeEnterMap = new HashMap<>();

    public TypeEnter(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
        this.symbolTable = compilerContext.getSymbolTable();
        this.fillTypeEnters(compilerContext.getElements());
    }

    private void fillTypeEnters(final Elements elements) {
        typeEnterMap.put(ElementKind.RECORD, new RecordTypeEnter(elements));
    }

    public void put(final TypeElement symbol,
                    final ClassDeclaration tree,
                    final CompilationUnit compilationUnit) {
        this.symbolToTreeMap.put(symbol, tree);
        this.treeToCompilationUnitMap.put(tree, compilationUnit);
    }

    @Override
    public void complete(final Symbol symbol) throws CompleteException {
        symbol.setCompleter(Completer.NULL_COMPLETER);

        if (symbol instanceof ClassSymbol currentClass) {
            if (currentClass.getMembers() == null) {
                currentClass.setMembers(new WritableScope());
            }

            if (currentClass.getNestingKind() == NestingKind.TOP_LEVEL) {
                final var moduleElement = findModuleElement(currentClass);
                final var compilationUnit = findCompilationUnit(currentClass);
                fillImports(moduleElement, compilationUnit);
            }

            final var classDeclaration = this.symbolToTreeMap.get(symbol);

            final var compilationUnit = this.treeToCompilationUnitMap.get(classDeclaration);

            final var globalScope = new GlobalScope(
                    compilationUnit,
                    compilerContext
            );

            classDeclaration.accept(this, new SymbolScope(
                    (DeclaredType) currentClass.asType(),
                    globalScope
            ));

            final var typeEnter = this.typeEnterMap.get(currentClass.getKind());

            if (typeEnter != null) {
                typeEnter.complete(currentClass);
            }

            completeClass(classDeclaration, new SymbolScope((DeclaredType) currentClass.asType(), globalScope));
        }
    }

    private void completeClass(final ClassDeclaration classDeclaration,
                               final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        completeOwner(currentClass.getEnclosingElement());
        fillInSuper(scope, classDeclaration);
        fillInInterfaces(scope, classDeclaration);
        generateConstructor(classDeclaration, scope);
        initInstanceFields(classDeclaration);
    }

    private void completeOwner(final Symbol symbol) {
        if (symbol.getKind() != ElementKind.PACKAGE) {
            completeOwner(symbol.getEnclosingElement());
        }
        symbol.complete();
    }

    private void fillInSuper(final Scope scope,
                             final ClassDeclaration classDeclaration) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        if (Constants.OBJECT.equals(currentClass.getQualifiedName())) {
            return;
        }

        final TypeMirror superType;

        if (classDeclaration.getExtending() != null) {
            classDeclaration.getExtending().accept(this, scope);
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

    private void fillInInterfaces(final Scope scope,
                                  final ClassDeclaration classDeclaration) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        final List<ExpressionTree> interfaces = classDeclaration.getImplementing();

        if (!interfaces.isEmpty()) {
            final var interfaceTypes = interfaces.stream()
                    .map(it -> it.accept(this, scope))
                    .map(it -> (ExpressionTree) it)
                    .map(ExpressionTree::getType)
                    .toList();

            currentClass.setInterfaces(interfaceTypes);
        }
    }

    @Override
    public Void visitUnknown(final Tree tree,
                             final Scope currentClass) {
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

        final var packageDeclaration = compilationUnit.getPackageDeclaration();

        if (packageDeclaration != null) {
            final var enclosedElements = packageDeclaration.getPackageElement().getEnclosedElements();
            final var namedImportScope = compilationUnit.getNamedImportScope();
            enclosedElements.forEach(namedImportScope::define);
        }

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
                //import static java.util.List.*;
                final var elements = importStaticImport(moduleElement, classOrPackageName, compilationUnit);

                for (final var element : elements) {
                    compilationUnit.getNamedImportScope().define(element);
                }

            } else {
                //import static java.util.List.of;
                final var symbol = importStaticSingleImport(moduleElement, classOrPackageName, compilationUnit);

                if (symbol != null) {
                    compilationUnit.getNamedImportScope().define(symbol);
                    importItem.setSymbol(symbol);
                }
            }

        } else {
            if (isStarImport) {
                //import java.util.*;
                final var packageSymbol = importStarImport(moduleElement, classOrPackageName, compilationUnit);

                if (packageSymbol != null) {
                    packageSymbol.getMembers().elements().forEach(element ->
                            compilationUnit.getNamedImportScope().define(element));
                    importItem.setSymbol(packageSymbol);
                }

            } else {
                //import java.util.List;
                final var clazz = importSingleImport(moduleElement, classOrPackageName, compilationUnit);

                if (clazz != null) {
                    compilationUnit.getNamedImportScope().define(clazz);
                    importItem.setSymbol(clazz);
                }
            }


        }
    }

    private Symbol importStaticSingleImport(final ModuleElement moduleElement,
                                            final String classOrPackageName,
                                            final CompilationUnit compilationUnit) {
        final var sepIndex = classOrPackageName.lastIndexOf('.');
        final var className = classOrPackageName.substring(0, sepIndex);
        final var memberName = classOrPackageName.substring(sepIndex + 1);
        final var clazz = importSingleImport(moduleElement, className, compilationUnit);

        if (clazz != null) {
            final var symbol = clazz.getEnclosedElements().stream()
                    .filter(it -> it.getSimpleName().equals(memberName))
                    .findFirst()
                    .orElse(null);

            if (symbol != null) {
                compilationUnit.getNamedImportScope().define(symbol);
            }

            return symbol;
        }

        return null;
    }

    private List<? extends Element> importStaticImport(final ModuleElement moduleElement,
                                                       final String classOrPackageName,
                                                       final CompilationUnit compilationUnit) {
        var clazz = loader.loadClass(moduleElement, classOrPackageName);

        if (clazz == null) {
            return List.of();
        } else {
            final var enclosedElements = clazz.getEnclosedElements();

            for (final var enclosedElement : enclosedElements) {
                compilationUnit.getNamedImportScope().define(enclosedElement);
            }

            return enclosedElements;
        }

    }

    private ClassSymbol importSingleImport(final ModuleElement moduleElement,
                                           final String classOrPackageName,
                                           final CompilationUnit compilationUnit) {
        var clazz = loader.loadClass(moduleElement, classOrPackageName);

        if (clazz != null) {
            compilationUnit.getNamedImportScope().define(clazz);
        } else {
            clazz = loader.getTypes()
                    .getErrorType(classOrPackageName)
                    .asTypeElement();
        }

        return (ClassSymbol) clazz;
    }

    private PackageSymbol importStarImport(final ModuleElement moduleElement,
                                           final String classOrPackageName,
                                           final CompilationUnit compilationUnit) {
        final var packageSymbol = symbolTable.lookupPackage(
                (ModuleSymbol) moduleElement,
                classOrPackageName
        );

        if (packageSymbol != null) {
            final var scope = compilationUnit.getStartImportScope();

            packageSymbol.getMembers().elements().stream()
                    .filter(Element::isType)
                    .forEach(scope::define);
        }

        return packageSymbol;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        final var annotations = classDeclaration.getModifiers().getAnnotations().stream()
                .map(annotationTree -> (AnnotationMirror) annotationTree.accept(this, scope))
                .toList();

        final var typeArguments = classDeclaration.getTypeParameters().stream()
                .map(it -> (TypeParameterElement) it.accept(this, scope))
                .map(TypeParameterElement::asType)
                .toList();

        currentClass.setAnnotations(annotations);

        final var type = (CClassType) currentClass.asType();
        type.setTypeArguments(typeArguments);

        classDeclaration.getEnclosedElements().forEach(enclosedElement -> enclosedElement.accept(this, scope));
        return classDeclaration;
    }

    @Override
    public Object visitFunction(final Function function,
                                final Scope scope) {
        final var kind = toElementKind(function.getKind());

        var flags = function.getModifiers().getFlags();

        if (!Flags.hasAccessModifier(flags)) {
            flags += Flags.PUBLIC;
        }

        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        final TypeMirror receiverType;

        if (function.getReceiverParameter() != null) {
            function.getReceiverParameter().accept(this, null);
            receiverType = function.getReceiverParameter().getVariableType()
                    .getType();
        } else {
            receiverType = null;
        }

        final var method = new MethodSymbol(
                kind,
                flags,
                function.getSimpleName(),
                currentClass,
                receiverType,
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );

        final var functionScope = new FunctionScope(
                scope,
                method
        );

        function.getTypeParameters().forEach(typeParameter -> typeParameter.accept(this, functionScope));

        //TODO
        final var typeParameterElements = new ArrayList<TypeParameterElement>();

        final var annotations = function.getModifiers().getAnnotations().stream()
                .map(it -> (AnnotationMirror) it.accept(this, functionScope))
                .toList();

        final var thrownTypes = function.getThrownTypes().stream()
                .map(thrownType -> thrownType.accept(this, functionScope))
                .map(thrownType -> (ExpressionTree) thrownType)
                .map(ExpressionTree::getType)
                .toList();

        method.setThrownTypes(thrownTypes);
        method.setAnnotations(annotations);

        function.getReturnType().accept(this, functionScope);

        final TypeMirror returnType;

        if (function.getKind() == Kind.CONSTRUCTOR) {
            returnType = currentClass.asType();
        } else {
            returnType = function.getReturnType().getType();
        }

        method.setReturnType(returnType);

        function.getParameters().forEach(it ->
                it.accept(this, functionScope));

        final var parameters = function.getParameters().stream()
                .map(param -> (VariableSymbol) param.getName().getSymbol())
                .toList();

        parameters.forEach(method::addParameter);

        function.getThrownTypes()
                .forEach(thrownType -> thrownType.accept(this, functionScope));

        currentClass.addEnclosedElement(method);
        function.setMethodSymbol(method);

        return function;
    }

    private boolean isVarArgFunction(final Function function) {
        final var parameters = function.getParameters();

        if (parameters.isEmpty()) {
            return false;
        }

        return parameters.getLast().hasFlag(Flags.VARARGS);
    }


    @Override
    public Void visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                 final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        variableDeclaratorStatement.getVariableType().accept(this, scope);

        if (variableDeclaratorStatement.hasFlag(Flags.VARARGS)) {
            var arrayType = (CArrayType) variableDeclaratorStatement.getVariableType().getType();
            arrayType = arrayType.makeVarArg();
            variableDeclaratorStatement.getVariableType().setType(arrayType);
        }

        final var symbol = createVariable(variableDeclaratorStatement);
        variableDeclaratorStatement.getName().setSymbol(symbol);

        if (symbol.getKind() == ElementKind.FIELD
                || symbol.getKind() == ElementKind.ENUM_CONSTANT
                || symbol.getKind() == ElementKind.RECORD_COMPONENT) {
            currentClass.addEnclosedElement(symbol);
        }

        return null;
    }

    private VariableSymbol createVariable(final VariableDeclaratorTree variableDeclaratorTree) {
        final var type = variableDeclaratorTree.getVariableType().getType();
        final Object constantValue;

        if (Flags.hasFlag(variableDeclaratorTree.getFlags(), Flags.FINAL)) {
            constantValue = getConstantValue(variableDeclaratorTree.getValue());
        } else {
            constantValue = null;
        }

        return new VariableSymbolBuilderImpl()
                .kind(ElementKind.valueOf(variableDeclaratorTree.getKind().name()))
                .simpleName(variableDeclaratorTree.getName().getName())
                .type(type)
                .flags(variableDeclaratorTree.getFlags())
                .constantValue(constantValue)
                .build();
    }

    private Object getConstantValue(final Tree tree) {
        if (tree instanceof LiteralExpressionTree literalExpressionTree) {
            return switch (literalExpressionTree.getLiteralKind()) {
                case BYTE,
                     BOOLEAN,
                     CHAR,
                     DOUBLE,
                     FLOAT,
                     INTEGER,
                     LONG,
                     STRING,
                     SHORT -> literalExpressionTree.getLiteral();
                default -> null;
            };
        } else {
            return null;
        }
    }

    @Override
    public Void visitPrimitiveType(final PrimitiveTypeTree primitiveType, final Scope scope) {
        if (primitiveType.getKind() == PrimitiveTypeTree.Kind.VOID) {
            primitiveType.setType(loader.getTypes().getNoType(TypeKind.VOID));
        } else {
            final var kind = toTypeMirrorKind(primitiveType.getKind());
            final var type = loader.getTypes().getPrimitiveType(kind);
            primitiveType.setType(type);
        }

        return null;
    }

    @Override
    public Object visitArrayType(final ArrayTypeTree arrayTypeTree,
                                 final Scope scope) {
        arrayTypeTree.getComponentType().accept(this, scope);
        final var componentType = arrayTypeTree.getComponentType().getType();
        final var arrayType = types.getArrayType(componentType);
        arrayTypeTree.setType(arrayType);
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

    private CompilationUnit findCompilationUnit(final ClassSymbol classSymbol) {
        final var classTree = this.symbolToTreeMap.get(classSymbol);
        return this.treeToCompilationUnitMap.get(classTree);
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final Scope scope) {
        var type = scope.resolveType(identifier.getName());

        if (type == null) {
            final var currentClass = (ClassSymbol) scope.getCurrentClass();

            final var compilationUnit = findCompilationUnit(currentClass);
            type = compilationUnit.getScope()
                    .resolveType(identifier.getName());

            if (type == null) {
                type = resolveType(identifier.getName(), currentClass);
            }
        }

        if (type == null) {
            type = types.getErrorType(identifier.getName());
        }

        identifier.setType(type);
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
                                      final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();
        final var clazz = typeIdentifier.getClazz();
        final var name = TreeUtils.getClassName(clazz);
        DeclaredType type = (DeclaredType) resolveType(name, currentClass);

        if (type == null) {
            type = loader.getTypes().getErrorType(name);
        }

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> typeParam.accept(this, scope))
                    .map(typeParam -> (Tree) typeParam)
                    .map(Tree::getType)
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
    public Object visitTypeVariable(final TypeVariableTree typeVariableTree, final Scope param) {
        final var typeVariable = new CTypeVariable(typeVariableTree.getIdentifier().getName());
        typeVariableTree.setType(typeVariable);
        return typeVariableTree;
    }

    @Override
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree,
                                  final Scope scope) {
        final var identifier = (IdentifierTree) assignmentExpressionTree.getLeft();
        final var name = identifier.getName();
        assignmentExpressionTree.getRight().accept(this, scope);
        final AnnotationValue annotationValue = toAnnotationValue(assignmentExpressionTree.getRight());

        final var executableElement = new MethodSymbolBuilderImpl()
                .simpleName(name)
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
                final var type = (DeclaredType) fieldAccessExpressionTree.getSelected().getType();
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
                                         final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();
        final var moduleElement = findModuleElement(currentClass);

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
                                     final Scope scope) {
        final var currentClass = scope.getCurrentClass();
        final Symbol currentSymbol;

        if (scope instanceof FunctionScope functionScope) {
            currentSymbol = (Symbol) functionScope.getCurrentMethod();
        } else {
            currentSymbol = (Symbol) scope.getCurrentClass();
        }

        final var typeVariable = (CTypeVariable) types.getTypeVariable(
                typeParameterTree.getIdentifier().getName(),
                null,
                null
        );

        if (currentSymbol instanceof ClassSymbol) {
            final var classType = (CClassType) currentSymbol.asType();
            classType.addTypeArgument(typeVariable);
        } else if (currentSymbol instanceof MethodSymbol methodSymbol) {
            final var methodType = methodSymbol.asType();
            methodType.addTypeVariable(typeVariable);
        }

        var typeBound = typeParameterTree.getTypeBound().stream()
                .map(it -> (ExpressionTree) it.accept(this, scope))
                .map(ExpressionTree::getType)
                .toList();

        final var upperBound = switch (typeBound.size()) {
            case 0 -> loader.loadClass(
                    findModuleElement(currentClass),
                    Constants.OBJECT
            ).asType();
            case 1 -> typeBound.getFirst();
            default -> types.getIntersectionType(typeBound);
        };

        typeVariable.setUpperBound(upperBound);

        return typeVariable.asElement();
    }

    private void generateConstructor(final ClassDeclaration classDeclaration,
                                     final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        if (currentClass.getKind() == ElementKind.INTERFACE) {
            return;
        }

        final var constructors = ElementFilter.constructorsIn(currentClass.getMembers().elements());

        if (constructors.isEmpty()) {
            addConstructor(currentClass, classDeclaration);
        }

        TreeFilter.constructorsIn(classDeclaration.getEnclosedElements())
                .forEach(constructor -> conditionalInvokeSuper(classDeclaration, constructor, scope));
    }

    private void addConstructor(final ClassSymbol classSymbol,
                                final ClassDeclaration classDeclaration) {
        final var superclass = classSymbol.getSuperclass();
        final var statements = new ArrayList<StatementTree>();

        final var accessFlags =
                Constants.ENUM.equals(superclass.asTypeElement().getQualifiedName())
                        ? Flags.PRIVATE
                        : Flags.PUBLIC;

        final var body = TreeMaker.blockStatement(
                statements,
                -1,
                -1
        );

        final var returnType = TreeMaker.primitiveTypeTree(PrimitiveTypeTree.Kind.VOID, -1, -1);
        returnType.setType(types.getNoType(TypeKind.VOID));

        final var constructor = new FunctionBuilder()
                .simpleName(Constants.INIT)
                .kind(Kind.CONSTRUCTOR)
                .returnType(returnType)
                .modifiers(new Modifiers(List.of(), accessFlags))
                .body(body)
                .build();

        classDeclaration.enclosedElement(constructor);
    }

    private List<VariableDeclaratorTree> createParameters(final ClassSymbol classSymbol,
                                                          final Scope scope) {
        final var superclass = classSymbol.getSuperclass();
        final var superConstructor = findConstructor(superclass.asTypeElement());
        return superConstructor.getParameters().stream()
                .map(superParameter -> {
                    final var name = IdentifierTree.create(superParameter.getSimpleName());
                    return new VariableDeclaratorTreeBuilder()
                            .variableType(createTypeTree(superParameter.asType()))
                            .kind(Kind.PARAMETER)
                            .name(name)
                            .build();
                })
                .toList();
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
            return createDeclaredTypeTree(declaredType);
        } else if (typeMirror instanceof PrimitiveType primitiveType) {
            return createPrimitiveTypeTree(primitiveType);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    private ExpressionTree createDeclaredTypeTree(final DeclaredType declaredType) {
        final var qualifiedName = declaredType.asTypeElement().getQualifiedName();
        final var names = qualifiedName.split("\\.");
        ExpressionTree expressionTree = IdentifierTree.create(names[0]);

        for (var index = 1; index < names.length; index++) {
            final var identifier = IdentifierTree.create(names[index]);
            expressionTree = new FieldAccessExpressionBuilder()
                    .selected(expressionTree)
                    .field(identifier)
                    .build();
        }

        expressionTree.setType(declaredType);
        return expressionTree;
    }

    private ExpressionTree createPrimitiveTypeTree(final PrimitiveType primitiveType) {
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
    }

    private void conditionalInvokeSuper(final ClassDeclaration classDeclaration,
                                        final Function constructor,
                                        final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        final var body = constructor.getBody();

        if (body == null) {
            return;
        }

        final var statements = new ArrayList<>(body.getStatements());
        final var statementCount = statements.size();

        final var addConstructorInvocation = statements.stream()
                .noneMatch(this::isConstructorInvocation);

        //final var arguments = createConstructorArguments(currentClass);
        final var arguments = addParametersToConstructorIfNeeded(currentClass, constructor, scope);

        if (addConstructorInvocation) {
            final var superCall = TreeMaker.methodInvocationTree(
                    new CFieldAccessExpressionTree(
                            IdentifierTree.create(Constants.THIS),
                            IdentifierTree.create(Constants.SUPER)
                    ),
                    List.of(),
                    arguments,
                    classDeclaration.getLineNumber(),
                    classDeclaration.getColumnNumber()
            );

            statements.addFirst(TreeMaker.expressionStatement(superCall, superCall.getLineNumber(), superCall.getColumnNumber()));
        }

        final var lastStatement = statements.getLast();

        if (!(lastStatement instanceof ReturnStatementTree)) {
            statements.add(TreeMaker.returnStatement(null, -1, -1));
        }

        if (statements.size() > statementCount) {
            final var newBody = body.builder()
                    .statements(statements)
                    .build();
            constructor.setBody(newBody);
        }

        if (constructor.getMethodSymbol() != null) {
            currentClass.removeEnclosedElement((Symbol) constructor.getMethodSymbol());
            constructor.setMethodSymbol(null);
        }

        final var method = symbolCreator.createMethod(constructor);
        constructor.setMethodSymbol(method);
        currentClass.addEnclosedElement(method);
    }

    private List<ExpressionTree> addParametersToConstructorIfNeeded(final ClassSymbol classSymbol,
                                                                    final Function constructor,
                                                                    final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();
        if (shouldAddArgumentsAsConstructorParameter(currentClass)) {
            final var parameters = createParameters(classSymbol, scope);
            constructor.addParameters(0, parameters);
            return parameters.stream()
                    .map(parameter -> (ExpressionTree) IdentifierTree.create(parameter.getName().getName()))
                    .toList();
        } else {
            return Collections.emptyList();
        }
    }

    private boolean shouldAddArgumentsAsConstructorParameter(final ClassSymbol classSymbol) {
        return classSymbol.getKind() == ElementKind.ENUM;
    }

    private boolean isConstructorInvocation(final StatementTree statement) {
        return statement instanceof ExpressionStatementTree expressionStatement
                && expressionStatement.getExpression() instanceof MethodInvocationTree methodInvocationTree
                && isThisOrSuper(methodInvocationTree.getMethodSelector());
    }

    private boolean isThisOrSuper(final ExpressionTree expressionTree) {
        if (expressionTree instanceof IdentifierTree identifierTree) {
            return Constants.THIS.equals(identifierTree.getName())
                    || Constants.SUPER.equals(identifierTree.getName());
        } else if (expressionTree instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            final var selected = fieldAccessExpressionTree.getSelected();

            if (!(selected instanceof IdentifierTree identifierTree)) {
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

    private boolean isSuperExpression(final ExpressionTree expressionTree) {
        return switch (expressionTree) {
            case IdentifierTree identifierTree -> Constants.SUPER.equals(identifierTree.getName());
            case FieldAccessExpressionTree fieldAccessExpressionTree ->
                    isSuperExpression(fieldAccessExpressionTree.getField());
            case MethodInvocationTree methodInvocationTree ->
                    isSuperExpression(methodInvocationTree.getMethodSelector());
            case null, default -> false;
        };
    }

    private void initInstanceFields(final ClassDeclaration classDeclaration) {
        final var instanceFields = TreeFilter.fieldsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(field -> !field.hasFlag(Flags.STATIC))
                .filter(field -> field.getValue() != null)
                .filter(field -> !(field.getValue() instanceof LiteralExpressionTree))
                .toList();

        if (instanceFields.isEmpty()) {
            return;
        }

        TreeFilter.constructorsIn(classDeclaration.getEnclosedElements())
                .forEach(constructor -> {
                    final var statements = constructor.getBody().getStatements();
                    int invokeSuperIndex = -1;

                    for (var i = 0; invokeSuperIndex == -1 && i < statements.size(); i++) {
                        final var statement = statements.get(i);
                        if (statement instanceof ExpressionStatementTree expressionStatementTree
                                && isSuperExpression(expressionStatementTree.getExpression())) {
                            invokeSuperIndex = i;
                        }
                    }

                    if (invokeSuperIndex != -1) {
                        initInstanceFields(constructor, invokeSuperIndex, instanceFields);
                    }
                });
    }

    private void initInstanceFields(final Function constructor,
                                    final int invokeSuperIndex,
                                    final List<VariableDeclaratorTree> instanceFields) {
        final var initInstanceFields = instanceFields.stream()
                .map(field -> {
                    //this.myList = new ArrayList<>();

                    final var left = new FieldAccessExpressionBuilder()
                            .selected(IdentifierTree.create(Constants.THIS))
                            .field(IdentifierTree.create(field.getName().getName()))
                            .build();

                    final var expression = new BinaryExpressionBuilder()
                            .left(left)
                            .tag(Tag.ASSIGN)
                            .right((ExpressionTree) field.getValue())
                            .build();

                    return TreeMaker.expressionStatement(
                            expression,
                            field.getLineNumber(),
                            field.getColumnNumber());
                }).toList();

        final var bodyStatements = constructor.getBody().getStatements();
        bodyStatements.addAll(invokeSuperIndex + 1, initInstanceFields);
    }

    @Override
    public Object visitNewClass(final NewClassExpression newClassExpression, final Scope scope) {
        newClassExpression.getName().accept(this, scope);
        return null;
    }

    @Override
    public Object visitThrowStatement(final ThrowStatement throwStatement, final Scope scope) {
        throwStatement.getExpression().accept(this, scope);
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Scope scope) {
        ifStatementTree.getExpression().accept(this, scope);
        ifStatementTree.getThenStatement().accept(this, scope);
        if (ifStatementTree.getElseStatement() != null) {
            ifStatementTree.getElseStatement().accept(this, scope);
        }

        return null;
    }

    @Override
    public Object visitWildCardExpression(final WildcardExpressionTree wildCardExpression,
                                          final Scope scope) {
        if (wildCardExpression.getBoundKind() != BoundKind.UNBOUND) {
            wildCardExpression.getBound().accept(this, scope);
        }

        TypeMirror extendsBound = null;
        TypeMirror superBound = null;

        if (wildCardExpression.getBoundKind() == BoundKind.EXTENDS) {
            extendsBound = wildCardExpression.getBound().getType();
        } else if (wildCardExpression.getBoundKind() == BoundKind.SUPER) {
            superBound = wildCardExpression.getBound().getType();
        }

        final var wilcardType = types.getWildcardType(extendsBound, superBound);
        wildCardExpression.setType(wilcardType);

        return wildCardExpression;
    }

    public Object visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                             final Scope scope) {
        final var selected = fieldAccessExpression.getSelected();
        selected.accept(this, scope);

        final var varElement = TreeUtils.getSymbol(selected);

        if (varElement != null) {
            final var varType = varElement.asType();
            final DeclaredType declaredType = asDeclaredType(varType);
            final var symbolScope = new SymbolScope(
                    declaredType,
                    scope
            );
            fieldAccessExpression.getField().accept(this, symbolScope);
        } else if (selected.getType() != null) {
            final DeclaredType declaredType = asDeclaredType(selected.getType());
            final var classScope = new ClassScope(
                    declaredType,
                    null,
                    scope.getCompilationUnit(),
                    loader
            );
            fieldAccessExpression.getField().accept(this, classScope);
        }

        fieldAccessExpression.setType(fieldAccessExpression.getField().getType());
        return fieldAccessExpression;
    }

    private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return declaredType;
        } else {
            final var variableType = (VariableType) typeMirror;
            return (DeclaredType) variableType.getInterferedType();
        }
    }

}
