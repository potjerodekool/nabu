package io.github.potjerodekool.nabu.util;

import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.lang.model.element.*;

import java.io.Writer;
import java.util.*;

/**
 * Utility methods for working on elements.
 */
public interface Elements {

    /**
     * @param name A package name.
     * @return Return the package with the given name if it exists or else null.
     */
    PackageElement getPackageElement(CharSequence name);

    /**
     * @param module A module
     * @param name A package name
     * @return Return the package with the given name if it exists in the module or else null.
     */
    PackageElement getPackageElement(ModuleElement module,
                                     CharSequence name);

    /**
     * @param name A package name.
     * @return Returns a Set of packages with the given name.
     */
    Set<? extends PackageElement> getAllPackageElements(String name);

    /**
     * @param name A type name.
     * @return Returns the class with the given name if it exists or else null.
     */
    TypeElement getTypeElement(CharSequence name);

    /**
     * @param name A type name.
     * @return Returns the class with the given name if it exists in the module or else null.
     */
    TypeElement getTypeElement(ModuleElement module, CharSequence name);

    /**
     * @param name A type name.
     * @return Returns a set of classes with the given name.
     */
    Set<? extends TypeElement> getAllTypeElements(String name);

    /**
     * @param name A module name.
     * @return Returns the module element with the given name if it exists or else null.
     */
    ModuleElement getModuleElement(String name);

    /**
     * @return Returns a set of all modules.
     */
    Set<? extends ModuleElement> getAllModuleElements();

    /**
     * @param a An annotation.
     * @return Returns the element values of the annotation.
     * For those elements who haven't a value the default value is returned.
     */
    Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(AnnotationMirror a);

    /**
     * @param e An element.
     * @return Return the document comment of the element of null if it is absent.
     */
    String getDocComment(Element e);

    /**
     * @param e An element.
     * @return Returns true if the element is annotated with the Deprecated annotation.
     */
    boolean isDeprecated(Element e);

    /**
     * @param e An element.
     * @return Returns the origin of the element.
     */
    default Elements.Origin getOrigin(Element e) {
        return Origin.EXPLICIT;
    }

    /**
     * @param c An annotated construct.
     * @param a An annotation.
     * @return Returns the origin.
     */
    default Origin getOrigin(AnnotatedConstruct c,
                             AnnotationMirror a) {
        return Origin.EXPLICIT;
    }

    /**
     * @param m A module.
     * @param directive A module directive.
     * @return Returns the origin.
     */
    default Origin getOrigin(ModuleElement m,
                             ModuleElement.Directive directive) {
        return Elements.Origin.EXPLICIT;
    }

    enum Origin {
        EXPLICIT,

        MANDATED,

        SYNTHETIC;

        public boolean isDeclared() {
            return this != SYNTHETIC;
        }
    }

    /**
     * @param e An executable element.
     * @return Returns true if the executable element is a bridge.
     */
    default boolean isBridge(ExecutableElement e) {
        return false;
    }

    /**
     * @param type A type element.
     * @return Returns the binary name of the given type element.
     */
    String getBinaryName(TypeElement type);

    /**
     * @param e An element.
     * @return Returns the package of the given element.
     */
    PackageElement getPackageOf(Element e);

    /**
     * @param e An element.
     * @return Returns the module of the given element or null if the element is not part of a module.
     */
    default ModuleElement getModuleOf(Element e) {
        return null;
    }

    /**
     * @param type A type element.
     * @return Returns all members of the given type element.
     */
    List<? extends Element> getAllMembers(TypeElement type);

    /**
     * @param e An element.
     * @return Returns the outermost type element of the given element.
     */
    default TypeElement getOutermostTypeElement(Element e) {
        return switch (e.getKind()) {
            case PACKAGE,
                 MODULE,
                 OTHER -> null;
            default -> {
                Element enclosing = e;

                do {
                    final var possibleTypeElement = ElementFilter.typesIn(List.of(enclosing));

                    if (!possibleTypeElement.isEmpty()) {
                        var typeElement = possibleTypeElement.getFirst();
                        if (typeElement.getNestingKind() == NestingKind.TOP_LEVEL) {
                            yield typeElement;
                        }
                    }
                    enclosing = enclosing.getEnclosingElement();
                } while (true);
            }
        };
    }

    /**
     * @param e An element.
     * @return Returns all annotations of the given element.
     */
    List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e);

    /**
     * @param hider An element.
     * @param hidden Another element.
     * @return Returns true if hider hides the hidden element.
     */
    boolean hides(Element hider,
                  Element hidden);

    /**
     * @param overrider An element.
     * @param overridden Another element.
     * @param type A type element.
     * @return Returns true if the overrider element overrides the overridden element in the given type.
     */
    boolean overrides(ExecutableElement overrider,
                      ExecutableElement overridden,
                      TypeElement type);

    /**
     * @param value A value.
     * @return Return the constant expression of the given value of null if it isn't a constant value.
     */
    String getConstantExpression(Object value);

    /**
     * @param w A writer.
     * @param elements Some elements.
     * Prints the elements with the given writer.
     */
    void printElements(Writer w,
                       Element... elements);

    /**
     * @param type A type.
     * @return Returns true if the given type is a functional interface.
     */
    boolean isFunctionalInterface(TypeElement type);

    /**
     * @param module A module.
     * @return Returns true if the module is an automatic module.
     */
    boolean isAutomaticModule(ModuleElement module);

    /**
     * @param accessor An executable element.
     * @return Return the record component for the given executable element or null.
     */
    RecordComponentElement recordComponentFor(ExecutableElement accessor);

    /**
     * @param e An executable element.
     * @return Returns true if the given executable element is a canonical constructor.
     */
    boolean isCanonicalConstructor(ExecutableElement e);

    /**
     * @param e An executable element.
     * @return Return true if the given executable element is a compact constructor.
     */
    boolean isCompactConstructor(ExecutableElement e);

    /**
     * @param e An element.
     * @return Return the file object of the given element or null.
     */
    FileObject getFileObjectOf(Element e);
}
