package io.github.potjerodekool.nabu.compiler.lang.model.element;

public class UnknownDirectiveException extends UnknownEntityException {
    public UnknownDirectiveException(ModuleElement.Directive d, Object p) {
        super("Unknown directive " + d);
    }
}
