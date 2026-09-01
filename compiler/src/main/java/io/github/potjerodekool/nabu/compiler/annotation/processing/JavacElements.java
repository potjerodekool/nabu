package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.JElement;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.JName;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.JPackageElement;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ToNabuMapper;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.type.TypeWrapperFactory;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;

import javax.lang.model.element.*;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JavacElements implements Elements {

    private final io.github.potjerodekool.nabu.util.Elements nabuElements;
    private final Map<CharSequence, PackageElement> packageCache = new HashMap<>();
    private final Map<CharSequence, TypeElement> typeElementCache = new HashMap<>();
    private final Map<CharSequence, Map<CharSequence, TypeElement>> moduleTypeElementCache = new HashMap<>();

    private final PackageElement DEFAULT_PACKAGE = new JPackageElement(new PackageSymbol(
            null,
            ""
    ));

    public JavacElements(final io.github.potjerodekool.nabu.util.Elements nabuElements) {
        this.nabuElements = nabuElements;
    }

    @Override
    public PackageElement getPackageElement(final CharSequence name) {
        return packageCache.computeIfAbsent(name, charSequence -> {
            final var nabuPackageElement = nabuElements.getPackageElement(name);
            if (nabuPackageElement == null) {
                return DEFAULT_PACKAGE;
            }
            return new JPackageElement(nabuPackageElement);
        });
    }

    @Override
    public TypeElement getTypeElement(final CharSequence name) {
        return typeElementCache.computeIfAbsent(name, (key) -> {
            final var typeElement = nabuElements.getTypeElement(name);

            if (typeElement == null) {
                return null;
            } else {
                return (TypeElement) ElementWrapperFactory.wrap(typeElement);
            }
        });
    }

    @Override
    public TypeElement getTypeElement(final ModuleElement module,
                                      final CharSequence name) {
        final var map = moduleTypeElementCache.computeIfAbsent(
                module.getQualifiedName(),
                (k) -> new HashMap<>()
        );

        return map.computeIfAbsent(name, (key) -> {
            final var typeElement = nabuElements.getTypeElement(
                    (io.github.potjerodekool.nabu.lang.model.element.ModuleElement) ElementWrapperFactory.unwrap(module),
                    key
            );

            if (typeElement == null) {
                return null;
            }

            return (TypeElement) ElementWrapperFactory.wrap(typeElement);
        });
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(final AnnotationMirror a) {
        @SuppressWarnings("unchecked")
        final var result = (Map<? extends ExecutableElement, ? extends AnnotationValue>) nabuElements.getElementValuesWithDefaults(ToNabuMapper.accept(a));
        return result;
    }

    @Override
    public String getDocComment(final Element e) {
        return nabuElements.getDocComment(ElementWrapperFactory.unwrap(e));
    }

    @Override
    public boolean isDeprecated(final Element e) {
        return nabuElements.isDeprecated(ElementWrapperFactory.unwrap(e));
    }

    @Override
    public Name getBinaryName(final TypeElement type) {
        final var binaryName = nabuElements.getBinaryName(
                (io.github.potjerodekool.nabu.lang.model.element.TypeElement) ElementWrapperFactory.unwrap(type)
        );
        return new JName(binaryName);
    }

    @Override
    public PackageElement getPackageOf(final Element e) {
        final var element = (JElement<?>) e;
        final var packageElement = nabuElements.getPackageOf(element.getOriginal());

        if (packageElement == null) {
            return DEFAULT_PACKAGE;
        } else {
            return getPackageElement(packageElement.getQualifiedName());
        }
    }

    @Override
    public List<? extends Element> getAllMembers(final TypeElement type) {
        return nabuElements.getAllMembers(
                (io.github.potjerodekool.nabu.lang.model.element.TypeElement) ElementWrapperFactory.unwrap(type)
        ).stream()
                .map(ElementWrapperFactory::wrap)
                .toList();
    }

    @Override
    public List<? extends AnnotationMirror> getAllAnnotationMirrors(final Element e) {
        return nabuElements.getAllAnnotationMirrors(ElementWrapperFactory.unwrap(e)).stream()
                .map(TypeWrapperFactory::wrap)
                .toList();
    }

    @Override
    public boolean hides(final Element hider,
                         final Element hidden) {
        return nabuElements.hides(ElementWrapperFactory.unwrap(hider), ElementWrapperFactory.unwrap(hidden));
    }

    @Override
    public boolean overrides(final ExecutableElement overrider,
                             final ExecutableElement overridden,
                             final TypeElement type) {
        return nabuElements.overrides(
                (io.github.potjerodekool.nabu.lang.model.element.ExecutableElement) ElementWrapperFactory.unwrap(overrider),
                (io.github.potjerodekool.nabu.lang.model.element.ExecutableElement) ElementWrapperFactory.unwrap(overridden),
                (io.github.potjerodekool.nabu.lang.model.element.TypeElement) ElementWrapperFactory.unwrap(type)
        );
    }

    @Override
    public String getConstantExpression(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value.toString();
    }

    @Override
    public void printElements(final Writer w, final Element... elements) {
        try {
            for (final var element : elements) {
                w.write(element.toString());
                w.write(System.lineSeparator());
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Name getName(final CharSequence cs) {
        return new JName(cs.toString());
    }

    @Override
    public boolean isFunctionalInterface(final TypeElement type) {
        return nabuElements.isFunctionalInterface(
                (io.github.potjerodekool.nabu.lang.model.element.TypeElement) ElementWrapperFactory.unwrap(type)
        );
    }

    @Override
    public ModuleElement getModuleOf(final Element e) {
        return (ModuleElement) ElementWrapperFactory.wrap(nabuElements.getModuleOf(ElementWrapperFactory.unwrap(e)));
    }
}
