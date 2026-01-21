package io.github.potjerodekool.nabu;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import io.github.potjerodekool.nabu.ByteCodePrinter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;


public class StringsTest {

    @Disabled
    @Test
    void test() {
        //ByteCodePrinter.print("C:\\projects\\nabu\\compiler-test\\target\\classes\\io\\github\\potjerodekool\\nabu\\example\\PetMapper.class");
        ByteCodePrinter.validate("C:\\projects\\nabu\\compiler-test\\target\\classes\\io\\github\\potjerodekool\\nabu\\example\\PetMapper.class");

        //ByteCodePrinter.print("C:\\projects\\nabu\\jpa-support\\target\\test-classes\\io\\github\\potjerodekool\\nabu\\EnumUsage$1.class");
        //


        /*
        //final var strings = Strings.class.getDeclaredConstructor().newInstance();
        final var strings = new Strings();
        final var actual = strings.sayHi();
        final var expected = "Hi Evert";
        assertEquals(expected, actual);
        */
    }
}
