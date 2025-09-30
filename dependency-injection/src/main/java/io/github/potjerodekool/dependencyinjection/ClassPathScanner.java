package io.github.potjerodekool.dependencyinjection;

import io.github.potjerodekool.dependencyinjection.bean.BeanDefinition;
import io.github.potjerodekool.dependencyinjection.bean.AutoConfigReader;
import org.objectweb.asm.ClassReader;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class ClassPathScanner {

    public List<BeanDefinition<?>> scan() {
        final var beanDefinitions = new ArrayList<BeanDefinition<?>>();

        try {
            final var resources = getClassLoader()
                    .getResources("META-INF/io.github.potjerodekool.autoconfig.AutoConfiguration");

            final var iterator = resources.asIterator();

            while (iterator.hasNext()) {
                final var resource = iterator.next().toURI();
                read(resource, beanDefinitions);
            }
        } catch (final Exception ignored) {
            //Ignore
        }

        return beanDefinitions;
    }

    private void read(final URI uri,
                      final ArrayList<BeanDefinition<?>> beanDefinitions) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(uri.toURL().openStream())))) {
            String line;

            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !line.startsWith("#")) {
                    beanDefinitions.addAll(loadConfiguration(line));
                }
            }
        }
    }


    private ClassLoader getClassLoader() {
        final var classLoader = ClassPathScanner.class.getClassLoader();
        return classLoader != null
                ? classLoader
                : ClassLoader.getSystemClassLoader();
    }

    private List<BeanDefinition<?>> loadConfiguration(final String className) {
        final var classLoader = getClass().getClassLoader();

        try (var inputStream = classLoader.getResourceAsStream(
                className.replace('.', '/') + ".class")) {

            if (inputStream != null) {
                final var data = inputStream.readAllBytes();
                final var instance = classLoader.loadClass(className).getDeclaredConstructor().newInstance();
                final var classReader = new ClassReader(data);
                final var reader = new AutoConfigReader(instance);
                classReader.accept(reader, 0);
                return reader.getBeanDefinitions();
            }
        } catch (final Exception ignored) {
            //Ignore
        }
        return List.of();
    }
}

