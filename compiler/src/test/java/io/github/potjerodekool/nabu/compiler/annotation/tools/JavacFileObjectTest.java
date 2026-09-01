package io.github.potjerodekool.nabu.compiler.annotation.tools;

import io.github.potjerodekool.nabu.compiler.InMemoryFileObject;
import org.junit.jupiter.api.Test;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class JavacFileObjectTest {

    @Test
    void kindReturnsSourceByDefault() {
        final var obj = new JavacFileObject(null, "com.example", null);
        assertEquals(JavaFileObject.Kind.SOURCE, obj.getKind());
    }

    @Test
    void kindReturnsProvidedKind() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.CLASS);
        assertEquals(JavaFileObject.Kind.CLASS, obj.getKind());
    }

    @Test
    void isNameCompatibleReturnsTrueForSameKind() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertTrue(obj.isNameCompatible("anything", JavaFileObject.Kind.SOURCE));
    }

    @Test
    void isNameCompatibleReturnsFalseForDifferentKind() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertFalse(obj.isNameCompatible("anything", JavaFileObject.Kind.CLASS));
    }

    @Test
    void getNestingKindReturnsTopLevel() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertEquals(javax.lang.model.element.NestingKind.TOP_LEVEL, obj.getNestingKind());
    }

    @Test
    void getAccessLevelReturnsPublic() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertEquals(javax.lang.model.element.Modifier.PUBLIC, obj.getAccessLevel());
    }

    @Test
    void toUriReturnsStringUri() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        final var uri = obj.toUri();
        assertTrue(uri.toString().startsWith("string:///generated/"));
    }

    @Test
    void getNameIncludesPackageNameAndExtension() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertEquals("com.example..java", obj.getName());
    }

    @Test
    void getNameIncludesModuleName() {
        final var module = new io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol(0L, "test");
        final var obj = new JavacFileObject(module, "com.example", JavaFileObject.Kind.SOURCE);
        assertEquals("test/com.example..java", obj.getName());
    }

    @Test
    void openInputStreamWithoutDelegateThrows() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertThrows(IOException.class, obj::openInputStream);
    }

    @Test
    void openOutputStreamWithoutDelegateThrows() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertThrows(IOException.class, obj::openOutputStream);
    }

    @Test
    void openReaderWithoutDelegateThrows() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertThrows(IOException.class, () -> obj.openReader(false));
    }

    @Test
    void getCharContentWithoutDelegateThrows() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertThrows(IOException.class, () -> obj.getCharContent(false));
    }

    @Test
    void openWriterWithoutDelegateThrows() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertThrows(IOException.class, obj::openWriter);
    }

    @Test
    void getLastModifiedReturnsZero() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertEquals(0, obj.getLastModified());
    }

    @Test
    void deleteReturnsFalse() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertFalse(obj.delete());
    }

    @Test
    void getModuleSymbolReturnsModule() {
        final var module = new io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol(0L, "test");
        final var obj = new JavacFileObject(module, "com.example", JavaFileObject.Kind.SOURCE);
        assertSame(module, obj.getModuleSymbol());
    }

    @Test
    void getPackageNameReturnsPackageName() {
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE);
        assertEquals("com.example", obj.getPackageName());
    }

    @Test
    void delegateDelegatesOpenInputStream() throws IOException {
        final var delegate = new InMemoryFileObject("hello", "Test.java");
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE, delegate);

        try (var is = obj.openInputStream()) {
            assertEquals("hello", new String(is.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void delegateDelegatesGetCharContent() throws IOException {
        final var delegate = new InMemoryFileObject("hello world", "Test.java");
        final var obj = new JavacFileObject(null, "com.example", JavaFileObject.Kind.SOURCE, delegate);

        final var content = obj.getCharContent(false);
        assertEquals("hello world", content.toString());
    }
}
