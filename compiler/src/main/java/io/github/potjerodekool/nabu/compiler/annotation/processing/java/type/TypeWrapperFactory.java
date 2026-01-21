package io.github.potjerodekool.nabu.compiler.annotation.processing.java.type;

import io.github.potjerodekool.nabu.lang.model.element.AnnotationMirror;
import io.github.potjerodekool.nabu.lang.model.element.AnnotationValue;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.type.*;

import javax.lang.model.type.TypeKind;

public final class TypeWrapperFactory {

    private TypeWrapperFactory() {
    }

    public static javax.lang.model.type.TypeMirror wrap(final TypeMirror typeMirror) {
        if (typeMirror == null) {
            return new JNoType(TypeKind.NONE, typeMirror);
        }
        return switch (typeMirror.getKind()) {
            case DECLARED -> new JDeclaredType((DeclaredType) typeMirror);
            case INT -> new JPrimitiveType(TypeKind.INT, (PrimitiveType) typeMirror);
            case BYTE -> new JPrimitiveType(TypeKind.BYTE, (PrimitiveType) typeMirror);
            case BOOLEAN -> new JPrimitiveType(TypeKind.BOOLEAN, (PrimitiveType) typeMirror);
            case CHAR -> new JPrimitiveType(TypeKind.CHAR, (PrimitiveType) typeMirror);
            case LONG -> new JPrimitiveType(TypeKind.LONG, (PrimitiveType) typeMirror);
            case DOUBLE -> new JPrimitiveType(TypeKind.DOUBLE, (PrimitiveType) typeMirror);
            case FLOAT -> new JPrimitiveType(TypeKind.FLOAT, (PrimitiveType) typeMirror);
            case SHORT -> new JPrimitiveType(TypeKind.SHORT, (PrimitiveType) typeMirror);
            case VOID -> new JNoType(TypeKind.VOID, typeMirror);
            case EXECUTABLE -> new JExecutableType((ExecutableType) typeMirror);
            case TYPEVAR -> new JTypeVariable(typeMirror);
            case WILDCARD -> new JWildcartType((WildcardType) typeMirror);
            default -> throw new TodoException("" + typeMirror.getKind());
        };
    }

    public static javax.lang.model.element.AnnotationMirror wrap(final AnnotationMirror annotationMirror) {
       return new JAnnotationMirror(annotationMirror);
    }

    public static <T extends TypeMirror> T unwrap(final javax.lang.model.type.TypeMirror typeMirror) {
        return typeMirror != null
                ? (T) ((JAbstractType<?>)typeMirror).getOriginal()
                : null;
    }

}
