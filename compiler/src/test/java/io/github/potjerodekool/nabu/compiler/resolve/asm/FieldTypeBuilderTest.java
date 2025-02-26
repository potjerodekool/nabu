package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.PackageElementBuilder;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.SignatureParser;
import io.github.potjerodekool.nabu.compiler.type.Types;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldTypeBuilderTest {

    private AsmClassElementLoader loader;
    private Types types;
    private SignatureParser builder;

    @BeforeEach
    void setup() {
        loader = new AsmClassElementLoader();
        types = loader.getTypes();
        builder = new SignatureParser(Opcodes.ASM9, loader);
    }

    @AfterEach
    void tearDown() {
        loader.close();
    }

    private TypeMirror build(final String signature) {
        final var reader = new SignatureReader(signature);
        reader.accept(builder);
        return builder.createFieldType();
    }

    @Test
    void test1() {
        final var actual = build("Ljava/util/Optional<*>;");

        final var optionalClazz = loader.resolveClass("java.util.Optional");

        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        final var expected = types.getDeclaredType(
                optionalClazz,
                types.getWildcardType(objectType, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }


    @Test
    void test2() {
        final var actual = build("TT;");
        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        final var expected = types.getTypeVariable("T", objectType, null);
        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void test3() {
        final var actual = build("Ljava/lang/invoke/ClassSpecializer<TT;TK;TS;>.Factory;");

        var classSpecializerType = (CClassType) types.getDeclaredType(new ClassBuilder()
                .enclosingElement(PackageElementBuilder.createFromName("java.lang.invoke"))
                .name("ClassSpecializer")
                .build());

        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        classSpecializerType = classSpecializerType.withTypeArguments(
                types.getTypeVariable("T", objectType, null),
                types.getTypeVariable("K", objectType, null),
                types.getTypeVariable("S", objectType, null)
        );

        var factoryType = (CClassType) types.getDeclaredType(new ClassBuilder()
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
    void test4() {
        final var actual = build("Ljava/util/List<Ljava/lang/module/Configuration;>;");
        final var listClass = loader.resolveClass("java.util.List");

        final var configurationClass = new ClassBuilder()
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
    void test5() {
        final var actual = build("[Ljava/lang/reflect/TypeVariable<*>;");

        final var typeVariabeleClass = loader.resolveClass("java.lang.reflect/TypeVariable");

        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        final var expected = types.getArrayType(
                types.getDeclaredType(
                        typeVariabeleClass,
                        types.getWildcardType(
                                objectType,
                                null
                        )
                )
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void test6() {
        final var objectType = loader.resolveClass(Constants.OBJECT).asType();

        final var actual = build("[TT;");
        final var expected = types.getArrayType(
                types.getTypeVariable("T", objectType, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }


}