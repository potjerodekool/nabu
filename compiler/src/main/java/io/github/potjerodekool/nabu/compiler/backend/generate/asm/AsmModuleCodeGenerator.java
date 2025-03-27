package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.ast.element.QualifiedNameable;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.SymbolVisitor;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;

public class AsmModuleCodeGenerator extends ModuleVisitor
        implements SymbolVisitor<Void, Void>,
        ModuleElement.DirectiveVisitor<Void, Void> {

    protected AsmModuleCodeGenerator(final int api,
                                     final ModuleVisitor visitor) {
        super(api, visitor);
    }

    @Override
    public Void visitUnknown(final Symbol symbol, final Void unused) {
        return null;
    }

    @Override
    public Void visitModule(final ModuleSymbol moduleSymbol, final Void unused) {
        moduleSymbol.getEnclosedPackages().forEach(packageSymbol ->
                packageSymbol.accept(this, unused));
        moduleSymbol.getRequires().forEach(requiresDirective ->
                requiresDirective.accept(this, unused));

        return null;
    }

    @Override
    public void visitMainClass(final String mainClass) {
        throw new TodoException();
    }

    @Override
    public Void visitPackage(final PackageSymbol packageSymbol, final Void unused) {
        visitPackage(packageSymbol.getQualifiedName());
        return null;
    }

    @Override
    public Void visitRequires(final ModuleElement.RequiresDirective d, final Void unused) {
        var access = 0;

        if (d.isTransitive()) {
            access += Opcodes.ACC_TRANSITIVE;
        }

        visitRequire(
                d.getDependency().getQualifiedName(),
                access,
                null
        );
        return null;
    }

    @Override
    public Void visitExports(final ModuleElement.ExportsDirective d, final Void unused) {
        final var access = 0;
        final var modules = d.getTargetModules().stream()
                .map(QualifiedNameable::getQualifiedName)
                .toArray(String[]::new);

        visitExport(d.getPackage().getQualifiedName(), access, modules);
        return null;
    }

    @Override
    public Void visitOpens(final ModuleElement.OpensDirective d, final Void unused) {
        int access = 0;
        final var modules = d.getTargetModules().stream()
                .map(QualifiedNameable::getQualifiedName)
                .toArray(String[]::new);

        visitOpen(
                d.getPackage().getQualifiedName(),
                access,
                modules
        );
        return null;
    }

    @Override
    public Void visitUses(final ModuleElement.UsesDirective d, final Void unused) {
        visitUse(ClassUtils.getInternalName(d.getService().getQualifiedName()));
        return null;
    }

    @Override
    public Void visitProvides(final ModuleElement.ProvidesDirective d, final Void unused) {
        final var providers = d.getImplementations().stream()
                .map(QualifiedNameable::getQualifiedName)
                .map(ClassUtils::getInternalName)
                .toArray(String[]::new);

        visitProvide(
                ClassUtils.getInternalName(d.getService().getQualifiedName()),
                providers
        );

        return null;
    }

}
