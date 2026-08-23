package io.github.potjerodekool.nabu.compiler.backend.ir;

import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.type.*;

/**
 * Vertaalt een Nabu TypeMirror naar een IRType.
 *
 * Primitieve types worden exact gemapt.
 * Referentietypes (klassen, arrays, generics) worden na type-erasure
 * opaque pointers: Ptr(I8) — identiek aan hoe LLVM en de JVM ze behandelen.
 *
 * Gebruik:
 *   IRType irType = TypeMirrorToIRType.map(typeMirror);
 */
public final class TypeMirrorToIRType {

    private TypeMirrorToIRType() {}

    /**
     * Mapt een volledig opgelost TypeMirror naar een IRType.
     * TypeMirrors moeten al opgelost zijn door de semantic analysis fase.
     */
    public static IRType map(TypeMirror type) {
        if (type == null) return IRType.VOID;

        if (type instanceof VariableType variableType) {
            return map(variableType.getInterferedType());
        }

        return switch (type.getKind()) {
            // -------------------------------------------------------
            // Primitieven
            // -------------------------------------------------------
            case BOOLEAN -> IRType.BOOL;
            case BYTE    -> new IRType.Int(8);
            case SHORT   -> new IRType.Int(16);
            case CHAR    -> new IRType.Int(16);   // char = UTF-16 codeunit
            case INT     -> IRType.I32;
            case LONG    -> IRType.I64;
            case FLOAT   -> IRType.F32;
            case DOUBLE  -> IRType.F64;
            case VOID    -> IRType.VOID;

            // -------------------------------------------------------
            // Arrays — na erasure: pointer naar het elementtype
            // -------------------------------------------------------
            case ARRAY -> {
                if (type instanceof ArrayType arrayType) {
                    IRType elementType = map(arrayType.getComponentType());
                    yield new IRType.Ptr(elementType, toJvmDescriptor(arrayType));
                }
                yield new IRType.Ptr(IRType.I8);
            }

            // -------------------------------------------------------
            // Gedeclareerde types (klassen, interfaces, enums, records)
            // Na type-erasure: opaque pointer
            // -------------------------------------------------------
            case DECLARED -> new IRType.Ptr(IRType.I8, toJvmDescriptor(type));

            // -------------------------------------------------------
            // Type-variabelen en wildcards — na erasure: de linkerbound
            // (recursief), anders Object = Ptr(I8)
            // -------------------------------------------------------
            case TYPEVAR -> {
                final var typeVariable = (TypeVariable) type;
                if (typeVariable.getUpperBound() != null) {
                    yield map(typeVariable.getUpperBound());
                }
                if (typeVariable.getLowerBound() != null) {
                    yield map(typeVariable.getLowerBound());
                }
                yield new IRType.Ptr(IRType.I8);
            }

            case WILDCARD -> {
                if (type instanceof WildcardType wildcardType) {
                    if (wildcardType.getExtendsBound() != null) {
                        yield map(wildcardType.getExtendsBound());
                    }
                    if (wildcardType.getSuperBound() != null) {
                        yield map(wildcardType.getSuperBound());
                    }
                }
                yield new IRType.Ptr(IRType.I8);
            }

            case INTERSECTION -> {
                if (type instanceof IntersectionType intersectionType
                        && !intersectionType.getBounds().isEmpty()) {
                    yield map(intersectionType.getBounds().get(0));
                }
                yield new IRType.Ptr(IRType.I8);
            }

            // -------------------------------------------------------
            // Null-type
            // -------------------------------------------------------
            case NULL -> new IRType.Ptr(IRType.I8);

            // -------------------------------------------------------
            // Executable (methode/constructor-type)
            // Wordt als functietype gemapt als we een functiepointer nodig hebben
            // -------------------------------------------------------
            case EXECUTABLE -> {
                if (type instanceof ExecutableType execType) {
                    IRType returnType = map(execType.getReturnType());
                    IRType[] paramTypes = execType.getParameterTypes()
                            .stream()
                            .map(TypeMirrorToIRType::map)
                            .toArray(IRType[]::new);
                    yield IRType.fn(returnType, paramTypes);
                }
                yield new IRType.Ptr(IRType.I8);
            }

            // -------------------------------------------------------
            // Overige — gebruik opaque pointer als veilige fallback
            // -------------------------------------------------------
            case NONE, PACKAGE, MODULE, UNION, ERROR ->
                    new IRType.Ptr(IRType.I8);
        };
    }

    /**
     * Mapt een TypeMirror naar het IRType van een methode-retourwaarde.
     * Identiek aan map() maar explicieter in zijn intentie.
     */
    public static IRType mapReturnType(TypeMirror returnType) {
        return map(returnType);
    }

    /**
     * Geeft true als het type een primitief is dat direct als IRValue
     * kan worden doorgegeven (geen pointer/alloca nodig voor parameters).
     */
    public static boolean isPrimitive(TypeMirror type) {
        if (type == null) return false;
        return type.getKind().isPrimitive();
    }

    /**
     * Geeft true als het type een referentietype is.
     * Referentietypes worden altijd als Ptr(I8) gerepresenteerd.
     */
    public static boolean isReference(TypeMirror type) {
        if (type == null) return false;
        return !type.getKind().isPrimitive()
                && type.getKind() != TypeKind.VOID;
    }

    /**
     * Converteert een TypeMirror naar een JVM type descriptor string.
     * Bijv. "Ljava/lang/String;", "[Ljava/lang/String;", "I", etc.
     */
    public static String toJvmDescriptor(TypeMirror type) {
        if (type == null) return null;
        return switch (type.getKind()) {
            case BYTE -> "B";
            case SHORT -> "S";
            case CHAR -> "C";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case BOOLEAN -> "Z";
            case VOID -> "V";
            case DECLARED -> {
                final var className = type.asTypeElement().getQualifiedName();
                yield "L" + className.replace('.', '/') + ";";
            }
            case ARRAY -> {
                if (type instanceof ArrayType arrayType) {
                    final var componentDesc = toJvmDescriptor(arrayType.getComponentType());
                    yield "[" + componentDesc;
                }
                yield "[Ljava/lang/Object;";
            }
            default -> null;
        };
    }
}
