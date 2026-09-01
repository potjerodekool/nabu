package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.ClassSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.lang.model.element.CCompoundAttribute;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.util.Elements;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.potjerodekool.nabu.test.TestUtils.fixLines;
import static org.junit.jupiter.api.Assertions.*;

class UtilImplTest extends AbstractCompilerTest {

    @AfterEach
    void cleanup() {
        final var ctx = getCompilerContext();
        if (ctx != null) {
            try {
                ctx.close();
            } catch (final Exception ignored) {
            }
        }
    }

    // ==================== ElementsImpl.quoteChar ====================

    @Test
    void quoteChar_backspace() {
        assertEquals("\\b", ElementsImpl.quoteChar('\b'));
    }

    @Test
    void quoteChar_formFeed() {
        assertEquals("\\f", ElementsImpl.quoteChar('\f'));
    }

    @Test
    void quoteChar_newline() {
        assertEquals("\\n", ElementsImpl.quoteChar('\n'));
    }

    @Test
    void quoteChar_carriageReturn() {
        assertEquals("\\r", ElementsImpl.quoteChar('\r'));
    }

    @Test
    void quoteChar_tab() {
        assertEquals("\\t", ElementsImpl.quoteChar('\t'));
    }

    @Test
    void quoteChar_singleQuote() {
        assertEquals("\\'", ElementsImpl.quoteChar('\''));
    }

    @Test
    void quoteChar_doubleQuote() {
        assertEquals("\\\"", ElementsImpl.quoteChar('"'));
    }

    @Test
    void quoteChar_backslash() {
        assertEquals("\\\\", ElementsImpl.quoteChar('\\'));
    }

    @ParameterizedTest
    @ValueSource(chars = {'a', 'Z', '0', '9', ' ', '~', '!', '@', '#', 'A', 'x'})
    void quoteChar_printableAscii_returnsSameChar(final char ch) {
        assertEquals(String.valueOf(ch), ElementsImpl.quoteChar(ch));
    }

    @Test
    void quoteChar_nullChar_returnsUnicodeEscape() {
        assertEquals("\\u0000", ElementsImpl.quoteChar('\u0000'));
    }

    @Test
    void quoteChar_highNonPrintableAscii_returnsUnicodeEscape() {
        assertEquals("\\u00ff", ElementsImpl.quoteChar('\u00ff'));
    }

    @Test
    void quoteChar_delChar_returnsUnicodeEscape() {
        assertEquals("\\u007f", ElementsImpl.quoteChar('\u007f'));
    }

    // ==================== ElementsImpl.getConstantExpression ====================

    @Test
    void getConstantExpression_byteValue() {
        assertEquals("(byte)0x0a", getElements().getConstantExpression((byte) 10));
    }

    @Test
    void getConstantExpression_byteZero() {
        assertEquals("(byte)0x00", getElements().getConstantExpression((byte) 0));
    }

    @Test
    void getConstantExpression_byteNegative() {
        assertEquals("(byte)0xff", getElements().getConstantExpression((byte) -1));
    }

    @Test
    void getConstantExpression_byteMax() {
        assertEquals("(byte)0x7f", getElements().getConstantExpression((byte) 127));
    }

    @Test
    void getConstantExpression_shortValue() {
        assertEquals("(short)42", getElements().getConstantExpression((short) 42));
    }

    @Test
    void getConstantExpression_shortNegative() {
        assertEquals("(short)-1", getElements().getConstantExpression((short) -1));
    }

    @Test
    void getConstantExpression_shortZero() {
        assertEquals("(short)0", getElements().getConstantExpression((short) 0));
    }

    @Test
    void getConstantExpression_longValue() {
        assertEquals("100L", getElements().getConstantExpression(100L));
    }

    @Test
    void getConstantExpression_longNegative() {
        assertEquals("-1L", getElements().getConstantExpression(-1L));
    }

    @Test
    void getConstantExpression_longMinValue() {
        assertEquals(Long.MIN_VALUE + "L", getElements().getConstantExpression(Long.MIN_VALUE));
    }

