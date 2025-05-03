package io.github.potjerodekool.nabu.compiler;

import io.github.potjerodekool.nabu.compiler.ast.element.ElementKind;
import io.github.potjerodekool.nabu.compiler.ast.element.ModuleElement;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.MethodSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.ClassSymbolLoader;
import io.github.potjerodekool.nabu.compiler.resolve.internal.SymbolTable;
import io.github.potjerodekool.nabu.compiler.resolve.scope.ImportScope;
import io.github.potjerodekool.nabu.compiler.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CPrimitiveType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.util.impl.TypesImpl;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

public class TestClassElementLoader implements ClassSymbolLoader {

    private final SymbolTable symbolTable = Mockito.mock(
            SymbolTable.class
    );

    private final Map<String, ClassSymbol> classes = new HashMap<>();

    private final Map<String, PackageSymbol> packages = new HashMap<>();

    private final TypesImpl types = new TypesImpl(symbolTable);

    public TestClassElementLoader() {
        final var objectType = createObjectClass().asType();

        addClass(objectType);

        lenient()
                .when(symbolTable.getObjectType())
                .thenReturn(objectType);

        lenient()
                .when(symbolTable.getStringType())
                .thenReturn(loadClass(null, Constants.STRING).asType());

        lenient()
                .when(symbolTable.getClassType())
                .thenReturn(loadClass(null, Constants.CLAZZ).asType());

        final var enumType = createEnumClass(objectType).asType();

        addClass(enumType);

        lenient()
                .when(symbolTable.getEnumType())
                .thenReturn(enumType);

        final var recordType = createRecordClass().asType();

        addClass(recordType);

        lenient()
                .when(symbolTable.getRecordType())
                .thenReturn(recordType);

        lenient()
                .when(symbolTable.getClassSymbol(
                        any(),
                        any()
                )).thenAnswer(answer -> {
                    final String name = answer.getArgument(1);
                    return classes.get(name);
                });

        lenient()
                .when(symbolTable.lookupPackage(
                        any(),
                        any()
                )).thenAnswer(answer -> {
                    final String packageName = answer.getArgument(1);
                    return packages.get(packageName);
                });
    }

    private void addClass(final TypeMirror typeMirror) {
        final var clazz = (ClassSymbol) typeMirror.asTypeElement();
        addClass(clazz);
    }

    private void addClass(final ClassSymbol classSymbol) {
        this.classes.put(classSymbol.getQualifiedName(), classSymbol);
    }

    @Override
    public ClassSymbol loadClass(final ModuleElement moduleElement, final String name) {
        var clazz = classes.get(name);

        if (clazz != null) {
            return clazz;
        }

        clazz = createClass(name);
        addClass(clazz);

        return clazz;
    }

    private ClassSymbol createClass(final String name) {
        final var elements = name.split("\\.");
        final var packageName = name.contains(".")
                ? name.substring(0, name.lastIndexOf('.'))
                : null;
        final var simpleName = elements[elements.length - 1];

        final PackageSymbol packageElement = findOrCreatePackage(
                null,
                packageName
        );

        final TypeMirror superType;

        if (Constants.OBJECT.equals(name)) {
            superType = null;
        } else {
            superType = classes.get(Constants.OBJECT).asType();
        }

        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName(simpleName)
                .enclosingElement(packageElement)
                .superclass(superType)
                .build();

        clazz.setMembers(new WritableScope());

        packageElement.getMembers().define(clazz);

        return clazz;
    }

    private ClassSymbol createObjectClass() {
        final var objectClass = loadClass(null, Constants.OBJECT);

        final var cloneMethod = createCloneMethod(objectClass.asType());

        objectClass.addEnclosedElement(cloneMethod);

        return objectClass;
    }

    private MethodSymbol createCloneMethod(final TypeMirror objectType) {
        return new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("clone")
                .returnType(objectType)
                .build();
    }

    private ClassSymbol createEnumClass(final TypeMirror objectType) {
        final var enumClass = createClass(Constants.ENUM);

        enumClass.addEnclosedElement(createConstructor(
                createParameter(createDeclaredType(Constants.STRING), "name"),
                createParameter(new CPrimitiveType(TypeKind.INT), "ordinal")
        ));

        enumClass.addEnclosedElement(createCloneMethod(objectType));

        final var typeVariable = new CTypeVariable("T",
                null,
                new CClassType(
                        null,
                        enumClass,
                        List.of(
                                new CTypeVariable(
                                        "T"
                                )
                        )
                ),
                null
        );

        final var valueOfMethod = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("valueOf")
                .parameter(createParameter(symbolTable.getClassType(), "type"))
                .parameter(createParameter(symbolTable.getStringType(), "name"))
                .typeParameter(typeVariable.asElement())
                .returnType(typeVariable)
                .build();

        enumClass.addEnclosedElement(valueOfMethod);

        enumClass.setSuperClass(objectType);

        return enumClass;
    }

    private ClassSymbol createRecordClass() {
        final var recordClass = createDeclaredType(Constants.RECORD);
        final var recordClassElement = (ClassSymbol) recordClass.asTypeElement();
        recordClassElement.addEnclosedElement(createConstructor());
        return recordClassElement;
    }

    private DeclaredType createDeclaredType(final String className) {
        final var sepIndex = className.lastIndexOf(".");
        final var simpleName = className.substring(sepIndex + 1);
        final var packageName = className.substring(0, sepIndex);
        final var packageSymbol = createPackage(packageName);

        final var classSymbol = new ClassSymbol();
        classSymbol.setSimpleName(simpleName);
        classSymbol.setEnclosingElement(packageSymbol);
        classSymbol.setMembers(new WritableScope());

        classSymbol.setType(
                new CClassType(
                        null,
                        classSymbol,
                        List.of()
                )
        );
        return (DeclaredType) classSymbol.asType();
    }

    private PackageSymbol createPackage(final String name) {
        if (name == null) {
            return PackageSymbol.UNNAMED_PACKAGE;
        }

        PackageSymbol packageSymbol = null;

        for (final var packageName : name.split("\\.")) {
            packageSymbol = new PackageSymbol(
                    packageSymbol,
                    packageName
            );
            packageSymbol.setMembers(new WritableScope());

            packages.put(
                    packageSymbol.getQualifiedName(),
                    packageSymbol
            );
        }

        return packageSymbol;
    }

    public MethodSymbol createConstructor(final VariableSymbol... parameters) {
        final var builder = new MethodSymbolBuilderImpl()
                .kind(ElementKind.CONSTRUCTOR)
                .simpleName(Constants.INIT);

        for (final var parameter : parameters) {
            builder.parameter(parameter);
        }

        return builder.build();
    }

    private VariableSymbol createParameter(final TypeMirror type,
                                           final String name) {
        return new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .type(type)
                .simpleName(name)
                .build();

    }

    @Override
    public SymbolTable getSymbolTable() {
        return symbolTable;
    }

    @Override
    public TypesImpl getTypes() {
        return types;
    }

    @Override
    public PackageSymbol findOrCreatePackage(final ModuleElement moduleElement, final String packageName) {
        var packageSymbol = this.packages.get(packageName);

        if (packageSymbol == null) {
            packageSymbol = createPackage(packageName);
        }

        return packageSymbol;
    }

    @Override
    public void importJavaLang(final ImportScope importScope) {
        //throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
    }

}
