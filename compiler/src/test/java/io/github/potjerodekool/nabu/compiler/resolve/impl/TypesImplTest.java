package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.AsmTypeResolver;
import io.github.potjerodekool.nabu.compiler.resolve.asm.TypeBuilder;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.CTypeVariable;
import io.github.potjerodekool.nabu.compiler.type.impl.CWildcardType;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.NestingKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.type.BoundKind;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypesImplTest extends AbstractCompilerTest {

    private final Types types = getCompilerContext().getClassElementLoader().getTypes();
    private final AsmTypeResolver typeResolver = new AsmTypeResolver(getCompilerContext().getClassElementLoader(), null);
    private final TypeBuilder typeBuilder = new TypeBuilder();
    private final SymbolTable symbolTable = getCompilerContext().getSymbolTable();

    @Test
    void asMemberOf() {
        final var module = getCompilerContext().getSymbolTable().getJavaBase();

        final var setClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.util.Set");
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(module, Constants.OBJECT).asType();

        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(module, Constants.STRING);

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
                .simpleName("add")
                .enclosingElement(setClass)
                .returnType(types.getNoType(TypeKind.VOID))
                .parameters(
                        List.of(
                                createParameter("index", intType),
                                createParameter("element",  types.getTypeVariable("E",objectType, null))
                        )
                )
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

    private VariableSymbol createParameter(final String name,
                                           final TypeMirror type) {
        return (VariableSymbol) new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName(name)
                .type(type)
                .build();
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
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var arrayListClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.util.ArrayList");
        final var arrayListType = types.getDeclaredType(arrayListClass);
        final var listClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.util.List");
        final var listType = types.getDeclaredType(listClass);
        assertTrue(types.isSubType(arrayListType, listType));
    }

    @Test
    void isSubTypeGeneric() {
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var arrayListType = (DeclaredType) getCompilerContext().getClassElementLoader().loadClass(module, "java.util.ArrayList").asType();
        final var listType = (DeclaredType) getCompilerContext().getClassElementLoader().loadClass(module, "java.util.List").asType();
        final var objectType = (DeclaredType) getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.Object").asType();
        final var stringType = (DeclaredType) getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.String").asType();

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
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.Object").asType();
        final var stringType = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.String").asType();
        final var cloneableType = getCompilerContext().getClassElementLoader().loadClass(module, Constants.CLONEABLE).asType();
        final var serializableType = getCompilerContext().getClassElementLoader().loadClass(module, Constants.SERIALIZABLE).asType();
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
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var actual = typeBuilder.parseFieldSignature("Ljava/util/Optional<*>;", typeResolver, module);

        final var optionalClazz = getCompilerContext().getClassElementLoader().loadClass(module, "java.util.Optional");

        final var expected = types.getDeclaredType(
                optionalClazz,
                types.getWildcardType(null, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void isSameType2() {
        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();
        final var actual = typeBuilder.parseFieldSignature("TT;", typeResolver, module);
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(module, Constants.OBJECT).asType();

        final var expected = types.getTypeVariable("T", objectType, null);
        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void isSameType3() {
        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();

        final var actual = typeBuilder.parseFieldSignature("Ljava/lang/invoke/ClassSpecializer<TT;TK;TS;>.Factory;", typeResolver, module);

        var classSpecializerType = types.getDeclaredType(new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .enclosingElement(PackageElementBuilder.createFromName("java.lang.invoke"))
                .simpleName("ClassSpecializer")
                .typeParameters(List.of(
                        new CTypeVariable("T", null, null, null).asElement(),
                        new CTypeVariable("K", null, null, null).asElement(),
                        new CTypeVariable("S", null, null, null).asElement()
                ))
                .build());

        final var objectType = getCompilerContext().getClassElementLoader().loadClass(module, Constants.OBJECT).asType();

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
                .simpleName("Factory")
                .outerType(classSpecializerType)
                .build());

        assertTrue(types.isSameType(
                factoryType,
                actual
        ));
    }

    @Test
    void isSameType4() {
        final var module = getCompilerContext().getSymbolTable().getJavaBase();

        final var actual = typeBuilder.parseFieldSignature("Ljava/util/List<Ljava/lang/module/Configuration;>;", typeResolver, module);
        final var listClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.util.List");

        final var configurationClass = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .enclosingElement(PackageElementBuilder.createFromName("java.lang.module"))
                .simpleName("Configuration")
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
        final var module = getCompilerContext().getSymbolTable().getJavaBase();
        final var actual = typeBuilder.parseFieldSignature("[Ljava/lang/reflect/TypeVariable<*>;", typeResolver, module);

        final var typeVariableClass = getCompilerContext().getClassElementLoader().loadClass(module, "java.lang.reflect.TypeVariable");

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
        final var module = getCompilerContext().getSymbolTable().getUnnamedModule();
        final var objectType = getCompilerContext().getClassElementLoader().loadClass(module, Constants.OBJECT).asType();

        final var actual = typeBuilder.parseFieldSignature("[TT;", typeResolver, module);
        final var expected = types.getArrayType(
                types.getTypeVariable("T", objectType, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void getWildcardType() {
        var wildcard = types.getWildcardType(null, null);
        assertEquals(BoundKind.UNBOUND, wildcard.getBoundKind());

        final var stringClass = getCompilerContext().getClassElementLoader().loadClass(null, Constants.STRING)
                .asType();
        wildcard = types.getWildcardType(stringClass, null);
        assertEquals(BoundKind.EXTENDS, wildcard.getBoundKind());
        assertEquals(stringClass, wildcard.getExtendsBound());

        wildcard = types.getWildcardType(null, stringClass);
        assertEquals(BoundKind.SUPER, wildcard.getBoundKind());
        assertEquals(stringClass, wildcard.getSuperBound());

        assertThrows(IllegalArgumentException.class, () ->
                types.getWildcardType(stringClass, stringClass));
    }

    @Test
    void capture() {
        final var clazz = new ClassSymbolBuilder()
                .build();

        final var wildcard = new CWildcardType(
                null,
                BoundKind.UNBOUND,
                symbolTable.getBoundClass()
        );

        final var type = new CClassType(
                null,
                clazz,
                List.of(wildcard)
        );

        types.capture(type);
    }

}
