package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.Directive;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import org.objectweb.asm.ModuleVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol.createFlatName;

public class AsmModuleBuilder extends ModuleVisitor {

    private final ModuleSymbol moduleSymbol;
    private final List<ModuleElement.ExportsDirective> exportsDirectives = new ArrayList<>();
    private final List<ModuleElement.OpensDirective> opensDirectives = new ArrayList<>();
    private final List<ModuleElement.UsesDirective> usesDirectives = new ArrayList<>();
    private final List<ModuleElement.ProvidesDirective> providesDirectives = new ArrayList<>();
    private final List<ModuleElement.RequiresDirective> requiresDirectives = new ArrayList<>();

    private final SymbolTable symbolTable;

    protected AsmModuleBuilder(final int api,
                               final ModuleSymbol moduleSymbol,
                               final SymbolTable symbolTable) {
        super(api);
        this.moduleSymbol = moduleSymbol;
        this.symbolTable = symbolTable;
    }

    @Override
    public void visitMainClass(final String mainClass) {
        //TODO
    }

    @Override
    public void visitRequire(final String module, final int access, final String version) {
        final var moduleSymbol = symbolTable.getModule(module);
        this.requiresDirectives.add(
                new Directive.RequiresDirective(
                        moduleSymbol,
                        Set.of()
                )
        );
    }

    @Override
    public void visitPackage(final String packageName) {
        symbolTable.enterPackage(moduleSymbol, createFlatName(packageName));
    }

    @Override
    public void visitExport(final String packageName, final int access, final String... modules) {
        final var packageSymbol = symbolTable.lookupPackage(moduleSymbol, createFlatName(packageName));

        final var directive = new Directive.ExportsDirective(
                packageSymbol,
                List.of()
        );

        exportsDirectives.add(directive);
    }

    @Override
    public void visitOpen(final String packageName, final int access, final String... modules) {
        final var packageSymbol = symbolTable.lookupPackage(moduleSymbol, createFlatName(packageName));

        final var directive = new Directive.OpensDirective(
                packageSymbol,
                List.of()
        );

        opensDirectives.add(directive);
    }

    @Override
    public void visitUse(final String service) {
        this.usesDirectives.add(new Directive.UsesDirective(null));
    }

    @Override
    public void visitProvide(final String service, final String... providers) {
        providesDirectives.add(
                new Directive.ProvidesDirective(
                        null,
                        List.of()
                )
        );
    }

    @Override
    public void visitEnd() {
        moduleSymbol.setExports(exportsDirectives);
        moduleSymbol.setOpens(opensDirectives);
        moduleSymbol.setUses(usesDirectives);
        moduleSymbol.setProvides(providesDirectives);
        moduleSymbol.setRequires(requiresDirectives);
    }
}
