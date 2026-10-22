package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.CompleteException;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Completer;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.lang.model.element.builder.AnnotationBuilder;
import io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.Lombok;
import io.github.potjerodekool.nabu.compiler.type.impl.CArrayType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.*;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.backend.lower.SymbolCreator;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
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

public class TypeEnter extends AbstractTreeVisitor<Object, Scope> implements Completer, TreeVisitor<Object, Scope> {

    private final Map<TypeElement, ClassDeclaration> symbolToTreeMap = new HashMap<>();
    private final Map<ClassDeclaration, CompilationUnit> treeToCompilationUnitMap = new HashMap<>();

    private final CompilerContext compilerContext;
    private final ClassElementLoader loader;
    private final Types types;
    private final SymbolTable symbolTable;
    private final SymbolCreator symbolCreator = new SymbolCreator();
    private final Map<ElementKind, Completer> typeEnterMap = new HashMap<>();
    private final Lombok lombok;

    public TypeEnter(final CompilerContext compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
        final var contextImpl = (CompilerContextImpl) compilerContext;
        this.symbolTable = contextImpl.getSymbolTable();
        this.fillTypeEnters(compilerContext.getElements());
        this.lombok = new Lombok(contextImpl);
    }

    private void fillTypeEnters(final Elements elements) {
        typeEnterMap.put(ElementKind.RECORD, new RecordTypeEnter(elements));
    }

    public boolean isSourceEntered(final String qualifiedName) {
        return symbolToTreeMap.keySet().stream()
                .anyMatch(symbol -> symbol instanceof ClassSymbol classSymbol
                        && classSymbol.getQualifiedName().toString().equals(qualifiedName));
    }

    public TypeMirror getStringType() {
        return symbolTable.getStringType();
    }

    public ClassSymbol findSourceSymbol(final String qualifiedName) {
        return symbolToTreeMap.keySet().stream()
                .filter(symbol -> symbol instanceof ClassSymbol classSymbol
                        && classSymbol.getQualifiedName().toString().equals(qualifiedName))
                .map(symbol -> (ClassSymbol) symbol)
                .findFirst()
                .orElse(null);
    }

    public ClassDeclaration getSourceTree(final TypeElement symbol) {
        return symbolToTreeMap.get(symbol);
    }

    public CompilationUnit getCompilationUnit(final ClassDeclaration tree) {
        return treeToCompilationUnitMap.get(tree);
    }

    private TypeMirror findMemberTypeInTopLevelClass(final ClassSymbol currentClass,
                                                     final String name) {
        if (currentClass == null) {
            // Annotatie op een top-level klasse: opgelost in de scope van de
            // compilatie-eenheid, zonder huidige klasse.
            return null;
        }
        ClassSymbol topLevel = currentClass;
        while (topLevel.getEnclosingElement() instanceof ClassSymbol enclosingSymbol) {
            topLevel = enclosingSymbol;
        }

        return searchMemberTypes(topLevel, name);
    }

