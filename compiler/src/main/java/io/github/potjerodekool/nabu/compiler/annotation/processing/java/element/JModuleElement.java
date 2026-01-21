package io.github.potjerodekool.nabu.compiler.annotation.processing.java.element;

import io.github.potjerodekool.nabu.tools.TodoException;

import javax.lang.model.element.ElementVisitor;
import javax.lang.model.element.ModuleElement;
import java.util.List;

public class JModuleElement extends JElement<io.github.potjerodekool.nabu.lang.model.element.ModuleElement> implements ModuleElement {

    protected JModuleElement(final io.github.potjerodekool.nabu.lang.model.element.ModuleElement original) {
        super(original);
    }

    @Override
    public boolean isOpen() {
        return getOriginal().isError();
    }

    @Override
    public boolean isUnnamed() {
        return getOriginal().isUnnamed();
    }

    @Override
    public List<? extends Directive> getDirectives() {
        throw new TodoException();
    }

    @Override
    public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
        return v.visitModule(this, p);
    }
}
