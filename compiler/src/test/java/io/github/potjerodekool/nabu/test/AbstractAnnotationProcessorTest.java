package io.github.potjerodekool.nabu.test;

import io.github.potjerodekool.nabu.compiler.annotation.processing.*;
import io.github.potjerodekool.nabu.compiler.annotation.processing.java.element.ElementWrapperFactory;
import io.github.potjerodekool.nabu.compiler.lang.support.java.JavaLanguageSupport;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.PathFileObject;
import io.github.potjerodekool.nabu.tools.diagnostic.ConsoleDiagnosticListener;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import org.junit.jupiter.api.Assertions;

import javax.annotation.processing.Processor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class AbstractAnnotationProcessorTest extends AbstractCompilerTest {

    protected final String SOURCE_OUTPUT_PATH = "target/gen-test-sources";

    protected void process(final Set<TypeElement> rootElements,
                           final List<Processor> processors) {
        final var context = getCompilerContext();
        final var loader = context.getClassElementLoader();
        final var elements = context.getElements();
        final var symbolTable = context.getSymbolTable();
        final var fileManager = context.getFileManager();
        final var types = context.getTypes();
        final var javacElements = new JavacElements(elements);
        final var javacTypes = new JavacTypes(types);
        final var options = context.getCompilerOptions();

        final var messager = new JavacMessager(new ConsoleDiagnosticListener(), elements);
        final var filer = new JavacFiler(
                symbolTable,
                elements,
                fileManager
        );

        final var environment = new JavacProcessingEnvironment(
                messager,
                filer,
                javacElements,
                javacTypes,
                options
        );

        for (final var processor : processors) {
            processor.init(environment);
        }

        final var wrappedRootElements = rootElements.stream()
                .map(this::wrapTypeElement)
                .collect(Collectors.toSet());

        final var processorStates = processors.stream()
                .map(processor -> {
                    final var supportedAnnotationTypes = processor.getSupportedAnnotationTypes();
                    final var loadedAnnotations = supportedAnnotationTypes.stream()
                            .map(className -> loadClass(loader, className))
                            .filter(Objects::nonNull)
                            .map(ElementWrapperFactory::wrap)
                            .map(clazz ->  (javax.lang.model.element.TypeElement) clazz)
                            .collect(Collectors.toSet());
                    return new ProcessorState(processor, loadedAnnotations);
                })
                .toList();

        environment.round(wrappedRootElements, processorStates);
    }

    private TypeElement loadClass(final ClassElementLoader loader,
                                  final String className) {
        return loader.loadClass(null, className);
    }

    private javax.lang.model.element.TypeElement wrapTypeElement(final TypeElement typeElement) {
        return (javax.lang.model.element.TypeElement) ElementWrapperFactory.wrap(typeElement);
    }


    protected void assertGeneratedSourceFileExists(final String fileName) {
        final var path = resolveSourceFilePath(fileName);

        Assertions.assertTrue(
                Files.exists(path),
                () -> {
                    final var absolutePath = path.toAbsolutePath().toString();
                    return String.format("%s doesn't exists", absolutePath);
                }
        );
    }

    private Path resolveSourceFilePath(final String fileName) {
        return Paths.get(SOURCE_OUTPUT_PATH, fileName);
    }

    protected CompilationUnit parseJavaSource(final String fileName) {
        final var path = resolveSourceFilePath(fileName);

        return new JavaLanguageSupport()
                .parse(
                        new PathFileObject(
                                new FileObject.Kind(".java", true),
                                path
                        ),
                        getCompilerContext()
                );
    }

    protected String createClassPath(String... pathElements) {
        return String.join(File.pathSeparator, pathElements);
    }
}
