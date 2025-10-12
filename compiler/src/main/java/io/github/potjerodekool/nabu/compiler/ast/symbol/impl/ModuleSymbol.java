package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.tools.FileManager.Location;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.ModuleTypeImpl;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ElementVisitor;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ModuleSymbol extends TypeSymbol implements ModuleElement {

    public ClassSymbol moduleInfo;

    private final List<PackageSymbol> enclosedPackages = new ArrayList<>();

    private PackageSymbol unnamedPackage;

    private final Map<String, PackageSymbol> visiblePackages = new HashMap<>();

    private List<Directive> directives = new ArrayList<>();
    private List<RequiresDirective> requires = new ArrayList<>();
    private List<ExportsDirective> exports = new ArrayList<>();
    private List<OpensDirective> opens = new ArrayList<>();
    private List<ProvidesDirective> provides = new ArrayList<>();
    private List<UsesDirective> uses = new ArrayList<>();

    private Location sourceLocation;
    private Location classLocation;

    public ModuleSymbol(final long flags,
                        final String name,
                        final Symbol owner) {
        super(
                ElementKind.MODULE,
                flags,
                name,
                new ModuleTypeImpl(null),
                owner
        );
    }

    public static ModuleSymbol create(final String name,
                                      String moduleInfo) {
        final var module = new ModuleSymbol(0, name, null);
        final var moduleInfoClass = new ClassSymbol(
                ElementKind.CLASS,
                NestingKind.TOP_LEVEL,
                Flags.MODULE,
                moduleInfo,
                new CClassType(
                        null,
                        null,
                        List.of()
                ),
                module,
                List.of(),
                List.of()
        );
        module.setModuleInfo(moduleInfoClass);
        return module;
    }

    public PackageSymbol getUnnamedPackage() {
        return unnamedPackage;
    }

    public void setUnnamedPackage(final PackageSymbol unnamedPackage) {
        this.unnamedPackage = unnamedPackage;
    }

    public void addEnclosedPackage(final int index,
                                   final PackageSymbol packageSymbol) {
        this.enclosedPackages.add(index, packageSymbol);
    }

    public List<PackageSymbol> getEnclosedPackages() {
        return enclosedPackages;
    }

    public Map<String, PackageSymbol> getVisiblePackages() {
        return visiblePackages;
    }

    public void addVisiblePackage(final String name,
                                  final PackageSymbol packageSymbol) {
        if (name.contains("/")) {
            throw new IllegalArgumentException();
        }

        this.visiblePackages.put(name, packageSymbol);
    }

    @Override
    public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
        return v.visitModule(this, p);
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitModule(this, p);
    }

    @Override
    public boolean isOpen() {
        return false;
    }

    @Override
    public boolean isUnnamed() {
        return false;
    }

    @Override
    public List<? extends Directive> getDirectives() {
        return directives;
    }

    public void setDirectives(final List<Directive> directives) {
        this.directives = directives;
    }

    @Override
    public List<ExportsDirective> getExports() {
        return exports;
    }

    public void setExports(final List<ExportsDirective> exports) {
        this.exports = exports;
    }

    @Override
    public List<OpensDirective> getOpens() {
        return opens;
    }

    public void setOpens(final List<OpensDirective> opens) {
        this.opens = opens;
    }

    @Override
    public List<ProvidesDirective> getProvides() {
        return provides;
    }

    public void setProvides(final List<ProvidesDirective> provides) {
        this.provides = provides;
    }

    @Override
    public List<RequiresDirective> getRequires() {
        return requires;
    }

    public void setRequires(final List<RequiresDirective> requires) {
        this.requires = requires;
    }

    @Override
    public List<UsesDirective> getUses() {
        return uses;
    }

    public void setUses(final List<UsesDirective> uses) {
        this.uses = uses;
    }

    @Override
    public ClassSymbol getModuleInfo() {
        return moduleInfo;
    }

    public void setModuleInfo(final ClassSymbol moduleInfo) {
        this.moduleInfo = moduleInfo;
    }

    public Location getSourceLocation() {
        return sourceLocation;
    }

    public void setSourceLocation(final Location sourceLocation) {
        this.sourceLocation = sourceLocation;
    }

    public Location getClassLocation() {
        return classLocation;
    }

    public void setClassLocation(final Location classLocation) {
        this.classLocation = classLocation;
    }
}
