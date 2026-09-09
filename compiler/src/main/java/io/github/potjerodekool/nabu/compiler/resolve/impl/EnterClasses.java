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
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.tree.PatternTreeVisitor;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;

import java.util.ArrayList;

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

        clazz.setKind(ElementKind.valueOf(classDeclaration.getKind().name()));
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
    }

    private void enterNestedClasses(final CClassDeclaration classDeclaration,
                                    final ClassSymbol owner,
                                    final Scope scope) {
        final var list = new ArrayList<>(classDeclaration.getEnclosedElements());

        list.stream()
                .filter(ClassDeclaration.class::isInstance)
                .map(ClassDeclaration.class::cast)
                .forEach(nested -> {
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

                    enterNestedClasses(nestedDeclaration, clazz, scope);
                });
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
