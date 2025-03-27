package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolTable;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;

public interface ClassSymbolLoader extends ClassElementLoader {

    SymbolTable getSymbolTable();

    TypesImpl getTypes();
}
