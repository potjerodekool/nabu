package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.dependencyinjection.ApplicationContext;
import io.github.potjerodekool.nabu.compiler.CompilerContext;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.compiler.io.NabuCFileManager;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmTypeResolver;
import io.github.potjerodekool.nabu.compiler.resolve.asm.TypeBuilder;
import io.github.potjerodekool.nabu.compiler.type.DeclaredType;
import io.github.potjerodekool.nabu.compiler.type.TypeKind;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.util.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypesImplTest {

    private final CompilerContext compilerContext = new CompilerContextImpl(
            new ApplicationContext(),
            new NabuCFileManager()
    );
    private final ClassElementLoader loader = compilerContext.getClassElementLoader();
    private final Types types = loader.getTypes();
    private final AsmTypeResolver typeResolver = new AsmTypeResolver(loader, null);
    private final TypeBuilder typeBuilder = new TypeBuilder();

    @Test
    void asMemberOf() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();

        final var setClass = (ClassSymbol) loader.loadClass(module, "java.util.Set");
        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        final var stringClass = loader.loadClass(module, Constants.STRING);

        final var stringType = types.getDeclaredType(
                null,
                stringClass
        );

        final var stringSetType = types.getDeclaredType(
                null,
                setClass,
                stringType
        );

        final var intType = types.getPrimitiveType(TypeKind.INT);

        final var method = new MethodSymbolBuilderImpl()
                .name("add")
                .enclosingElement(setClass)
                .returnType(types.getNoType(TypeKind.VOID))
                .argumentTypes(List.of(intType, types.getTypeVariable("E",objectType, null)))
                .build();

        final var methodType = types.asMemberOf(
                stringSetType,
                method
        );

        final var printer = new TypePrinter();
        methodType.accept(printer, null);
        final var actual = printer.getText();

        assertEquals("void (int, java.lang.String)", actual);
    }

    @Test
    void isSubTypePrimitive() {
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.FLOAT), types.getPrimitiveType(TypeKind.DOUBLE)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.LONG), types.getPrimitiveType(TypeKind.FLOAT)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.INT), types.getPrimitiveType(TypeKind.LONG)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.CHAR), types.getPrimitiveType(TypeKind.INT)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.SHORT), types.getPrimitiveType(TypeKind.INT)));
        assertTrue(types.isSubType(types.getPrimitiveType(TypeKind.BYTE), types.getPrimitiveType(TypeKind.SHORT)));
    }

    @Test
    void isSubTypeNonGeneric() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var arrayListType = (DeclaredType) loader.loadClass(module, "java.util.ArrayList").asType();
        final var listType = (DeclaredType) loader.loadClass(module, "java.util.List").asType();
        assertTrue(types.isSubType(arrayListType, listType));
    }

    @Test
    void isSubTypeGeneric() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var arrayListType = (DeclaredType) loader.loadClass(module, "java.util.ArrayList").asType();
        final var listType = (DeclaredType) loader.loadClass(module, "java.util.List").asType();
        final var objectType = (DeclaredType) loader.loadClass(module, "java.lang.Object").asType();
        final var stringType = (DeclaredType) loader.loadClass(module, "java.lang.String").asType();

        final var arrayListOfObjectType = types.getDeclaredType(
                (TypeElement) arrayListType.asElement(),
                objectType
        );

        final var listOfStringType = types.getDeclaredType(
                (TypeElement) listType.asElement(),
                stringType
        );

        final var arrayListOfStringType = types.getDeclaredType(
                (TypeElement) arrayListType.asElement(),
                stringType
        );

        assertFalse(types.isSubType(
                arrayListOfObjectType,
                listOfStringType
        ));

        assertTrue(types.isSubType(
                arrayListOfStringType,
                listOfStringType
        ));
    }

    @Test
    void isSubTypeArray() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var objectType = loader.loadClass(module, "java.lang.Object").asType();
        final var stringType = loader.loadClass(module, "java.lang.String").asType();
        final var cloneableType = loader.loadClass(module, Constants.CLONEABLE).asType();
        final var serializableType = loader.loadClass(module, Constants.SERIALIZABLE).asType();
        final var intType = types.getPrimitiveType(TypeKind.INT);

        assertTrue(types.isSubType(
                types.getArrayType(stringType),
                types.getArrayType(objectType)
        ));

        assertTrue(
                types.isSubType(
                        types.getArrayType(stringType),
                        cloneableType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(stringType),
                        serializableType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(intType),
                        objectType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(intType),
                        cloneableType
                )
        );

        assertTrue(
                types.isSubType(
                        types.getArrayType(intType),
                        serializableType
                )
        );
    }

    @Test
    void isSameType1() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var actual = typeBuilder.parseFieldSignature("Ljava/util/Optional<*>;", typeResolver, module);

        final var optionalClazz = loader.loadClass(module, "java.util.Optional");

        final var expected = types.getDeclaredType(
                optionalClazz,
                types.getWildcardType(null, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void isSameType2() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getUnnamedModule();
        final var actual = typeBuilder.parseFieldSignature("TT;", typeResolver, module);
        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        final var expected = types.getTypeVariable("T", objectType, null);
        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void isSameType3() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getUnnamedModule();

        final var actual = typeBuilder.parseFieldSignature("Ljava/lang/invoke/ClassSpecializer<TT;TK;TS;>.Factory;", typeResolver, module);

        var classSpecializerType = types.getDeclaredType(new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .enclosingElement(PackageElementBuilder.createFromName("java.lang.invoke"))
                .name("ClassSpecializer")
                .typeParameters(List.of(
                        new CTypeVariable("T", null, null).asElement(),
                        new CTypeVariable("K", null, null).asElement(),
                        new CTypeVariable("S", null, null).asElement()
                ))
                .build());

        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        classSpecializerType =
                types.getDeclaredType(
                        classSpecializerType.asTypeElement(),
                        types.getTypeVariable("T", objectType, null),
                        types.getTypeVariable("K", objectType, null),
                        types.getTypeVariable("S", objectType, null
                ));

        var factoryType = types.getDeclaredType(new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .nestingKind(NestingKind.MEMBER)
                .enclosingElement(classSpecializerType.asElement())
                .name("Factory")
                .outerType(classSpecializerType)
                .build());

        assertTrue(types.isSameType(
                factoryType,
                actual
        ));
    }

    @Test
    void isSameType4() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();

        final var actual = typeBuilder.parseFieldSignature("Ljava/util/List<Ljava/lang/module/Configuration;>;", typeResolver, module);
        final var listClass = loader.loadClass(module, "java.util.List");

        final var configurationClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .enclosingElement(PackageElementBuilder.createFromName("java.lang.module"))
                .name("Configuration")
                .build();

        final var expected = types.getDeclaredType(
                listClass,
                types.getDeclaredType(
                        configurationClass
                )
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void isSameType5() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getJavaBase();
        final var actual = typeBuilder.parseFieldSignature("[Ljava/lang/reflect/TypeVariable<*>;", typeResolver, module);

        final var typeVariableClass = loader.loadClass(module, "java.lang.reflect.TypeVariable");

        final var expected = types.getArrayType(
                types.getDeclaredType(
                        typeVariableClass,
                        types.getWildcardType(
                                null,
                                null
                        )
                )
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void isSameType6() {
        final var asmLoader = (AsmClassElementLoader) loader;
        final var module = asmLoader.getSymbolTable().getUnnamedModule();
        final var objectType = loader.loadClass(module, Constants.OBJECT).asType();

        final var actual = typeBuilder.parseFieldSignature("[TT;", typeResolver, module);
        final var expected = types.getArrayType(
                types.getTypeVariable("T", objectType, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }

}
