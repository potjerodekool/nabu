package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;

import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import org.objectweb.asm.ClassReader;

public final class ClazzReader {

    private ClazzReader() {
    }

    public static TypeElement read(final byte[] bytecode,
                                   final SymbolTable cache,
                                   final ClassElementLoader classElementLoader,
                                   final ClassSymbol classSymbol,
                                   final ModuleSymbol moduleSymbol) {
        final var classReader = new ClassReader(bytecode);
        final var visitor = new AsmClassBuilder(
                cache,
                classElementLoader,
                classSymbol,
                moduleSymbol
        );
        classReader.accept(visitor, 0);
        return visitor.getClazz();
    }
}

