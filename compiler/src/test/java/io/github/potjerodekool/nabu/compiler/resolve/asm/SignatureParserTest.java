package io.github.potjerodekool.nabu.compiler.resolve.asm;

import io.github.potjerodekool.nabu.compiler.internal.Factory;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.test.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.backend.generate.signature.SignatureGenerator;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.asm.signature.SignatureParser;
import io.github.potjerodekool.nabu.compiler.type.impl.CMethodType;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeParameterElement;
import io.github.potjerodekool.nabu.type.*;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import static org.junit.jupiter.api.Assertions.*;

class SignatureParserTest extends AbstractCompilerTest {

    protected Factory<ClassElementLoader> createElementLoader() {
        return AsmClassElementLoader::new;
    }

    @Test
    void test1() {
        parseAndAssertFieldSignature("TT;");
    }

    @Test
    void test2() {
        parseAndAssertFieldSignature("[Ljava/util/Optional<Ljava/lang/String;>;");
    }

    @Test
    void test3() {
        parseAndAssertFieldSignature("[Ljava/lang/reflect/TypeVariable<*>;");
    }

    @Test
    void test5() {
        parseAndAssertFieldSignature("Ljava/util/Optional<*>;");
    }

    @Test
    void test6() {
        parseAndAssertClassSignature("<T:Ljava/lang/Object;>Ljava/lang/Object;Ljava/io/Serializable;Ljava/lang/reflect/GenericDeclaration;Ljava/lang/reflect/Type;Ljava/lang/reflect/AnnotatedElement;Ljava/lang/invoke/TypeDescriptor$OfField<Ljava/lang/Class<*>;>;Ljava/lang/constant/Constable;");
    }

    @Test
    void test7() {
        parseAndAssertClassSignature("<X:Ljava/lang/Object;>Ljava/lang/Object;Ljakarta/persistence/criteria/From<TX;TX;>;");
    }

    @Test
    void test8() {
        parseAndAssertMethodSignature("<T:Ljava/lang/Object;>([TT;)[TT;");
    }

    @Test
    void test9() {
        parseAndAssertMethodSignature("<U:Ljava/lang/Object;T:TU;>(Ljava/util/concurrent/CompletableFuture<TT;>;)Ljava/util/concurrent/CompletableFuture<TU;>;");
    }

    @Test
    void test10() {
        parseAndAssertMethodSignature("<Y::Ljava/lang/Comparable<-TY;>;>(Ljakarta/persistence/criteria/Expression<+TY;>;TY;)Ljakarta/persistence/criteria/Predicate;");
    }

    @Test
    void test11() {
        parseAndAssertMethodSignature("(I)TE;");
    }

    @Test
    void test() {
        final var signature = "Ljava/util/Optional<Ljava/lang/String;>;";
        final var reader = new SignatureReader(signature);
        final var visitor = new SignaturePrinter(Opcodes.ASM9);
        reader.accept(visitor);
    }

    private void parseAndAssertClassSignature(final String signature) {
        final var parser = parseSignature(signature);
        final var formalTypeParameters = parser.createFormalTypeParameters();

        final var superType = parser.createSuperType();

        final var interfaceTypes = parser.createInterfaceTypes();

        final var typeParams = formalTypeParameters.stream()
                .map(it -> (TypeParameterElement) it.asElement())
                .toList();

        final var actual = SignatureGenerator.getClassSignature(typeParams, superType, interfaceTypes);

        assertEquals(signature, actual);
    }

    private void parseAndAssertFieldSignature(final String signature) {
        final var fieldType = parseSignature(signature).createFieldType();
        //final var printer = new DefaultSignaturePrinter();

        final var actual = SignatureGenerator.getFieldSignature(fieldType);

        //final var actual = fieldType.accept(printer, null);
        assertEquals(signature, actual);
    }

