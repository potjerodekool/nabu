package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.scope.*;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.*;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.impl.CCompilationTreeUnit;

public class EnterClasses extends AbstractTreeVisitor<Object, Scope> {

    private final CompilerContextImpl compilerContext;
    private final ClassSymbolLoader classElementLoader;
    private final TypeEnter typeEnter;

    public EnterClasses(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.classElementLoader = compilerContext.getClassElementLoader();
        this.typeEnter = compilerContext.getTypeEnter();
    }

    @Override
    public Object visitCompilationUnit(final CompilationUnit compilationUnit, final Scope scope) {
        final var globalScope = new GlobalScope(compilationUnit, compilerContext);

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

        compilationUnit.getClasses().forEach(classDeclaration -> classDeclaration.accept(this, globalScope));

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
        final var packageElement = (PackageSymbol) scope.getPackageElement();
        final var module = (ModuleSymbol) scope.findModuleElement();

        final var clazz = classElementLoader.getSymbolTable()
                .enterClass(
                        module,
                        classDeclaration.getSimpleName(),
                        packageElement
                );

        clazz.setKind(ElementKind.valueOf(classDeclaration.getKind().name()));
        clazz.setNestingKind(NestingKind.TOP_LEVEL);
        clazz.setFlags(classDeclaration.getModifiers().getFlags());
        clazz.setSimpleName(classDeclaration.getSimpleName());
        clazz.setEnclosingElement(packageElement);

        final var clazzDeclaration = (CClassDeclaration) classDeclaration;

        clazzDeclaration.setClassSymbol(clazz);
        packageElement.getMembers().define(clazz);
        clazz.setCompleter(typeEnter);

        typeEnter.put(
                clazz,
                classDeclaration,
                scope.getCompilationUnit()
        );

        return classDeclaration;
    }

}