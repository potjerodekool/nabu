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

/**
 * Object to get utils.
 */
public interface CompilerContext extends AutoCloseable {

    /**
     * @return Returns the loader to load classes.
     */
    ClassElementLoader getClassElementLoader();

    /**
     * @return Returns the utility for elements.
     */
    Elements getElements();

    /**
     * @return Return the resolver to resolve method calls.
     */
    MethodResolver getMethodResolver();

    /**
     * @return Returns the utility to box or unbox arguments.
     */
    ArgumentBoxer getArgumentBoxer();

    /**
     * @param searchType A searchType.
     * @param scope A scope.
     * @return Returns an optional ElementResolver.
     */
    Optional<ElementResolver> findSymbolResolver(TypeMirror searchType,
                                                 Scope scope);

    /**
     * @return Returns a builder for elements.
     */
    ElementBuilders getElementBuilders();
}
