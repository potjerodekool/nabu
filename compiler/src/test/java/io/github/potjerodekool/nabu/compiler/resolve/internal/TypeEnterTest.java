package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.test.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.ImportItem;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.builder.NewClassExpressionBuilder;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.tree.impl.CImportItemTree;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.tree.statement.impl.CBlockStatementTree;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TypeEnterTest extends AbstractCompilerTest {

    private TypeEnter typeEnter;

    @BeforeEach
    void setup() {
        typeEnter = new TypeEnter(getCompilerContext());
    }

    @Test
    void createConstructorForSubclassOfObject() {
        final var packageSymbol = new PackageSymbol(
                null,
                ""
        );

        final var clazz = new ClassSymbol();
        clazz.setSimpleName("SomeClass");
        clazz.setType(new CClassType(
                null,
                clazz,
                List.of()
        ));

        clazz.setEnclosingElement(packageSymbol);

        final var classDeclaration = new ClassDeclarationBuilder()
                .modifiers(new Modifiers())
                .build();
        final var compilationUnit = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                -1,
                -1
        );

        typeEnter.put(clazz, classDeclaration, compilationUnit);
        typeEnter.complete(clazz);

        final var constructor = classDeclaration.getEnclosedElements().getFirst();
        final var actual = TreePrinter.print(constructor);
        final var expected = """
                public fun <init>(): void {
                    this.super();
                    return;
                }
                """;

        assertEquals(expected, actual);
    }

    @Test
    void createConstructorForEnum() {
        final var packageSymbol = new PackageSymbol(
                null,
                ""
        );

        final var clazz = new ClassSymbol();
        clazz.setKind(ElementKind.ENUM);
        clazz.setType(new CClassType(
                null,
                clazz,
                List.of()
        ));

        clazz.setEnclosingElement(packageSymbol);

        final var classDeclaration = new ClassDeclarationBuilder()
                .modifiers(new Modifiers())
                .build();
        final var compilationUnit = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                -1,
                -1
        );

        typeEnter.put(clazz, classDeclaration, compilationUnit);
        typeEnter.complete(clazz);

        final var constructor = classDeclaration.getEnclosedElements().getFirst();
        final var actual = TreePrinter.print(constructor);
        final var expected = """
                private fun <init>(name : java.lang.String, ordinal : int): void {
                    this.super(name, ordinal);
                    return;
                }
                """;

        assertEquals(expected, actual);
    }

    @Test
    void createConstructorForEnumWithExistingConstructor() {
        final var packageSymbol = new PackageSymbol(
                null,
                ""
        );

        final var clazz = new ClassSymbol();
        clazz.setKind(ElementKind.ENUM);
        clazz.setType(new CClassType(
                null,
                clazz,
                List.of()
        ));

        clazz.setEnclosingElement(packageSymbol);

        final var returnType = new CIdentifierTree("void");

        final var parameter = new VariableDeclaratorTreeBuilder()
                .kind(Kind.PARAMETER)
                .name(new CIdentifierTree("text"))
                .variableType(new CIdentifierTree("java.lang.String"))
                .build();

        final var constructor = new FunctionBuilder()
                .kind(Kind.CONSTRUCTOR)
                .simpleName(Constants.INIT)
                .parameters(List.of(parameter))
                .body(new CBlockStatementTree(List.of()))
                .returnType(returnType)
                .build();

        final var classDeclaration = new ClassDeclarationBuilder()
                .modifiers(new Modifiers())
                .enclosedElements(List.of(constructor))
                .build();

        final var compilationUnit = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                -1,
                -1
        );

        typeEnter.put(clazz, classDeclaration, compilationUnit);
        typeEnter.complete(clazz);

        final var foundConstructor = classDeclaration.getEnclosedElements().getFirst();
        final var actual = TreePrinter.print(foundConstructor);
        final var expected = """
                fun <init>(name : java.lang.String, ordinal : int, text : java.lang.String): void {
                    this.super(name, ordinal);
                    return;
                }
                """;

        assertEquals(expected, actual);
    }

    @Test
    void singleImportItem() {
        final var compilationUnit = doImport(createImportItem("java.util.List", false));
        final var importedClassNames = getClasses(compilationUnit).stream()
                .map(ClassSymbol::getQualifiedName)
                .filter(it -> !it.startsWith("java.lang."))
                .toList();

        assertEquals(
                List.of("java.util.List"),
                importedClassNames
        );
    }

    @Test
    void starImportItem() {
        final var javaBase = getCompilerContext().getSymbolTable().getJavaBase();

        getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.util.List");
        getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.util.Iterator");
        getCompilerContext().getClassElementLoader().loadClass(javaBase, "java.util.Collection");

        final var compilationUnit = doImport(createImportItem("java.util.*", false));

        /* Check the imported classes. Since java.lang is auto imported ignore them and
         * check other other classes that are imported.
         */
        final var importedClassNames = getClasses(compilationUnit).stream()
                .map(ClassSymbol::getQualifiedName)
                .filter(it -> !it.startsWith("java.lang."))
                .collect(Collectors.toSet());

        assertEquals(
                Set.of(
                        "java.util.AbstractList",
                        "java.util.ArrayList",
                        "java.util.Collection",
                        "java.util.Iterator",
                        "java.util.List",
                        "java.util.Optional",
                        "java.util.Set"
                ),
                importedClassNames
        );
    }


    @Test
    void staticStarImportItem() {
        final var compilationUnit = doImport(createImportItem("java.util.List.*", true));

        final var importedMethodNames = getMethods(compilationUnit).stream()
                .map(MethodSymbol::getSimpleName)
                .toList();

        assertEquals(
                List.of(
                        "add",
                        "iterator",
                        "of"
                ),
                importedMethodNames
        );
    }

    @Test
    void staticImportItem() {
        final var compilationUnit = doImport(createImportItem("java.util.List.of", true));

        final var importedMethodNames = getMethods(compilationUnit).stream()
                .map(MethodSymbol::getSimpleName)
                .toList();

        assertEquals(
                List.of(
                        "of"
                ),
                importedMethodNames
        );
    }


    private List<ClassSymbol> getClasses(final CompilationUnit compilationUnit) {
        return compilationUnit.getNamedImportScope().elements().stream()
                .filter(it -> it instanceof ClassSymbol)
                .map(it -> (ClassSymbol) it)
                .toList();
    }

    public List<MethodSymbol> getMethods(final CompilationUnit compilationUnit) {
        return compilationUnit.getNamedImportScope().elements().stream()
                .filter(it -> it instanceof MethodSymbol)
                .map(it -> (MethodSymbol) it)
                .toList();
    }

    private CompilationUnit doImport(final ImportItem importItem) {
        final var packageSymbol = new PackageSymbol(
                null,
                ""
        );

        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();
        packageSymbol.setModuleSymbol(module);

        final var clazz = new ClassSymbolBuilder()
                .enclosingElement(packageSymbol)
                .kind(ElementKind.CLASS)
                .build();

        final var classDeclaration = new ClassDeclarationBuilder()
                .modifiers(new Modifiers(0))
                .build();

        final var javaBase = getCompilerContext().getSymbolTable().getJavaBase();

        getCompilerContext().getClassElementLoader().loadClass(
                javaBase,
                "java.util.Collection"
        );

        final var compilationUnit = new CCompilationTreeUnit(
                null,
                List.of(importItem),
                List.of(),
                -1,
                -1
        );

        typeEnter.put(
                clazz,
                classDeclaration,
                compilationUnit
        );

        typeEnter.complete(clazz);

        return compilationUnit;
    }

    private ImportItem createImportItem(final String className,
                                        final boolean isStatic) {
        ExpressionTree fieldAccess = null;

        for (final var name : className.split("\\.")) {
            if (fieldAccess == null) {
                fieldAccess = new CIdentifierTree(name);
            } else {
                fieldAccess = new CFieldAccessExpressionTree(
                        fieldAccess,
                        new CIdentifierTree(name)
                );
            }
        }

        return new CImportItemTree(
                (FieldAccessExpressionTree) fieldAccess,
                isStatic,
                -1,
                -1
        );
    }

    @Test
    void initFields() {
        final var field = new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .variableType(IdentifierTree.create("List"))
                .name(IdentifierTree.create("list"))
                .value(new NewClassExpressionBuilder()
                        .name(IdentifierTree.create("ArrayList"))
                        .build())
                .build();

        final var classDeclaration = new ClassDeclarationBuilder()
                .kind(Kind.CLASS)
                .simpleName("SomeClass")
                .enclosingElement(field)
                .build();

        final var compilationUnit = new CCompilationTreeUnit(
                null,
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var packageSymbol = new PackageSymbol(
                null,
                ""
        );

        final var clazz = new ClassSymbolBuilder()
                .enclosingElement(packageSymbol)
                .kind(ElementKind.CLASS)
                .build();

        typeEnter.put(clazz, classDeclaration, compilationUnit);
        typeEnter.complete(clazz);

        final var actual = TreePrinter.print(classDeclaration);
        final var expected = """
                public class SomeClass {
                    list : List;
                    public fun <init>(): void {
                        this.super();
                        this.list = new ArrayList();
                        return;
                    }
                
                }
                """;
        assertEquals(expected, actual);
    }

}