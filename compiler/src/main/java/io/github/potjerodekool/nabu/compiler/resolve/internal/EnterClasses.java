package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.log.LogLevel;
import io.github.potjerodekool.nabu.compiler.log.Logger;
import io.github.potjerodekool.nabu.compiler.log.LoggerFactory;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.scope.GlobalScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.PackageDeclaration;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;

public class EnterClasses extends AbstractTreeVisitor<Object, Scope> {

    private static final Logger logger = LoggerFactory.getLogger(EnterClasses.class.getName());

    private final CompilerContextImpl compilerContext;
    private final ClassElementLoader classElementLoader;
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
            ((CCompilationTreeUnit) compilationUnit).setModuleElement((ModuleSymbol) module);
        } else {
            final var module = compilerContext.getSymbolTable().getUnnamedModule();
            ((CCompilationTreeUnit) compilationUnit).setModuleElement(module);
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

        final var clazz = compilerContext.getSymbolTable()
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
        packageElement.getMembers().define(clazz);

        if (clazz.getCompleter().isTerminal()) {
            //logger.log(LogLevel.WARN, "Completer is terminal for " + clazz);
        }

        clazz.setCompleter(typeEnter);

        typeEnter.put(
                clazz,
                classDeclaration,
                scope.getCompilationUnit()
        );

        return classDeclaration;
    }

}