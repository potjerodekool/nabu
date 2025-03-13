package io.github.potjerodekool.nabu.compiler.tree.impl;

import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.tree.*;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

import java.util.ArrayList;
import java.util.List;

public class CCompilationUnit extends CTree implements CompilationUnit {

    private final List<Tree> definitions = new ArrayList<>();

    private final ImportScope importScope = new ImportScope();

    private final List<ImportItem> importItems = new ArrayList<>();

    private FileObject fileObject;

    private boolean isTransformed = false;

    public CCompilationUnit(final FileObject fileObject,
                            final List<ImportItem> importItems,
                            final List<Tree> declarations,
                            final int lineNumber,
                            final int charPositionInLine) {
        super(lineNumber, charPositionInLine);
        this.fileObject = fileObject;
        this.importItems.addAll(importItems);
        this.definitions.addAll(declarations);
    }

    public void add(final Tree element) {
        this.definitions.add(element);
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

    public ImportScope getImportScope() {
        return importScope;
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
