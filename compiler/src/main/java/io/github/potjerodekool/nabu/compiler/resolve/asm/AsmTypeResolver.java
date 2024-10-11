package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableArrayType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import org.objectweb.asm.Type;

public class AsmTypeResolver {

    private final AsmClassElementLoader classElementLoader;

    private final Types types;

    public AsmTypeResolver(final AsmClassElementLoader classElementLoader) {
        this.classElementLoader = classElementLoader;
        this.types = classElementLoader.getTypes();
    }

    public AsmClassElementLoader getClassElementLoader() {
        return classElementLoader;
    }

    public TypeMirror resolveByDescriptor(final String descriptor) {
        final var asmType = Type.getType(descriptor);

        return switch (asmType.getSort()) {
            case Type.VOID -> types.getVoidType();
            case Type.BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case Type.CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            case Type.BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case Type.SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case Type.INT -> types.getPrimitiveType(TypeKind.INT);
            case Type.FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case Type.LONG ->  types.getPrimitiveType(TypeKind.LONG);
            case Type.DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);

            case Type.ARRAY -> {
                final var componentType = resolveType(asmType.getElementType());
                yield new ImmutableArrayType(componentType);
            }
            case Type.OBJECT -> classElementLoader.resolveType(asmType.getInternalName());
            default -> throw new UnsupportedOperationException("" + asmType.getSort());
        };
    }

    public TypeMirror resolveType(final Type type) {
        if (type == null) {
            return null;
        }

        return switch (type.getSort()) {
            case Type.VOID -> types.getVoidType();
            case Type.BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case Type.CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            case Type.BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case Type.SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case Type.INT -> types.getPrimitiveType(TypeKind.INT);
            case Type.FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case Type.LONG ->  types.getPrimitiveType(TypeKind.LONG);
            case Type.DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);

            case Type.ARRAY -> {
                final var componentType = resolveType(type.getElementType());
                yield new ImmutableArrayType(componentType);
            }
            case Type.OBJECT -> classElementLoader.resolveType(type.getInternalName());
            default -> throw new UnsupportedOperationException("" + type.getSort());
        };
    }
}
