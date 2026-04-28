package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tools.TodoException;

public final class AccessChecker {

    private AccessChecker() {
    }

    public static boolean isAccessible(final Element element,
                                       final TypeElement caller) {
        final var callerModule = caller.getModuleElement().orElse(null);

        if (element instanceof TypeElement typeElement) {
            return isAccessible(typeElement, caller, callerModule);
        }

        return false;
    }

    private static boolean isAccessible(final TypeElement element,
                                        final TypeElement caller,
                                        final ModuleElement callerModule) {
        if (element.isPublic()) {
            final var moduleOptional = element.getModuleElement();
            if (!moduleOptional.isEmpty()) {
                return true;
            } else {
                throw new TodoException();
            }
        }

        throw new TodoException();
    }

    private boolean isSameModule(final ModuleElement first,
                                 final ModuleElement second) {
        return first == second;
    }
}
