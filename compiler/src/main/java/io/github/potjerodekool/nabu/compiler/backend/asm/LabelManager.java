package io.github.potjerodekool.nabu.compiler.backend.asm;

import org.objectweb.asm.Label;
import java.util.HashMap;
import java.util.Map;

public class LabelManager {
    private final Map<String, Label> labels = new HashMap<>();

    public Label getLabel(String name) {
        return labels.computeIfAbsent(name, k -> new Label());
    }
}