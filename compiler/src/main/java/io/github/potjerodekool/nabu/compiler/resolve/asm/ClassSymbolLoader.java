package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;

public interface ClassSymbolLoader extends ClassElementLoader {

    SymbolTable getSymbolTable();

    TypesImpl getTypes();
}
