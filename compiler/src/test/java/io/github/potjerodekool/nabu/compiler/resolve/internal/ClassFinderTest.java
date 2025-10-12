package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.tools.CompilerOptions;
import io.github.potjerodekool.nabu.tools.CompilerOption;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.mockito.Mockito.mock;

class ClassFinderTest {

    @Test
    void getCompleter() {
        try (var filesMock = Mockito.mockStatic(Files.class, InvocationOnMock::callRealMethod);
             var fileManager = new NabuCFileManager()) {

            final var compilerOptions = new CompilerOptions.CompilerOptionsBuilder()
                    .option(CompilerOption.CLASS_PATH, "myPath")
                            .build();

            fileManager.processOptions(compilerOptions);
            final var symbolTable = new SymbolTable();

            final var compilerContext = mock(CompilerContextImpl.class);

            final var finder = new ClassFinder(
                    symbolTable,
                    fileManager,
                    null,
                    compilerContext
            );

            final var moduleSymbol = new ModuleSymbol(
                    0,
                    "unnanmed",
                    null
            );

            final var packageSymbol = new PackageSymbol(
                    null,
                    "somepackage"
            );
            packageSymbol.setModuleSymbol(moduleSymbol);
            packageSymbol.setCompleter(finder.getCompleter());

            filesMock.when(() -> Files.exists(Mockito.any())).thenReturn(true);

            final var classFile = Paths.get("MyClass.class");

            filesMock.when(() -> Files.list(Mockito.any())).thenReturn(
                    Stream.of(classFile)
            );

            packageSymbol.complete();
        }
    }
}