package io.github.potjerodekool.nabu.compiler.tree;

import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.resolve.scope.CompositeScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.Scope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.StartImportScope;
import io.github.potjerodekool.nabu.compiler.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.compiler.tree.element.ModuleDeclaration;

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
