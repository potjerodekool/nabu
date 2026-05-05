package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.tools.TodoException;

public final class AccessChecker {

    private AccessChecker() {
    }

    public static boolean isAccessible(final Element element,
                                       final Element currentElement) {
        final var callerModule = currentElement.getModuleElement();

        if (element instanceof TypeElement typeElement) {
            return isAccessible(typeElement, callerModule, currentElement);
        } else {
            return isAccessibleMember(element, currentElement);
        }
    }

    private static boolean isAccessibleMember(final Element element,
                                              final Element caller) {
        if (element.getKind() == ElementKind.INTERFACE && element.isDefaultAccess()) {
            return true;
        }

        if (element.isPublic()) {
            return true;
        } else if (element.isProtected()) {
            if (isSameModule(element.getModuleElement(), caller.getModuleElement())) {
                return element.getPackageElement().equals(caller.getPackageElement());
            }
        } else if (element.isDefaultAccess()) {
            if (isSameModule(element.getModuleElement(), caller.getModuleElement())) {
                return element.getPackageElement().equals(caller.getPackageElement());
            }
        } else if (element.isPrivate()) {
            return element == caller;
        }

        return false;
    }

    private static boolean isAccessible(final TypeElement element,
                                        final ModuleElement callerModule,
                                        final Element caller) {
        if (element.isPublic()) {
            return isAccessiblePublic(element, callerModule);
        } else if (element.isDefaultAccess()) {
            return isAccessibleDefaultAccess(element, callerModule, caller);
        }

        throw new TodoException();
    }

    private static boolean isAccessiblePublic(final TypeElement element,
                                              final ModuleElement callerModule) {
        final var module = element.getModuleElement();

        if (module != null) {
            if (isSameModule(module, callerModule)) {
                return true;
            } else {
                return isVisibleToModule(
                        element,
                        module,
                        callerModule
                );
            }
        } else {
            return true;
        }
    }

    private static boolean isAccessibleDefaultAccess(final TypeElement element,
                                                     final ModuleElement callerModule,
                                                     final Element caller) {
        return isSameModule(element.getModuleElement(), callerModule)
                || element.getPackageElement().equals(caller.getPackageElement());
    }

    private static boolean isVisibleToModule(final TypeElement element,
                                             final ModuleElement module,
                                             final ModuleElement callerModule) {
        final var packageElement = findPackage(element);
        final var exportOptional = module.getExports().stream()
                .filter(export -> export.getPackage() == packageElement)
                .findFirst();

        if (exportOptional.isEmpty()) {
            return false;
        } else {
            final var export = exportOptional.get();
            return export.getTargetModules().isEmpty() || export.getTargetModules().contains(callerModule);
        }
    }

    private static PackageElement findPackage(final Element element) {
        if (element instanceof PackageElement packageElement) {
            return packageElement;
        } else if (element instanceof TypeElement typeElement) {
            return findPackage(typeElement.getEnclosingElement());
        } else {
            return null;
        }
    }

    private static boolean isSameModule(final ModuleElement first,
                                        final ModuleElement second) {
        return first == second;
    }
}
