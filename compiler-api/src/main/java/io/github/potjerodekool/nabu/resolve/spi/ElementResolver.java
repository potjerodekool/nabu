package io.github.potjerodekool.nabu.resolve.spi;

import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.type.TypeMirror;

/**
 * A resolver to resolve elements fields.
 * May be implemented for implementing DSLs.
 */
public interface ElementResolver {

    /**
     * @param name A field name.
     * @param searchType A search type to search on.
     * @return Returns the resolved field or null.
     */
    Element resolve(String name,
                    TypeMirror searchType);

    /**
     * @param searchType A type.
     * @param compilerContext Compiler context.
     * @param scope Scope
     * @return Returns true if the given searchType is supported to search on by this resolver.
     */
    boolean supports(TypeMirror searchType,
                     CompilerContext compilerContext,
                     Scope scope);
}
