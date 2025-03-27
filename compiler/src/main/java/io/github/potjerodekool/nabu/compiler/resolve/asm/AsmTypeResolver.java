package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.util.Types;
import org.objectweb.asm.Type;

public class AsmTypeResolver {

    private final ClassElementLoader classElementLoader;
    private final Types types;
    private final ModuleSymbol moduleSymbol;

    public AsmTypeResolver(final ClassElementLoader classElementLoader,
                           final ModuleSymbol moduleSymbol) {
        this.classElementLoader = classElementLoader;
        this.types = classElementLoader.getTypes();
        this.moduleSymbol = moduleSymbol;
    }

    public ClassElementLoader getClassElementLoader() {
        return classElementLoader;
    }

    public TypeMirror resolveByDescriptor(final String descriptor) {
        final var asmType = Type.getType(descriptor);
        return asTypeMirror(asmType);
    }

    public TypeMirror asTypeMirror(final Type type) {
        if (type == null) {
            return null;
        }

        return switch (type.getSort()) {
            case Type.VOID -> types.getNoType(TypeKind.VOID);
            case Type.BOOLEAN -> types.getPrimitiveType(TypeKind.BOOLEAN);
            case Type.CHAR -> types.getPrimitiveType(TypeKind.CHAR);
            case Type.BYTE -> types.getPrimitiveType(TypeKind.BYTE);
            case Type.SHORT -> types.getPrimitiveType(TypeKind.SHORT);
            case Type.INT -> types.getPrimitiveType(TypeKind.INT);
            case Type.FLOAT -> types.getPrimitiveType(TypeKind.FLOAT);
            case Type.LONG ->  types.getPrimitiveType(TypeKind.LONG);
            case Type.DOUBLE -> types.getPrimitiveType(TypeKind.DOUBLE);

            case Type.ARRAY -> {
                final var componentType = asTypeMirror(type.getElementType());
                yield types.getArrayType(componentType);
            }
            case Type.OBJECT -> classElementLoader.loadClass(moduleSymbol, type.getClassName()).asType();
            default -> throw new UnsupportedOperationException("" + type.getSort());
        };
    }
}
