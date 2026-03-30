package io.github.potjerodekool.nabu.compiler.backend.translate;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.ir.IRFunction;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.builder.VariableElementBuilder;
import io.github.potjerodekool.nabu.test.TestUtils;
import io.github.potjerodekool.nabu.testing.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tools.TodoException;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.NestingKind;
import io.github.potjerodekool.nabu.tree.element.impl.CClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.impl.CFunction;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TranslateV2Test extends AbstractCompilerTest {

    private TranslateV2 translateV2 = new TranslateV2();

    @Test
    void visitFunction() {
        final var compilerContext = getCompilerContext();
        final var types = compilerContext.getTypes();
        final var returnType = types.getNoType(TypeKind.VOID);
        final var stringType = compilerContext.getClassElementLoader().loadClass(
                null,
                Constants.STRING
        ).asType();
        final var stringArrayType = types.getArrayType(stringType);

        final var functionBody = TreeMaker.blockStatement(
                List.of(
                        TreeMaker.returnStatement(
                                null,
                                0,
                                0
                        )
                ),
                0,
                0
        );

        final var treeFunction = TreeMaker.function(
                "hello",
                Kind.METHOD,
                new Modifiers(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                functionBody,
                null,
                0,
                0
        );

        final var methodSymbol = mock(MethodSymbol.class);
        treeFunction.setMethodSymbol(methodSymbol);

        final var parameter = compilerContext.getElementBuilders()
                .variableElementBuilder()
                .kind(ElementKind.PARAMETER)
                .simpleName("args")
                .type(stringArrayType)
                .build();

        final var clazz = TreeMaker.classDeclaration(
                Kind.CLASS,
                NestingKind.TOP_LEVEL,
                new Modifiers(),
                "MyClass",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                0,
                0
        );

        when(methodSymbol.getReturnType()).thenReturn(returnType);
        when(methodSymbol.getParameters()).thenReturn(List.of(parameter));

        translateV2.visitClass(clazz, null);
        translateV2.visitFunction(treeFunction, null);
        final var module = translateV2.getModule();
        final var printer = new ModulePrinter();
        printer.print(module);
        final var actual = TestUtils.fixLines(printer.getText());

        assertEquals("""
                void hello([java.lang.String args) {
                }
                """, actual);
    }
}

class ModulePrinter {

    private final StringWriter stringWriter = new StringWriter();
    private final BufferedWriter writer = new BufferedWriter(stringWriter);

    public void print(final IRModule module) {
        module.functions().forEach(this::printFunction);
    }

    private void printFunction(final IRFunction function) {
        print(function.returnType);
        print(" " + function.name);
        print("(");

        final var params = function.params;
        final var lastIndex = params.size() - 1;

        for (var i = 0; i < params.size(); i++) {
            final var param = params.get(i);
            printParam((IRValue.Temp)param);

            if (i != lastIndex) {
                print(",");
            }
        }

        printLn(") {");
        printLn("}");
    }

    private void printParam(final IRValue.Temp param) {
        print(param.type());
        print(" ");
        print(param.name());
    }

    private void printLn(String text) {
        print(text);
        newLine();
    }

    private void newLine() {
        try {
            writer.newLine();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void print(String text) {
        try {
            writer.write(text);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void print(final IRType type) {
        switch (type) {
            case IRType.Void ignored -> print("void");
            case IRType.CustomType customType -> print(customType.name());
            case IRType.Array arrayType -> {
                print("[");
                print(arrayType.elem());
            }
            default -> throw new TodoException();
        }
    }

    public String getText() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return stringWriter.getBuffer().toString();
    }
}
