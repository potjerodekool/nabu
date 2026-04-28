package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ElementsImplTest extends AbstractCompilerTest {

    @Test
    void getAllMembers() {
        final var arrayList = loadClass("java.util.ArrayList");
        final var allMembers = getCompilerContext().getElements().getAllMembers(arrayList);
        final var methods = allMembers.stream()
                .flatMap(CollectionUtils.mapOnly(ExecutableElement.class))
                .filter(method -> method.getSimpleName().equals("get"))
                .toList();

        methods.forEach(method -> {
            final var owner = method.getEnclosingElement();
            System.out.println(owner.getSimpleName() + "." + method.getSimpleName());
        });


    }
}