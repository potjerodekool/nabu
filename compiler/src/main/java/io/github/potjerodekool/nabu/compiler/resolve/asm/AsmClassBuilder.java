package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;

import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.objectweb.asm.*;

import java.util.Arrays;

class AsmClassBuilder extends ClassVisitor {

    private final SymbolTable symbolTable;

    private final ClassElementLoader classElementLoader;

    private final AsmTypeResolver asmTypeResolver;

    private final TypeBuilder typeBuilder;

    private final Types types;

    private final ClassSymbol clazz;

    private final ModuleSymbol moduleSymbol;

    public TypeElement getClazz() {
        return clazz;
    }

    protected AsmClassBuilder(final SymbolTable symbolTable,
                              final ClassElementLoader classElementLoader,
                              final ClassSymbol classSymbol,
                              final ModuleSymbol moduleSymbol) {
        super(Opcodes.ASM9);
        this.symbolTable = symbolTable;
        this.classElementLoader = classElementLoader;
        this.asmTypeResolver = new AsmTypeResolver(classElementLoader, moduleSymbol);
        this.typeBuilder = new TypeBuilder();
        this.types = classElementLoader.getTypes();
        this.clazz = classSymbol;
        this.moduleSymbol = moduleSymbol;
    }

    @Override
    public void visit(final int version,
                      final int access,
                      final String name,
                      final String signature,
                      final String superName,
                      final String[] interfaces) {

        final var kind = resolveKind(access);
        final var flags = AccessUtils.parseClassAccessToFlags(access);
        final NestingKind nestingKind;

        final String simpleName;
        final Symbol enclosingElement;

        if (name.contains("$")) {
            nestingKind = NestingKind.MEMBER;
            final var sepIndex = name.lastIndexOf("$");
            final var simpleNameStart = sepIndex + 1;
            simpleName = name.substring(simpleNameStart);
            final var outerName = name.substring(0, sepIndex);
            enclosingElement = (Symbol) classElementLoader.loadClass(moduleSymbol, outerName);
        } else {
            nestingKind = NestingKind.TOP_LEVEL;

            final var packageEnd = name.lastIndexOf('/');
            final var qualifiedName = name.replace('/', '.');

            if (packageEnd > -1) {
                final var packageName = qualifiedName.substring(0, packageEnd);
                enclosingElement = symbolTable.lookupPackage(
                        moduleSymbol,
                        packageName
                );
            } else {
                enclosingElement = null;
            }
            simpleName = qualifiedName.substring(packageEnd + 1);
        }

        if (enclosingElement instanceof PackageSymbol packageSymbol) {
            packageSymbol.setModuleSymbol(moduleSymbol);
        }

        clazz.setFlags(flags);
        clazz.setKind(kind);
        clazz.setNestingKind(nestingKind);
        clazz.setSimpleName(simpleName);

        if (enclosingElement instanceof PackageSymbol packageSymbol) {
            packageSymbol.addEnclosedElement(clazz);
        } else if (enclosingElement instanceof ClassSymbol enclosingClass) {
            enclosingClass.addEnclosedElement(clazz);
        }

        if (signature != null) {
            typeBuilder.parseClassSignature(signature, clazz, asmTypeResolver, clazz.resolveModuleSymbol());
        } else {
            final TypeMirror outerType;

            if (nestingKind == NestingKind.MEMBER) {
                outerType = enclosingElement.asType();
            } else {
                outerType = null;
            }

            final var type = (CClassType) clazz.asType();
            type.setOuterType(outerType);

            if (superName != null) {
                var superElement = classElementLoader.loadClass(moduleSymbol, superName);

                if (superElement == null) {
                    superElement = classElementLoader.loadClass(moduleSymbol, superName);
                }

                final var superType = superElement.asType();
                clazz.setSuperClass(superType);
            }

            if (interfaces != null) {
                final var interfaceTypes = Arrays.stream(interfaces)
                        .map(interfaceName -> {
                            final var interfaceClass = classElementLoader.loadClass(moduleSymbol, interfaceName);

                            if (interfaceClass != null) {
                                return interfaceClass.asType();
                            } else {
                                return types.getErrorType(interfaceName);
                            }
                        })
                        .toList();

                clazz.setInterfaces(interfaceTypes);
            }
        }
    }

    private ElementKind resolveKind(final int access) {
        if (has(access, Opcodes.ACC_ANNOTATION)) {
            return ElementKind.ANNOTATION_TYPE;
        } else if (has(access, Opcodes.ACC_ENUM)) {
            return ElementKind.ENUM;
        } else if (has(access, Opcodes.ACC_RECORD)) {
            return ElementKind.RECORD;
        } else if (has(access, Opcodes.ACC_INTERFACE)) {
            return ElementKind.INTERFACE;
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
                typeBuilder,
                moduleSymbol
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

    @Override
    public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
        return AsmAnnotationBuilder.createBuilder(
                api,
                descriptor,
                visible,
                clazz,
                classElementLoader,
                moduleSymbol
        );
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(final int typeRef, final TypePath typePath, final String descriptor, final boolean visible) {
        throw new TodoException();
    }

    @Override
    public void visitAttribute(final Attribute attribute) {
    }

    @Override
    public ModuleVisitor visitModule(final String name, final int access, final String version) {
        if (moduleSymbol == null) {
            return null;
        } else {
            return new AsmModuleBuilder(
                    api,
                    this.moduleSymbol,
                    symbolTable
            );
        }
    }

}

