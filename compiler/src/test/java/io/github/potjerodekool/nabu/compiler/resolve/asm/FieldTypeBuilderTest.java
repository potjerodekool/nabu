package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.resolve.Types;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.FieldTypeBuilder;
import io.github.potjerodekool.nabu.compiler.transform.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.type.immutable.ImmutableWildcardType;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableClassType;
import io.github.potjerodekool.nabu.compiler.type.TypeMirror;
import io.github.potjerodekool.nabu.compiler.type.mutable.MutableTypeVariable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldTypeBuilderTest {

    private AsmClassElementLoader loader;
    private Types types;
    private FieldTypeBuilder builder;

    @BeforeEach
    void setup( ){
        loader = new AsmClassElementLoader();
        types = loader.getTypes();
        builder = new FieldTypeBuilder(Opcodes.ASM9, loader);
    }

    @AfterEach
    void tearDown() {
        loader.close();
    }

    private TypeMirror build(final String signature) {
        final var reader = new SignatureReader(signature);
        reader.accept(builder);
        return builder.getFieldType();
    }

    @Test
    void test1() {
        final var actual = build("Ljava/util/Optional<*>;");

        final var optionalClazz = new ClassBuilder()
                .name("java.util.Optional")
                        .build();

        final var expected = types.getDeclaredType(
                optionalClazz,
                new ImmutableWildcardType(null, null)
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void test2() {
        final var actual = build("TT;");
        final var expected = new MutableTypeVariable("T");
        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void test3() {
        final var actual = build("Ljava/lang/invoke/ClassSpecializer<TT;TK;TS;>.Factory;");

        final var classSpecializerType = (MutableClassType) types.getDeclaredType(new ClassBuilder()
                .name("java.lang.invoke.ClassSpecializer")
                .build());

        classSpecializerType.addParameterType(new MutableTypeVariable("T"));
        classSpecializerType.addParameterType(new MutableTypeVariable("K"));
        classSpecializerType.addParameterType(new MutableTypeVariable("S"));

        var factoryType = (MutableClassType) types.getDeclaredType(new ClassBuilder()
                .name("java.lang.invoke.ClassSpecializer.Factory")
                .build());
        factoryType.setOuterType(classSpecializerType);

        assertTrue(types.isSameType(
                factoryType,
                actual
        ));
    }

    // Ljava/util/Map<Ljava/lang/Class<+Ljava/lang/annotation/Annotation;>;Ljava/lang/annotation/Annotation;>;

    @Test
    void test4() {
        final var actual = build("Ljava/util/List<Ljava/lang/module/Configuration;>;");
        final var listClass = new ClassBuilder()
                .name("java.util.List")
                .build();

        final var configurationClass = new ClassBuilder()
                .name("java.lang.module.Configuration")
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

        final var typeVariabeleClass = new ClassBuilder()
                .name("java.lang.reflect.TypeVariable")
                .build();

        final var expected = types.getArrayType(
                types.getDeclaredType(
                        typeVariabeleClass,
                        new ImmutableWildcardType()
                )
        );

        assertTrue(types.isSameType(expected, actual));
    }

    @Test
    void test6() {
        final var actual = build("[TT;");
        final var expected = types.getArrayType(
                new MutableTypeVariable("T")
        );

        assertTrue(types.isSameType(expected, actual));
    }


}