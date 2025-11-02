package io.github.potjerodekool.nabu.tree.impl;

import io.github.potjerodekool.nabu.resolve.scope.CompositeScope;
import io.github.potjerodekool.nabu.resolve.scope.NamedImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.scope.StartImportScope;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of CompilationUnit.
 */
public class CCompilationTreeUnit extends CTree implements CompilationUnit {

    private final List<Tree> definitions = new ArrayList<>();

    private final NamedImportScope namedImportScope = new NamedImportScope();

    private final StartImportScope startImportScope = new StartImportScope();

    private final CompositeScope compositeImportScope = new CompositeScope(
            namedImportScope,
            startImportScope
    );

    private CompositeScope globalScope;

    private final List<ImportItem> importItems = new ArrayList<>();

    private FileObject fileObject;

    private boolean isTransformed = false;

    private ModuleElement moduleElement;

    public CCompilationTreeUnit(final FileObject fileObject,
                                final List<ImportItem> importItems,
                                final List<Tree> declarations,
                                final int lineNumber,
                                final int columnNumber) {
        super(lineNumber, columnNumber);
        this.fileObject = fileObject;
        this.importItems.addAll(importItems);
        this.definitions.addAll(declarations);
    }

    public ModuleElement getModuleElement() {
        return moduleElement;
    }

    public void setModuleElement(final ModuleElement moduleElement) {
        this.moduleElement = moduleElement;
    }

    public void add(final Tree element) {
        this.definitions.add(element);
    }

    @Override
    public ModuleDeclaration getModuleDeclaration() {
        if (definitions.isEmpty()) {
            return null;
        } else {
            final var first = definitions.getFirst();
            return first instanceof ModuleDeclaration moduleDeclaration
                    ? moduleDeclaration
                    : null;
        }
    }

    public List<ClassDeclaration> getClasses() {
        return definitions.stream()
                .filter(it -> it instanceof ClassDeclaration)
                .map(it -> (ClassDeclaration) it)
                .toList();
    }

    public PackageDeclaration getPackageDeclaration() {
        if (definitions.isEmpty()) {
            return null;
        }

        final var first = definitions.getFirst();
        return first instanceof PackageDeclaration packageDeclaration
                ? packageDeclaration
                : null;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitCompilationUnit(this, param);
    }

    public void addImport(final ImportItem importItem) {
        importItems.add(importItem);
    }

    public List<ImportItem> getImportItems() {
        return importItems;
    }

    @Override
    public NamedImportScope getNamedImportScope() {
        return namedImportScope;
    }

    @Override
    public StartImportScope getStartImportScope() {
        return startImportScope;
    }

    @Override
    public CompositeScope getCompositeImportScope() {
        return compositeImportScope;
    }

    @Override
    public Scope getScope() {
        final var globalScope = getGlobalScope();

        if (globalScope != null) {
            return globalScope;
        } else {
            return compositeImportScope;
        }
    }

    private Scope getGlobalScope() {
        if (globalScope != null) {
            return globalScope;
        }

        final var packageDeclaration = getPackageDeclaration();

        if (packageDeclaration == null) {
            return null;
        }

        final var packageElement = packageDeclaration.getPackageElement();

        if (packageElement == null) {
            return null;
        }

        globalScope = new CompositeScope(
                packageElement.getMembers(),
                compositeImportScope
        );

        return globalScope;
    }

    public FileObject getFileObject() {
        return fileObject;
    }

    public void setFileObject(final FileObject fileObject) {
        this.fileObject = fileObject;
    }

    public boolean isTransformed() {
        return isTransformed;
    }

    public void markTransformed() {
        this.isTransformed = true;
    }

}
