package io.github.potjerodekool.nabu.tree;

import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public interface Tree {

    int getLineNumber();

    int getColumnNumber();

    TypeMirror getType();

    void setType(TypeMirror type);

    default List<? extends Tree> children() {
        return Collections.emptyList();
    }

}