    private void parseAndAssertMethodSignature(final String signature) {
        final var methodSignature = parseSignature(signature).createMethodSignature();
        final var methodType = new CMethodType(
                null,
                null,
                methodSignature.typeVariables(),
                methodSignature.returnType(),
                methodSignature.argumentTypes(),
                methodSignature.thrownTypes()
        );
        final var printer = new Printer();
        printer.process(methodType);

        final var actual = SignatureGenerator.getMethodSignature(methodType);

        assertEquals(signature, printer.getText());
        assertEquals(signature, actual);
    }

    protected SignatureParser parseSignature(final String signature) {
        final var loader = getCompilerContext().getClassElementLoader();
        final var javaBase = getCompilerContext().getSymbolTable().getJavaBase();

        final var reader = new SignatureReader(signature);

        final var signatureBuilder = new SignatureParser(
                Opcodes.ASM9,
                loader,
                javaBase
        );

        reader.accept(signatureBuilder);
        return signatureBuilder;
    }

}

class Printer implements TypeVisitor<Object, PrinterContext> {

    private final StringBuilder builder = new StringBuilder();

    public void process(final TypeMirror typeMirror) {
        typeMirror.accept(this, new PrinterContext());
    }

    @Override
    public Object visitUnknownType(final TypeMirror typeMirror, final PrinterContext param) {
        return null;
    }

    @Override
    public Object visitArrayType(final ArrayType arrayType, final PrinterContext param) {
        print("[");
        arrayType.getComponentType().accept(this, param);
        return null;
    }

    @Override
    public Object visitDeclaredType(final DeclaredType declaredType, final PrinterContext param) {
        if (declaredType.getEnclosingType() != null) {
            declaredType.getEnclosingType().accept(this, param);
            print("$");
        }

        final var clazz = (TypeElement) declaredType.asElement();
        print("L");
        print(clazz.getQualifiedName().replace('.', '/'));

        if (declaredType.getTypeArguments() != null && !declaredType.getTypeArguments().isEmpty()) {
            print("<");

            final var typeArgs = declaredType.getTypeArguments();

            typeArgs.forEach(typeArg -> typeArg.accept(this, param));

            print(">");
        }

        print(";");

        return null;
    }

    @Override
    public Object visitMethodType(final ExecutableType methodType, final PrinterContext context) {
        final var typeVariables = methodType.getTypeVariables();

        if (!typeVariables.isEmpty()) {
            final var subContext = context.withFormalTypeVariable(true);
            print("<");
            typeVariables.forEach(typeVariable -> typeVariable.accept(this, subContext));
            print(">");
        }

        print("(");
        methodType.getParameterTypes().forEach(argType -> argType.accept(this, context));
        print(")");
        methodType.getReturnType().accept(this, context);
        return null;
    }

    @Override
    public Object visitNoType(final NoType noType, final PrinterContext param) {
        print("V");
        return null;
    }

    @Override
    public Object visitPrimitiveType(final PrimitiveType primitiveType, final PrinterContext param) {
        final var descriptor = switch (primitiveType.getKind()) {
            case TypeKind.BYTE -> "B";
            case TypeKind.CHAR -> "C";
            case TypeKind.DOUBLE -> "D";
            case TypeKind.FLOAT -> "F";
            case TypeKind.INT -> "I";
            case TypeKind.LONG -> "J";
            case TypeKind.SHORT -> "S";
            case TypeKind.BOOLEAN -> "Z";
            default -> throw new IllegalArgumentException();
        };
        print(descriptor);
        return null;
    }

    @Override
    public Object visitWildcardType(final WildcardType wildcardType, final PrinterContext param) {
        if (wildcardType.getExtendsBound() != null) {
            if (isObjectType(wildcardType.getExtendsBound())) {
                print("*");
            } else {
                print("+");
                wildcardType.getExtendsBound().accept(this, param);
            }
        } else if (wildcardType.getSuperBound() != null) {
            print("-");
            wildcardType.getSuperBound().accept(this, param);
        } else {
            print("*");
        }
        return null;
    }

