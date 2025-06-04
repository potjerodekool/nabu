package io.github.potjerodekool.nabu.compiler.backend.lower.codegen;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.tree.element.impl.CClassDeclaration;

public interface EnumUsage {
    CClassDeclaration getMemberClass();

    String getFieldName(TypeElement enumClass);
}