    private TypeMirror searchMemberTypes(final ClassSymbol type,
                                         final String name) {
        final var members = type.getEnclosedElements();
        if (members == null) {
            return null;
        }

        for (final var member : members) {
            if (member instanceof ClassSymbol memberSymbol
                    && (memberSymbol.getKind().isClass()
                    || memberSymbol.getKind().isInterface()
                    || memberSymbol.getKind() == ElementKind.ENUM
                    || memberSymbol.getKind() == ElementKind.ANNOTATION_TYPE)) {
                if (memberSymbol.getSimpleName().contentEquals(name)) {
                    return memberSymbol.asType();
                }
                final var nested = searchMemberTypes(memberSymbol, name);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
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

        if (!(symbol instanceof ClassSymbol currentClass)) {
            completeImpl(symbol, null);
            return;
        }

        final var depthState = completeDepth.get();
        depthState.depth++;
        if (depthState.depth > 200) {
            throw new IllegalStateException("complete recursion too deep: depth=" + depthState.depth
                    + " source=" + currentClass.getSourceFile());
        }
        final var count = depthState.counts.merge(currentClass, 1, Integer::sum);
        depthState.history.addLast(currentClass + " @" + System.identityHashCode(currentClass)
                + " src=" + currentClass.getSourceFile());
        try {
            if (count > 20) {
                throw new IllegalStateException("complete-cyclus: " + currentClass
                        + " source=" + currentClass.getSourceFile()
                        + " completesPerClass=" + count);
            }
            completeImpl(symbol, currentClass);
        } catch (java.lang.StackOverflowError so) {
            throw new IllegalStateException("StackOverflowError in complete: depth=" + depthState.depth
                    + " history=" + depthState.history
                    + "\n" + so, so);
        } finally {
            depthState.history.removeLast();
            depthState.counts.remove(currentClass);
            depthState.depth--;
        }
    }

    private static final ThreadLocal<DepthState> completeDepth =
            ThreadLocal.withInitial(DepthState::new);

    private static final class DepthState {
        int depth;
        final java.util.IdentityHashMap<Symbol, Integer> counts = new java.util.IdentityHashMap<>();
        final java.util.ArrayDeque<String> history = new java.util.ArrayDeque<>();
    }

    private void completeImpl(final Symbol symbol,
                              final ClassSymbol currentClass) {
            if (currentClass.getMembers() == null) {
                currentClass.setMembers(new WritableScope());
            }

            if (currentClass.getNestingKind() == NestingKind.TOP_LEVEL) {
                final var moduleElement = findModuleElement(currentClass);
                final var compilationUnit = findCompilationUnit(currentClass);

                if (compilationUnit != null) {
                    final var packageDeclaration = compilationUnit.getPackageDeclaration();
                    if (packageDeclaration != null && packageDeclaration.getPackageElement() == null) {
                        packageDeclaration.setPackageElement((PackageElement) currentClass.getEnclosingElement());
                    }

                    fillImports(moduleElement, compilationUnit);
                }
            }

            final var classDeclaration = this.symbolToTreeMap.get(symbol);

            final var compilationUnit = this.treeToCompilationUnitMap.get(classDeclaration);

            final var globalScope = new GlobalScope(
                    compilationUnit,
                    compilerContext
            );

            if (classDeclaration != null) {
                acceptTree(
                        classDeclaration,
                        new SymbolScope(
                                (DeclaredType) currentClass.asType(),
                                globalScope
                        )
                );
            }

            final var typeEnter = this.typeEnterMap.get(currentClass.getKind());

            if (typeEnter != null) {
                typeEnter.complete(currentClass);
            }

            if (classDeclaration != null) {
                completeClass(classDeclaration, new SymbolScope((DeclaredType) currentClass.asType(), globalScope));

                lombok(classDeclaration, globalScope);
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

    private void lombok(final ClassDeclaration classDeclaration,
                        final Scope scope) {
        final var file = scope.getCompilationUnit().getFileObject();
        if (".java".equals(file.getKind().extension())) {
            lombok.apply(classDeclaration);
        }
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

        TypeMirror superType;

        if (classDeclaration.getExtending() != null) {
            completeEnclosingTypes(currentClass, scope);
            acceptTree(classDeclaration.getExtending(), scope);
            superType = classDeclaration.getExtending().getType();

            superType = reResolveNestedSuperClass(currentClass, classDeclaration, superType);
        } else {
            final var superClassName = switch (currentClass.getKind()) {
                case RECORD -> Constants.RECORD;
                case ENUM -> Constants.ENUM;
                default -> Constants.OBJECT;
            };

            superType = loader.loadClass(findModuleElement(currentClass), superClassName).asType();
        }

        superType = canonicalizeDeclaredType(superType);

        if (Constants.ENUM.equals(superType.asTypeElement().getQualifiedName())) {
            superType = compilerContext.getTypes().getDeclaredType(
                    superType.asTypeElement(),
                    currentClass.asType()
            );
        }

        currentClass.setSuperClass(superType);
    }

    private void completeEnclosingTypes(final TypeElement currentClass,
                                        final Scope scope) {
        Element enclosing = currentClass.getEnclosingElement();

        while (enclosing instanceof ClassSymbol classSymbol) {
            classSymbol.complete();
            enclosing = classSymbol.getEnclosingElement();
        }
    }

        private TypeMirror reResolveNestedSuperClass(final ClassSymbol currentClass,
                                                 final ClassDeclaration classDeclaration,
                                                 final TypeMirror superType) {
        if (!(superType instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType)) {
            return superType;
        }

        final var element = declaredType.asElement();

        if (element instanceof ClassSymbol classSymbol
                && (classSymbol.getSourceFile() != null || classSymbol.getClassFile() != null)
                && !classSymbol.getEnclosedElements().isEmpty()) {
            return superType;
        }

        final var name = nameOfExpression(classDeclaration.getExtending());

        if (name == null || name.isBlank()) {
            return superType;
        }

        final var nestedName = String.join("$", name.split("\\."));

        final var parts = nestedName.split("\\$");

        final var anchor = parts[0];

        ClassSymbol anchorSymbol = null;

        for (Element ancestor = currentClass.getEnclosingElement();
             ancestor != null && !(ancestor instanceof io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol) && !(ancestor instanceof io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol);
             ancestor = ancestor.getEnclosingElement()) {
            if (!(ancestor instanceof ClassSymbol typeElement)) {
                continue;
            }

            if (containsTypeMember(typeElement, anchor)) {
                anchorSymbol = typeElement;
                break;
            }
        }

        if (anchorSymbol == null) {
            return superType;
        }

        final var targetName = anchorSymbol.getQualifiedName() + "$" + nestedName;

        final var realSymbol = symbolToTreeMap.entrySet().stream()
                .filter(entry -> entry.getKey() instanceof ClassSymbol classSymbol
                        && classSymbol.getQualifiedName().contentEquals(targetName))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);

        if (realSymbol != null) {
            // Typeargumenten behouden (zie canonicalizeDeclaredType). Het
            // eerder opgeloste type is hier een fouttype zonder argumenten
            // (de geneste naam was nog niet vindbaar); de argumenten staan
            // dan alleen nog op de TypeApply-boom:
            // `class Builder extends ArgSpec.Builder<Builder>`.
            List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
            if (typeArguments.isEmpty()
                    && classDeclaration.getExtending() instanceof io.github.potjerodekool.nabu.tree.expression.TypeApplyTree typeApply
                    && typeApply.getTypeParameters() != null) {
                typeArguments = typeApply.getTypeParameters().stream()
                        .map(Tree::getType)
                        .filter(Objects::nonNull)
                        .toList();
                if (typeArguments.size() != typeApply.getTypeParameters().size()) {
                    typeArguments = List.of();
                }
            }
            if (!typeArguments.isEmpty()) {
                return compilerContext.getTypes().getDeclaredType(
                        (ClassSymbol) realSymbol,
                        typeArguments.toArray(new TypeMirror[0]));
            }
            return ((ClassSymbol) realSymbol).asType();
        }

        return superType;
    }

    private boolean containsTypeMember(final ClassSymbol classSymbol, final String simpleName) {
        final var it = classSymbol.getMembers().getSymbolsByName(simpleName).iterator();

        while (it.hasNext()) {
            if (it.next() instanceof ClassSymbol) {
                return true;
            }
        }

        return false;
    }

    private String nameOfExpression(final ExpressionTree expressionTree) {
        if (expressionTree instanceof io.github.potjerodekool.nabu.tree.expression.TypeApplyTree typeApply) {
            return nameOfExpression(typeApply.getClazz());
        }

        if (expressionTree instanceof io.github.potjerodekool.nabu.tree.expression.IdentifierTree identifierTree) {
            return identifierTree.getName();
        }

        if (expressionTree instanceof io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree fieldAccess) {
            final var selected = nameOfExpression(fieldAccess.getSelected());
            return selected == null
                    ? fieldAccess.getField().getName()
                    : selected + "." + fieldAccess.getField().getName();
        }

        return null;
    }

    private void fillInInterfaces(final Scope scope,
                                  final ClassDeclaration classDeclaration) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        final List<ExpressionTree> interfaces = classDeclaration.getImplementing();

        if (!interfaces.isEmpty()) {
            final var interfaceTypes = interfaces.stream()
                    .map(it -> acceptTree(it, scope))
                    .map(it -> (ExpressionTree) it)
                    .map(ExpressionTree::getType)
                    .map(this::canonicalizeDeclaredType)
                    .toList();

            currentClass.setInterfaces(interfaceTypes);
        }
    }

    private TypeMirror canonicalizeDeclaredType(final TypeMirror type) {
        final var element = type.asTypeElement();

        if (!(element instanceof ClassSymbol classSymbol)) {
            return type;
        }

        final var canonical = SymbolTable.getInstance((CompilerContextImpl) compilerContext)
                .findClasses(classSymbol.getFlatName()).stream()
                .filter(c -> !c.isError())
                .findFirst()
                .orElse(null);

        if (canonical == null || canonical == classSymbol) {
            return type;
        }

        // De typeargumenten behouden: `class Pos extends Base<Pos>` werd anders
        // de generieke declaratie Base<T>, en een self-type builder
        // (T label() in Base<T extends Base<T>>) leverde dan T i.p.v. Pos op.
        if (type instanceof io.github.potjerodekool.nabu.type.DeclaredType declaredType
                && !declaredType.getTypeArguments().isEmpty()) {
            return compilerContext.getTypes().getDeclaredType(
                    canonical,
                    declaredType.getTypeArguments().toArray(new TypeMirror[0]));
        }
        return canonical.asType();
    }

    @Override
    public Void visitUnknown(final Tree tree,
                             final Scope currentClass) {
        return null;
    }

    private final java.util.Set<CompilationUnit> importsFilledUnits =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Vult de importscopes van een compilatie-eenheid vroegtijdig (vÃ³Ã³r de
     * resolutie), onafhankelijk van lazy klassymbol-completion. Idempotent.
     */
    public void fillImportsForUnit(final CompilationUnit compilationUnit) {
        if (compilationUnit == null || !importsFilledUnits.add(compilationUnit)) {
            return;
        }

        var module = compilationUnit instanceof io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit unit
                ? unit.getModuleElement()
                : null;

        if (module == null) {
            module = symbolTable.getUnnamedModule();
        }

        fillImports(module, compilationUnit);
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
            final var packageElement = packageDeclaration.getPackageElement();

            if (packageElement != null) {
                final var enclosedElements = packageElement.getEnclosedElements();
                final var namedImportScope = compilationUnit.getNamedImportScope();
                enclosedElements.forEach(namedImportScope::define);
            }
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

        if (clazz == null && classOrPackageName.contains(".")) {
            // Puntnotatie voor geneste klassen ("a.b.Outer.Inner") wordt via
            // de platte naam ("a.b.Outer$Inner") gezocht.
            final var parts = classOrPackageName.split("\\.");

            for (var splitIndex = 1; splitIndex < parts.length && clazz == null; splitIndex++) {
                final var pkg = String.join(".", java.util.Arrays.copyOfRange(parts, 0, splitIndex));
                final var nestedName = String.join("$", java.util.Arrays.copyOfRange(parts, splitIndex, parts.length));

                clazz = loader.loadClass(moduleElement, pkg + "." + nestedName);
            }
        }

        if (clazz != null) {
            compilationUnit.getNamedImportScope().define(clazz);
        } else {
            clazz = compilerContext.getTypes()
                    .getErrorType(classOrPackageName)
                    .asTypeElement();
        }

        return (ClassSymbol) clazz;
    }

    private PackageElement importStarImport(final ModuleElement moduleElement,
                                           final String classOrPackageName,
                                           final CompilationUnit compilationUnit) {
        // Voltooi (en scan indien nodig) het pakket â€” bijv. java.util.*.
        final var packageSymbol = symbolTable.lookupPackage(
                moduleElement,
                classOrPackageName
        );

        if (packageSymbol != null && packageSymbol.exists()
                && !packageSymbol.getMembers().elements().isEmpty()) {
            final var scope = compilationUnit.getStartImportScope();

            packageSymbol.getMembers().elements().stream()
                    .filter(Element::isType)
                    .forEach(scope::define);

            return packageSymbol;
        }

        // Anders kan een star-import een klasse betreffen
        // (bijv. import picocli.CommandLine.Model.*): definieer dan de
        // geneste typen van die klasse in de start-importscope. Eenvoudige
        // namen zonder echte binding (stub zoals een pseudo-'util'-klasse)
        // worden verworpen.
        final var containerClass = loadNestedClass(moduleElement, classOrPackageName);

        if (containerClass instanceof io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol classSymbol
                && (classSymbol.getSourceFile() != null
                || classSymbol.getClassFile() != null
                || (classSymbol.getMembers() != null && !classSymbol.getMembers().elements().isEmpty()))) {
            final var scope = compilationUnit.getStartImportScope();

            ElementFilter.elements(
                            containerClass,
                            element ->
                                    element.getKind().isClass()
                                            || element.getKind().isInterface()
                                            || element.getKind() == ElementKind.ENUM
                                            || element.getKind() == ElementKind.ANNOTATION_TYPE,
                            TypeElement.class
                    ).forEach(scope::define);

            return null;
        }

        return packageSymbol;
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        final var annotations = classDeclaration.getModifiers().getAnnotations().stream()
                .map(annotationTree -> (AnnotationMirror) acceptTree(annotationTree, annotationScope(scope, currentClass)))
                .toList();

        final var typeArguments = classDeclaration.getTypeParameters().stream()
                .map(it -> (TypeParameterElement) acceptTree(it, scope))
                .map(TypeParameterElement::asType)
                .toList();

        currentClass.setAnnotations(annotations);

        final var type = (CClassType) currentClass.asType();
        type.setTypeArguments(typeArguments);

        // Snapshot: de walk hieronder kan de enclosed-elements lijst van
        // classDeclaration muteren; zonder snapshot levert dat een
        // ConcurrentModificationException op.
        final var enclosedElements = new ArrayList<>(classDeclaration.getEnclosedElements());

        enclosedElements.stream()
                .filter(enclosedElement -> !(enclosedElement instanceof ClassDeclaration))
                .forEach(enclosedElement -> acceptTree(enclosedElement, scope));

        return classDeclaration;
    }

    private Scope annotationScope(final Scope scope,
                                  final TypeElement currentClass) {
        final var enclosingElement = currentClass.getEnclosingElement();

        if (enclosingElement instanceof TypeElement enclosingType) {
            return new SymbolScope(
                    (DeclaredType) enclosingType.asType(),
                    scope.getGlobalScope()
            );
        }

        return scope.getGlobalScope() != null
                ? scope.getGlobalScope()
                : scope;
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
            acceptTree(function.getReceiverParameter(), null);
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

        function.getTypeParameters().forEach(typeParameter -> acceptTree(typeParameter, functionScope));

        final var annotations = function.getModifiers().getAnnotations().stream()
                .map(it -> (AnnotationMirror) acceptTree(it, functionScope))
                .toList();

        final var thrownTypes = function.getThrownTypes().stream()
                .map(thrownType -> acceptTree(thrownType, functionScope))
                .map(thrownType -> (ExpressionTree) thrownType)
                .map(ExpressionTree::getType)
                .toList();

        method.setThrownTypes(thrownTypes);
        method.setAnnotations(annotations);

        acceptTree(function.getReturnType(), functionScope);

        final TypeMirror returnType;

        if (function.getKind() == Kind.CONSTRUCTOR) {
            returnType = currentClass.asType();
        } else {
            returnType = function.getReturnType().getType();
        }

        method.setReturnType(returnType);

        // Standaardwaarde van een annotatie-element (AnnotationDefault),
        // zoals een element-waarde in een annotatie (zie visitAssignment).
        if (function.getDefaultValue() != null) {
            acceptTree(function.getDefaultValue(), functionScope);
            final var defaultValue = toAnnotationValue(function.getDefaultValue(), functionScope);
            if (defaultValue != null) {
                method.setDefaultValue(defaultValue);
            }
        }

        function.getParameters().forEach(it ->
                acceptTree(it, functionScope));

        final var parameters = function.getParameters().stream()
                .map(param -> (VariableSymbol) param.getName().getSymbol())
                .toList();

        parameters.forEach(method::addParameter);

        function.getThrownTypes()
                .forEach(thrownType -> acceptTree(thrownType, functionScope));

        final var existingMethod = currentClass.getEnclosedElements().stream()
                .filter(element -> element instanceof ExecutableElement)
                .map(element -> (ExecutableElement) element)
                .filter(element -> element.getSimpleName().equals(method.getSimpleName()))
                .filter(element -> element.getKind() == method.getKind())
                .filter(element -> element.getParameters().size() == method.getParameters().size())
                .filter(element -> {
                    final var existingParams = element.getParameters().stream()
                            .map(Element::asType)
                            .toList();
                    final var newParams = method.getParameters().stream()
                            .map(Element::asType)
                            .toList();
                    for (var i = 0; i < existingParams.size(); i++) {
                        if (!types.isSameType(existingParams.get(i), newParams.get(i))) {
                            return false;
                        }
                    }
                    return true;
                })
                .findFirst();

        if (existingMethod.isPresent()) {
            function.setMethodSymbol(existingMethod.get());
            return function;
        }

        currentClass.addEnclosedElement(method);
        function.setMethodSymbol(method);

        return function;
    }


    @Override
    public Void visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                 final Scope scope) {
        final var currentClass = (ClassSymbol) scope.getCurrentClass();

        acceptTree(variableDeclaratorStatement.getVariableType(), scope);

        if (variableDeclaratorStatement.hasFlag(Flags.VARARGS)) {
            var arrayType = (CArrayType) variableDeclaratorStatement.getVariableType().getType();
            arrayType = arrayType.makeVarArg();
            variableDeclaratorStatement.getVariableType().setType(arrayType);
        }

        final var annotations = variableDeclaratorStatement.getAnnotations().stream()
                .map(annotationTree -> (AnnotationMirror) acceptTree(annotationTree, scope))
                .toList();

        final var name = variableDeclaratorStatement.getName().getName();
        final var variableKind = ElementKind.valueOf(variableDeclaratorStatement.getKind().name());
        final var isClassMember = variableKind == ElementKind.FIELD
                || variableKind == ElementKind.ENUM_CONSTANT
                || variableKind == ElementKind.RECORD_COMPONENT;

        if (isClassMember) {
            final var existing = currentClass.getEnclosedElements().stream()
                    .filter(element -> element instanceof VariableSymbol)
                    .filter(element -> element.getKind() == variableKind)
                    .filter(element -> element.getSimpleName().equals(name))
                    .findFirst();

            if (existing.isPresent()) {
                variableDeclaratorStatement.getName().setSymbol((VariableSymbol) existing.get());
                return null;
            }
        }

        final var symbol = createVariable(variableDeclaratorStatement);
        symbol.setAnnotations(annotations);
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

    private ClassDeclaration sourceDeclaration(final TypeElement typeElement) {
        final var qualifiedName = typeElement.getQualifiedName();
        return symbolToTreeMap.entrySet().stream()
                .filter(entry -> entry.getKey().getQualifiedName().equals(qualifiedName))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private boolean isSourceEnumConstant(final TypeElement typeElement, final String name) {
        final var declaration = sourceDeclaration(typeElement);
        if (declaration == null || declaration.getKind() != Kind.ENUM) {
            return false;
        }
        for (final var member : declaration.getEnclosedElements()) {
            if (member instanceof VariableDeclaratorTree constant
                    && constant.getKind() == Kind.ENUM_CONSTANT
                    && constant.getName().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private Object sourceConstantValue(final TypeElement typeElement, final String fieldName) {
        final var declaration = sourceDeclaration(typeElement);
        if (declaration == null) {
            return null;
        }
        for (final var member : declaration.getEnclosedElements()) {
            if (member instanceof VariableDeclaratorTree field
                    && field.getName().getName().equals(fieldName)
                    && Flags.hasFlag(field.getFlags(), Flags.FINAL)) {
                return getConstantValue(field.getValue());
            }
        }
        return null;
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
            primitiveType.setType(compilerContext.getTypes().getNoType(TypeKind.VOID));
        } else {
            final var kind = toTypeMirrorKind(primitiveType.getKind());
            final var type = compilerContext.getTypes().getPrimitiveType(kind);
            primitiveType.setType(type);
        }

        return null;
    }

    @Override
    public Object visitArrayType(final ArrayTypeTree arrayTypeTree,
                                 final Scope scope) {
        acceptTree(arrayTypeTree.getComponentType(), scope);
        TypeMirror componentType = arrayTypeTree.getComponentType().getType();
        // Elke dimensie voegt een array-type toe: `Text[][]` is
        // getArrayType(getArrayType(Text)). Eén keer wrappen verliest de
        // dimensie (descriptor `[LText;` i.p.v. `[[LText;`).
        final var dimensions = arrayTypeTree.getDimensions();
        final int dimensionCount = (dimensions == null || dimensions.isEmpty())
                ? 1
                : dimensions.size();
        for (int i = 0; i < dimensionCount; i++) {
            componentType = types.getArrayType(componentType);
        }
        arrayTypeTree.setType(componentType);
        return arrayTypeTree;
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
        if (kind == Kind.ANNOTATION) {
            return ElementKind.ANNOTATION_TYPE;
        }

        return ElementKind.valueOf(kind.name());
    }

    @Override
    public Object visitAnnotation(final AnnotationTree annotationTree, final Scope scope) {
        acceptTree(annotationTree.getName(), scope);
        final var annotationType = (DeclaredType) annotationTree.getName().getType();

        final var valuePairs = new ArrayList<Pair<ExecutableElement, AnnotationValue>>();
        for (final var argument : annotationTree.getArguments()) {
            final var value = acceptTree(argument, scope);
            if (value != null) {
                valuePairs.add((Pair<ExecutableElement, AnnotationValue>) value);
            }
        }
        final var values = valuePairs.stream()
                .filter(pair -> pair.second() != null)
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
        final var currentClass = (ClassSymbol) scope.getCurrentClass();
        var type = scope.resolveType(identifier.getName());

        if (type == null) {
            final var compilationUnit = findCompilationUnit(currentClass);

            if (compilationUnit != null) {
                type = compilationUnit.getScope()
                        .resolveType(identifier.getName());
            }

            if (type == null) {
                type = resolveType(identifier.getName(), currentClass);
            }
        }

        if (type == null && identifier.getName().indexOf('.') > 0) {
            type = resolveNestedTypeName(identifier.getName(), scope, currentClass);
        }

        if (type == null) {
            type = types.getErrorType(identifier.getName());
        }

        identifier.setType(type);
        return identifier;
    }

    /**
     * Een gestippelde naam waarvan het eerste segment een type is en de rest
     * geneste membertypes ({@code @Qa.Tag} als annotatie op klasse Qa zelf).
     */
    private TypeMirror resolveNestedTypeName(final String name,
                                             final Scope scope,
                                             final ClassSymbol currentClass) {
        final String[] parts = name.split("\\.");
        TypeMirror type = scope.resolveType(parts[0]);
        if (type == null) {
            type = resolveType(parts[0], currentClass);
        }
        for (int i = 1; i < parts.length && type != null; i++) {
            final var element = type.asTypeElement();
            if (element == null) {
                return null;
            }
            final String memberName = parts[i];
            type = ElementFilter.elements(
                            element,
                            member -> member.getKind().isClass()
                                    || member.getKind().isInterface()
                                    || member.getKind() == ElementKind.ENUM
                                    || member.getKind() == ElementKind.ANNOTATION_TYPE,
                            TypeElement.class
                    ).stream()
                    .filter(member -> member.getSimpleName().contentEquals(memberName))
                    .map(TypeElement::asType)
                    .findFirst()
                    .orElse(null);
        }
        return type;
    }

    private TypeMirror resolveType(final String name,
                                   final ClassSymbol currentClass) {
        // Member types van de huidige klasse en enclosing klassen (met hun
        // superklassen) hebben voorrang: bijv. 'CommandSpec' binnen
        // CommandLine$Model$CommandSpec$Builder.
        var enclosingClass = currentClass;        while (enclosingClass != null) {
            final var enclosingSimpleName = enclosingClass.getSimpleName();

            if (enclosingSimpleName != null && name.equals(enclosingSimpleName.toString())) {
                return enclosingClass.asType();
            }

            var ancestor = enclosingClass;

            while (ancestor != null) {
                final var memberTypeOptional = ElementFilter.elements(
                                ancestor,
                                element ->
                                        element.getKind().isClass()
                                                || element.getKind().isInterface()
                                                || element.getKind() == ElementKind.ENUM
                                                || element.getKind() == ElementKind.ANNOTATION_TYPE,
                                TypeElement.class
                        ).stream()
                        .filter(elem -> elem.getSimpleName().contentEquals(name))
                        .findFirst();

                if (memberTypeOptional.isPresent()) {
                    return memberTypeOptional.get().asType();
                }

                final var superclass = ancestor.getSuperclass();

                if (superclass instanceof io.github.potjerodekool.nabu.type.DeclaredType superType) {
                    final var superElement = superType.asElement();

                    if (superElement instanceof ClassSymbol superSymbol) {
                        ancestor = superSymbol;
                    } else {
                        ancestor = null;
                    }
                } else {
                    ancestor = null;
                }
            }

            final var outerClass = enclosingClass.getEnclosingElement();

            if (outerClass instanceof ClassSymbol enclosingSymbol) {
                enclosingClass = enclosingSymbol;
            } else {
                enclosingClass = null;
            }
        }

        final var declarationScopeType = findMemberTypeInTopLevelClass(currentClass, name);
        if (declarationScopeType != null) {
            return declarationScopeType;
        }

        final var compilationUnit = findCompilationUnit(currentClass);

        TypeMirror type = null;

        if (compilationUnit != null) {
            type = compilationUnit.getCompositeImportScope()
                    .resolveType(name);
        }

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

    private TypeElement loadNestedClass(final ModuleElement moduleElement,
                                        final String fullName) {
        var clazz = loader.loadClass(moduleElement, fullName);

        if (clazz != null) {
            return clazz;
        }

        // Puntnotatie voor geneste klassen ("a.b.Outer.Inner") wordt via de
        // platte naam ("a.b.Outer$Inner") gezocht.
        final var parts = fullName.split("\\.");

        for (var splitIndex = 1; splitIndex < parts.length; splitIndex++) {
            final var pkg = String.join(".", java.util.Arrays.copyOfRange(parts, 0, splitIndex));
            final var nestedName = String.join("$", java.util.Arrays.copyOfRange(parts, splitIndex, parts.length));

            final var candidate = loader.loadClass(moduleElement, pkg + "." + nestedName);

            if (candidate != null) {
                return candidate;
            }
        }

        return null;
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
            type = compilerContext.getTypes().getErrorType(name);
        }

        unflagResolvedPlaceholder(type);

        if (typeIdentifier.getTypeParameters() != null) {
            final var typeParams = typeIdentifier.getTypeParameters().stream()
                    .map(typeParam -> acceptTree(typeParam, scope))
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

    private static void unflagResolvedPlaceholder(final DeclaredType type) {
        final var element = type != null ? type.asTypeElement() : null;
        final var isErrorType = type instanceof ErrorType;

        if (element instanceof Symbol symbol
                && symbol.isError()
                && !isErrorType) {
            // The symbol was created as an error placeholder earlier and
            // reclaimed, but the stale error flag was still propagated to
            // the resolved type. Now that resolution succeeded on the real
            // source/declared class, the flag must not keep poisoning it.
            symbol.setError(false);
        }
    }

    @Override
    public Object visitTypeVariable(final TypeVariableTree typeVariableTree,
                                    final Scope param) {
        final var typeVariable = new CTypeVariable(typeVariableTree.getIdentifier().getName());
        typeVariableTree.setType(typeVariable);
        return typeVariableTree;
    }

    @Override
    public Object visitAssignment(final AssignmentExpressionTree assignmentExpressionTree,
                                  final Scope scope) {
        final var identifier = (IdentifierTree) assignmentExpressionTree.getLeft();
        final var name = identifier.getName();
        acceptTree(assignmentExpressionTree.getRight(), scope);
        final AnnotationValue annotationValue = toAnnotationValue(assignmentExpressionTree.getRight(), scope);

        final var executableElement = new MethodSymbolBuilderImpl()
                .simpleName(name)
                .build();

        return new Pair<>(
                executableElement,
                annotationValue
        );
    }

    private AnnotationValue toAnnotationValue(final ExpressionTree expressionTree, final Scope scope) {
        return switch (expressionTree) {
            // A nested annotation (`tag = @Tag("t")`, also as an array
            // element): it was dropped, and an array of them held nulls.
            case AnnotationTree annotationTree -> (AnnotationValue) acceptTree(annotationTree, scope);
            case LiteralExpressionTree literalExpressionTree ->
                    AnnotationBuilder.createConstantValue(literalExpressionTree.getLiteral());
            case FieldAccessExpressionTree fieldAccessExpressionTree -> {
                final var type = (DeclaredType) fieldAccessExpressionTree.getSelected().getType();
                var value = (VariableElement) fieldAccessExpressionTree.getField().getSymbol();
                // Een enumconstante (`RetentionPolicy.RUNTIME`) waarvan het
                // veldsymbool hier nog niet is opgelost: zelf opzoeken. Anders
                // werd het een klasse-waarde, gold @Retention niet en waren
                // picocli's annotaties tijdens runtime onzichtbaar.
                if (value == null
                        && !"class".equals(fieldAccessExpressionTree.getField().getName())
                        && type != null
                        && type.asTypeElement() != null) {
                    final var fieldName = fieldAccessExpressionTree.getField().getName();
                    value = type.asTypeElement().getEnclosedElements().stream()
                            .filter(element -> element.getKind() == ElementKind.ENUM_CONSTANT
                                    && element.getSimpleName().equals(fieldName))
                            .map(element -> (VariableElement) element)
                            .findFirst()
                            .orElse(null);
                    if (value == null && isSourceEnumConstant(type.asTypeElement(), fieldName)) {
                        // Enum uit broncode die nog niet is ingevoerd: alleen
                        // naam en type zijn nodig voor de annotatiewaarde.
                        value = new VariableSymbolBuilderImpl()
                                .kind(ElementKind.ENUM_CONSTANT)
                                .simpleName(fieldName)
                                .type(type)
                                .build();
                    }
                    if (value == null) {
                        // Een constante (`default CommandSpec.DEFAULT_COMMAND_NAME`):
                        // haar waarde, niet een klasse-waarde ("Invalid default").
                        var constant = type.asTypeElement().getEnclosedElements().stream()
                                .filter(element -> element.getKind() == ElementKind.FIELD
                                        && element.getSimpleName().equals(fieldName))
                                .map(element -> ((VariableElement) element).getConstantValue())
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse(null);
                        if (constant == null) {
                            // Klasse uit broncode die nog niet is ingevoerd:
                            // de literal-initializer van het final veld.
                            constant = sourceConstantValue(type.asTypeElement(), fieldName);
                        }
                        if (constant != null) {
                            yield AnnotationBuilder.createConstantValue(constant);
                        }
                    }
                }

                if (value != null) {
                    yield AnnotationBuilder.createEnumValue(
                            type,
                            value
                    );
                } else {
                    yield AnnotationBuilder.createClassAttribute(fieldAccessExpressionTree.getSelected().getType());
                }
            }
            case NewArrayExpression newArrayExpression -> {
                final var type = newArrayExpression.getType();
                // The elements resolved first (`{Level.HIGH, Level.LOW}`): an
                // enum constant without a type became a class value "null".
                final var values = newArrayExpression.getElements().stream()
                        .map(element -> {
                            if (!(element instanceof AnnotationTree)) {
                                acceptTree(element, scope);
                            }
                            return toAnnotationValue(element, scope);
                        })
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
            // The symbol's own list too: getTypeParameters() was empty for a
            // generic source method, and its Signature attribute named type
            // variables it did not declare (TypeNotPresentException).
            if (typeVariable.asElement() instanceof io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement element) {
                methodSymbol.addTypeParameter(element);
            }
        }

        var typeBound = typeParameterTree.getTypeBound().stream()
                .map(it -> (ExpressionTree) acceptTree(it, scope))
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
        acceptTree(newClassExpression.getName(), scope);
        return null;
    }

    @Override
    public Object visitThrowStatement(final ThrowStatement throwStatement, final Scope scope) {
        acceptTree(throwStatement.getExpression(), scope);
        return null;
    }

    @Override
    public Object visitIfStatement(final IfStatementTree ifStatementTree, final Scope scope) {
        acceptTree(ifStatementTree.getExpression(), scope);
        acceptTree(ifStatementTree.getThenStatement(), scope);
        if (ifStatementTree.getElseStatement() != null) {
            acceptTree(ifStatementTree.getElseStatement(), scope);
        }

        return null;
    }

    @Override
    public Object visitWildCardExpression(final WildcardExpressionTree wildCardExpression,
                                          final Scope scope) {
        if (wildCardExpression.getBoundKind() != BoundKind.UNBOUND) {
            acceptTree(wildCardExpression.getBound(), scope);
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
        acceptTree(selected, scope);

        final var varElement = TreeUtils.getSymbol(selected);

        // A package-qualified name (`extends java.util.Random`): the
        // qualifier is no type (JLS 6.5.5.2), so the whole name is one.
        // Looking up `Random` in the error type of `java.util` made the
        // superclass an error type and its constructors unresolvable. The
        // qualifier may even resolve to some type (`java.lang.annotation`
        // in `java.lang.annotation.RetentionPolicy`), so the whole name is
        // tried first whenever the qualifier is no variable.
        if (varElement == null) {
            final var qualifiedName = nameOfExpression(fieldAccessExpression);
            TypeMirror type = qualifiedName != null && scope.getCurrentClass() instanceof ClassSymbol currentClass
                    ? resolveType(qualifiedName, currentClass)
                    : null;
            if ((type == null || type instanceof ErrorType) && qualifiedName != null) {
                // Without a class in scope (an annotation of a class
                // header: `@Retention(java.lang.annotation.RetentionPolicy.RUNTIME)`).
                final var loaded = loader.loadClass(null, qualifiedName);
                type = loaded != null ? loaded.asType() : null;
            }
            if (type != null && !(type instanceof ErrorType)) {
                fieldAccessExpression.getField().setType(type);
                fieldAccessExpression.setType(type);
                return fieldAccessExpression;
            }
        }

        if (varElement != null) {
            final var varType = varElement.asType();
            final DeclaredType declaredType = asDeclaredType(varType);
            final var symbolScope = new SymbolScope(
                    declaredType,
                    scope
            );
            acceptTree(fieldAccessExpression, symbolScope);
        } else if (selected.getType() != null) {
            final DeclaredType declaredType = asDeclaredType(selected.getType());
            final var classScope = new ClassScope(
                    declaredType,
                    null,
                    scope.getCompilationUnit(),
                    compilerContext
            );
            acceptTree(fieldAccessExpression.getField(), classScope);
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
