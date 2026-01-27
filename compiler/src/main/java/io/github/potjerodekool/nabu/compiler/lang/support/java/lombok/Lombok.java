package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.lang.support.java.lombok.handler.*;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;

import java.io.IOException;
import java.util.*;

/**
 * If lombok is on the classpath then classes are processed
 * and lombok annotations are handled by annotation handlers.
 */
public class Lombok {

    private final CompilerContext context;
    private DetectionState detectionState = DetectionState.RESOLVE;
    private final Map<String, AnnotationHandler> handlerMap = new HashMap<>();

    public Lombok(final CompilerContext context) {
        this.context = context;
    }

    public void apply(final ClassDeclaration classDeclaration) {
        detectLombok(context);

        if (detectionState == DetectionState.NOT_FOUND) {
            return;
        }

        acceptTree(classDeclaration);
    }

    private void detectLombok(final CompilerContext context) {
        if (detectionState == DetectionState.RESOLVE) {
            detectionState = DetectionState.NOT_FOUND;

            final var fileManager = context.getFileManager();
            final var files = fileManager.list(
                    StandardLocation.CLASS_PATH,
                    "META-INF",
                    Set.of(new FileObject.Kind(".MF", false)));

            final var iterator = files.iterator();
            final var properties = new Properties();
            String moduleName;

            while (detectionState == DetectionState.NOT_FOUND
                    && iterator.hasNext()) {
                final var file = iterator.next();
                properties.clear();
                try (var input = file.openInputStream()) {
                    properties.load(input);
                    moduleName = properties.getProperty("Automatic-Module-Name");

                    if ("lombok".equals(moduleName)) {
                        detectionState = DetectionState.FOUND;
                        initHandlers();
                    }
                } catch (final IOException ignored) {
                }
            }
        }
    }

    private void initHandlers() {
        final var getterHandler = new GetterHandler(context);
        final var setterHandler = new SetterHandler(context);
        final var requiredArgsConstructorHandler = new RequiredArgsConstructorHandler(context);

        registerHandler(getterHandler);
        registerHandler(setterHandler);
        registerHandler(new NoArgsConstructorHandler(context));
        registerHandler(requiredArgsConstructorHandler);
        registerHandler(new AllArgsConstructorHandler(context));
        registerHandler(new DataHandler(
                getterHandler,
                setterHandler,
                requiredArgsConstructorHandler
        ));
    }

    private void registerHandler(final AnnotationHandler annotationHandler) {
        this.handlerMap.put(annotationHandler.getAnnotationName(), annotationHandler);
    }

    private void acceptTree(final Tree tree) {
        if (Objects.requireNonNull(tree) instanceof ClassDeclaration classDeclaration) {
            processClass((ClassSymbol) classDeclaration.getClassSymbol());
            TreeFilter.fieldsIn(classDeclaration.getEnclosedElements()).forEach(this::acceptTree);
        }
    }

    private void processClass(final ClassSymbol classSymbol) {
        classSymbol.getAnnotationMirrors().forEach(annotation -> {
            final var annotationClass = annotation.getAnnotationType().asTypeElement();
            final var annotationName = annotationClass.getQualifiedName();

            if (annotationName.startsWith("lombok.")) {
                final var handler = this.handlerMap.get(annotationName);

                if (handler != null) {
                    handler.handle(classSymbol);
                }
            }
        });
    }

}

enum DetectionState {
    RESOLVE,
    FOUND,
    NOT_FOUND
}