    @Test
    void getConstantExpression_longZero() {
        assertEquals("0L", getElements().getConstantExpression(0L));
    }

    @Test
    void getConstantExpression_floatValue() {
        assertEquals("3.14f", getElements().getConstantExpression(3.14f));
    }

    @Test
    void getConstantExpression_floatNaN() {
        assertEquals("0.0f/0.0f", getElements().getConstantExpression(Float.NaN));
    }

    @Test
    void getConstantExpression_floatPositiveInfinity() {
        assertEquals("1.0f/0.0f", getElements().getConstantExpression(Float.POSITIVE_INFINITY));
    }

    @Test
    void getConstantExpression_floatNegativeInfinity() {
        assertEquals("-1.0f/0.0f", getElements().getConstantExpression(Float.NEGATIVE_INFINITY));
    }

    @Test
    void getConstantExpression_floatZero() {
        assertEquals("0.0f", getElements().getConstantExpression(0.0f));
    }

    @Test
    void getConstantExpression_doubleValue() {
        assertEquals("2.718281828", getElements().getConstantExpression(2.718281828));
    }

    @Test
    void getConstantExpression_doubleNaN() {
        assertEquals("0.0/0.0", getElements().getConstantExpression(Double.NaN));
    }

    @Test
    void getConstantExpression_doublePositiveInfinity() {
        assertEquals("1.0/0.0", getElements().getConstantExpression(Double.POSITIVE_INFINITY));
    }

    @Test
    void getConstantExpression_doubleNegativeInfinity() {
        assertEquals("-1.0/0.0", getElements().getConstantExpression(Double.NEGATIVE_INFINITY));
    }

    @Test
    void getConstantExpression_doubleZero() {
        assertEquals("0.0", getElements().getConstantExpression(0.0));
    }

    @Test
    void getConstantExpression_charValue() {
        assertEquals("'A'", getElements().getConstantExpression('A'));
    }

    @Test
    void getConstantExpression_charTab() {
        assertEquals("'\\t'", getElements().getConstantExpression('\t'));
    }

    @Test
    void getConstantExpression_charNewline() {
        assertEquals("'\\n'", getElements().getConstantExpression('\n'));
    }

    @Test
    void getConstantExpression_charUnicodeEscape() {
        assertEquals("'\\u0000'", getElements().getConstantExpression('\u0000'));
    }

