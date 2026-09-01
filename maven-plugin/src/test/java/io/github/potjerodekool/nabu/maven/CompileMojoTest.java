package io.github.potjerodekool.nabu.maven;

import org.junit.jupiter.api.Test;

import java.util.List;

class CompileMojoTest {

    @Test
    void filterNabuSourceRoots() throws NoSuchFieldException, IllegalAccessException {
        final var mojo = new CompileMojo();
        setField("generatedSourcesDirectory", "C:\\temp\\fakedir", mojo);

        final var filterdList = mojo.filterNabuSourceRoots(List.of(
                "C:\\projects\\petstore\\src\\main\\java",
                "C:\\projects\\petstore\\src\\main\\nabu"
        ));

        System.out.println(filterdList);
    }

    private void setField(final String fieldName,
                          final Object value,
                          final CompileMojo mojo) throws NoSuchFieldException, IllegalAccessException {
        final var field = mojo.getClass().getDeclaredField(fieldName);
        try {
            field.trySetAccessible();
            field.set(mojo, value);
        } finally {
            field.setAccessible(false);
        }
    }
}