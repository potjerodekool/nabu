package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.NestingKind;
import io.github.potjerodekool.nabu.compiler.ast.element.TypeParameterElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.internal.MemberOfVisitor;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.util.Types;
import org.junit.jupiter.api.Test;

class MemberOfVisitorTest {

    private final CompilerContext compilerContext = new CompilerContextImpl(
            new ApplicationContext(),
            new NabuCFileManager()
    );
    private final ClassElementLoader loader = compilerContext.getClassElementLoader();
    private final Types types = loader.getTypes();

    @Test
    void visitDeclaredType() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();

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

        final var clazz = (ClassSymbol) new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.TOP_LEVEL)
                .name("SomeClass")
                .build();

        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        final var typeVar = types.getTypeVariable(
                "T",
                objectType,
                null
        );

        final var method = new MethodSymbolBuilderImpl()
                .name("get")
                .enclosingElement(clazz)
                .returnType(typeVar)
                .argumentType(intType)
                .typeParameter((TypeParameterElement) typeVar.asElement())
                .build();

        classType.accept(visitor, method);
    }
}