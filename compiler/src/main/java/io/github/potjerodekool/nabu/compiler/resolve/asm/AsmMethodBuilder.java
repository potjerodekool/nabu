package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.MethodBuilder;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.MethodSignature;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.List;

public class AsmMethodBuilder extends MethodVisitor {

    protected AsmMethodBuilder(final int api,
                               final int access,
                               final String name,
                               final String descriptor,
                               final String signature,
                               final String[] exceptions,
                               final TypeElement clazz,
                               final AsmTypeResolver asmTypeResolver,
                               final TypeBuilder typeBuilder) {
        super(api);

        final var modifiers = Modifiers.parse(access);
        final var elementKind = "<init>".equals(name) ? ElementKind.CONSTRUCTOR
                : ElementKind.METHOD;

        final MethodSignature methodSignature;

        if (signature != null) {
            methodSignature = typeBuilder.parseMethodSignature(
                    signature
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

            methodSignature = new MethodSignature(
                    List.of(),
                    returnType,
                    argumentTypes,
                    List.of()
            );
        }

        final var methodSymbol = new MethodBuilder()
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
                .modifiers(modifiers)
                .build();

        clazz.addEnclosedElement(methodSymbol);
    }

}
