package io.github.potjerodekool.nabu.compiler.resolve.internal;

import io.github.potjerodekool.nabu.compiler.TestClassElementLoader;
import io.github.potjerodekool.nabu.compiler.TreePrinter;
import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.tree.Modifiers;
import io.github.potjerodekool.nabu.compiler.tree.CompilationUnit;
import io.github.potjerodekool.nabu.compiler.tree.ImportItem;
import io.github.potjerodekool.nabu.compiler.tree.element.Kind;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.ClassDeclarationBuilder;
import io.github.potjerodekool.nabu.compiler.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.builder.NewClassExpressionBuilder;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.compiler.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.compiler.tree.impl.CCompilationTreeUnit;
import io.github.potjerodekool.nabu.compiler.tree.impl.CImportItemTree;
import io.github.potjerodekool.nabu.compiler.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.compiler.tree.statement.impl.CBlockStatementTree;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TypeEnterTest {

    private final CompilerContextImpl compilerContext = mock(CompilerContextImpl.class);
    private final ClassSymbolLoader loader = new TestClassElementLoader();
    private TypeEnter typeEnter;

    @BeforeEach
    void setup() {
        when(compilerContext.getClassElementLoader()).thenReturn(loader);
        typeEnter = new TypeEnter(compilerContext);
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
                .type(new CIdentifierTree("java.lang.String"))
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
        final var compilationUnit = doImport(createImportItem("java.lang.Class", false));
        final var importedClassNames = getClasses(compilationUnit).stream()
                .map(ClassSymbol::getQualifiedName)
                .toList();

        assertEquals(
                List.of("java.lang.Class"),
                importedClassNames
        );
    }

    @Test
    void starImportItem() {
        loader.loadClass(null, "java.util.List");
        loader.loadClass(null, "java.util.Iterator");
        loader.loadClass(null, "java.util.Collection");

        final var compilationUnit = doImport(createImportItem("java.util.*", false));

        final var importedClassNames = getClasses(compilationUnit).stream()
                .map(ClassSymbol::getQualifiedName)
                .toList();

        assertEquals(
                List.of(
                        "java.util.List",
                        "java.util.Iterator",
                        "java.util.Collection"
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

        final var clazz = new ClassSymbolBuilder()
                .enclosingElement(packageSymbol)
                .kind(ElementKind.CLASS)
                .build();

        final var classDeclaration = new ClassDeclarationBuilder()
                .modifiers(new Modifiers(0))
                .build();

        loader.loadClass(
                null,
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
        CFieldAccessExpressionTree fieldAccess = null;

        for (final var name : className.split("\\.")) {
            if (fieldAccess != null) {
                if (fieldAccess.getField() == null) {
                    fieldAccess.field(new CIdentifierTree(name));
                } else {
                    fieldAccess = new CFieldAccessExpressionTree(
                            fieldAccess,
                            new CIdentifierTree(name)
                    );
                }
            } else {
                fieldAccess = new CFieldAccessExpressionTree(
                        new CIdentifierTree(name),
                        null
                );
            }
        }

        return new CImportItemTree(
                fieldAccess,
                isStatic,
                -1,
                -1
        );
    }

    @Test
    void initFields() {
        final var field = new VariableDeclaratorTreeBuilder()
                .kind(Kind.FIELD)
                .type(IdentifierTree.create("List"))
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