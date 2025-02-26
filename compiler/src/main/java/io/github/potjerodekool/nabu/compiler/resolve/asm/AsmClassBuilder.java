package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.resolve.SymbolTable;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

class AsmClassBuilder extends ClassVisitor {

    private final SymbolTable symbolTable;

    private final AsmClassElementLoader classElementLoader;

    private final AsmTypeResolver asmTypeResolver;

    private final TypeBuilder typeBuilder;

    private ClassSymbol clazz;

    public TypeElement getClazz() {
        return clazz;
    }

    protected AsmClassBuilder(final SymbolTable symbolTable,
                              final AsmClassElementLoader classElementLoader) {
        super(Opcodes.ASM9);
        this.symbolTable = symbolTable;
        this.classElementLoader = classElementLoader;
        this.asmTypeResolver = new AsmTypeResolver(classElementLoader);
        this.typeBuilder = new TypeBuilder(asmTypeResolver);
    }

    @Override
    public void visit(final int version,
                      final int access,
                      final String name,
                      final String signature,
                      final String superName,
                      final String[] interfaces) {

        final var kind = resolveKind(access);
        final NestingKind nestingKind;

        final String simpleName;
        final Element enclosingElement;

        if (name.contains("$")) {
            nestingKind = NestingKind.MEMBER;
            final var sepIndex = name.lastIndexOf("$");
            final var simpleNameStart = sepIndex + 1;
            simpleName = name.substring(simpleNameStart);
            final var outerName = name.substring(0, sepIndex);
            enclosingElement = classElementLoader.resolveClass(outerName);
        } else {
            nestingKind = NestingKind.TOP_LEVEL;

            final var packageEnd = name.lastIndexOf('/');
            final var qualifiedName = name.replace('/', '.');

            if (packageEnd > -1) {
                final var packageName = qualifiedName.substring(0, packageEnd);
                enclosingElement = symbolTable.findOrCreatePackage(packageName);
            } else {
                enclosingElement = null;
            }
            simpleName = qualifiedName.substring(packageEnd + 1);
        }

        clazz = new ClassSymbol(kind, nestingKind, Set.of(), simpleName, enclosingElement);

        if (enclosingElement != null) {
            enclosingElement.addEnclosedElement(clazz);
        }

        this.symbolTable.addClassSymbol(name, clazz);

        if (signature != null) {
            typeBuilder.parseClassSignature(signature, clazz);
        } else {
            final var type = new CClassType(
                    null,
                    clazz,
                    List.of()
            );
            clazz.setType(type);

            if (superName != null) {
                final var superType = classElementLoader.resolveClass(superName).asType();
                clazz.setSuperClass(superType);
            }

            if (interfaces != null) {
                Arrays.stream(interfaces)
                        .map(classElementLoader::resolveClass)
                        .map(Element::asType)
                        .filter(Objects::nonNull)
                        .forEach(interfaceType -> clazz.addInterface(interfaceType));
            }
        }
    }

    private ElementKind resolveKind(final int access) {
        if (has(access, Opcodes.ACC_INTERFACE)) {
            return ElementKind.INTERFACE;
        } else if (has(access, Opcodes.ACC_ANNOTATION)) {
            return ElementKind.ANNOTATION;
        } else if (has(access, Opcodes.ACC_ENUM)) {
            return ElementKind.ENUM;
        } else if (has(access, Opcodes.ACC_RECORD)) {
            return ElementKind.RECORD;
        } else {
            return ElementKind.CLASS;
        }
    }

    private boolean has(final int access,
                        final int opcode) {
        return (access & opcode) == opcode;
    }

    @Override
    public MethodVisitor visitMethod(final int access,
                                     final String name,
                                     final String descriptor,
                                     final String signature,
                                     final String[] exceptions) {

        final var isSynthetic = (access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC;

        if (isSynthetic) {
            return null;
        }

        return new AsmMethodBuilder(
                this.api,
                access,
                name,
                descriptor,
                signature,
                exceptions,
                clazz,
                asmTypeResolver,
                typeBuilder
        );
    }

    @Override
    public FieldVisitor visitField(final int access, final String name, final String descriptor, final String signature, final Object value) {
        return new FieldBuilder(
                this.api,
                access,
                name,
                descriptor,
                signature,
                value,
                clazz,
                asmTypeResolver,
                typeBuilder
        );
    }

}
