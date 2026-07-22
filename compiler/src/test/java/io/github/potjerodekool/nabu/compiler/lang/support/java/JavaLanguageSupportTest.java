package io.github.potjerodekool.nabu.compiler.lang.support.java;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

class JavaLanguageSupportTest extends AbstractCompilerTest {

    @Override
    protected String getClassPath() {
        return "C:\\Users\\evert\\.m2\\repository\\org\\projectlombok\\lombok\\1.18.42\\lombok-1.18.42.jar";
    }

    @Test
    void parse() {
        final var parser = new JavaLanguageSupport();
        final var fileObject = createFileObject(
                """                        
                        public class MyClass {
                            private final Map<String, byte[]> imageStore = new HashMap<String, byte[]>();
                        }"""
        );

        final var cu = parser.parse(
                fileObject,
                getCompilerContext()
        );

        // C:\Users\evert\.m2\repository\org\projectlombok\lombok\1.18.42\lombok-1.18.42.jar!\META-INF\MANIFEST.MF
        System.out.println(cu);
    }

    @Test
    void findLombok() {
        try {
            var resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
            resources.asIterator().forEachRemaining(resource -> {
                try (var input = resource.openStream()) {
                    final var properties = new Properties();
                    properties.load(input);
                    System.out.println("module " + properties.getProperty("Automatic-Module-Name"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testValidClassDeclaration() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("public class Test { }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testValidMethodWithParameters() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method(int x, String y) { } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }

    @Test
    public void testValidLoopStatement() throws IOException {
        InputStream inputStream = new ByteArrayInputStream("public class Test { void method() { for(int i=0; i<10; i++) { } } }".getBytes());
        JavaCompilerParser.parse(inputStream);
    }
}