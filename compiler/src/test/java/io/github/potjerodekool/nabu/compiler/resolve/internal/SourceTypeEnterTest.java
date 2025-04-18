package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.FileObject;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.io.NabuFileObject;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

class SourceTypeEnterTest {

    private final CompilerContextImpl compilerContext = new CompilerContextImpl(
            null,
            new NabuCFileManager()
    );

//    @Test
    void fill() {
        final var companyClass = createClass(
                Paths.get("C:\\projects\\nabu\\compiler-test\\src\\main\\java\\io\\github\\potjerodekool\\employee\\Company.java")
        );

        enter(companyClass, compilerContext.getTypeEnter());

        final var employeeClass = createClass(
                Paths.get("C:\\projects\\nabu\\compiler-test\\src\\main\\java\\io\\github\\potjerodekool\\employee\\Employee.java")
        );

        enter(employeeClass, compilerContext.getTypeEnter());

        companyClass.complete();

        //new SourceTypeEnter(compilerContext).fill(companyClass);
    }

    private void enter(final ClassSymbol classSymbol,
                       final TypeEnter typeEnter) {
        final var enterClasses = new EnterClasses(
                compilerContext
        );

        final var sourceEnterClasses = new SourceEnterClasses(
                enterClasses
        );

        final var cu = sourceEnterClasses.enter(classSymbol);
        final var classes = cu.getClasses();
        final var classDeclaration = classes.getFirst();

        typeEnter.put(classSymbol, classDeclaration, cu);
        classSymbol.setCompleter(typeEnter);
    }

    private ClassSymbol createClass(final Path path) {
        final var clazz = new ClassSymbolBuilder()
                .build();

        final var fileObject = new NabuFileObject(
                FileObject.Kind.SOURCE_JAVA,
                path
        );

        clazz.setKind(ElementKind.CLASS);
        clazz.setSourceFile(fileObject);

        var name = path.toString();
        var start = name.lastIndexOf(File.separatorChar) + 1;
        name = name.substring(start);
        var end = name.lastIndexOf(".");
        name = name.substring(0, end);

        clazz.setSimpleName(name);
        clazz.setEnclosingElement(PackageSymbol.UNNAMED_PACKAGE);
        return clazz;
    }
}