package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;

public interface EnumUsage {
    CClassDeclaration getMemberClass();

    String getFieldName(TypeElement enumClass);
}
