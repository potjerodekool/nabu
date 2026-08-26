package io.github.potjerodekool.nabu.compiler.backend.asm;

import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.List;

/**
 * Builds JVM generic signatures ({@code Signature} attribute) from Nabu TypeMirrors.
 *
 * @see <a href="https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html#jvms-4.7.9.1">
 *     JVM Spec §4.7.9.1 – Signature Attribute</a>
 */
public final class JvmSignatureBuilder {

    private JvmSignatureBuilder() {}

    /**
     * Builds a class signature: {@code <T:Ljava/lang/Object;>Ljava/lang/Object;Linterface<TT;>;}
     */
    public static String buildClassSignature(TypeElement typeElement) {
        final var typeParams = typeElement.getTypeParameters();
        final var superclass = typeElement.getSuperclass();
        final var interfaces = typeElement.getInterfaces();

        final boolean hasTypeParams = !typeParams.isEmpty();
        final boolean isObjectSuper = isJavaLangObject(superclass);
        final boolean hasInterfaces = !interfaces.isEmpty();

        if (!hasTypeParams && isObjectSuper && !hasInterfaces) {
            return null;
        }

        final var sb = new StringBuilder();

        if (hasTypeParams) {
            sb.append('<');
            for (final var tp : typeParams) {
                sb.append(tp.getSimpleName());
                sb.append(':');
                final var bounds = tp.getBounds();
                if (bounds.isEmpty()) {
                    sb.append("Ljava/lang/Object;");
                } else {
                    for (int i = 0; i < bounds.size(); i++) {
                        if (i > 0) sb.append(':');
                        appendTypeMirror(sb, bounds.get(i));
                    }
                }
            }
            sb.append('>');
        }

        appendTypeMirror(sb, superclass);

        for (final var iface : interfaces) {
            appendTypeMirror(sb, iface);
        }

        return sb.toString();
    }

    /**
     * Builds a field signature: {@code Ljava/util/List<Ljava/lang/String;>;}
     */
    public static String buildFieldSignature(TypeMirror type) {
        if (type == null) return null;
        if (type.getKind().isPrimitive()) return null;

        final var sb = new StringBuilder();
        appendTypeMirror(sb, type);
        final var result = sb.toString();
        return result.equals("Ljava/lang/Object;") ? null : result;
    }

    /**
     * Builds a method signature: {@code <T:Ljava/lang/Object;>(TT;)Ljava/lang/String;}
     */
    public static String buildMethodSignature(TypeParameterElement[] typeParams,
                                               TypeMirror returnType,
                                               List<? extends TypeMirror> paramTypes) {
        final var sb = new StringBuilder();

        if (typeParams.length > 0) {
            sb.append('<');
            for (final var tp : typeParams) {
                sb.append(tp.getSimpleName());
                sb.append(':');
                final var bounds = tp.getBounds();
                if (bounds.isEmpty()) {
                    sb.append("Ljava/lang/Object;");
                } else {
                    for (int i = 0; i < bounds.size(); i++) {
                        if (i > 0) sb.append(':');
                        appendTypeMirror(sb, bounds.get(i));
                    }
                }
            }
            sb.append('>');
        }

        sb.append('(');
        for (final var param : paramTypes) {
            appendTypeMirror(sb, param);
        }
        sb.append(')');

        appendTypeMirror(sb, returnType);

        return sb.toString();
    }

    private static boolean isJavaLangObject(TypeMirror type) {
        if (type instanceof DeclaredType declared) {
            if (declared.asElement() instanceof TypeElement te) {
                return "java.lang.Object".equals(te.getQualifiedName());
            }
        }
        return false;
    }

    /**
     * Appends the JVM internal-form signature for a TypeMirror.
     */
    static void appendTypeMirror(StringBuilder sb, TypeMirror type) {
        if (type == null) {
            sb.append('L');
            sb.append("java/lang/Object");
            sb.append(';');
            return;
        }

        switch (type.getKind()) {
            case BYTE    -> sb.append('B');
            case SHORT   -> sb.append('S');
            case CHAR    -> sb.append('C');
            case INT     -> sb.append('I');
            case LONG    -> sb.append('J');
            case FLOAT   -> sb.append('F');
            case DOUBLE  -> sb.append('D');
            case BOOLEAN -> sb.append('Z');
            case VOID    -> sb.append('V');

            case ARRAY -> {
                sb.append('[');
                appendTypeMirror(sb, getComponentType(type));
            }

            case TYPEVAR -> {
                if (type instanceof io.github.potjerodekool.nabu.type.TypeVariable tv) {
                    final var element = tv.asElement();
                    if (element instanceof io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement tpe) {
                        sb.append(tpe.getSimpleName());
                    } else {
                        sb.append(element.getSimpleName());
                    }
                } else {
                    sb.append(type.toString());
                }
            }

            case WILDCARD -> {
                sb.append('?');
                final var upper = type.getUpperBound();
                final var lower = type.getLowerBound();
                if (lower != null && lower.getKind() != TypeKind.NONE) {
                    sb.append("super ");
                    appendTypeMirror(sb, lower);
                } else if (upper != null && upper.getKind() != TypeKind.NONE) {
                    sb.append("extends ");
                    appendTypeMirror(sb, upper);
                }
            }

            case DECLARED -> {
                if (type instanceof DeclaredType declared) {
                    final var element = declared.asElement();
                    if (element instanceof TypeElement te) {
                        sb.append('L');
                        sb.append(te.getQualifiedName().toString().replace('.', '/'));
                        final var typeArgs = declared.getTypeArguments();
                        if (!typeArgs.isEmpty()) {
                            sb.append('<');
                            for (final var arg : typeArgs) {
                                appendTypeMirror(sb, arg);
                            }
                            sb.append('>');
                        }
                        sb.append(';');
                    } else {
                        sb.append("Ljava/lang/Object;");
                    }
                } else {
                    sb.append("Ljava/lang/Object;");
                }
            }

            default -> sb.append("Ljava/lang/Object;");
        }
    }

    private static TypeMirror getComponentType(TypeMirror type) {
        if (type instanceof io.github.potjerodekool.nabu.type.ArrayType arrayType) {
            return arrayType.getComponentType();
        }
        return type;
    }
}
