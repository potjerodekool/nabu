package io.github.potjerodekool.nabu.compiler.annotation.processing;

import javax.annotation.processing.Processor;
import javax.lang.model.element.TypeElement;
import java.util.List;
import java.util.Set;

public class ProcessorState {

    private final Processor processor;

    private final Set<? extends TypeElement> annotations;

    public ProcessorState(final Processor processor,
                          final Set<? extends TypeElement> annotations) {
        this.processor = processor;
        this.annotations = annotations;
        processor.getSupportedOptions();
        processor.getSupportedSourceVersion();
    }

    public Processor getProcessor() {
        return processor;
    }

    public Set<? extends TypeElement> getAnnotations() {
        return annotations;
    }
}