    @Test
    void getConstantExpression_nullValue_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                getElements().getConstantExpression(null));
    }

    @Test
    void getConstantExpression_nonPrimitive_throwsException() {
        assertThrows(IllegalArgumentException.class, () ->
                getElements().getConstantExpression(Integer.valueOf(42)));
    }

    // ==================== ElementsImpl.getDocComment ====================

    @Test
    void getDocComment_classElement_returnsEmptyString() {
        assertEquals("", getElements().getDocComment(loadClass("java.lang.String")));
    }

    @Test
    void getDocComment_methodElement_returnsEmptyString() {
        final var method = findMethod("java.lang.String", "length");
        assertEquals("", getElements().getDocComment(method));
    }

    @Test
    void getDocComment_integerClass_returnsEmptyString() {
        assertEquals("", getElements().getDocComment(loadClass("java.lang.Integer")));
    }

    // ==================== ElementsImpl.isDeprecated ====================

    @Test
    void isDeprecated_withDeprecatedAnnotation_returnsTrue() {
        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("DeprecatedClass")
                .annotations(createAnnotationMirror("java.lang.Deprecated"))
                .build();

        assertTrue(getElements().isDeprecated(classSymbol));
    }

    @Test
    void isDeprecated_withoutAnnotation_returnsFalse() {
        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("NormalClass")
                .build();

        assertFalse(getElements().isDeprecated(classSymbol));
    }

    // ==================== ElementsImpl.isBridge ====================

    @Test
    void isBridge_withBridgeFlag_returnsTrue() {
        final var method = createMethod("bridgeMethod", Flags.BRIDGE);
        assertTrue(getElements().isBridge(method));
    }

    @Test
    void isBridge_withoutBridgeFlag_returnsFalse() {
        final var method = createMethod("normalMethod", 0);
        assertFalse(getElements().isBridge(method));
    }

    // ==================== ElementsImpl.isAutomaticModule ====================

    @Test
    void isAutomaticModule_javaBase_returnsFalse() {
        assertFalse(getElements().isAutomaticModule(getCompilerContext().getModules().getJavaBase()));
    }

    @Test
    void isAutomaticModule_unnamedModule_returnsFalse() {
        assertFalse(getElements().isAutomaticModule(getCompilerContext().getModules().getUnnamedModule()));
    }

    // ==================== ElementsImpl.isCanonicalConstructor ====================

    @Test
    void isCanonicalConstructor_withRecordFlag_returnsTrue() {
        final var ctor = createConstructor("MyRecord", Flags.RECORD);
        assertTrue(getElements().isCanonicalConstructor(ctor));
    }

    @Test
    void isCanonicalConstructor_withoutRecordFlag_returnsFalse() {
        final var ctor = createConstructor("MyClass", 0);
        assertFalse(getElements().isCanonicalConstructor(ctor));
    }

    // ==================== ElementsImpl.isCompactConstructor ====================

    @Test
    void isCompactConstructor_withFlag_returnsTrue() {
        final var ctor = createConstructor("MyRecord", Flags.COMPACT_RECORD_CONSTRUCTOR);
        assertTrue(getElements().isCompactConstructor(ctor));
    }

    @Test
    void isCompactConstructor_withoutFlag_returnsFalse() {
        final var ctor = createConstructor("MyClass", 0);
        assertFalse(getElements().isCompactConstructor(ctor));
    }

    // ==================== ElementsImpl.getBinaryName ====================

    @Test
    void getBinaryName_stringClass() {
        assertEquals("java.lang.String", getElements().getBinaryName(loadClass("java.lang.String")));
    }

    @Test
    void getBinaryName_integerClass() {
        assertEquals("java.lang.Integer", getElements().getBinaryName(loadClass("java.lang.Integer")));
    }

    @Test
    void getBinaryName_hashMapClass() {
        assertEquals("java.util.HashMap", getElements().getBinaryName(loadClass("java.util.HashMap")));
    }

    // ==================== ElementsImpl.getPackageOf ====================

    @Test
    void getPackageOf_classElement() {
        final var pkg = getElements().getPackageOf(loadClass("java.lang.String"));
        assertNotNull(pkg);
        assertEquals("java.lang", pkg.getQualifiedName());
    }

    @Test
    void getPackageOf_methodElement() {
        final var method = findMethod("java.lang.String", "length");
        final var pkg = getElements().getPackageOf(method);
        assertNotNull(pkg);
        assertEquals("java.lang", pkg.getQualifiedName());
    }

    // ==================== ElementsImpl.getOutermostTypeElement ====================

    @Test
    void getOutermostTypeElement_null_returnsNull() {
        assertNull(getElements().getOutermostTypeElement(null));
    }

    @Test
    void getOutermostTypeElement_topLevelClass_returnsItself() {
        final var stringClass = loadClass("java.lang.String");
        final var outermost = getElements().getOutermostTypeElement(stringClass);
        assertNotNull(outermost);
        assertEquals("java.lang.String", outermost.getQualifiedName());
    }

    @Test
    void getOutermostTypeElement_methodEnclosedByType_returnsType() {
        final var method = findMethod("java.lang.String", "length");
        final var outermost = getElements().getOutermostTypeElement(method);
        assertNotNull(outermost);
        assertEquals("java.lang.String", outermost.getQualifiedName());
    }

    @Test
    void getOutermostTypeElement_fieldEnclosedByType_returnsType() {
        final var stringClass = loadClass("java.lang.String");
        final var fields = ElementFilter.fieldsIn(stringClass.getEnclosedElements());
        if (!fields.isEmpty()) {
            final var outermost = getElements().getOutermostTypeElement(fields.getFirst());
            assertNotNull(outermost);
            assertEquals("java.lang.String", outermost.getQualifiedName());
        }
    }

    // ==================== ElementsImpl.printElements ====================

    @Test
    void printElements_classElement() {
        final var writer = new StringWriter();
        getElements().printElements(writer, loadClass("java.lang.String"));
        assertFalse(writer.toString().isEmpty());
    }

    // ==================== ElementsImpl.isFunctionalInterface ====================

    @Test
    void isFunctionalInterface_interfaceWithoutAnnotation_returnsFalse() {
        assertFalse(getElements().isFunctionalInterface(loadClass("java.lang.Comparable")));
    }

    @Test
    void isFunctionalInterface_class_returnsFalse() {
        assertFalse(getElements().isFunctionalInterface(loadClass("java.lang.String")));
    }

    @Test
    void isFunctionalInterface_interfaceWithAnnotation_returnsTrue() {
        final var iface = new ClassSymbolBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("MyFunc")
                .annotations(createAnnotationMirror("java.lang.FunctionalInterface"))
                .build();

        assertTrue(getElements().isFunctionalInterface(iface));
    }

    @Test
    void isFunctionalInterface_plainInterface_returnsFalse() {
        final var iface = new ClassSymbolBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("MyInterface")
                .build();

        assertFalse(getElements().isFunctionalInterface(iface));
    }

    // ==================== ElementsImpl.getOrigin(Element) ====================

    @Test
    void getOrigin_explicitElement_returnsExplicit() {
        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("ExplicitClass")
                .build();

        assertEquals(Elements.Origin.EXPLICIT, getElements().getOrigin(classSymbol));
    }

    @Test
    void getOrigin_mandatedElement_returnsMandated() {
        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("MandatedClass")
                .flags(Flags.MANDATED)
                .build();

        assertEquals(Elements.Origin.MANDATED, getElements().getOrigin(classSymbol));
    }

    @Test
    void getOrigin_generatedDefaultConstructor_returnsMandated() {
        final var classSymbol = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("GeneratedClass")
                .flags(Flags.GENERATED_DEFAULT_CONSTRUCTOR)
                .build();

        assertEquals(Elements.Origin.MANDATED, getElements().getOrigin(classSymbol));
    }

    // ==================== ElementsImpl.recordComponentFor ====================

    @Test
    void recordComponentFor_nonRecordMethod_returnsNull() {
        final var method = createMethod("getValue", 0);
        assertNull(getElements().recordComponentFor(method));
    }

    // ==================== ElementsImpl.getModuleElement ====================

    @Test
    void getModuleElement_unnamedModule() {
        assertNotNull(getElements().getModuleElement(""));
    }

    // ==================== ElementsImpl.getTypeElement ====================

    @Test
    void getTypeElement_loadedClass() {
        final var stringClass = loadClass("java.lang.String");
        assertNotNull(stringClass);
        assertEquals("java.lang.String", stringClass.getQualifiedName());
    }

    // ==================== ElementsImpl.getPackageElement ====================

    @Test
    void getPackageElement_javaLang() {
        final var pkg = getElements().getPackageElement("java.lang");
        assertNotNull(pkg);
        assertEquals("java.lang", pkg.getQualifiedName());
    }

    // ==================== ElementsImpl.overrides ====================

    @Test
    void overrides_arrayListGet_overrides_listGet() {
        final var arrayList = loadClass("java.util.ArrayList");
        final var list = loadClass("java.util.List");

        final var arrayListGetMethod = ElementFilter.methodsIn(arrayList.getEnclosedElements())
                .stream()
                .filter(m -> m.getSimpleName().equals("get"))
                .findFirst()
                .orElseThrow();

        final var listGetMethod = ElementFilter.methodsIn(list.getEnclosedElements())
                .stream()
                .filter(m -> m.getSimpleName().equals("get"))
                .findFirst()
                .orElseThrow();

        assertTrue(getElements().overrides(arrayListGetMethod, listGetMethod, arrayList));
    }

    // ==================== Origin enum ====================

    @Test
    void originEnum_isDeclared() {
        assertTrue(Elements.Origin.EXPLICIT.isDeclared());
        assertTrue(Elements.Origin.MANDATED.isDeclared());
        assertFalse(Elements.Origin.SYNTHETIC.isDeclared());
    }

    // ==================== ElementPrinter tests ====================

    @Test
    void elementPrinter_printEmptyClass() throws IOException {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("EmptyClass")
                .build();

        final var output = printElement(clazz);
        assertTrue(output.contains("class EmptyClass"));
        assertTrue(output.contains("{"));
        assertTrue(output.contains("}"));
    }

    @Test
    void elementPrinter_printPublicFinalClass() throws IOException {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .flags(Flags.PUBLIC + Flags.FINAL)
                .simpleName("MyClass")
                .build();

        final var output = printElement(clazz);
        assertTrue(output.contains("public final class MyClass"));
    }

    @Test
    void elementPrinter_printInterface() throws IOException {
        final var iface = new ClassSymbolBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("MyInterface")
                .build();

        final var output = printElement(iface);
        assertTrue(output.contains("interface MyInterface"));
    }

    @Test
    void elementPrinter_printAbstractClass() throws IOException {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .flags(Flags.PUBLIC + Flags.ABSTRACT)
                .simpleName("AbstractClass")
                .build();

        final var output = printElement(clazz);
        assertTrue(output.contains("public abstract class AbstractClass"));
    }

    @Test
    void elementPrinter_printClassWithEnclosedMethod() throws IOException {
        final var returnType = getCompilerContext().getTypes().getNoType(TypeKind.VOID);
        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .flags(Flags.PUBLIC)
                .simpleName("doSomething")
                .returnType(returnType)
                .build();

        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("MyClass")
                .enclosedElement(method)
                .build();

        final var output = printElement(clazz);
        assertTrue(output.contains("public fun doSomething()"));
        assertTrue(output.contains("void"));
    }

    @Test
    void elementPrinter_printMethodWithParameters() throws IOException {
        final var stringType = loadClass("java.lang.String").asType();
        final var returnType = getCompilerContext().getTypes().getNoType(TypeKind.VOID);
        final var param = new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName("name")
                .type(stringType)
                .build();

        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("greet")
                .returnType(returnType)
                .parameter(param)
                .build();

        final var output = printElement(method);
        assertTrue(output.contains("fun greet(name"));
        assertTrue(output.contains(")"));
    }

    @Test
    void elementPrinter_printMethodWithReturnType() throws IOException {
        final var stringType = loadClass("java.lang.String").asType();
        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("getName")
                .returnType(stringType)
                .build();

        final var output = printElement(method);
        assertTrue(output.contains("fun getName()"));
        assertTrue(output.contains("java.lang.String"));
    }

    @Test
    void elementPrinter_printVariableField() throws IOException {
        final var stringType = loadClass("java.lang.String").asType();
        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .simpleName("name")
                .type(stringType)
                .build();

        final var output = printElement(field);
        assertTrue(output.contains("name"));
        assertTrue(output.contains("java.lang.String"));
        assertTrue(output.contains(";"));
    }

    @Test
    void elementPrinter_printVariableLocal() throws IOException {
        final var intType = getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT);
        final var localVar = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName("count")
                .type(intType)
                .build();

        final var output = printElement(localVar);
        assertTrue(output.contains("count"));
    }

    @Test
    void elementPrinter_printFieldWithModifiers() throws IOException {
        final var stringType = loadClass("java.lang.String").asType();
        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .flags(Flags.PRIVATE + Flags.FINAL)
                .simpleName("name")
                .type(stringType)
                .build();

        final var output = printElement(field);
        assertTrue(output.contains("private final"));
    }

    @Test
    void elementPrinter_printMethodWithMultipleParameters() throws IOException {
        final var stringType = loadClass("java.lang.String").asType();
        final var intType = getCompilerContext().getTypes().getPrimitiveType(TypeKind.INT);
        final var returnType = getCompilerContext().getTypes().getNoType(TypeKind.VOID);

        final var param1 = new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName("name")
                .type(stringType)
                .build();

        final var param2 = new VariableSymbolBuilderImpl()
                .kind(ElementKind.PARAMETER)
                .simpleName("age")
                .type(intType)
                .build();

        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("configure")
                .returnType(returnType)
                .parameter(param1)
                .parameter(param2)
                .build();

        final var output = printElement(method);
        assertTrue(output.contains("fun configure(name"));
        assertTrue(output.contains(", age"));
    }

    @Test
    void elementPrinter_printWithBufferedWriter() throws IOException {
        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("BufferedClass")
                .build();

        final var writer = new StringWriter();
        final var bufferedWriter = new java.io.BufferedWriter(writer);
        ElementPrinter.print(clazz, bufferedWriter);
        final var output = fixLines(writer.toString());
        assertTrue(output.contains("class BufferedClass"));
    }

    @Test
    void elementPrinter_printClassWithMultipleEnclosedElements() throws IOException {
        final var returnType = getCompilerContext().getTypes().getNoType(TypeKind.VOID);
        final var stringType = loadClass("java.lang.String").asType();

        final var method1 = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("method1")
                .returnType(returnType)
                .build();

        final var method2 = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .simpleName("method2")
                .returnType(stringType)
                .build();

        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .flags(Flags.PRIVATE)
                .simpleName("value")
                .type(stringType)
                .build();

        final var clazz = new ClassSymbolBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("MultiElementClass")
                .enclosedElement(method1)
                .enclosedElement(method2)
                .enclosedElement(field)
                .build();

        final var output = printElement(clazz);
        assertTrue(output.contains("class MultiElementClass"));
        assertTrue(output.contains("method1"));
        assertTrue(output.contains("method2"));
        assertTrue(output.contains("value"));
    }

    @Test
    void elementPrinter_printMethodWithModifiers() throws IOException {
        final var returnType = getCompilerContext().getTypes().getNoType(TypeKind.VOID);
        final var method = new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .flags(Flags.PUBLIC + Flags.STATIC)
                .simpleName("staticMethod")
                .returnType(returnType)
                .build();

        final var output = printElement(method);
        assertTrue(output.contains("public static"));
        assertTrue(output.contains("fun staticMethod"));
    }

    @Test
    void elementPrinter_printFieldWithPrimitiveType() throws IOException {
        final var boolType = getCompilerContext().getTypes().getPrimitiveType(TypeKind.BOOLEAN);
        final var field = new VariableSymbolBuilderImpl()
                .kind(ElementKind.FIELD)
                .flags(Flags.PRIVATE)
                .simpleName("active")
                .type(boolType)
                .build();

        final var output = printElement(field);
        assertTrue(output.contains("active"));
    }

    // ==================== Helper methods ====================

    private Elements getElements() {
        return getCompilerContext().getElements();
    }

    private ExecutableElement findMethod(final String className, final String methodName) {
        final var clazz = loadClass(className);
        return ElementFilter.methodsIn(clazz.getEnclosedElements())
                .stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .findFirst()
                .orElseThrow();
    }

    private ExecutableElement createMethod(final String name, final long flags) {
        return new MethodSymbolBuilderImpl()
                .kind(ElementKind.METHOD)
                .flags(flags)
                .simpleName(name)
                .build();
    }

    private ExecutableElement createConstructor(final String name, final long flags) {
        return new MethodSymbolBuilderImpl()
                .kind(ElementKind.CONSTRUCTOR)
                .flags(flags)
                .simpleName(name)
                .build();
    }

    private AnnotationMirror createAnnotationMirror(final String annotationClassName) {
        final var annotationClass = loadClass(annotationClassName);
        return new CCompoundAttribute(
                (io.github.potjerodekool.nabu.type.DeclaredType) annotationClass.asType(),
                Map.of()
        );
    }

    private String printElement(final Element element) throws IOException {
        final var writer = new StringWriter();
        ElementPrinter.print(element, writer);
        return fixLines(writer.toString());
    }
}
