package io.github.potjerodekool.nabu.compiler.annotation.processing;

import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.util.Pair;

import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;
import java.util.stream.Collectors;

public class JavacProcessingEnvironment implements ProcessingEnvironment {

    private final Messager messager;
    private final JavacFiler filer;
    private final Elements elements;
    private final Types types;
    private final Map<String, String> options;

    public JavacProcessingEnvironment(final Messager messager,
                                      final JavacFiler filer,
                                      final Elements elements,
                                      final Types types,
                                      final CompilerOptions options) {
        this.messager = messager;
        this.filer = filer;
        this.elements = elements;
        this.types = types;
        this.options = extractAnnotationProcessorsOptions(options);
    }

    private static Map<String, String> extractAnnotationProcessorsOptions(final CompilerOptions options) {
        return options.stream()
                .filter(entry -> entry.getKey().optionName().startsWith("-A"))
                .map(entry -> new Pair<>(
                        entry.getKey().optionName().substring(2),
                        entry.getValue())
                ).collect(Collectors.toUnmodifiableMap(
                        Pair::first,
                        Pair::second
                ));
    }

    @Override
    public Map<String, String> getOptions() {
        return options;
    }

    @Override
    public Messager getMessager() {
        return messager;
    }

    @Override
    public JavacFiler getFiler() {
        return filer;
    }

    @Override
    public Elements getElementUtils() {
        return elements;
    }

    @Override
    public Types getTypeUtils() {
        return types;
    }

    @Override
    public SourceVersion getSourceVersion() {
        return SourceVersion.RELEASE_17;
    }

    @Override
    public Locale getLocale() {
        throw new TodoException();
    }

    public void round(final Set<TypeElement> rootElements,
                      final List<ProcessorState> processorStates) {
        final var roundEnvironment = new JavacRoundEnvironment(false, rootElements);

        processorStates.forEach(processorState -> {
            final var processor = processorState.getProcessor();
            processor.process(processorState.getAnnotations(), roundEnvironment);
        });
    }
}
