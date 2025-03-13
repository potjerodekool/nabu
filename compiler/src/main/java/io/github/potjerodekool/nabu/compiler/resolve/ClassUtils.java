package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;

public final class ClassUtils {

    private ClassUtils() {
    }

    public static String getInternalName(final String name) {
        return name.replace(".", "/");
    }

    public static String getClassName(final String innerName) {
        return innerName.replace('/', '.');
    }

    public static String getClassDescriptor(final String className) {
        final var internalName = ClassUtils.getInternalName(className);
        return "L" + internalName + ";";
    }

    public static String getDescriptor(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return getClassDescriptor(declaredType.getTypeElement().getQualifiedName());
        }

        throw new TodoException();
    }
    }
