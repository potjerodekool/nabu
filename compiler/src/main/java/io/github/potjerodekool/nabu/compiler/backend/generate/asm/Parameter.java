package io.github.potjerodekool.nabu.compiler.backend.generate.asm;

import io.github.potjerodekool.nabu.compiler.backend.ir.type.IType;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;

import java.util.List;

record Parameter(String name, IType type,
                 List<? extends AnnotationMirror> annotationMirrors) {
}