package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.ast.element.PackageElement;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.tree.element.CClassDeclaration;

import java.util.ArrayList;
import java.util.List;

public class CompilationUnit extends Tree {

    private final List<Tree> definitions = new ArrayList<>();

    private PackageElement packageElement;

    private final ImportScope importScope = new ImportScope();

    private final List<ImportItem> importItems = new ArrayList<>();

    private FileObject fileObject;

    private boolean isTransformed = false;

    public void add(final Tree element) {
        this.definitions.add(element);
    }

    public List<CClassDeclaration> getClasses() {
        return definitions.stream()
                .filter(it -> it instanceof CClassDeclaration)
                .map(it -> (CClassDeclaration) it)
                .toList();
    }

    public CPackageDeclaration getPackageDeclaration() {
        if (definitions.isEmpty()) {
            return null;
        }

        final var first = definitions.getFirst();
        return first instanceof CPackageDeclaration packageDeclaration
                ? packageDeclaration
                : null;
    }

    @Override
    public <R, P> R accept(final TreeVisitor<R, P> visitor, final P param) {
        return visitor.visitCompilationUnit(this, param);
    }

    public PackageElement getPackageElement() {
        return packageElement;
    }

    public void setPackageElement(final PackageElement packageElement) {
        this.packageElement = packageElement;
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
