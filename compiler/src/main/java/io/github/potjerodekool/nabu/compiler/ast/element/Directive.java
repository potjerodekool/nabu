package io.github.potjerodekool.nabu.compiler.ast.element;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class Directive implements ModuleElement.Directive {

    private final ModuleElement.DirectiveKind kind;

    public Directive(final ModuleElement.DirectiveKind kind) {
        this.kind = kind;
    }

    @Override
    public ModuleElement.DirectiveKind getKind() {
        return kind;
    }

    public enum RequiresFlag {
        TRANSITIVE(),
        STATIC_PHASE(),
        SYNTHETIC(),
        MANDATED(),
        EXTRA()
    }

    public enum ExportsFlag {
        SYNTHETIC(),
        MANDATED()
    }

    public enum OpensFlag {
        SYNTHETIC(),
        MANDATED()
    }

    public static class RequiresDirective extends Directive implements ModuleElement.RequiresDirective {

        private final ModuleElement module;

        private final Set<RequiresFlag> flags;

        public RequiresDirective(final ModuleElement module) {
            this(module, EnumSet.noneOf(RequiresFlag.class));
        }

        public RequiresDirective(final ModuleElement module,
                                 final Set<RequiresFlag> flags) {
            super(ModuleElement.DirectiveKind.REQUIRES);
            this.module = module;
            this.flags = flags;
        }

        private boolean hasFlag(final RequiresFlag flag) {
            return flags.contains(flag);
        }

        @Override
        public boolean isStatic() {
            return hasFlag(RequiresFlag.STATIC_PHASE);
        }

        @Override
        public boolean isTransitive() {
            return hasFlag(RequiresFlag.TRANSITIVE);
        }

        @Override
        public ModuleElement getDependency() {
            return module;
        }

        @Override
        public <R, P> R accept(final ModuleElement.DirectiveVisitor<R, P> v, final P p) {
            return v.visitRequires(this, p);
        }

        public Set<RequiresFlag> getFlags() {
            return flags;
        }
    }

    public static class ExportsDirective extends Directive implements ModuleElement.ExportsDirective {

        private final PackageElement packageSymbol;
        private final List<ModuleElement> modules;
        private final Set<ExportsFlag> flags;

        public ExportsDirective(final PackageElement packageSymbol,
                                final List<ModuleElement> modules) {
            this(packageSymbol, modules, EnumSet.noneOf(ExportsFlag.class));
        }

        public ExportsDirective(final PackageElement packageSymbol,
                                final List<ModuleElement> modules,
                                final Set<ExportsFlag> flags) {
            super(ModuleElement.DirectiveKind.EXPORTS);
            Objects.requireNonNull(packageSymbol);
            this.packageSymbol = packageSymbol;
            this.modules = modules == null
                    ? null
                    : modules.stream().toList();
            this.flags = flags;
        }

        @Override
        public PackageElement getPackage() {
            return packageSymbol;
        }

        @Override
        public List<ModuleElement> getTargetModules() {
            return modules;
        }

        @Override
        public <R, P> R accept(final ModuleElement.DirectiveVisitor<R, P> v, final P p) {
            return v.visitExports(this, p);
        }

        public Set<ExportsFlag> getFlags() {
            return flags;
        }
    }

    public static class OpensDirective extends Directive implements ModuleElement.OpensDirective {

        private final PackageElement packageSymbol;
        private final List<ModuleElement> modules;
        private final Set<OpensFlag> flags;

        public OpensDirective(final PackageElement packageSymbol,
                              final List<ModuleElement> modules) {
            this(packageSymbol, modules, EnumSet.noneOf(OpensFlag.class));
        }

        public OpensDirective(final PackageElement packageSymbol,
                              final List<ModuleElement> modules,
                              final Set<OpensFlag> flags) {
            super(ModuleElement.DirectiveKind.OPENS);
            this.packageSymbol = packageSymbol;
            this.modules = modules == null
                    ? null
                    : modules.stream()
                    .toList();
            this.flags = flags;
        }

        @Override
        public PackageElement getPackage() {
            return packageSymbol;
        }

        @Override
        public List<? extends ModuleElement> getTargetModules() {
            return modules;
        }


        @Override
        public <R, P> R accept(final ModuleElement.DirectiveVisitor<R, P> v, final P p) {
            return v.visitOpens(this, p);
        }

        public Set<OpensFlag> getFlags() {
            return flags;
        }
    }

    public static class ProvidesDirective extends Directive implements ModuleElement.ProvidesDirective {

        private final TypeElement service;
        private final List<TypeElement> implementations;

        public ProvidesDirective(final TypeElement service,
                                 final List<TypeElement> implementations) {
            super(ModuleElement.DirectiveKind.PROVIDES);
            this.service = service;
            this.implementations = implementations.stream()
                    .toList();
        }

        @Override
        public <R, P> R accept(final ModuleElement.DirectiveVisitor<R, P> v, final P p) {
            return v.visitProvides(this, p);
        }

        @Override
        public TypeElement getService() {
            return service;
        }

        @Override
        public List<? extends TypeElement> getImplementations() {
            return implementations;
        }
    }

    public static class UsesDirective extends Directive implements ModuleElement.UsesDirective {

        private final TypeElement service;

        public UsesDirective(final TypeElement service) {
            super(ModuleElement.DirectiveKind.USES);
            this.service = service;
        }

        @Override
        public TypeElement getService() {
            return service;
        }

        @Override
        public <R, P> R accept(final ModuleElement.DirectiveVisitor<R, P> v, final P p) {
            return v.visitUses(this, p);
        }
    }
}
