package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.MethodResolver;

public class CompilerContext {

    private final ClassElementLoader classElementLoader;
    private final MethodResolver methodResolver;
    private final ArgumentBoxer argumentBoxer;

    public CompilerContext(final ClassElementLoader classElementLoader) {
        this.classElementLoader = classElementLoader;
        this.methodResolver = new MethodResolver(
                classElementLoader.getTypes()
        );
        this.argumentBoxer = new ArgumentBoxer(
                classElementLoader,
                methodResolver
        );
    }

    public ClassElementLoader getClassElementLoader() {
        return classElementLoader;
    }

    public MethodResolver getMethodResolver() {
        return methodResolver;
    }

    public ArgumentBoxer getArgumentBoxer() {
        return argumentBoxer;
    }
}
