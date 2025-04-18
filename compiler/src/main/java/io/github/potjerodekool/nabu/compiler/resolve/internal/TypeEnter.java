package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.*;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.backend.lower.SymbolCreator;
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
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.compiler.tree.expression.*;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.FieldAccessExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CPrimitiveTypeTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ExpressionStatementTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.ReturnStatementTree;
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

    private final ClassSymbolLoader loader;
    private final Types types;
    private final SymbolTable symbolTable;
    private final SymbolCreator symbolCreator = new SymbolCreator();
    private final Map<ElementKind, Completer> typeEnterMap = new HashMap<>();

    public TypeEnter(final CompilerContextImpl compilerContext) {
        this.loader = compilerContext.getClassElementLoader();
        this.types = loader.getTypes();
        this.symbolTable = loader.getSymbolTable();
        this.fillTypeEnters(compilerContext.getElements());
    }

    private void fillTypeEnters(final Elements elements) {
        typeEnterMap.put(ElementKind.RECORD, new RecordTypeEnter(elements));
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

            final var typeEnter = this.typeEnterMap.get(currentClass.getKind());

            if (typeEnter != null) {
                typeEnter.complete(currentClass);
            }

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
                    packageSymbol.getMembers().elements().forEach(element -> {
                        compilationUnit.getNamedImportScope().define(element);
                    });
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
        final var packageSymbol = loader.getSymbolTable().lookupPackage(
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

        final var name = classDeclaration.getSimpleName();

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


        function.getParameters().forEach(it ->
                it.accept(this, currentClass));

        final var parameters = function.getParameters().stream()
                .map(param -> (VariableSymbol) param.getName().getSymbol())
                .toList();

        final var method = new MethodSymbolBuilderImpl()
                .kind(toElementKind(function.getKind()))
                .simpleName(function.getSimpleName())
                .enclosingElement(currentClass)
                .returnType(returnType)
                .parameters(parameters)
                .receiverType(receiverType)
                .flags(flags)
                .annotations(annotations)
                .build();

        currentClass.addEnclosedElement(method);
        function.setMethodSymbol(method);

        return function;
    }

    @Override
    public Void visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                 final ClassSymbol currentClass) {
        variableDeclaratorStatement.getType().accept(this, currentClass);

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
        final var type = variableDeclaratorTree.getType().getType();
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
                .map(constructor -> (CFunction) constructor)
                .forEach(constructor -> conditionalInvokeSuper(
                        classSymbol,
                        classDeclaration,
                        constructor
                ));
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
                .modifiers(new CModifiers(List.of(), accessFlags))
                .body(body)
                .build();

        classDeclaration.enclosedElement(constructor);
    }

    private List<VariableDeclaratorTree> createParameters(final ClassSymbol classSymbol,
                                                          final List<ExpressionTree> arguments) {
        return arguments.stream()
                .map(argument -> {
                    final var identifier = (IdentifierTree) argument;
                    final var param = new VariableDeclaratorTreeBuilder()
                            .type(createTypeTree(argument.getType()))
                            .kind(Kind.PARAMETER)
                            .name(identifier)
                            .build();
                    param.accept(this, classSymbol);
                    return param;
                }).toList();
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

    private void conditionalInvokeSuper(final ClassSymbol classSymbol,
                                        final ClassDeclaration classDeclaration,
                                        final CFunction constructor) {
        final var body = constructor.getBody();
        final var statements = new ArrayList<>(body.getStatements());
        final var statementCount = statements.size();

        final var addConstructorInvocation = statements.stream()
                .noneMatch(this::isConstructorInvocation);

        final var arguments = createConstructorArguments(classSymbol);
        addParametersToConstructorIfNeeded(classSymbol, constructor, arguments);

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
            classSymbol.removeEnclosedElement((Symbol) constructor.getMethodSymbol());
            constructor.setMethodSymbol(null);
        }

        final var method = symbolCreator.createMethod(constructor);
        constructor.setMethodSymbol(method);
        classSymbol.addEnclosedElement(method);
    }

    private void addParametersToConstructorIfNeeded(final ClassSymbol classSymbol,
                                                    final CFunction constructor,
                                                    final List<ExpressionTree> arguments) {
        if (shouldAddArgumentsAsConstructorParameter(classSymbol)) {
            constructor.addParameters(0, createParameters(classSymbol, arguments));
        }
    }

    private boolean shouldAddArgumentsAsConstructorParameter(final ClassSymbol classSymbol) {
        return classSymbol.getKind() == ElementKind.ENUM;
    }

    private List<ExpressionTree> createConstructorArguments(final ClassSymbol classSymbol) {
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
            arguments = List.of();
        }

        return arguments;
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
}
