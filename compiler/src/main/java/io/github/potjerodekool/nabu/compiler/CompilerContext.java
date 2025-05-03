package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.compiler.util.Elements;

public interface CompilerContext extends AutoCloseable {
    ClassElementLoader getClassElementLoader();

    Elements getElements();

    MethodResolver getMethodResolver();

    ArgumentBoxer getArgumentBoxer();
}
