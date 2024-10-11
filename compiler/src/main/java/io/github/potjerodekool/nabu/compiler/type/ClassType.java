package io.github.potjerodekool.nabu.compiler.type;

import java.util.List;

public interface ClassType extends DeclaredType {
    ClassType getOuterType();

    List<TypeMirror> getParameterTypes();
}
