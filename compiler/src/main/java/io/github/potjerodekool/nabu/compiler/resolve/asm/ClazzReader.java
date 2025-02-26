package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.TypeElement;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import org.objectweb.asm.ClassReader;

public final class ClazzReader {

    private ClazzReader() {
    }

    public static TypeElement read(final byte[] bytecode,
                                   final SymbolTable cache,
                                   final AsmClassElementLoader classElementLoader) {
        final var classReader = new ClassReader(bytecode);
        final var visitor = new AsmClassBuilder(cache, classElementLoader);
        classReader.accept(visitor, 0);
        return visitor.getClazz();
    }
}

