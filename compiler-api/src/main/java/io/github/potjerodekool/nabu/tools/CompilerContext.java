package io.github.potjerodekool.nabu.tools;

import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilders;
import io.github.potjerodekool.nabu.resolve.ArgumentBoxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.resolve.spi.ElementResolver;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Elements;

import java.util.Optional;

public interface CompilerContext extends AutoCloseable {
    ClassElementLoader getClassElementLoader();

    Elements getElements();

    MethodResolver getMethodResolver();

    ArgumentBoxer getArgumentBoxer();

    Optional<ElementResolver> findSymbolResolver(TypeMirror searchType,
                                                 Scope scope);

    ElementBuilders getElementBuilders();
}