    private boolean isObjectType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            final var clazz = (TypeElement) declaredType.asElement();
            return Constants.OBJECT.equals(clazz.getQualifiedName());
        } else {
            return false;
        }
    }

    @Override
    public Object visitTypeVariable(final TypeVariable typeVariable,
                                    final PrinterContext context) {
        final var name = typeVariable.asElement().getSimpleName();

        final var isFormalTypeVariable = context.isFormalTypeVariable();

        if (isFormalTypeVariable
                && typeVariable.getUpperBound() != null) {

            if (context.isBound()) {
                print("T");
            }

            print(name);

            final var upperBound = typeVariable.getUpperBound();

            print(":");

            if (hasTypeArguments(upperBound)) {
                print(":");
            }

            upperBound.accept(this, context.withBound(true));
        } else if (isFormalTypeVariable
                && typeVariable.getLowerBound() != null) {
            if (context.isBound()) {
                print("T");
            }

            print(name);
            print(":");

            final var lowerBound = typeVariable.getLowerBound();
            if (hasTypeArguments(lowerBound)) {
                print(":");
            }

            lowerBound.accept(this, context.withBound(true));
        } else {
            print("T" + name + ";");
        }

        return null;
    }

    private boolean hasTypeArguments(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            return !declaredType.getTypeArguments().isEmpty();
        } else {
            return false;
        }
    }

    private Printer print(final String text) {
        builder.append(text);
        return this;
    }

    public String getText() {
        return builder.toString();
    }

}

class SignaturePrinter extends SignatureVisitor {

    protected SignaturePrinter(final int api) {
        super(api);
    }

    private void print(final String text) {
        System.out.print(text);
    }

    private void println(final String text) {
        System.out.println(text);
    }

    @Override
    public void visitFormalTypeParameter(final String name) {
        println(name);
    }

    @Override
    public SignatureVisitor visitClassBound() {
        return this;
    }

    @Override
    public SignatureVisitor visitInterfaceBound() {
        return this;
    }

    @Override
    public SignatureVisitor visitSuperclass() {
        println("SUPER_CLASS");
        return this;
    }

    @Override
    public SignatureVisitor visitInterface() {
        return this;
    }

    @Override
    public SignatureVisitor visitParameterType() {
        return this;
    }

    @Override
    public SignatureVisitor visitReturnType() {
        return this;
    }

    @Override
    public SignatureVisitor visitExceptionType() {
        return this;
    }

    @Override
    public void visitBaseType(final char descriptor) {
        println(descriptor + "");
    }

    @Override
    public void visitTypeVariable(final String name) {
        println(name);
    }

    @Override
    public SignatureVisitor visitArrayType() {
        return this;
    }

    @Override
    public void visitClassType(final String name) {
        println(name);
    }

    @Override
    public void visitInnerClassType(final String name) {
        println(name);
    }

    @Override
    public void visitTypeArgument() {
        println("TypeArg");
    }

    @Override
    public SignatureVisitor visitTypeArgument(final char wildcard) {
        return this;
    }

    @Override
    public void visitEnd() {
        println("END");
    }
}

class PrinterContext {

    private final boolean isFormalTypeVariable;
    private final boolean isBound;

    PrinterContext() {
        this.isFormalTypeVariable = false;
        this.isBound = false;
    }

    PrinterContext(final boolean isFormalTypeVariable,
                   final boolean isBound) {
        this.isFormalTypeVariable = isFormalTypeVariable;
        this.isBound = isBound;
    }

    public boolean isFormalTypeVariable() {
        return isFormalTypeVariable;
    }

    public boolean isBound() {
        return isBound;
    }

    public PrinterContext withFormalTypeVariable(final boolean value) {
        return new PrinterContext(value, isBound);
    }

    public PrinterContext withBound(final boolean value) {
        return new PrinterContext(false, value);
    }

}