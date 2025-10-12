package io.github.potjerodekool.nabu.type;

import java.util.List;

public interface IntersectionType extends TypeMirror {

    List<? extends TypeMirror> getBounds();
}
