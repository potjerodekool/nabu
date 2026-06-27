package io.github.potjerodekool.nabu.compiler.lang.support.java.lomboksupport.handler;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.lang.model.element.*;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.List;
import java.util.Optional;

public class LombokAccessUtils {

    public static Optional<ExecutableElement> findMethod(final String methodName,
                                                         final TypeMirror returnType,
                                                         final List<TypeMirror> parameterTypes,
                                                         final TypeElement classDeclaration,
                                                         final Types types) {
        return ElementFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> methodName.equals(method.getSimpleName()))
                .filter(method -> {
                    if (method.getParameters().size() != parameterTypes.size()) {
                        return false;
                    } else {
                        final var count = method.getParameters().size();

                        for (int i = 0; i < count; i++) {
                            final var paramType = method.getParameters().get(i).asType();
                            final var expectedParamType = parameterTypes.get(i);
                            if (!types.isSameType(expectedParamType, paramType)) {
                                return false;
                            }
                        }

                        return true;
                    }
                })
                .filter(method -> types.isSameType(method.getReturnType(), returnType))
                .findFirst();
    }

    public static Optional<ExecutableElement> findGetterMethod(final String fieldName,
                                                               final TypeMirror fieldType,
                                                               final TypeElement classDeclaration,
                                                               final Types types) {
        final var getterName = createGetterName(fieldName);

        return ElementFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> getterName.equals(method.getSimpleName()))
                .filter(method -> method.getParameters().isEmpty())
                .filter(method -> types.isSameType(method.getReturnType(), fieldType))
                .findFirst();
    }

    public static Optional<ExecutableElement> findSetterMethod(final String fieldName,
                                                               final TypeMirror fieldType,
                                                               final ClassSymbol classDeclaration,
                                                               final Types types) {
        final var setterName = createSetterName(fieldName);

        return ElementFilter.methodsIn(classDeclaration.getEnclosedElements()).stream()
                .filter(method -> setterName.equals(method.getSimpleName()))
                .filter(method -> method.getParameters().size() == 1)
                .filter(method -> types.isSameType(method.getParameters().getFirst().asType(), fieldType))
                .findFirst();
    }

    public static String createGetterName(final String fieldName) {
        return "get" + upperFirst(fieldName);
    }

    public static String createSetterName(final String fieldName) {
        return "set" + upperFirst(fieldName);
    }

    private static String upperFirst(final String value) {
        final var first = Character.toUpperCase(value.charAt(0));

        if (value.length() == 1) {
            return Character.toString(first);
        } else {
            return first + value.substring(1);
        }
    }

    public static void addGetterMethod(final VariableElement field,
                                       final long accessLevel,
                                       final TypeElement classSymbol,
                                       final CompilerContext compilerContext) {
        final var builder = compilerContext.getElementBuilders().executableElementBuilder();

        final var getterName = createGetterName(field.getSimpleName());

        final var getter = (Symbol) builder
                .returnType(field.asType())
                .simpleName(getterName)
                .kind(ElementKind.METHOD)
                .flags(accessLevel)
                .build();

        classSymbol.addEnclosedElement(getter);
    }

    public static void addSetterMethod(final VariableElement field,
                                       final long accessLevel,
                                       final ClassSymbol classDeclaration,
                                       final CompilerContext compilerContext) {
        final var types = compilerContext.getTypes();

        final var parameterBuilder = compilerContext.getElementBuilders().variableElementBuilder();
        final var parameter = parameterBuilder.kind(ElementKind.PARAMETER)
                .simpleName(field.getSimpleName())
                .type(field.asType())
                .build();


        final var executableElementBuilder = compilerContext.getElementBuilders().executableElementBuilder();

        final var setterName = createSetterName(field.getSimpleName());

        final var setter = (Symbol) executableElementBuilder
                .returnType(types.getNoType(TypeKind.VOID))
                .simpleName(setterName)
                .kind(ElementKind.METHOD)
                .flags(accessLevel)
                .parameter(parameter)
                .build();

        classDeclaration.addEnclosedElement(setter);
    }
}
