package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.resolve.scope.CompositeScope;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.scope.StartImportScope;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;

import java.util.List;

public interface CompilationUnit extends Tree {

    List<ClassDeclaration> getClasses();

    PackageDeclaration getPackageDeclaration();

    List<ImportItem> getImportItems();

    ImportScope getNamedImportScope();

    StartImportScope getStartImportScope();

    CompositeScope getCompositeImportScope();

    Scope getScope();

    FileObject getFileObject();

    boolean isTransformed();

    ModuleDeclaration getModuleDeclaration();
}
