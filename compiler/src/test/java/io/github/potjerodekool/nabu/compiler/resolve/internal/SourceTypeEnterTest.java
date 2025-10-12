package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.io.impl.NabuFileObject;
import io.github.potjerodekool.nabu.compiler.resolve.internal.java.JavaSourceEnterClasses;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

class SourceTypeEnterTest extends AbstractCompilerTest {

//    @Test
    void fill() {
        final var companyClass = createClass(
                Paths.get("C:\\projects\\nabu\\compiler-test\\src\\main\\java\\io\\github\\potjerodekool\\employee\\Company.java")
        );

        enter(companyClass, getCompilerContext().getTypeEnter());

        final var employeeClass = createClass(
                Paths.get("C:\\projects\\nabu\\compiler-test\\src\\main\\java\\io\\github\\potjerodekool\\employee\\Employee.java")
        );

        enter(employeeClass, getCompilerContext().getTypeEnter());

        companyClass.complete();

        //new SourceTypeEnter(compilerContext).fill(companyClass);
    }

    private void enter(final ClassSymbol classSymbol,
                       final TypeEnter typeEnter) {
        final var enterClasses = new EnterClasses(
                getCompilerContext()
        );

        final var sourceEnterClasses = new JavaSourceEnterClasses(
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
                new FileObject.Kind(".java", true),
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