package io.github.potjerodekool.nabu.compiler.type;

import java.util.List;

public interface UnionType extends TypeMirror {
    List<? extends TypeMirror> getAlternatives();
}
