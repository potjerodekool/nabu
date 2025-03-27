package io.github.potjerodekool.nabu.compiler.util;

import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.ast.element.*;

import java.io.Writer;
import java.util.*;

public interface Elements {

    PackageElement getPackageElement(String name);

    PackageElement getPackageElement(ModuleElement module, String name);

    Set<? extends PackageElement> getAllPackageElements(String name);

    TypeElement getTypeElement(String name);

    TypeElement getTypeElement(ModuleElement module, String name);

    Set<? extends TypeElement> getAllTypeElements(String name);

    ModuleElement getModuleElement(String name);

    default Set<? extends ModuleElement> getAllModuleElements() {
        return Collections.emptySet();
    }

    Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(AnnotationMirror a);

    String getDocComment(Element e);

    boolean isDeprecated(Element e);

    default Elements.Origin getOrigin(Element e) {
        return Origin.EXPLICIT;
    }

    default Origin getOrigin(AnnotatedConstruct c,
                             AnnotationMirror a) {
        return Origin.EXPLICIT;
    }

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

    default boolean isBridge(ExecutableElement e) {
        return false;
    }

    String getBinaryName(TypeElement type);

    PackageElement getPackageOf(Element e);

    default ModuleElement getModuleOf(Element e) {
        return null;
    }

    List<? extends Element> getAllMembers(TypeElement type);

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

    List<? extends AnnotationMirror> getAllAnnotationMirrors(Element e);

    boolean hides(Element hider, Element hidden);

    boolean overrides(ExecutableElement overrider, ExecutableElement overridden,
                      TypeElement type);

    String getConstantExpression(Object value);

    void printElements(Writer w, Element... elements);

    boolean isFunctionalInterface(TypeElement type);

    default boolean isAutomaticModule(ModuleElement module) {
        return false;
    }

    default RecordComponentElement recordComponentFor(ExecutableElement accessor) {
        if (accessor.getEnclosingElement().getKind() == ElementKind.RECORD) {

            return ElementFilter.recordComponentsIn(accessor.getEnclosingElement().getEnclosedElements()).stream()
                    .filter(record -> Objects.equals(
                            record.getAccessor(),
                            accessor
                    ))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    default boolean isCanonicalConstructor(ExecutableElement e) {
        return false;
    }

    default boolean isCompactConstructor(ExecutableElement e) {
        return false;
    }

    default FileObject getFileObjectOf(Element e) {
        throw new UnsupportedOperationException();
    }
}
