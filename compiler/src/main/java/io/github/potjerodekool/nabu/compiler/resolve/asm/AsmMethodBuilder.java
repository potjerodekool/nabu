package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassUtils;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.MethodSignature;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.List;

public class AsmMethodBuilder extends MethodVisitor {

    private final MethodSymbol method;
    private final ClassElementLoader loader;
    private final ModuleSymbol moduleSymbol;

    protected AsmMethodBuilder(final int api,
                               final int access,
                               final String name,
                               final String descriptor,
                               final String signature,
                               final String[] exceptions,
                               final ClassSymbol clazz,
                               final AsmTypeResolver asmTypeResolver,
                               final TypeBuilder typeBuilder,
                               final ModuleSymbol moduleSymbol) {
        super(api);
        this.loader = asmTypeResolver.getClassElementLoader();
        this.moduleSymbol = moduleSymbol;
        final var module = clazz.resolveModuleSymbol();

        final var flags = AccessUtils.parseMethodAccessToFlags(access);

        final var elementKind = Constants.INIT.equals(name) ? ElementKind.CONSTRUCTOR
                : ElementKind.METHOD;

        final MethodSignature methodSignature;

        if (signature != null) {
            methodSignature = typeBuilder.parseMethodSignature(
                    signature,
                    asmTypeResolver,
                    moduleSymbol
            );
        } else {
            final var asmMethodType = Type.getMethodType(descriptor);

            final var argumentTypes = Arrays.stream(asmMethodType.getArgumentTypes())
                    .map(asmTypeResolver::asTypeMirror)
                    .toList();

            final var argCount = Type.getMethodType(descriptor).getArgumentCount();

            if (argCount != argumentTypes.size()) {
                throw new IllegalStateException();
            }

            final var returnType = asmTypeResolver.asTypeMirror(asmMethodType.getReturnType());

            final List<TypeMirror> thrownTypes;

            if (exceptions != null) {
                thrownTypes = Arrays.stream(exceptions)
                        .map(exceptionName -> typeOrError(
                                loader.loadClass(module, exceptionName),
                                exceptionName))
                        .toList();
            } else {
                thrownTypes = List.of();
            }

            methodSignature = new MethodSignature(
                    List.of(),
                    returnType,
                    argumentTypes,
                    thrownTypes
            );
        }

        this.method = new MethodSymbolBuilderImpl()
                .kind(elementKind)
                .name(name)
                .enclosingElement(clazz)
                .typeParameters(
                        methodSignature.typeVariables().stream()
                                .map(it -> (TypeParameterElement) it.asElement())
                                .toList()
                )
                .returnType(methodSignature.returnType())
                .argumentTypes(methodSignature.argumentTypes())
                .thrownTypes(methodSignature.thrownTypes())
                .flags(flags)
                .build();

        clazz.addEnclosedElement(method);
    }

    private TypeMirror typeOrError(final TypeElement typeElement,
                                   final String innerName) {
        if (typeElement != null) {
            return typeElement.asType();
        } else {
            return loader.getTypes().getErrorType(ClassUtils.getClassName(innerName));
        }
    }

    @Override
    public AnnotationVisitor visitAnnotationDefault() {
        return new AsmAnnotationDefaultValueBuilder(api, loader, method, moduleSymbol);
    }
}
