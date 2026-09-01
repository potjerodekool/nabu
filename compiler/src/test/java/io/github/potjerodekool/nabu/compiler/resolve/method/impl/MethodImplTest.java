package io.github.potjerodekool.nabu.compiler.resolve.method.impl;

import io.github.potjerodekool.nabu.compiler.AbstractCompilerTest;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.ModuleSymbolBuilder;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.type.impl.*;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.lang.model.element.builder.TypeElementBuilder;
import io.github.potjerodekool.nabu.type.*;
import io.github.potjerodekool.nabu.util.Types;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MethodImplTest extends AbstractCompilerTest {

    private Types types() {
        return getCompilerContext().getTypes();
    }

    // ==================== SimpleTypeMapApplier ====================

    @Test
    void simpleTypeMapApplier_visitPrimitiveType_returnsSame() {
        final var map = Map.<String, TypeMirror>of();
        final var primitive = new CPrimitiveType(TypeKind.INT);
        final var result = SimpleTypeMapApplier.apply(map, primitive, types());
        assertSame(primitive, result);
    }

    @Test
    void simpleTypeMapApplier_visitNoType_returnsSame() {
        final var map = Map.<String, TypeMirror>of();
        final var noType = new CNoType();
        final var result = SimpleTypeMapApplier.apply(map, noType, types());
        assertSame(noType, result);
    }

    @Test
    void simpleTypeMapApplier_visitUnknownType_returnsSame() {
        final var map = Map.<String, TypeMirror>of();
        final var unknownType = new CUnknownType();
        final var result = SimpleTypeMapApplier.apply(map, unknownType, types());
        assertSame(unknownType, result);
    }

    @Test
    void simpleTypeMapApplier_visitTypeVariable_mapped() {
        final var stringClass = loadClass("java.lang.String");
        final var typeVariable = new CTypeVariable("T");
        final Map<String, TypeMirror> map = Map.of("T", stringClass.asType());
        final var result = SimpleTypeMapApplier.apply(map, typeVariable, types());
        assertEquals(stringClass.asType(), result);
    }

    @Test
    void simpleTypeMapApplier_visitTypeVariable_notInMap() {
        final var typeVariable = new CTypeVariable("E");
        final Map<String, TypeMirror> map = Map.of("T", new CPrimitiveType(TypeKind.INT));
        final var result = SimpleTypeMapApplier.apply(map, typeVariable, types());
        assertSame(typeVariable, result);
    }

    @Test
    void simpleTypeMapApplier_visitDeclaredType_noTypeArgs() {
        final var map = Map.<String, TypeMirror>of();
        final var stringClass = loadClass("java.lang.String");
        final var result = SimpleTypeMapApplier.apply(map, stringClass.asType(), types());
        assertEquals(stringClass.asType(), result);
    }

    @Test
    void simpleTypeMapApplier_visitDeclaredType_withTypeArgs() {
        final var stringClass = loadClass("java.lang.String");
        final var listClass = loadClass("java.util.List");
        final var typeVariable = new CTypeVariable("E");
        final var parameterized = types().getDeclaredType(listClass, typeVariable);

        final Map<String, TypeMirror> map = Map.of("E", stringClass.asType());
        final var result = SimpleTypeMapApplier.apply(map, parameterized, types());
        assertInstanceOf(DeclaredType.class, result);
        final var resultDeclared = (DeclaredType) result;
        assertEquals(1, resultDeclared.getTypeArguments().size());
        assertEquals(stringClass.asType(), resultDeclared.getTypeArguments().getFirst());
    }

    @Test
    void simpleTypeMapApplier_visitArrayType_unchangedComponent() {
        final var map = Map.<String, TypeMirror>of();
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var arrayType = new CArrayType(intType);
        final var result = SimpleTypeMapApplier.apply(map, arrayType, types());
        assertSame(arrayType, result);
    }

    @Test
    void simpleTypeMapApplier_visitArrayType_changedComponent() {
        final var stringClass = loadClass("java.lang.String");
        final var typeVariable = new CTypeVariable("T");
        final var arrayType = new CArrayType(typeVariable);

        final Map<String, TypeMirror> map = Map.of("T", stringClass.asType());
        final var result = SimpleTypeMapApplier.apply(map, arrayType, types());
        assertInstanceOf(ArrayType.class, result);
        assertNotSame(arrayType, result);
        assertEquals(stringClass.asType(), ((ArrayType) result).getComponentType());
    }

    @Test
    void simpleTypeMapApplier_visitWildcardType_extends() {
        final var typeVariable = new CTypeVariable("T");
        final var wildcard = new CWildcardType(typeVariable, BoundKind.EXTENDS, null);
        final var stringClass = loadClass("java.lang.String");
        final Map<String, TypeMirror> map = Map.of("T", stringClass.asType());

        final var result = SimpleTypeMapApplier.apply(map, wildcard, types());
        assertInstanceOf(WildcardType.class, result);
        final var resultWildcard = (WildcardType) result;
        assertEquals(BoundKind.EXTENDS, resultWildcard.getBoundKind());
        assertEquals(stringClass.asType(), resultWildcard.getExtendsBound());
    }

    @Test
    void simpleTypeMapApplier_visitWildcardType_super() {
        final var typeVariable = new CTypeVariable("T");
        final var wildcard = new CWildcardType(typeVariable, BoundKind.SUPER, null);
        final var stringClass = loadClass("java.lang.String");
        final Map<String, TypeMirror> map = Map.of("T", stringClass.asType());

        final var result = SimpleTypeMapApplier.apply(map, wildcard, types());
        assertInstanceOf(WildcardType.class, result);
        final var resultWildcard = (WildcardType) result;
        assertEquals(BoundKind.SUPER, resultWildcard.getBoundKind());
        assertEquals(stringClass.asType(), resultWildcard.getSuperBound());
    }

    @Test
    void simpleTypeMapApplier_visitWildcardType_unbound() {
        final var wildcard = new CWildcardType(null, BoundKind.UNBOUND, null);
        final var map = Map.<String, TypeMirror>of();
        final var result = SimpleTypeMapApplier.apply(map, wildcard, types());
        assertInstanceOf(WildcardType.class, result);
        final var resultWildcard = (WildcardType) result;
        assertEquals(BoundKind.UNBOUND, resultWildcard.getBoundKind());
    }

    @Test
    void simpleTypeMapApplier_visitMethodType() {
        final var stringClass = loadClass("java.lang.String");
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var typeVariable = new CTypeVariable("T");
        final var methodType = new CMethodType(
                createMethodSymbol(), null, List.of(), typeVariable,
                List.of(typeVariable), List.of()
        );

        final Map<String, TypeMirror> map = Map.of("T", stringClass.asType());
        final var result = SimpleTypeMapApplier.apply(map, methodType, types());
        assertInstanceOf(ExecutableType.class, result);
        final var resultExec = (ExecutableType) result;
        assertEquals(stringClass.asType(), resultExec.getReturnType());
        assertEquals(1, resultExec.getParameterTypes().size());
        assertEquals(stringClass.asType(), resultExec.getParameterTypes().getFirst());
    }

    @Test
    void simpleTypeMapApplier_instanceVisitMethodType() {
        final var map = new HashMap<String, TypeMirror>();
        final var applier = new SimpleTypeMapApplier(map, types());
        final var voidType = types().getNoType(TypeKind.VOID);
        final var methodType = new CMethodType(
                createMethodSymbol(), null, List.of(), voidType, List.of(), List.of()
        );

        final var result = methodType.accept(applier, null);
        assertInstanceOf(ExecutableType.class, result);
    }

    @Test
    void simpleTypeMapApplier_visitMethodType_withNullReturn() {
        final var mapped = new HashMap<String, TypeMirror>();
        final var applier = new SimpleTypeMapApplier(mapped, types());
        final var methodType = new CMethodType(
                createMethodSymbol(), null, List.of(), null, List.of(), List.of()
        );

        final var result = methodType.accept(applier, null);

        assertInstanceOf(ExecutableType.class, result);

        final var typeApplierResult = methodType.accept(new TypeApplier(new TypeMap(), types()), null);
        assertInstanceOf(ExecutableType.class, typeApplierResult);
    }

    // ==================== TypeMapFiller ====================

    @Test
    void typeMapFiller_visitDeclaredType_elseBranch_addsTypeParams() {
        final var stringClass = loadClass("java.lang.String");
        final var listClass = loadClass("java.util.List");
        final var parameterized = types().getDeclaredType(listClass, stringClass.asType());
        final var filler = new TypeMapFiller(types());

        final var result = filler.visitDeclaredType(parameterized, new CNoType());

        assertSame(parameterized, result);
        assertTrue(filler.getTypeMap().containsKey("E"));
        assertEquals(stringClass.asType(), filler.getTypeMap().get("E"));
    }

    @Test
    void typeMapFiller_visitDeclaredType_otherPrimitive() {
        final var stringClass = loadClass("java.lang.String");
        final var listClass = loadClass("java.util.List");
        final var parameterized = types().getDeclaredType(listClass, stringClass.asType());
        final var filler = new TypeMapFiller(types());

        final var result = filler.visitDeclaredType(parameterized, new CPrimitiveType(TypeKind.INT));

        assertSame(parameterized, result);
    }

    @Test
    void typeMapFiller_visitDeclaredType_functionalMethod() {
        final var supplierClass = loadClass("java.util.function.Supplier");
        final var stringClass = loadClass("java.lang.String");
        final var parameterized = types().getDeclaredType(supplierClass, stringClass.asType());
        final var executable = new CMethodType(
                createMethodSymbol(), null, List.of(), new CNoType(),
                List.of(stringClass.asType()), List.of()
        );
        final var filler = new TypeMapFiller(types());

        final var result = filler.visitDeclaredType(parameterized, executable);

        assertNull(result);
    }

    // ==================== TypeApplier ====================

    @Test
    void typeApplier_visitPrimitiveType_returnsSame() {
        final var typeMap = new TypeMap();
        final var applier = new TypeApplier(typeMap, types());
        final var primitive = new CPrimitiveType(TypeKind.BOOLEAN);
        final var result = primitive.accept(applier, null);
        assertSame(primitive, result);
    }

    @Test
    void typeApplier_visitNoType_returnsSame() {
        final var typeMap = new TypeMap();
        final var applier = new TypeApplier(typeMap, types());
        final var noType = new CNoType();
        final var result = noType.accept(applier, null);
        assertSame(noType, result);
    }

    @Test
    void typeApplier_visitUnknownType_returnsSame() {
        final var typeMap = new TypeMap();
        final var applier = new TypeApplier(typeMap, types());
        final var unknown = new CUnknownType();
        final var result = unknown.accept(applier, null);
        assertSame(unknown, result);
    }

    @Test
    void typeApplier_visitTypeVariable_mapped() {
        final var stringClass = loadClass("java.lang.String");
        final var typeVariable = new CTypeVariable("T");
        final var typeMap = new TypeMap();
        typeMap.put("T", stringClass.asType());
        final var applier = new TypeApplier(typeMap, types());

        final var result = typeVariable.accept(applier, null);
        assertEquals(stringClass.asType(), result);
    }

    @Test
    void typeApplier_visitTypeVariable_notInMap() {
        final var typeVariable = new CTypeVariable("E");
        final var typeMap = new TypeMap();
        typeMap.put("T", new CPrimitiveType(TypeKind.INT));
        final var applier = new TypeApplier(typeMap, types());

        final var result = typeVariable.accept(applier, null);
        assertSame(typeVariable, result);
    }

    @Test
    void typeApplier_visitDeclaredType_noTypeArgs() {
        final var stringClass = loadClass("java.lang.String");
        final var typeMap = new TypeMap();
        final var applier = new TypeApplier(typeMap, types());

        final var result = stringClass.asType().accept(applier, null);
        assertEquals(stringClass.asType(), result);
    }

    @Test
    void typeApplier_visitDeclaredType_withTypeArgs() {
        final var stringClass = loadClass("java.lang.String");
        final var listClass = loadClass("java.util.List");
        final var typeVariable = new CTypeVariable("E");
        final var parameterized = types().getDeclaredType(listClass, typeVariable);

        final var typeMap = new TypeMap();
        typeMap.put("E", stringClass.asType());
        final var applier = new TypeApplier(typeMap, types());

        final var result = parameterized.accept(applier, null);
        assertInstanceOf(DeclaredType.class, result);
        final var resultDeclared = (DeclaredType) result;
        assertEquals(1, resultDeclared.getTypeArguments().size());
        assertEquals(stringClass.asType(), resultDeclared.getTypeArguments().getFirst());
    }

    @Test
    void typeApplier_visitArrayType_unchanged() {
        final var intType = new CPrimitiveType(TypeKind.INT);
        final var arrayType = new CArrayType(intType);
        final var typeMap = new TypeMap();
        final var applier = new TypeApplier(typeMap, types());

        final var result = arrayType.accept(applier, null);
        assertSame(arrayType, result);
    }

    @Test
    void typeApplier_visitArrayType_changed() {
        final var stringClass = loadClass("java.lang.String");
        final var typeVariable = new CTypeVariable("T");
        final var arrayType = new CArrayType(typeVariable);

        final var typeMap = new TypeMap();
        typeMap.put("T", stringClass.asType());
        final var applier = new TypeApplier(typeMap, types());

        final var result = arrayType.accept(applier, null);
        assertInstanceOf(ArrayType.class, result);
        assertNotSame(arrayType, result);
        assertEquals(stringClass.asType(), ((ArrayType) result).getComponentType());
    }

    @Test
    void typeApplier_visitWildcardType_extends() {
        final var typeVariable = new CTypeVariable("T");
        final var wildcard = new CWildcardType(typeVariable, BoundKind.EXTENDS, null);
        final var stringClass = loadClass("java.lang.String");

        final var typeMap = new TypeMap();
        typeMap.put("T", stringClass.asType());
        final var applier = new TypeApplier(typeMap, types());

        final var result = wildcard.accept(applier, null);
        assertInstanceOf(WildcardType.class, result);
        final var resultWildcard = (WildcardType) result;
        assertEquals(BoundKind.EXTENDS, resultWildcard.getBoundKind());
        assertEquals(stringClass.asType(), resultWildcard.getExtendsBound());
    }

    @Test
    void typeApplier_visitWildcardType_super() {
        final var typeVariable = new CTypeVariable("T");
        final var wildcard = new CWildcardType(typeVariable, BoundKind.SUPER, null);
        final var stringClass = loadClass("java.lang.String");

        final var typeMap = new TypeMap();
        typeMap.put("T", stringClass.asType());
        final var applier = new TypeApplier(typeMap, types());

        final var result = wildcard.accept(applier, null);
        assertInstanceOf(WildcardType.class, result);
        final var resultWildcard = (WildcardType) result;
        assertEquals(BoundKind.SUPER, resultWildcard.getBoundKind());
        assertEquals(stringClass.asType(), resultWildcard.getSuperBound());
    }

    @Test
    void typeApplier_visitWildcardType_unbound() {
        final var wildcard = new CWildcardType(null, BoundKind.UNBOUND, null);
        final var typeMap = new TypeMap();
        final var applier = new TypeApplier(typeMap, types());

        final var result = wildcard.accept(applier, null);
        assertInstanceOf(WildcardType.class, result);
        assertEquals(BoundKind.UNBOUND, ((WildcardType) result).getBoundKind());
    }

    @Test
    void typeApplier_visitMethodType() {
        final var stringClass = loadClass("java.lang.String");
        final var typeVariable = new CTypeVariable("T");
        final var methodType = new CMethodType(
                createMethodSymbol(), null, List.of(), typeVariable,
                List.of(typeVariable), List.of()
        );

        final var typeMap = new TypeMap();
        typeMap.put("T", stringClass.asType());
        final var applier = new TypeApplier(typeMap, types());

        final var result = methodType.accept(applier, null);
        assertInstanceOf(ExecutableType.class, result);
        final var resultExec = (ExecutableType) result;
        assertEquals(stringClass.asType(), resultExec.getReturnType());
        assertEquals(stringClass.asType(), resultExec.getParameterTypes().getFirst());
    }

    @Test
    void typeMapApplier_extendsTypeApplier() {
        final var typeMap = new TypeMap();
        final var applier = new TypeMapApplier(typeMap, types());
        assertInstanceOf(TypeApplier.class, applier);
    }

    // ==================== AccessChecker ====================

    private final Map<String, ModuleElement> modules = new HashMap<>();
    private final Map<String, PackageElement> packages = new HashMap<>();

    private ModuleElement module(final String moduleName) {
        return modules.computeIfAbsent(moduleName, name -> new ModuleSymbolBuilder()
                .simpleName(name)
                .build());
    }

    private PackageElement packageOf(final String moduleName,
                                     final String packageName) {
        return packages.computeIfAbsent(moduleName + ":" + packageName, key -> {
            final var module = (ModuleSymbol) module(moduleName);
            final var pkg = (PackageElement) getCompilerContext().getElementBuilders()
                    .packageElementBuilder()
                    .simpleName(packageName)
                    .module(module)
                    .build();
            module.setExports(
                    List.of(new Directive.ExportsDirective(pkg, List.of()))
            );
            return pkg;
        });
    }

    private TypeElement createClassWithFlags(final String moduleName,
                                              final String packageName,
                                              final String className,
                                              final long flags) {
        return getCompilerContext().getElementBuilders().typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName(className)
                .enclosingElement(packageOf(moduleName, packageName))
                .flags(flags)
                .build();
    }

    private ExecutableElement createMethodOnClass(final TypeElement owner,
                                                   final String methodName,
                                                   final long flags) {
        final var method = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName(methodName)
                .returnType(types().getNoType(TypeKind.VOID))
                .enclosingElement(owner)
                .flags(flags)
                .build();
        owner.addEnclosedElement(method);
        return method;
    }

    private ExecutableElement createMethodSymbol() {
        return getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("apply")
                .returnType(types().getNoType(TypeKind.VOID))
                .build();
    }

    @Test
    void accessChecker_publicClassAccessibleFromDifferentModule() {
        final var fooClass = createClassWithFlags("mod-a", "p", "Foo", Flags.PUBLIC);
        final var barClass = createClassWithFlags("mod-b", "q", "Bar", Flags.PUBLIC);
        assertTrue(AccessChecker.isAccessible(fooClass, barClass));
    }

    @Test
    void accessChecker_defaultAccessClassSameModule() {
        final var fooClass = createClassWithFlags("mod-a", "p", "Foo", 0);
        final var callerClass = createClassWithFlags("mod-a", "q", "Caller", Flags.PUBLIC);
        assertTrue(AccessChecker.isAccessible(fooClass, callerClass));
    }

    @Test
    void accessChecker_defaultAccessClassDifferentModule() {
        final var fooClass = createClassWithFlags("mod-a", "p", "Foo", 0);
        final var callerClass = createClassWithFlags("mod-b", "p", "Caller", Flags.PUBLIC);
        assertFalse(AccessChecker.isAccessible(fooClass, callerClass));
    }

    @Test
    void accessChecker_defaultAccessClassDifferentModuleDifferentPackage() {
        final var fooClass = createClassWithFlags("mod-a", "p", "Foo", 0);
        final var callerClass = createClassWithFlags("mod-b", "q", "Caller", Flags.PUBLIC);
        assertFalse(AccessChecker.isAccessible(fooClass, callerClass));
    }

    @Test
    void accessChecker_privateClassNotAccessible() {
        final var fooClass = createClassWithFlags("mod-a", "p", "Foo", Flags.PRIVATE);
        final var callerClass = createClassWithFlags("mod-a", "p", "Caller", Flags.PUBLIC);
        assertFalse(AccessChecker.isAccessible(fooClass, callerClass));
    }

    @Test
    void accessChecker_publicMemberAccessible() {
        final var ownerClass = createClassWithFlags("mod-a", "p", "Owner", Flags.PUBLIC);
        final var method = createMethodOnClass(ownerClass, "doStuff", Flags.PUBLIC);
        final var callerClass = createClassWithFlags("mod-b", "q", "Caller", Flags.PUBLIC);
        assertTrue(AccessChecker.isAccessible(method, callerClass));
    }

    @Test
    void accessChecker_defaultAccessMemberSamePackage() {
        final var ownerClass = createClassWithFlags("mod-a", "p", "Owner", Flags.PUBLIC);
        final var method = createMethodOnClass(ownerClass, "doStuff", 0);
        final var callerClass = createClassWithFlags("mod-a", "p", "Caller", Flags.PUBLIC);
        assertTrue(AccessChecker.isAccessible(method, callerClass));
    }

    @Test
    void accessChecker_defaultAccessMemberDifferentPackage() {
        final var ownerClass = createClassWithFlags("mod-a", "p", "Owner", Flags.PUBLIC);
        final var method = createMethodOnClass(ownerClass, "doStuff", 0);
        final var callerClass = createClassWithFlags("mod-a", "q", "Caller", Flags.PUBLIC);
        assertFalse(AccessChecker.isAccessible(method, callerClass));
    }

    @Test
    void accessChecker_privateMemberNotAccessible() {
        final var ownerClass = createClassWithFlags("mod-a", "p", "Owner", Flags.PUBLIC);
        final var method = createMethodOnClass(ownerClass, "doStuff", Flags.PRIVATE);
        final var callerClass = createClassWithFlags("mod-a", "p", "Caller", Flags.PUBLIC);
        assertFalse(AccessChecker.isAccessible(method, callerClass));
    }

    @Test
    void accessChecker_protectedMemberSameModuleSamePackage() {
        final var ownerClass = createClassWithFlags("mod-a", "p", "Owner", Flags.PUBLIC);
        final var method = createMethodOnClass(ownerClass, "doStuff", Flags.PROTECTED);
        final var callerClass = createClassWithFlags("mod-a", "p", "Caller", Flags.PUBLIC);
        assertTrue(AccessChecker.isAccessible(method, callerClass));
    }

    @Test
    void accessChecker_protectedMemberDifferentModuleNotSubclass() {
        final var ownerClass = createClassWithFlags("mod-a", "p", "Owner", Flags.PUBLIC);
        final var method = createMethodOnClass(ownerClass, "doStuff", Flags.PROTECTED);
        final var callerClass = createClassWithFlags("mod-b", "p", "Caller", Flags.PUBLIC);
        assertFalse(AccessChecker.isAccessible(method, callerClass));
    }

    @Test
    void accessChecker_interfaceWithDefaultAccessIsAccessible() {
        final var iface = getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.INTERFACE)
                .simpleName("MyInterface")
                .enclosingElement(packageOf("mod-a", "p"))
                .build();

        final var callerClass = createClassWithFlags("mod-a", "p", "Caller", Flags.PUBLIC);
        assertTrue(AccessChecker.isAccessible(iface, callerClass));
    }

    @Test
    void accessChecker_isSubclassViaExecutableElement() {
        final var elementBuilders = getCompilerContext().getElementBuilders();
        final var parentClass = createClassWithFlags("mod-a", "p", "Parent", Flags.PUBLIC);
        final var childClass = createClassWithFlags("mod-a", "p", "Child", Flags.PUBLIC);
        childClass.setSuperClass(parentClass.asType());

        final var method = elementBuilders.executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("doStuff")
                .returnType(types().getNoType(TypeKind.VOID))
                .enclosingElement(parentClass)
                .flags(Flags.PROTECTED)
                .build();
        parentClass.addEnclosedElement(method);

        final var callerClass = createClassWithFlags("mod-a", "p", "Caller", Flags.PUBLIC);

        final var callerMethod = elementBuilders.executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("callStuff")
                .returnType(types().getNoType(TypeKind.VOID))
                .enclosingElement(callerClass)
                .build();
        callerClass.addEnclosedElement(callerMethod);
        callerClass.setSuperClass(childClass.asType());

        assertTrue(AccessChecker.isAccessible(method, callerClass));
    }

    // ==================== TypeMapFiller ====================

    @Test
    void typeMapFiller_visitWildcardType_extends() {
        final var filler = new TypeMapFiller(types());
        final var stringClass = loadClass("java.lang.String");
        final var wildcard = new CWildcardType(stringClass.asType(), BoundKind.EXTENDS, null);

        final var result = filler.visitWildcardType(wildcard, null);
        assertEquals(stringClass.asType(), result);
    }

    @Test
    void typeMapFiller_visitWildcardType_super() {
        final var filler = new TypeMapFiller(types());
        final var stringClass = loadClass("java.lang.String");
        final var wildcard = new CWildcardType(stringClass.asType(), BoundKind.SUPER, null);

        final var result = filler.visitWildcardType(wildcard, null);
        assertEquals(stringClass.asType(), result);
    }

    @Test
    void typeMapFiller_visitWildcardType_unbound() {
        final var filler = new TypeMapFiller(types());
        final var wildcard = new CWildcardType(null, BoundKind.UNBOUND, null);

        final var result = filler.visitWildcardType(wildcard, null);
        assertEquals(types().getObjectType(), result);
    }

    @Test
    void typeMapFiller_visitTypeVariable_withParam() {
        final var filler = new TypeMapFiller(types());
        final var typeVariable = new CTypeVariable("T");
        final var stringClass = loadClass("java.lang.String");

        final var result = filler.visitTypeVariable(typeVariable, stringClass.asType());
        assertEquals(stringClass.asType(), result);
        assertEquals(stringClass.asType(), filler.getTypeMap().get("T"));
    }

    @Test
    void typeMapFiller_visitTypeVariable_nullParam() {
        final var filler = new TypeMapFiller(types());
        final var typeVariable = new CTypeVariable("T");

        final var result = filler.visitTypeVariable(typeVariable, null);
        assertSame(typeVariable, result);
    }

    @Test
    void typeMapFiller_visitUnknownType() {
        final var filler = new TypeMapFiller(types());
        final var unknown = new CUnknownType();

        final var result = filler.visitUnknownType(unknown, null);
        assertSame(unknown, result);
    }

    @Test
    void typeMapFiller_visitDeclaredType_withDeclaredTypeOther() {
        final var filler = new TypeMapFiller(types());
        final var consumerClass = loadClass("java.util.function.Consumer");
        final var typeVariable = new CTypeVariable("T");
        final var consumerType = types().getDeclaredType(consumerClass, typeVariable);

        final var stringClass = loadClass("java.lang.String");
        final var otherConsumerType = types().getDeclaredType(consumerClass, stringClass.asType());

        filler.visitDeclaredType(consumerType, otherConsumerType);
        assertEquals(stringClass.asType(), filler.getTypeMap().get("T"));
    }

    @Test
    void typeMapFiller_visitDeclaredType_withPrimitiveTypeOther() {
        final var filler = new TypeMapFiller(types());
        final var listClass = loadClass("java.util.List");
        final var typeVariable = new CTypeVariable("E");
        final var listType = types().getDeclaredType(listClass, typeVariable);
        final var primitive = new CPrimitiveType(TypeKind.INT);

        final var result = filler.visitDeclaredType(listType, primitive);
        assertSame(listType, result);
    }

    @Test
    void typeMapFiller_visitDeclaredType_noTypeArgs() {
        final var filler = new TypeMapFiller(types());
        final var stringClass = loadClass("java.lang.String");
        final var objectClass = loadClass("java.lang.Object");

        final var result = filler.visitDeclaredType((DeclaredType) stringClass.asType(), objectClass.asType());
        assertSame(stringClass.asType(), result);
    }

    @Test
    void typeMapFiller_visitMethodType_matchingParamCount() {
        final var filler = new TypeMapFiller(types());
        final var typeVariable = new CTypeVariable("T");
        final var stringClass = loadClass("java.lang.String");

        final var methodType1 = new CMethodType(null, null, List.of(), typeVariable, List.of(typeVariable), List.of());
        final var methodType2 = new CMethodType(null, null, List.of(), typeVariable, List.of(stringClass.asType()), List.of());

        filler.visitMethodType(methodType1, methodType2);
        assertEquals(stringClass.asType(), filler.getTypeMap().get("T"));
    }

    @Test
    void typeMapFiller_visitMethodType_differentParamCount() {
        final var filler = new TypeMapFiller(types());
        final var typeVariable = new CTypeVariable("T");
        final var stringClass = loadClass("java.lang.String");

        final var methodType1 = new CMethodType(null, null, List.of(), typeVariable, List.of(typeVariable), List.of());
        final var methodType2 = new CMethodType(null, null, List.of(), typeVariable, List.of(stringClass.asType(), stringClass.asType()), List.of());

        filler.visitMethodType(methodType1, methodType2);
        assertNull(filler.getTypeMap().get("T"));
    }

    // ==================== OverrideChecker ====================

    private OverrideChecker createChecker() {
        return new OverrideChecker(getCompilerContext().getTypes());
    }

    private TypeElement createSimpleClass(final String name) {
        final var packageSymbol = getCompilerContext()
                .getElementBuilders()
                .packageElementBuilder()
                .createUnnamed();
        return getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName(name)
                .enclosingElement(packageSymbol)
                .build();
    }

    private ExecutableElement createSimpleMethod(final String name,
                                                   final TypeElement returnType,
                                                   final TypeElement... paramTypes) {
        final var builder = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName(name)
                .returnType(returnType != null ? returnType.asType()
                        : getCompilerContext().getTypes().getNoType(TypeKind.VOID));

        for (int i = 0; i < paramTypes.length; i++) {
            final var param = getCompilerContext().getElementBuilders()
                    .variableElementBuilder()
                    .kind(ElementKind.PARAMETER)
                    .simpleName("p" + i)
                    .type(paramTypes[i].asType())
                    .build();
            builder.parameter(param);
        }
        return builder.build();
    }

    @Test
    void overrideChecker_notOverriddenIfReturnTypeNotCovariant() {
        final var checker = createChecker();
        final var parentClass = createSimpleClass("Parent");
        final var childClass = createSimpleClass("Child");
        final var stringClass = loadClass("java.lang.String");
        final var integerClass = loadClass("java.lang.Integer");

        final var parentMethod = createSimpleMethod("foo", stringClass);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createSimpleMethod("foo", integerClass);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void overrideChecker_notOverriddenIfNotAccessibleDifferentPackage() {
        final var checker = createChecker();
        final var parentPackage = getCompilerContext()
                .getElementBuilders()
                .packageElementBuilder()
                .simpleName("other")
                .build();
        final var parentClass = getCompilerContext().getElementBuilders()
                .typeElementBuilder()
                .kind(ElementKind.CLASS)
                .simpleName("Parent")
                .enclosingElement(parentPackage)
                .build();
        final var childClass = createSimpleClass("Child");

        final var parentMethod = createSimpleMethod("foo", null);
        parentClass.addEnclosedElement(parentMethod);

        final var childMethod = createSimpleMethod("foo", null);
        childClass.addEnclosedElement(childMethod);

        childClass.setSuperClass(parentClass.asType());

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void overrideChecker_notOverriddenWhenNoEnclosingType() {
        final var checker = createChecker();
        final var parentMethod = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("foo")
                .flags(Flags.PUBLIC)
                .build();
        final var childMethod = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("foo")
                .build();
        final var childClass = createSimpleClass("Child");

        assertFalse(checker.overrides(childMethod, parentMethod, childClass));
    }

    @Test
    void overrideChecker_sameClassAndNestedOverriderReturnsTrue() {
        final var checker = createChecker();
        final var ownerClass = createSimpleClass("Owner");

        final var nestedMethod = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("holder")
                .build();
        ownerClass.addEnclosedElement(nestedMethod);

        final var overridden = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("foo")
                .flags(Flags.PUBLIC)
                .returnType(types().getNoType(TypeKind.VOID))
                .enclosingElement(ownerClass)
                .build();
        ownerClass.addEnclosedElement(overridden);

        final var overrider = getCompilerContext().getElementBuilders()
                .executableElementBuilder()
                .kind(ElementKind.METHOD)
                .simpleName("foo")
                .returnType(types().getNoType(TypeKind.VOID))
                .enclosingElement(nestedMethod)
                .build();

        assertTrue(checker.overrides(overrider, overridden, ownerClass));
    }

    // ==================== CandidateMatcher ====================

    @Test
    void candidateMatcher_visitUnknownType_returnsTrue() {
        final var matcher = new CandidateMatcher(types());
        assertTrue(matcher.visitUnknownType(new CUnknownType(), null));
    }

    @Test
    void candidateMatcher_bestMatchSameAsArgument() {
        final var argClass = createSimpleClass("Arg");
        final var parentClass = createSimpleClass("Parent");
        argClass.setSuperClass(parentClass.asType());

        final var argType = argClass.asType();
        final var matcher = new CandidateMatcher(types());
        matcher.setArgumentType(argType);

        assertTrue(matcher.visitDeclaredType((DeclaredType) argType, argType));
        assertFalse(matcher.visitDeclaredType((DeclaredType) argType, parentClass.asType()));
    }

    @Test
    void candidateMatcher_argumentSubtypeOfBoth() {
        final var argClass = createSimpleClass("Arg");
        final var parentClass = createSimpleClass("Parent");
        final var rootClass = createSimpleClass("Root");
        parentClass.setSuperClass(rootClass.asType());
        argClass.setSuperClass(parentClass.asType());

        final var matcher = new CandidateMatcher(types());
        matcher.setArgumentType(argClass.asType());

        assertTrue(matcher.visitDeclaredType((DeclaredType) parentClass.asType(), rootClass.asType()));
    }

    @Test
    void candidateMatcher_argumentNotSubtypeOfCandidate() {
        final var argClass = createSimpleClass("Arg");
        final var parentClass = createSimpleClass("Parent");
        argClass.setSuperClass(parentClass.asType());

        final var matcher = new CandidateMatcher(types());
        matcher.setArgumentType(argClass.asType());

        assertFalse(matcher.visitDeclaredType((DeclaredType) parentClass.asType(), loadClass("java.lang.String").asType()));
    }

    @Test
    void candidateMatcher_argumentNotSubtypeOfBestMatch() {
        final var argClass = createSimpleClass("Arg");
        final var parentClass = createSimpleClass("Parent");
        argClass.setSuperClass(parentClass.asType());
        final var stringType = loadClass("java.lang.String").asType();

        final var matcher = new CandidateMatcher(types());
        matcher.setArgumentType(argClass.asType());

        assertFalse(matcher.visitDeclaredType((DeclaredType) stringType, stringType));
    }

    // ==================== TypeMap ====================

    @Test
    void typeMap_extendsHashMap() {
        final var typeMap = new TypeMap();
        final var stringClass = loadClass("java.lang.String");
        typeMap.put("T", stringClass.asType());
        assertEquals(1, typeMap.size());
        assertEquals(stringClass.asType(), typeMap.get("T"));
    }
}
