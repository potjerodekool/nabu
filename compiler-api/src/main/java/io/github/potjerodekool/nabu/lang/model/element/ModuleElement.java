package io.github.potjerodekool.nabu.lang.model.element;

import java.util.List;

/**
 * A module element.
 */
public interface ModuleElement extends Element, QualifiedNameable  {

    @Override
    List<? extends Element> getEnclosedElements();

    boolean isOpen();

    boolean isUnnamed();

    @Override
    Element getEnclosingElement();

    List<? extends Directive> getDirectives();

    List<ExportsDirective> getExports();

    List<OpensDirective> getOpens();

    List<ProvidesDirective> getProvides();

    List<RequiresDirective> getRequires();

    List<UsesDirective> getUses();

    TypeElement getModuleInfo();

    enum DirectiveKind {
        REQUIRES,
        EXPORTS,
        OPENS,
        USES,
        PROVIDES
    }

    interface Directive {
        ModuleElement.DirectiveKind getKind();

        <R, P> R accept(ModuleElement.DirectiveVisitor<R, P> v, P p);
    }

    interface DirectiveVisitor<R, P> {
        default R visit(ModuleElement.Directive d) {
            return d.accept(this, null);
        }

        default R visit(ModuleElement.Directive d, P p) {
            return d.accept(this, p);
        }

        R visitRequires(ModuleElement.RequiresDirective d, P p);

        R visitExports(ModuleElement.ExportsDirective d, P p);

        R visitOpens(ModuleElement.OpensDirective d, P p);

        R visitUses(ModuleElement.UsesDirective d, P p);

        R visitProvides(ModuleElement.ProvidesDirective d, P p);

        default R visitUnknown(ModuleElement.Directive d, P p) {
            throw new UnknownDirectiveException(d, p);
        }
    }

    interface RequiresDirective extends ModuleElement.Directive {
        boolean isStatic();

        boolean isTransitive();

        ModuleElement getDependency();
    }

    interface ExportsDirective extends ModuleElement.Directive {

        PackageElement getPackage();

        List<? extends ModuleElement> getTargetModules();
    }

    interface OpensDirective extends ModuleElement.Directive {

        PackageElement getPackage();

        List<? extends ModuleElement> getTargetModules();
    }

    interface ProvidesDirective extends ModuleElement.Directive {

        TypeElement getService();

        List<? extends TypeElement> getImplementations();
    }

    interface UsesDirective extends ModuleElement.Directive {
        TypeElement getService();
    }
}
