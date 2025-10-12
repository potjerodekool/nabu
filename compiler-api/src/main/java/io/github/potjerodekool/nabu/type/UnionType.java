package io.github.potjerodekool.nabu.type;

import java.util.List;

public interface UnionType extends TypeMirror {
    List<? extends TypeMirror> getAlternatives();
}
