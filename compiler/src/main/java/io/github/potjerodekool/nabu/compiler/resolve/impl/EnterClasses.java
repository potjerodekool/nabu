package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.tree.PatternTreeVisitor;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.expression.NewClassExpression;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;

import java.util.ArrayList;
import java.util.Stack;

public class EnterClasses implements PatternTreeVisitor<Void, Scope> {

    private final CompilerContextImpl compilerContext;
    private final ClassElementLoader classElementLoader;
    private final TypeEnter typeEnter;

    public EnterClasses(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.classElementLoader = compilerContext.getClassElementLoader();
        this.typeEnter = compilerContext.getTypeEnter();
    }

    @Override
    public Void acceptTree(final Tree tree,
                           final Scope scope) {
        switch (tree) {
            case CompilationUnit compilationUnit -> visitCompilationUnit(compilationUnit);
            case PackageDeclaration packageDeclaration -> visitPackageDeclaration(packageDeclaration, scope);
            case ClassDeclaration classDeclaration -> visitClass(classDeclaration, scope);
            default -> {
            }
        }

        return null;
    }

    private void visitCompilationUnit(final CompilationUnit compilationUnit) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);

        if (compilationUnit.getModuleDeclaration() != null) {
            acceptTree(compilationUnit.getModuleDeclaration(), globalScope);
            final var module = compilationUnit.getModuleDeclaration().getModuleSymbol();
            ((CCompilationTreeUnit) compilationUnit).setModuleElement(module);
        } else {
            final var unit = (CCompilationTreeUnit) compilationUnit;
            var module = unit.getModuleElement();

            if (module == null) {
                module = SymbolTable.getInstance(compilerContext).getUnnamedModule();
                unit.setModuleElement(module);
            }
        }

        if (compilationUnit.getPackageDeclaration() != null) {
            acceptTree(compilationUnit.getPackageDeclaration(), globalScope);
        } else {
            final var module = (ModuleSymbol) globalScope.findModuleElement();
            final var unnamedPackage = compilerContext.getSymbolTable().lookupPackage(
                    module,
                    ""
            );
            globalScope.setPackageElement(unnamedPackage);
        }

        compilationUnit.getClasses().forEach(classDeclaration ->
                acceptTree(classDeclaration, globalScope));
    }

    private void visitPackageDeclaration(final PackageDeclaration packageDeclaration,
                                         final Scope scope) {
        final var packageElement = classElementLoader.findOrCreatePackage(
                scope.findModuleElement(),
                packageDeclaration.getQualifiedName()
        );
        scope.setPackageElement(packageElement);
        packageDeclaration.setPackageElement(packageElement);
    }

    private void visitClass(final ClassDeclaration classDeclaration,
                            final Scope scope) {
        final var packageElement = (PackageSymbol) scope.getPackageElement();
        final var module = (ModuleSymbol) scope.findModuleElement();

        final var clazz = SymbolTable.getInstance(compilerContext)
                .enterClass(
                        module,
                        classDeclaration.getSimpleName(),
                        packageElement
                );

        // Via kindOf: een top-level @interface (Kind.ANNOTATION) bestaat niet
        // als ElementKind en liet de compiler crashen.
        clazz.setKind(kindOf(classDeclaration));
        clazz.setNestingKind(NestingKind.valueOf(classDeclaration.getNestingKind().name()));
        clazz.setFlags(classDeclaration.getModifiers().getFlags());
        clazz.setSimpleName(classDeclaration.getSimpleName());
        clazz.setEnclosingElement(packageElement);

        final var clazzDeclaration = (CClassDeclaration) classDeclaration;

        clazzDeclaration.setClassSymbol(clazz);
        clazz.setError(false);
        packageElement.define(clazz);

        clazz.setCompleter(typeEnter);

        typeEnter.put(
                clazz,
                classDeclaration,
                scope.getCompilationUnit()
        );

        enterNestedClasses(clazzDeclaration, clazz, scope);

        enterAnonymousClasses(classDeclaration, clazz, scope);
    }

    /**
     * Walks the entire class body (incl. function bodies and nested classes)
     * to find anonymous class declarations ({@code new X() { ... }}) and enter
     * them, mirroring javac's Enter phase.
     *
     * <p>The walk reuses the base {@link AbstractTreeVisitor} full recursion:
     * the base dispatch descends into function bodies, expressions and nested
     * classes, so this walker only has to track the enclosing class and
     * override {@code visitNewClass} to enter an anonymous body.
     */
    private void enterAnonymousClasses(final ClassDeclaration classDeclaration,
                                       final ClassSymbol owner,
                                       final Scope scope) {
        final var enclosingClasses = new Stack<ClassSymbol>();
        final var counters = new java.util.HashMap<ClassSymbol, Integer>();

        enclosingClasses.push(owner);

        // Of de walk nu binnen een methode-body is. Een symbol-loze
        // declaratie in een methode-body is een lokale klasse (JLS 14.3);
        // een symbol-loze declaratie in een klasse-body is een member-
        // klasse (JLS 8.1) - ook in lokale klassen.
        final var inFunctionBody = new boolean[]{false};

        final var anonymousEnterer = new AbstractTreeVisitor<Void, Scope>() {

            @Override
            public Void visitFunction(final Function function,
                                      final Scope scope) {
                final var previous = inFunctionBody[0];
                inFunctionBody[0] = true;
                super.visitFunction(function, scope);
                inFunctionBody[0] = previous;
                return null;
            }

            @Override
            public Void visitClass(final ClassDeclaration clazzDeclaration,
                                   final Scope scope) {
                // Een symbol-loze declaratie in een klasse-body is een
                // member-klasse (JLS 8.1), in een methode-body een lokale
                // klasse (JLS 14.3): beslis vÃ³Ã³r we afdalen.
                final var wasInFunctionBody = inFunctionBody[0];

                // Kunstmatige (lege) declaraties van enum-constanten
                // overslaan we, net als bij anonieme klassen.
                if (clazzDeclaration.getClassSymbol() == null
                        && clazzDeclaration.getEnclosedElements() != null
                        && !clazzDeclaration.getEnclosedElements().isEmpty()) {
                    if (wasInFunctionBody) {
                        enterLocalClass(clazzDeclaration, enclosingClasses.peek(), counters, scope);
                    } else {
                        // Nested klasse in een klasse-body: member van de
                        // omsluitende klasse. Ook in lokale klassen, anders
                        // vind member-lookup als `new Inner()` (binnen
                        // Outer.viaInner) het type niet terug.
                        enterMemberClass(
                                clazzDeclaration,
                                enclosingClasses.peek(),
                                scope
                        );
                    }
                }

                if (clazzDeclaration.getClassSymbol() instanceof ClassSymbol classSymbol
                        && enclosingClasses.peek() != classSymbol) {
                    enclosingClasses.push(classSymbol);
                    counters.putIfAbsent(classSymbol, 0);
                }

                // Bij het afdalen in de klasse-body zijn we per definitie
                // niet meer "in een methode"; geneste functies zetten de
                // vlag in hun eigen visitFunction.
                inFunctionBody[0] = false;
                super.visitClass(clazzDeclaration, scope);
                inFunctionBody[0] = wasInFunctionBody;

                if (enclosingClasses.size() > 1) {
                    enclosingClasses.pop();
                }
                return null;
            }

            @Override
            public Void visitNewClass(final NewClassExpression newClassExpression,
                                      final Scope scope) {
                final var classDeclaration = newClassExpression.getClassDeclaration();

                // Enum-constants are represented as a NewClassExpression with an
                // artificial, empty ClassDeclaration; those are not anonymous.
                // Een echte anonieme klasse met een lege body (`new X() { }`)
                // wel: anders blijft het symbool null en wordt de interface
                // zelf ge-instantieerd (een interface kan niet worden
                // ge-instantieerd).
                final boolean enumConstant = classDeclaration != null
                        && classDeclaration.getKind() == Kind.ENUM
                        && (classDeclaration.getEnclosedElements() == null
                                || classDeclaration.getEnclosedElements().isEmpty());
                if (classDeclaration != null && !enumConstant) {
                    enterAnonymousClass(classDeclaration, enclosingClasses.peek(),
                            counters, scope);
                }

                return super.visitNewClass(newClassExpression, scope);
            }
        };

        anonymousEnterer.acceptTree(classDeclaration, scope);
    }

    private void enterAnonymousClass(final ClassDeclaration classDeclaration,
                                     final ClassSymbol enclosing,
                                     final java.util.HashMap<ClassSymbol, Integer> counters,
                                     final Scope scope) {
        final var module = (ModuleSymbol) scope.findModuleElement();
        final var currentCount = counters.merge(enclosing, 1, Integer::sum);

        final var clazz = SymbolTable.getInstance(compilerContext)
                .enterClass(
                        module,
                        Integer.toString(currentCount),
                        enclosing
                );

        clazz.setKind(ElementKind.CLASS);
        clazz.setNestingKind(NestingKind.ANONYMOUS);
        clazz.setEnclosingElement(enclosing);
        clazz.setError(false);
        clazz.setCompleter(typeEnter);

        final var anonDeclaration = (CClassDeclaration) classDeclaration;
        anonDeclaration.setClassSymbol(clazz);

        typeEnter.put(
                clazz,
                anonDeclaration,
                scope.getCompilationUnit()
        );
    }

    /**
     * Enters a local class (JLS 14.3): a class declared inside a method
     * body. Like javac it gets a name based on the enclosing class and a
     * shared per-class counter ({@code Gap$1Adder}); the nesting kind is
     * LOCAL and the enclosing element is the enclosing class (a local class
     * is not a member).
     */
    private void enterLocalClass(final ClassDeclaration classDeclaration,
                                 final ClassSymbol enclosing,
                                 final java.util.HashMap<ClassSymbol, Integer> counters,
                                 final Scope scope) {
        final var module = (ModuleSymbol) scope.findModuleElement();
        final var currentCount = counters.merge(enclosing, 1, Integer::sum);

        final var clazz = SymbolTable.getInstance(compilerContext)
                .enterClass(
                        module,
                        currentCount + classDeclaration.getSimpleName(),
                        enclosing
                );

        clazz.setKind(kindOf(classDeclaration));
        clazz.setNestingKind(NestingKind.LOCAL);
        clazz.setFlags(classDeclaration.getModifiers().getFlags());
        clazz.setSimpleName(classDeclaration.getSimpleName());
        clazz.setEnclosingElement(enclosing);
        clazz.setError(false);
        clazz.setCompleter(typeEnter);

        final var localDeclaration = (CClassDeclaration) classDeclaration;
        localDeclaration.setClassSymbol(clazz);

        typeEnter.put(
                clazz,
                localDeclaration,
                scope.getCompilationUnit()
        );
    }

    private void enterNestedClasses(final CClassDeclaration classDeclaration,
                                    final ClassSymbol owner,
                                    final Scope scope) {
        final var list = new ArrayList<>(classDeclaration.getEnclosedElements());

        list.stream()
                .filter(ClassDeclaration.class::isInstance)
                .map(ClassDeclaration.class::cast)
                .forEach(nested -> {
                    enterMemberClass(nested, owner, scope);
                    enterNestedClasses(
                            (CClassDeclaration) nested,
                            (ClassSymbol) nested.getClassSymbol(),
                            scope
                    );
                });
    }

    /**
     * Voert een geneste klasse-declaratie in als member van {@code owner}.
     * Wordt gebruikt door {@link #enterNestedClasses} en (voor klassen in een
     * klasse-body van een lokale klasse) door de walker in
     * {@link #enterAnonymousClasses}.
     */
    private void enterMemberClass(final ClassDeclaration nested,
                                  final ClassSymbol owner,
                                  final Scope scope) {
        final var module = (ModuleSymbol) scope.findModuleElement();

        final var clazz = SymbolTable.getInstance(compilerContext)
                .enterClass(
                        module,
                        nested.getSimpleName(),
                        owner
                );

        clazz.setKind(kindOf(nested));
        clazz.setNestingKind(computeNestingKind(nested, owner));
        clazz.setFlags(nested.getModifiers().getFlags());
        clazz.setSimpleName(nested.getSimpleName());

        final var nestedDeclaration = (CClassDeclaration) nested;

        nestedDeclaration.setClassSymbol(clazz);
        clazz.setError(false);
        owner.addEnclosedElement(clazz);
        clazz.setCompleter(typeEnter);

        typeEnter.put(
                clazz,
                nestedDeclaration,
                scope.getCompilationUnit()
        );
    }

    private NestingKind computeNestingKind(final ClassDeclaration classDeclaration,
                                           final ClassSymbol owner) {
        if (owner == null) {
            return NestingKind.TOP_LEVEL;
        }

        return NestingKind.MEMBER;
    }

    private ElementKind kindOf(final ClassDeclaration classDeclaration) {
        if (classDeclaration.getKind() == Kind.ANNOTATION) {
            return ElementKind.ANNOTATION_TYPE;
        }

        return ElementKind.valueOf(classDeclaration.getKind().name());
    }

}
