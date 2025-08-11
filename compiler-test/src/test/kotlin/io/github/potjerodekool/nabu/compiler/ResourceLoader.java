package io.github.potjerodekool.nabu.compiler;

import java.io.IOException;

public class ResourceLoader {

    private ResourceLoader() {
    }

    public static String readAsString(final String resourceName) throws IOException {
        try (var inputStream = ResourceLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            final var string = new String(inputStream.readAllBytes());
            return string.replace("\r", "");
        }
    }
}
