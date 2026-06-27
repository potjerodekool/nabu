package io.github.potjerodekool.nabu.compiler.lang.model.element;

/**
 * A record component element.
 */
public interface RecordComponentElement extends Element {

    ExecutableElement getAccessor();
}
