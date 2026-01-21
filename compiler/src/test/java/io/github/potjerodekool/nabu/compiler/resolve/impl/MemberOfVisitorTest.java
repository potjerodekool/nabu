package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Test;

class MemberOfVisitorTest extends AbstractCompilerTest {

    private final ClassElementLoader loader = getCompilerContext().getClassElementLoader();
    private final Types types = getCompilerContext().getTypes();

    @Test
    void visitDeclaredType() {
        final var module = getCompilerContext().getSymbolTable().getJavaBase();

        final var visitor = new MemberOfVisitor(types);
        final var listSymbol = loader.loadClass(module, "java.util.List");

        final var stringSymbol = loader.loadClass(module, Constants.STRING);

        final var stringType = types.getDeclaredType(
                stringSymbol
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);

        final var classType = types.getDeclaredType(
                listSymbol,
                stringType
        );

        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .simpleName("SomeClass")
                .build();

        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        final var typeVar = types.getTypeVariable(
                "T",
                objectType,
                null
        );

        final var method = new MethodSymbolBuilderImpl()
                .simpleName("get")
                .enclosingElement(clazz)
                .returnType(typeVar)
                .parameter(createParameter("index", intType))
                .typeParameter((TypeParameterElement) typeVar.asElement())
                .build();

        classType.accept(visitor, method);
    }

    private VariableSymbol createParameter(final String name,
                                           final TypeMirror type) {
        return (VariableSymbol) new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName(name)
                .type(type)
                .build();
    }
}