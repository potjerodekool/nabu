package io.github.potjerodekool.nabu.compiler.ast.element;

public interface ElementVisitor<R, P> {

    R visitUnknown(Element e, P p);

    default R visitExecutable(ExecutableElement executableElement, P p) {
        return visitUnknown(executableElement, p);
    }

    default R visitTypeParameter(TypeParameterElement typeParameterElement, P p) {
        return visitUnknown(typeParameterElement, p);
    }

    default R visitType(TypeElement typeElement, P p) {
        return visitUnknown(typeElement, p);
    }

    default R visitPackage(PackageElement packageElement, P p) {
        return visitUnknown(packageElement, p);
    }

    default R visitVariable(VariableElement variableElement, P p) {
        return visitUnknown(variableElement, p);
    }
}
