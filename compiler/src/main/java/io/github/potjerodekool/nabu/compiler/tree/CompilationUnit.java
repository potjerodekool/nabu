package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;

import java.util.List;

public interface CompilationUnit extends Tree {

    List<ClassDeclaration> getClasses();

    PackageDeclaration getPackageDeclaration();

    List<ImportItem> getImportItems();

    ImportScope getImportScope();

    FileObject getFileObject();

    boolean isTransformed();

}
