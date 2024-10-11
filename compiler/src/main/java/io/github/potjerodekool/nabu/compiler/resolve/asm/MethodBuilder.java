package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.MethodSymbol;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import java.util.Arrays;

public class MethodBuilder extends MethodVisitor {

    protected MethodBuilder(final int api,
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions,
                            final ClassSymbol clazz,
                            final AsmTypeResolver asmTypeResolver) {
        super(api);

        final var modifiers = Modifiers.parse(access);
        final var elementKind = "<init>".equals(name) ? ElementKind.CONSTRUCTOR
                : ElementKind.METHOD;

        final var methodSymbol = new MethodSymbol(elementKind, name, clazz);
        methodSymbol.addModifiers(modifiers);

        final var methodType = Type.getMethodType(descriptor);

        methodSymbol.getMethodType().setReturnType(asmTypeResolver.resolveType(methodType.getReturnType()));

        final var argumentTypes = Arrays.stream(methodType.getArgumentTypes())
                .map(asmTypeResolver::resolveType)
                .toList();

        final var argCount = Type.getMethodType(descriptor).getArgumentCount();

        if (argCount != argumentTypes.size()) {
            throw new IllegalStateException();
        }

        methodSymbol.getMethodType().getArgumentTypes().addAll(argumentTypes);

        clazz.addEnclosedElement(methodSymbol);
    }

}
