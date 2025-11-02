package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.resolve.scope.CompositeScope;
import io.github.potjerodekool.nabu.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.scope.StartImportScope;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;

import java.util.List;

/**
 * A compilation unit.
 */
public interface CompilationUnit extends Tree {

    /**
     * @return Returns a list of classes defined in the compilation unit.
     */
    List<ClassDeclaration> getClasses();

    /**
     * @return Returns the package declaration or null if absent.
     */
    PackageDeclaration getPackageDeclaration();

    /**
     * @return Returns a list of import items.
     */
    List<ImportItem> getImportItems();

    /**
     * @return Returns the named import scope.
     */
    ImportScope getNamedImportScope();

    /**
     * @return Returns the start importScope.
     */
    StartImportScope getStartImportScope();

    /**
     * @return Returns a composite import scope.
     */
    CompositeScope getCompositeImportScope();

    /**
     * @return Returns the scope.
     */
    Scope getScope();

    /**
     * @return Returns the file object.
     */
    FileObject getFileObject();

    /**
     * @return Returns if the compilation unit is transformed.
     */
    boolean isTransformed();

    /**
     * @return Returns the module declaration.
     */
    ModuleDeclaration getModuleDeclaration();
}
