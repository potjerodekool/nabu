package io.github.potjerodekool.nabu.compiler.lang.support.java.lombok;

import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeFilter;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.util.Types;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * If lombok is on the classpath then classes are processed
 * and lombok annotations are handled by annotation handlers.
 */
public class Lombok {

    private final CompilerContext context;
    private final Types types;
    private DetectionState detectionState = DetectionState.RESOLVE;
    private final Map<String, AnnotationHandler> handlerMap = new HashMap<>();

    public Lombok(final CompilerContext context) {
        this.context = context;
        this.types = context.getTypes();
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
        registerHandler(new HandleGetter(types));
        registerHandler(new HandleSetter(types));
    }

    private void registerHandler(final AnnotationHandler annotationHandler) {
        this.handlerMap.put(annotationHandler.getAnnotationName(), annotationHandler);
    }

    private void acceptTree(final Tree tree) {
        switch (tree) {
            case ClassDeclaration classDeclaration -> {
                processClass(classDeclaration);
                TreeFilter.fieldsIn(classDeclaration.getEnclosedElements()).forEach(field -> acceptTree(field));
            }
            default -> {
            }
        }
    }

    private void processClass(final ClassDeclaration classDeclaration) {
        classDeclaration.getModifiers().getAnnotations().forEach(annotationTree -> {
            final var annotationClass = annotationTree.getName().getType().asTypeElement();
            final var annotationName = annotationClass.getQualifiedName();

            if (annotationName.startsWith("lombok.")) {
                final var handler = this.handlerMap.get(annotationName);

                if (handler != null) {
                    handler.handle(classDeclaration);
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