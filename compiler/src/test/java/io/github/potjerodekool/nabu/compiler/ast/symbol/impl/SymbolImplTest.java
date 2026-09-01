package io.github.potjerodekool.nabu.compiler.ast.symbol.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.Completer;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CClassType;
import io.github.potjerodekool.nabu.compiler.type.impl.ModuleTypeImpl;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.TypeKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SymbolImplTest {

    // ==================== Symbol base class tests ====================

    @Test
    void symbolCreateFlatNameWithNullOwner() {
        assertEquals("Foo", Symbol.createFlatName(null, "Foo"));
    }

    @Test
    void symbolCreateFlatNameWithUnnamedPackageOwner() {
        assertEquals("Foo", Symbol.createFlatName(PackageSymbol.UNNAMED_PACKAGE, "Foo"));
    }

    @Test
    void symbolCreateFlatNameWithNamedPackageOwner() {
        var pkg = new PackageSymbol(null, "com");
        assertEquals("com.Foo", Symbol.createFlatName(pkg, "Foo"));
    }

    @Test
    void symbolCreateFlatNameWithDeclaredTypeOwner() {
        var classType = new CClassType(null, null, List.of());
        var classSymbol = new ClassSymbol(
                ElementKind.CLASS,
                NestingKind.TOP_LEVEL,
                0,
                "MyClass",
                classType,
                null,
                List.of(),
                List.of()
        );
        assertEquals("MyClass$Inner", Symbol.createFlatName(classSymbol, "Inner"));
    }

    @Test
    void symbolCreateFlatNameFromString() {
        assertEquals("com.example", Symbol.createFlatName("com/example"));
    }

    @Test
    void symbolGetAndSetSimpleName() {
        var symbol = createTestModuleSymbol(0, "mymodule");
        assertEquals("mymodule", symbol.getSimpleName());
        symbol.setSimpleName("renamed");
        assertEquals("renamed", symbol.getSimpleName());
    }

    @Test
    void symbolGetAndSetKind() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertEquals(ElementKind.MODULE, symbol.getKind());
        symbol.setKind(ElementKind.CLASS);
        assertEquals(ElementKind.CLASS, symbol.getKind());
    }

    @Test
    void symbolGetAndSetEnclosingElement() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getEnclosingElement());

        var pkg = new PackageSymbol(null, "com");
        symbol.setEnclosingElement(pkg);
        assertSame(pkg, symbol.getEnclosingElement());
    }

    @Test
    void symbolAsType() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNotNull(symbol.asType());
    }

    @Test
    void symbolSetType() {
        var symbol = createTestModuleSymbol(0, "mod");
        var newType = new CClassType(null, null, List.of());
        symbol.setType(newType);
        assertSame(newType, symbol.asType());
    }

    @Test
    void symbolFlagsAllFalseWithZero() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertFalse(symbol.isPublic());
        assertFalse(symbol.isPrivate());
        assertFalse(symbol.isProtected());
        assertTrue(symbol.isDefaultAccess());
        assertFalse(symbol.isStatic());
        assertFalse(symbol.isFinal());
        assertFalse(symbol.isSynthetic());
        assertFalse(symbol.isAbstract());
        assertFalse(symbol.isNative());
    }

    @Test
    void symbolFlagsPublic() {
        var symbol = createTestModuleSymbol(Flags.PUBLIC, "mod");
        assertTrue(symbol.isPublic());
        assertFalse(symbol.isPrivate());
        assertFalse(symbol.isProtected());
        assertFalse(symbol.isDefaultAccess());
        assertTrue(symbol.hasFlag(Flags.PUBLIC));
    }

    @Test
    void symbolFlagsPrivate() {
        var symbol = createTestModuleSymbol(Flags.PRIVATE, "mod");
        assertTrue(symbol.isPrivate());
        assertFalse(symbol.isPublic());
        assertFalse(symbol.isProtected());
        assertFalse(symbol.isDefaultAccess());
    }

    @Test
    void symbolFlagsProtected() {
        var symbol = createTestModuleSymbol(Flags.PROTECTED, "mod");
        assertTrue(symbol.isProtected());
        assertFalse(symbol.isPublic());
        assertFalse(symbol.isPrivate());
        assertFalse(symbol.isDefaultAccess());
    }

    @Test
    void symbolFlagsAbstract() {
        var symbol = createTestModuleSymbol(Flags.ABSTRACT, "mod");
        assertTrue(symbol.isAbstract());
    }

    @Test
    void symbolFlagsStatic() {
        var symbol = createTestModuleSymbol(Flags.STATIC, "mod");
        assertTrue(symbol.isStatic());
    }

    @Test
    void symbolFlagsFinal() {
        var symbol = createTestModuleSymbol(Flags.FINAL, "mod");
        assertTrue(symbol.isFinal());
    }

    @Test
    void symbolFlagsSynthetic() {
        var symbol = createTestModuleSymbol(Flags.SYNTHETIC, "mod");
        assertTrue(symbol.isSynthetic());
    }

    @Test
    void symbolFlagsNative() {
        var symbol = createTestModuleSymbol(Flags.NATIVE, "mod");
        assertTrue(symbol.isNative());
    }

    @Test
    void symbolDefaultAccessWhenMultipleFlagsButNoneAccess() {
        var symbol = createTestModuleSymbol(Flags.STATIC | Flags.FINAL, "mod");
        assertTrue(symbol.isDefaultAccess());
        assertFalse(symbol.isPublic());
        assertFalse(symbol.isPrivate());
        assertFalse(symbol.isProtected());
    }

    @Test
    void symbolGetAndSetFlags() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertEquals(0, symbol.getFlags());
        symbol.setFlags(Flags.PUBLIC | Flags.STATIC);
        assertEquals(Flags.PUBLIC | Flags.STATIC, symbol.getFlags());
        assertTrue(symbol.isPublic());
        assertTrue(symbol.isStatic());
    }

    @Test
    void symbolGetModifiers() {
        var symbol = createTestModuleSymbol(Flags.PUBLIC | Flags.ABSTRACT, "mod");
        var mods = symbol.getModifiers();
        assertTrue(mods.contains(Modifier.PUBLIC));
        assertTrue(mods.contains(Modifier.ABSTRACT));
        assertFalse(mods.contains(Modifier.PRIVATE));
    }

    @Test
    void symbolGetQualifiedNameDefault() {
        var symbol = createTestModuleSymbol(0, "myname");
        assertEquals("myname", symbol.getQualifiedName());
    }

    @Test
    void symbolGetBinaryNameDefault() {
        var symbol = createTestModuleSymbol(0, "myname");
        assertEquals("myname", symbol.getBinaryName());
    }

    @Test
    void symbolGetFlatNameDefault() {
        var symbol = createTestModuleSymbol(0, "myname");
        assertEquals("myname", symbol.getFlatName());
    }

    @Test
    void symbolGetEnclosedElementsDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertTrue(symbol.getEnclosedElements().isEmpty());
    }

    @Test
    void symbolGetMembersDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getMembers());
    }

    @Test
    void symbolGetInterfacesDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertTrue(symbol.getInterfaces().isEmpty());
    }

    @Test
    void symbolGetSuperclassDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getSuperclass());
    }

    @Test
    void symbolIsNoModule() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertFalse(symbol.isNoModule());
    }

    @Test
    void symbolIsErrorDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertFalse(symbol.isError());
    }

    @Test
    void symbolSetError() {
        var symbol = createTestModuleSymbol(0, "mod");
        symbol.setError(true);
        assertTrue(symbol.isError());
        symbol.setError(false);
        assertFalse(symbol.isError());
    }

    @Test
    void symbolGetCompleterDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNotNull(symbol.getCompleter());
        assertEquals(Completer.DEFAULT_COMPLETER, symbol.getCompleter());
    }

    @Test
    void symbolSetCompleter() {
        var symbol = createTestModuleSymbol(0, "mod");
        Completer custom = s -> {};
        symbol.setCompleter(custom);
        assertSame(custom, symbol.getCompleter());
    }

    @Test
    void symbolCompleteReplacesCompleterWithNullCompleter() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNotNull(symbol.getCompleter());
        symbol.complete();
        assertSame(Completer.NULL_COMPLETER, symbol.getCompleter());
    }

    @Test
    void symbolCompleteOnlyRunsOnce() {
        var symbol = createTestModuleSymbol(0, "mod");
        var callCount = new int[]{0};
        Completer counting = s -> callCount[0]++;
        symbol.setCompleter(counting);
        symbol.complete();
        assertEquals(1, callCount[0]);
        symbol.complete();
        assertEquals(1, callCount[0]);
    }

    @Test
    void symbolCompleteWithNullCompleterIsNoOp() {
        var symbol = createTestModuleSymbol(0, "mod");
        symbol.setCompleter(Completer.NULL_COMPLETER);
        symbol.complete();
        assertSame(Completer.NULL_COMPLETER, symbol.getCompleter());
    }

    @Test
    void symbolGetAnnotationMirrorsEmpty() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNotNull(symbol.getAnnotationMirrors());
        assertTrue(symbol.getAnnotationMirrors().isEmpty());
    }

    @Test
    void symbolGetAnnotationReturnsNull() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getAnnotation(Deprecated.class));
    }

    @Test
    void symbolGetAnnotationsByTypeReturnsNull() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getAnnotationsByType(Deprecated.class));
    }

    @Test
    void symbolGetSourceFileDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getSourceFile());
    }

    @Test
    void symbolGetClassFileDefault() {
        var symbol = createTestModuleSymbol(0, "mod");
        assertNull(symbol.getClassFile());
    }

    @Test
    void symbolSetClassFileNull() {
        var symbol = createTestModuleSymbol(0, "mod");
        symbol.setClassFile(null);
        assertNull(symbol.getClassFile());
    }

    @Test
    void symbolIsAccessibleInPublicAlwaysTrue() {
        var pkg = new PackageSymbol(null, "com");
        var publicSymbol = createTestModuleSymbol(Flags.PUBLIC, "mod");
        var clazz = new ClassSymbol(0, "Other", pkg);

        assertTrue(publicSymbol.isAccessibleIn(clazz, null));
    }

    @Test
    void symbolIsAccessibleInPrivateOnlyToEnclosing() {
        var pkg = new PackageSymbol(null, "com");
        var owner = new ClassSymbol(0, "Owner", pkg);
        var privateSymbol = createTestModuleSymbol(Flags.PRIVATE, "mod");
        privateSymbol.setEnclosingElement(owner);

        assertTrue(privateSymbol.isAccessibleIn(owner, null));
        var other = new ClassSymbol(0, "Other", pkg);
        assertFalse(privateSymbol.isAccessibleIn(other, null));
    }

    @Test
    void symbolIsAccessibleInProtectedNotInterface() {
        var pkg = new PackageSymbol(null, "com");
        var protectedSymbol = createTestModuleSymbol(Flags.PROTECTED, "mod");
        var clazz = new ClassSymbol(0, "Other", pkg);

        assertTrue(protectedSymbol.isAccessibleIn(clazz, null));
    }

    @Test
    void symbolIsAccessibleInProtectedInterfaceFlag() {
        var pkg = new PackageSymbol(null, "com");
        var protectedSymbol = createTestModuleSymbol(Flags.PROTECTED | Flags.INTERFACE, "mod");
        var clazz = new ClassSymbol(0, "Other", pkg);

        assertFalse(protectedSymbol.isAccessibleIn(clazz, null));
    }

    @Test
    void symbolIsSubClassSelf() {
        var pkg = new PackageSymbol(null, "com");
        var sym = new ClassSymbol(0, "A", pkg);
        assertTrue(sym.isSubClass(sym, null));
    }

    @Test
    void symbolGetPackageElement() {
        var pkg = new PackageSymbol(null, "com");
        var mod = new ModuleSymbol(0, "mymod");
        var modType = (ModuleTypeImpl) mod.asType();
        var symbol = createTestModuleSymbol(0, "mod");
        symbol.setEnclosingElement(pkg);

        assertSame(pkg, symbol.getPackageElement());
    }

    @Test
    void symbolGetPackageElementWalksUp() {
        var mod = new ModuleSymbol(0, "mymod");
        var pkg = new PackageSymbol(null, "com");
        var pkgType = (ModuleTypeImpl) mod.asType();
        var clazz = new ClassSymbol(0, "MyClass", pkg);

        assertSame(pkg, clazz.getPackageElement());
    }

    // ==================== ModuleSymbol tests ====================

    @Test
    void moduleSymbolConstructor() {
        var mod = new ModuleSymbol(0, "mymodule");
        assertEquals("mymodule", mod.getSimpleName());
        assertEquals(ElementKind.MODULE, mod.getKind());
    }

    @Test
    void moduleSymbolConstructorWithFlags() {
        var mod = new ModuleSymbol(Flags.PUBLIC, "mymod");
        assertTrue(mod.isPublic());
        assertEquals("mymod", mod.getSimpleName());
    }

    @Test
    void moduleSymbolCreate() {
        var mod = ModuleSymbol.create("mymodule", "module-info");
        assertEquals("mymodule", mod.getSimpleName());
        assertNotNull(mod.getModuleInfo());
        assertEquals("module-info", mod.getModuleInfo().getSimpleName());
        assertSame(mod, mod.getModuleInfo().getEnclosingElement());
    }

    @Test
    void moduleSymbolIsOpenFalse() {
        var mod = new ModuleSymbol(0, "mod");
        assertFalse(mod.isOpen());
    }

    @Test
    void moduleSymbolIsUnnamedFalse() {
        var mod = new ModuleSymbol(0, "mod");
        assertFalse(mod.isUnnamed());
    }

    @Test
    void moduleSymbolGetUnnamedPackageDefault() {
        var mod = new ModuleSymbol(0, "mod");
        assertNull(mod.getUnnamedPackage());
    }

    @Test
    void moduleSymbolSetUnnamedPackage() {
        var mod = new ModuleSymbol(0, "mod");
        var pkg = new PackageSymbol(null, "");
        mod.setUnnamedPackage(pkg);
        assertSame(pkg, mod.getUnnamedPackage());
        assertSame(mod, pkg.getModuleSymbol());
    }

    @Test
    void moduleSymbolEnclosedPackages() {
        var mod = new ModuleSymbol(0, "mod");
        assertTrue(mod.getEnclosedPackages().isEmpty());

        var pkg = new PackageSymbol(null, "com");
        mod.addEnclosedPackage(0, pkg);
        assertEquals(1, mod.getEnclosedPackages().size());
        assertSame(pkg, mod.getEnclosedPackages().get(0));
    }

    @Test
    void moduleSymbolAddEnclosedPackageAtIndex() {
        var mod = new ModuleSymbol(0, "mod");
        var pkg1 = new PackageSymbol(null, "a");
        var pkg2 = new PackageSymbol(null, "b");
        mod.addEnclosedPackage(0, pkg1);
        mod.addEnclosedPackage(0, pkg2);
        assertEquals(2, mod.getEnclosedPackages().size());
        assertSame(pkg2, mod.getEnclosedPackages().get(0));
        assertSame(pkg1, mod.getEnclosedPackages().get(1));
    }

    @Test
    void moduleSymbolVisiblePackages() {
        var mod = new ModuleSymbol(0, "mod");
        assertTrue(mod.getVisiblePackages().isEmpty());

        var pkg = new PackageSymbol(null, "com");
        mod.addVisiblePackage("com", pkg);
        assertEquals(1, mod.getVisiblePackages().size());
        assertSame(pkg, mod.getVisiblePackages().get("com"));
    }

    @Test
    void moduleSymbolAddVisiblePackageWithSlashThrows() {
        var mod = new ModuleSymbol(0, "mod");
        var pkg = new PackageSymbol(null, "com");
        assertThrows(IllegalArgumentException.class,
                () -> mod.addVisiblePackage("com/foo", pkg));
    }

    @Test
    void moduleSymbolDirectives() {
        var mod = new ModuleSymbol(0, "mod");
        assertNotNull(mod.getDirectives());
        assertTrue(mod.getDirectives().isEmpty());

        var emptyDirectives = List.<ModuleElement.Directive>of();
        mod.setDirectives(emptyDirectives);
        assertEquals(0, mod.getDirectives().size());
    }

    @Test
    void moduleSymbolExports() {
        var mod = new ModuleSymbol(0, "mod");
        assertNotNull(mod.getExports());
        assertTrue(mod.getExports().isEmpty());

        var empty = List.<ModuleElement.ExportsDirective>of();
        mod.setExports(empty);
        assertEquals(0, mod.getExports().size());
    }

    @Test
    void moduleSymbolOpens() {
        var mod = new ModuleSymbol(0, "mod");
        assertNotNull(mod.getOpens());
        assertTrue(mod.getOpens().isEmpty());

        var empty = List.<ModuleElement.OpensDirective>of();
        mod.setOpens(empty);
        assertEquals(0, mod.getOpens().size());
    }

    @Test
    void moduleSymbolProvides() {
        var mod = new ModuleSymbol(0, "mod");
        assertNotNull(mod.getProvides());
        assertTrue(mod.getProvides().isEmpty());

        var empty = List.<ModuleElement.ProvidesDirective>of();
        mod.setProvides(empty);
        assertEquals(0, mod.getProvides().size());
    }

    @Test
    void moduleSymbolRequires() {
        var mod = new ModuleSymbol(0, "mod");
        assertNotNull(mod.getRequires());
        assertTrue(mod.getRequires().isEmpty());

        var empty = List.<ModuleElement.RequiresDirective>of();
        mod.setRequires(empty);
        assertEquals(0, mod.getRequires().size());
    }

    @Test
    void moduleSymbolUses() {
        var mod = new ModuleSymbol(0, "mod");
        assertNotNull(mod.getUses());
        assertTrue(mod.getUses().isEmpty());

        var empty = List.<ModuleElement.UsesDirective>of();
        mod.setUses(empty);
        assertEquals(0, mod.getUses().size());
    }

    @Test
    void moduleSymbolGetAndSetModuleInfo() {
        var mod = new ModuleSymbol(0, "mod");
        assertNull(mod.getModuleInfo());

        var info = new ClassSymbol(0, "module-info", mod);
        mod.setModuleInfo(info);
        assertSame(info, mod.getModuleInfo());
    }

    @Test
    void moduleSymbolSourceAndClassLocation() {
        var mod = new ModuleSymbol(0, "mod");
        assertNull(mod.getSourceLocation());
        assertNull(mod.getClassLocation());
    }

    @Test
    void moduleSymbolToString() {
        var mod = new ModuleSymbol(0, "mymodule");
        assertEquals("mymodule", mod.toString());
    }

    @Test
    void moduleSymbolAcceptSymbolVisitor() {
        var mod = new ModuleSymbol(0, "mod");
        var visited = new boolean[]{false};
        SymbolVisitor<String, String> visitor = new SymbolVisitor<>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return null;
            }

            @Override
            public String visitModule(ModuleSymbol moduleSymbol, String p) {
                visited[0] = true;
                assertSame(mod, moduleSymbol);
                return "module";
            }
        };
        var result = mod.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("module", result);
    }

    @Test
    void moduleSymbolAcceptElementVisitor() {
        var mod = new ModuleSymbol(0, "mod");
        var visited = new boolean[]{false};
        ElementVisitor<String, String> visitor = new ElementVisitor<>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return null;
            }

            @Override
            public String visitModule(ModuleElement moduleElement, String p) {
                visited[0] = true;
                assertSame(mod, moduleElement);
                return "module";
            }
        };
        var result = mod.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("module", result);
    }

    // ==================== PackageSymbol tests ====================

    @Test
    void packageSymbolConstructor() {
        var pkg = new PackageSymbol(null, "com");
        assertEquals("com", pkg.getSimpleName());
        assertEquals(ElementKind.PACKAGE, pkg.getKind());
    }

    @Test
    void packageSymbolConstructorWithDotThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new PackageSymbol(null, "com.example"));
    }

    @Test
    void packageSymbolUnnamedPackageConstant() {
        assertNotNull(PackageSymbol.UNNAMED_PACKAGE);
        assertTrue(PackageSymbol.UNNAMED_PACKAGE.isUnnamed());
    }

    @Test
    void packageSymbolIsUnnamedForEmptyFullName() {
        var pkg = new PackageSymbol(null, "");
        assertTrue(pkg.isUnnamed());
    }

    @Test
    void packageSymbolIsUnnamedForNamed() {
        var pkg = new PackageSymbol(null, "com");
        assertFalse(pkg.isUnnamed());
    }

    @Test
    void packageSymbolGetFullName() {
        var pkg = new PackageSymbol(null, "com");
        assertEquals("com", pkg.getFullName());
    }

    @Test
    void packageSymbolGetFullNameNested() {
        var parent = new PackageSymbol(null, "org");
        var child = new PackageSymbol(parent, "nabu");
        assertEquals("org.nabu", child.getFullName());
    }

    @Test
    void packageSymbolGetFullNameRecalculates() {
        var parent = new PackageSymbol(null, "org");
        var child = new PackageSymbol(parent, "nabu");
        assertEquals("org.nabu", child.getFullName());
        child.setEnclosingElement(null);
        child.setEnclosingElement(PackageSymbol.UNNAMED_PACKAGE);
        var fullName = child.getFullName();
        assertNotNull(fullName);
    }

    @Test
    void packageSymbolGetQualifiedName() {
        var pkg = new PackageSymbol(null, "com");
        assertEquals("com", pkg.getQualifiedName());
    }

    @Test
    void packageSymbolGetQualifiedNameNested() {
        var parent = new PackageSymbol(null, "org");
        var child = new PackageSymbol(parent, "nabu");
        assertEquals("org.nabu", child.getQualifiedName());
    }

    @Test
    void packageSymbolGetQualifiedNameCaches() {
        var pkg = new PackageSymbol(null, "com");
        var qn1 = pkg.getQualifiedName();
        var qn2 = pkg.getQualifiedName();
        assertSame(qn1, qn2);
    }

    @Test
    void packageSymbolGetMembers() {
        var pkg = new PackageSymbol(null, "com");
        assertNotNull(pkg.getMembers());
    }

    @Test
    void packageSymbolDefine() {
        var pkg = new PackageSymbol(null, "com");
        var inner = new ClassSymbol(0, "MyClass", pkg);
        pkg.define(inner);
        assertNotNull(pkg.getMembers());
    }

    @Test
    void packageSymbolAddEnclosedElement() {
        var pkg = new PackageSymbol(null, "com");
        var inner = new ClassSymbol(0, "MyClass", pkg);
        pkg.addEnclosedElement(inner);
        assertNotNull(pkg.getMembers());
    }

    @Test
    void packageSymbolGetModuleSymbolDefault() {
        var pkg = new PackageSymbol(null, "com");
        assertNull(pkg.getModuleSymbol());
    }

    @Test
    void packageSymbolSetModuleSymbol() {
        var mod = new ModuleSymbol(0, "mymod");
        var pkg = new PackageSymbol(null, "com");
        pkg.setModuleSymbol(mod);
        assertSame(mod, pkg.getModuleSymbol());
    }

    @Test
    void packageSymbolGetPackageInfoDefault() {
        var pkg = new PackageSymbol(null, "com");
        assertNull(pkg.getPackageInfo());
    }

    @Test
    void packageSymbolSetPackageInfo() {
        var pkg = new PackageSymbol(null, "com");
        var info = new ClassSymbol(0, "package-info", pkg);
        pkg.setPackageInfo(info);
        assertSame(info, pkg.getPackageInfo());
    }

    @Test
    void packageSymbolExists() {
        var pkg = new PackageSymbol(null, "com");
        assertFalse(pkg.exists());
    }

    @Test
    void packageSymbolMarkExists() {
        var pkg = new PackageSymbol(null, "com");
        pkg.markExists();
        assertTrue(pkg.exists());
    }

    @Test
    void packageSymbolMarkExistsIdempotent() {
        var pkg = new PackageSymbol(null, "com");
        pkg.markExists();
        pkg.markExists();
        assertTrue(pkg.exists());
    }

    @Test
    void packageSymbolToString() {
        var pkg = new PackageSymbol(null, "com");
        assertEquals("com", pkg.toString());
    }

    @Test
    void packageSymbolToStringNested() {
        var parent = new PackageSymbol(null, "org");
        var child = new PackageSymbol(parent, "nabu");
        assertEquals("org.nabu", child.toString());
    }

    @Test
    void packageSymbolAcceptSymbolVisitor() {
        var pkg = new PackageSymbol(null, "com");
        var visited = new boolean[]{false};
        SymbolVisitor<String, String> visitor = new SymbolVisitor<>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return null;
            }

            @Override
            public String visitPackage(PackageSymbol packageSymbol, String p) {
                visited[0] = true;
                assertSame(pkg, packageSymbol);
                return "package";
            }
        };
        var result = pkg.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("package", result);
    }

    @Test
    void packageSymbolAcceptElementVisitor() {
        var pkg = new PackageSymbol(null, "com");
        var visited = new boolean[]{false};
        ElementVisitor<String, String> visitor = new ElementVisitor<>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return null;
            }

            @Override
            public String visitPackage(PackageElement packageElement, String p) {
                visited[0] = true;
                assertSame(pkg, packageElement);
                return "package";
            }
        };
        var result = pkg.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("package", result);
    }

    @Test
    void packageSymbolOnEnclosingChangedResetsNames() {
        var parent1 = new PackageSymbol(null, "org");
        var child = new PackageSymbol(parent1, "nabu");
        assertEquals("org.nabu", child.getQualifiedName());
        var parent2 = new PackageSymbol(null, "com");
        child.setEnclosingElement(parent2);
        assertEquals("com.nabu", child.getQualifiedName());
    }

    // ==================== ErrorSymbol tests ====================

    @Test
    void errorSymbolConstructor() {
        var error = new ErrorSymbol("MyError");
        assertEquals("MyError", error.getSimpleName());
    }

    @Test
    void errorSymbolIsErrorAlwaysTrue() {
        var error = new ErrorSymbol("err");
        assertTrue(error.isError());
    }

    @Test
    void errorSymbolExistsAlwaysFalse() {
        var error = new ErrorSymbol("err");
        assertFalse(error.exists());
    }

    @Test
    void errorSymbolAcceptSymbolVisitorReturnsNull() {
        var error = new ErrorSymbol("err");
        var result = error.accept(new SymbolVisitor<String, String>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return "unknown";
            }
        }, null);
        assertNull(result);
    }

    @Test
    void errorSymbolAcceptElementVisitorReturnsNull() {
        var error = new ErrorSymbol("err");
        var result = error.accept(new ElementVisitor<String, String>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return "unknown";
            }
        }, null);
        assertNull(result);
    }

    @Test
    void errorSymbolGetMembersReturnsEmptyScope() {
        var error = new ErrorSymbol("err");
        assertNotNull(error.getMembers());
        assertTrue(error.getMembers().elements().isEmpty());
    }

    // ==================== VariableSymbol tests ====================

    @Test
    void variableSymbolConstructor() {
        var vs = new VariableSymbol(
                ElementKind.FIELD, Flags.PUBLIC, "myField",
                AbstractType.noType, null, 42
        );
        assertEquals("myField", vs.getSimpleName());
        assertEquals(ElementKind.FIELD, vs.getKind());
        assertEquals(42, vs.getConstantValue());
    }

    @Test
    void variableSymbolConstructorWithNullConstant() {
        var vs = new VariableSymbol(
                ElementKind.LOCAL_VARIABLE, 0, "x",
                AbstractType.noType, null, null
        );
        assertNull(vs.getConstantValue());
    }

    @Test
    void variableSymbolConstructorWithStringConstant() {
        var vs = new VariableSymbol(
                ElementKind.FIELD, 0, "name",
                AbstractType.noType, null, "hello"
        );
        assertEquals("hello", vs.getConstantValue());
    }

    @Test
    void variableSymbolFlags() {
        var vs = new VariableSymbol(
                ElementKind.FIELD,
                Flags.PUBLIC | Flags.STATIC | Flags.FINAL,
                "CONST",
                AbstractType.noType, null, 100
        );
        assertTrue(vs.isPublic());
        assertTrue(vs.isStatic());
        assertTrue(vs.isFinal());
    }

    @Test
    void variableSymbolAcceptSymbolVisitor() {
        var vs = new VariableSymbol(
                ElementKind.FIELD, 0, "x",
                AbstractType.noType, null, null
        );
        var visited = new boolean[]{false};
        SymbolVisitor<String, String> visitor = new SymbolVisitor<>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return null;
            }

            @Override
            public String visitVariable(VariableSymbol variableSymbol, String p) {
                visited[0] = true;
                assertSame(vs, variableSymbol);
                return "var";
            }
        };
        var result = vs.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("var", result);
    }

    @Test
    void variableSymbolAcceptElementVisitor() {
        var vs = new VariableSymbol(
                ElementKind.FIELD, 0, "x",
                AbstractType.noType, null, null
        );
        var visited = new boolean[]{false};
        ElementVisitor<String, String> visitor = new ElementVisitor<>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return null;
            }

            @Override
            public String visitVariable(VariableElement variableElement, String p) {
                visited[0] = true;
                assertSame(vs, variableElement);
                return "var";
            }
        };
        var result = vs.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("var", result);
    }

    @Test
    void variableSymbolSetType() {
        var vs = new VariableSymbol(
                ElementKind.FIELD, 0, "x",
                AbstractType.noType, null, null
        );
        var newType = new CClassType(null, null, List.of());
        vs.setType(newType);
        assertSame(newType, vs.asType());
    }

    @Test
    void variableSymbolWithOwner() {
        var pkg = new PackageSymbol(null, "com");
        var owner = new ClassSymbol(0, "MyClass", pkg);
        var vs = new VariableSymbol(
                ElementKind.FIELD, 0, "x",
                AbstractType.noType, owner, null
        );
        assertSame(owner, vs.getEnclosingElement());
    }

    // ==================== MethodSymbol tests ====================

    @Test
    void methodSymbolConstructor() {
        var ms = new MethodSymbol(
                ElementKind.METHOD,
                Flags.PUBLIC,
                "doSomething",
                null,
                null,
                List.of(),
                AbstractType.noType,
                List.of(),
                List.of(),
                List.of()
        );
        assertEquals("doSomething", ms.getSimpleName());
        assertEquals(ElementKind.METHOD, ms.getKind());
    }

    @Test
    void methodSymbolGetReturnType() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, Flags.PUBLIC, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertSame(AbstractType.noType, ms.getReturnType());
    }

    @Test
    void methodSymbolSetReturnType() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, Flags.PUBLIC, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        var newReturn = new CClassType(null, null, List.of());
        ms.setReturnType(newReturn);
        assertSame(newReturn, ms.getReturnType());
    }

    @Test
    void methodSymbolParameters() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertTrue(ms.getParameters().isEmpty());
    }

    @Test
    void methodSymbolAddParameter() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        var param = new VariableSymbol(
                ElementKind.PARAMETER, 0, "x",
                AbstractType.noType, null, null
        );
        ms.addParameter(param);
        assertEquals(1, ms.getParameters().size());
        assertSame(param, ms.getParameters().get(0));
    }

    @Test
    void methodSymbolAddDuplicateParameterThrows() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        var param1 = new VariableSymbol(
                ElementKind.PARAMETER, 0, "x",
                AbstractType.noType, null, null
        );
        var param2 = new VariableSymbol(
                ElementKind.PARAMETER, 0, "x",
                AbstractType.noType, null, null
        );
        ms.addParameter(param1);
        assertThrows(IllegalArgumentException.class, () -> ms.addParameter(param2));
    }

    @Test
    void methodSymbolGetReceiverType() {
        var receiverType = new CClassType(null, null, List.of());
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, receiverType, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertSame(receiverType, ms.getReceiverType());
    }

    @Test
    void methodSymbolGetThrownTypes() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertTrue(ms.getThrownTypes().isEmpty());
    }

    @Test
    void methodSymbolSetThrownTypes() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        var exType = new CClassType(null, null, List.of());
        ms.setThrownTypes(List.of(exType));
        assertEquals(1, ms.getThrownTypes().size());
    }

    @Test
    void methodSymbolIsVarArgs() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, Flags.VARARGS, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertTrue(ms.isVarArgs());
    }

    @Test
    void methodSymbolIsNotVarArgs() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertFalse(ms.isVarArgs());
    }

    @Test
    void methodSymbolIsDefaultFalseForNonInterface() {
        var pkg = new PackageSymbol(null, "com");
        var owner = new ClassSymbol(0, "MyClass", pkg);
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                owner, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertFalse(ms.isDefault());
    }

    @Test
    void methodSymbolIsDefaultTrueForInterfaceNonAbstract() {
        var classType = new CClassType(null, null, List.of());
        var iface = new ClassSymbol(
                ElementKind.INTERFACE,
                NestingKind.TOP_LEVEL,
                0,
                "MyInterface",
                classType,
                null,
                List.of(),
                List.of()
        );
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                iface, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertTrue(ms.isDefault());
    }

    @Test
    void methodSymbolIsDefaultFalseForInterfaceAbstract() {
        var classType = new CClassType(null, null, List.of());
        var iface = new ClassSymbol(
                ElementKind.INTERFACE,
                NestingKind.TOP_LEVEL,
                0,
                "MyInterface",
                classType,
                null,
                List.of(),
                List.of()
        );
        var ms = new MethodSymbol(
                ElementKind.METHOD, Flags.ABSTRACT, "foo",
                iface, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertFalse(ms.isDefault());
    }

    @Test
    void methodSymbolGetDefaultValueDefault() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertNull(ms.getDefaultValue());
    }

    @Test
    void methodSymbolTypeParametersEmpty() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertTrue(ms.getTypeParameters().isEmpty());
    }

    @Test
    void methodSymbolAcceptSymbolVisitor() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        var visited = new boolean[]{false};
        SymbolVisitor<String, String> visitor = new SymbolVisitor<>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return null;
            }

            @Override
            public String visitMethod(MethodSymbol methodSymbol, String p) {
                visited[0] = true;
                assertSame(ms, methodSymbol);
                return "method";
            }
        };
        var result = ms.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("method", result);
    }

    @Test
    void methodSymbolAcceptElementVisitor() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        var visited = new boolean[]{false};
        ElementVisitor<String, String> visitor = new ElementVisitor<>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return null;
            }

            @Override
            public String visitExecutable(ExecutableElement executableElement, String p) {
                visited[0] = true;
                assertSame(ms, executableElement);
                return "method";
            }
        };
        var result = ms.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("method", result);
    }

    @Test
    void methodSymbolGetModuleElementNullOwner() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertNull(ms.getModuleElement());
    }

    @Test
    void methodSymbolOverridesAlwaysFalse() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertFalse(ms.overrides(null, null, null, false));
    }

    @Test
    void methodSymbolAsTypeIsMethodType() {
        var ms = new MethodSymbol(
                ElementKind.METHOD, 0, "foo",
                null, null, List.of(),
                AbstractType.noType, List.of(),
                List.of(), List.of()
        );
        assertNotNull(ms.asType());
        assertEquals(TypeKind.EXECUTABLE, ms.asType().getKind());
    }

    // ==================== TypeVariableSymbol tests ====================

    @Test
    void typeVariableSymbolConstructor() {
        var tvs = new TypeVariableSymbol("T", null);
        assertEquals("T", tvs.getSimpleName());
        assertEquals(ElementKind.TYPE_PARAMETER, tvs.getKind());
    }

    @Test
    void typeVariableSymbolGetGenericElement() {
        var pkg = new PackageSymbol(null, "com");
        var owner = new ClassSymbol(0, "MyClass", pkg);
        var tvs = new TypeVariableSymbol("E", owner);
        assertSame(owner, tvs.getGenericElement());
    }

    @Test
    void typeVariableSymbolGetEnclosingElement() {
        var pkg = new PackageSymbol(null, "com");
        var owner = new ClassSymbol(0, "MyClass", pkg);
        var tvs = new TypeVariableSymbol("E", owner);
        assertSame(owner, tvs.getEnclosingElement());
    }

    @Test
    void typeVariableSymbolEquals() {
        var tvs1 = new TypeVariableSymbol("T", null);
        var tvs2 = new TypeVariableSymbol("T", null);
        assertEquals(tvs1, tvs2);
    }

    @Test
    void typeVariableSymbolNotEqualsDifferentName() {
        var tvs1 = new TypeVariableSymbol("T", null);
        var tvs2 = new TypeVariableSymbol("U", null);
        assertNotEquals(tvs1, tvs2);
    }

    @Test
    void typeVariableSymbolNotEqualsNonTypeParameter() {
        var tvs = new TypeVariableSymbol("T", null);
        assertNotEquals(tvs, "not a type param");
    }

    @Test
    void typeVariableSymbolAcceptSymbolVisitor() {
        var tvs = new TypeVariableSymbol("T", null);
        var visited = new boolean[]{false};
        SymbolVisitor<String, String> visitor = new SymbolVisitor<>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return null;
            }

            @Override
            public String visitTypeVariable(TypeVariableSymbol typeVariableSymbol, String p) {
                visited[0] = true;
                assertSame(tvs, typeVariableSymbol);
                return "tv";
            }
        };
        var result = tvs.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("tv", result);
    }

    @Test
    void typeVariableSymbolAcceptElementVisitor() {
        var tvs = new TypeVariableSymbol("T", null);
        var visited = new boolean[]{false};
        ElementVisitor<String, String> visitor = new ElementVisitor<>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return null;
            }

            @Override
            public String visitTypeParameter(TypeParameterElement typeParameterElement, String p) {
                visited[0] = true;
                assertSame(tvs, typeParameterElement);
                return "tv";
            }
        };
        var result = tvs.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("tv", result);
    }

    // ==================== TypeSymbol static method tests ====================

    @Test
    void typeSymbolCreateFullNameNullOwner() {
        assertEquals("Foo", TypeSymbol.createFullName(null, "Foo"));
    }

    @Test
    void typeSymbolCreateFullNameEmptyOwnerName() {
        var pkg = new PackageSymbol(null, "");
        assertEquals("Foo", TypeSymbol.createFullName(pkg, "Foo"));
    }

    @Test
    void typeSymbolCreateFullNameErrorOwner() {
        var error = new ErrorSymbol("err");
        assertEquals("Foo", TypeSymbol.createFullName(error, "Foo"));
    }

    @Test
    void typeSymbolCreateFullNameNormalCase() {
        var pkg = new PackageSymbol(null, "com");
        assertEquals("com.Foo", TypeSymbol.createFullName(pkg, "Foo"));
    }

    @Test
    void typeSymbolCreateFullNameDotThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeSymbol.createFullName(null, "."));
    }

    @Test
    void typeSymbolGetErasureFieldDefault() {
        var mod = new ModuleSymbol(0, "mod");
        assertNull(mod.getErasureField());
    }

    @Test
    void classSymbolGetErasureFieldDefault() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNull(cs.getErasureField());
    }

    // ==================== ClassSymbol tests ====================

    @Test
    void classSymbolIsType() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertTrue(cs.isType());
    }

    @Test
    void classSymbolConstructorDefault() {
        var cs = new ClassSymbol();
        assertNotNull(cs);
    }

    @Test
    void classSymbolConstructorWithFlags() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(Flags.PUBLIC | Flags.ABSTRACT, "MyClass", pkg);
        assertTrue(cs.isPublic());
        assertTrue(cs.isAbstract());
        assertEquals("MyClass", cs.getSimpleName());
    }

    @Test
    void classSymbolGetNestingKind() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertEquals(NestingKind.TOP_LEVEL, cs.getNestingKind());
    }

    @Test
    void classSymbolSetNestingKind() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        cs.setNestingKind(NestingKind.MEMBER);
        assertEquals(NestingKind.MEMBER, cs.getNestingKind());
    }

    @Test
    void classSymbolGetSuperclassDefault() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNull(cs.getSuperclass());
    }

    @Test
    void classSymbolSetSuperClass() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var superType = new CClassType(null, null, List.of());
        cs.setSuperClass(superType);
        assertSame(superType, cs.getSuperclass());
    }

    @Test
    void classSymbolGetInterfaces() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNotNull(cs.getInterfaces());
        assertTrue(cs.getInterfaces().isEmpty());
    }

    @Test
    void classSymbolSetInterfaces() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var iface = new CClassType(null, null, List.of());
        cs.setInterfaces(List.of(iface));
        assertEquals(1, cs.getInterfaces().size());
    }

    @Test
    void classSymbolAcceptSymbolVisitor() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var visited = new boolean[]{false};
        SymbolVisitor<String, String> visitor = new SymbolVisitor<>() {
            @Override
            public String visitUnknown(Symbol symbol, String p) {
                return null;
            }

            @Override
            public String visitClass(ClassSymbol classSymbol, String p) {
                visited[0] = true;
                assertSame(cs, classSymbol);
                return "class";
            }
        };
        var result = cs.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("class", result);
    }

    @Test
    void classSymbolAcceptElementVisitor() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var visited = new boolean[]{false};
        ElementVisitor<String, String> visitor = new ElementVisitor<>() {
            @Override
            public String visitUnknown(Element e, String p) {
                return null;
            }

            @Override
            public String visitType(TypeElement typeElement, String p) {
                visited[0] = true;
                assertSame(cs, typeElement);
                return "class";
            }
        };
        var result = cs.accept(visitor, "test");
        assertTrue(visited[0]);
        assertEquals("class", result);
    }

    @Test
    void classSymbolGetEnclosedElements() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNotNull(cs.getEnclosedElements());
        assertTrue(cs.getEnclosedElements().isEmpty());
    }

    @Test
    void classSymbolAddEnclosedElement() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var field = new VariableSymbol(
                ElementKind.FIELD, 0, "x",
                AbstractType.noType, cs, null
        );
        cs.addEnclosedElement(field);
        assertNotNull(cs.getMembers());
    }

    @Test
    void classSymbolSetAndGetMembers() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNull(cs.getMembers());
    }

    @Test
    void classSymbolGetQualifiedNameNestedInPackage() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertEquals("com.MyClass", cs.getQualifiedName());
    }

    @Test
    void classSymbolGetQualifiedNameTopLevelNoPackage() {
        var cs = new ClassSymbol(0, "MyClass", null);
        assertEquals("MyClass", cs.getQualifiedName());
    }

    @Test
    void classSymbolGetBinaryName() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var binaryName = cs.getBinaryName();
        assertNotNull(binaryName);
    }

    @Test
    void classSymbolGetFlatName() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var flatName = cs.getFlatName();
        assertNotNull(flatName);
    }

    @Test
    void classSymbolGetAnnotationsByTypeExists() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNotNull(cs.getAnnotationsByType(Deprecated.class));
    }

    @Test
    void classSymbolGetPermittedSubclassesEmpty() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        assertNotNull(cs.getPermittedSubclasses());
        assertTrue(cs.getPermittedSubclasses().isEmpty());
    }

    @Test
    void classSymbolSetPermitted() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        var permitted = new ClassSymbol(0, "Sub", pkg);
        cs.setPermitted(List.of(permitted));
        assertEquals(1, cs.getPermittedSubclasses().size());
    }

    @Test
    void classSymbolGetModuleElementNoPackage() {
        var cs = new ClassSymbol(0, "MyClass", null);
        assertNull(cs.getModuleElement());
    }

    @Test
    void classSymbolSetClassFileNull() {
        var pkg = new PackageSymbol(null, "com");
        var cs = new ClassSymbol(0, "MyClass", pkg);
        cs.setClassFile(null);
        assertNull(cs.getClassFile());
    }

    @Test
    void classSymbolSetEnclosingElementResetsQualifiedName() {
        var pkg1 = new PackageSymbol(null, "org");
        var cs = new ClassSymbol(0, "MyClass", pkg1);
        assertEquals("org.MyClass", cs.getQualifiedName());

        var pkg2 = new PackageSymbol(null, "com");
        cs.setEnclosingElement(pkg2);
        assertEquals("com.MyClass", cs.getQualifiedName());
    }

    // ==================== Completer tests ====================

    @Test
    void completerDefaultCompleterIsNotTerminal() {
        assertFalse(Completer.DEFAULT_COMPLETER.isTerminal());
    }

    @Test
    void completerNullCompleterIsTerminal() {
        assertTrue(Completer.NULL_COMPLETER.isTerminal());
    }

    @Test
    void symbolCompleteWithCustomCompleter() {
        var symbol = createTestModuleSymbol(0, "mod");
        var completed = new boolean[]{false};
        Completer custom = s -> completed[0] = true;
        symbol.setCompleter(custom);
        symbol.complete();
        assertTrue(completed[0]);
        assertSame(Completer.NULL_COMPLETER, symbol.getCompleter());
    }

    // ==================== Enum constant in flags ====================

    @Test
    void flagsHasAccessModifier() {
        assertTrue(Flags.hasAccessModifier(Flags.PUBLIC));
        assertTrue(Flags.hasAccessModifier(Flags.PRIVATE));
        assertTrue(Flags.hasAccessModifier(Flags.PROTECTED));
        assertFalse(Flags.hasAccessModifier(0));
        assertFalse(Flags.hasAccessModifier(Flags.STATIC));
    }

    @Test
    void flagsParseFromModifiers() {
        var flags = Flags.parse(Set.of(Modifier.PUBLIC, Modifier.STATIC));
        assertTrue(Flags.hasFlag(flags, Flags.PUBLIC));
        assertTrue(Flags.hasFlag(flags, Flags.STATIC));
        assertFalse(Flags.hasFlag(flags, Flags.PRIVATE));
    }

    @Test
    void flagsParseEmptyModifiers() {
        var flags = Flags.parse(Set.of());
        assertEquals(0, flags);
    }

    @Test
    void flagsCreateModifiers() {
        var mods = Flags.createModifiers(Flags.PUBLIC | Flags.ABSTRACT);
        assertTrue(mods.contains(Modifier.PUBLIC));
        assertTrue(mods.contains(Modifier.ABSTRACT));
        assertFalse(mods.contains(Modifier.PRIVATE));
    }

    @Test
    void flagsCreateModifiersEmpty() {
        var mods = Flags.createModifiers(0);
        assertTrue(mods.isEmpty());
    }

    @Test
    void elementKindIsMethods() {
        assertTrue(ElementKind.CLASS.isClass());
        assertTrue(ElementKind.ENUM.isClass());
        assertTrue(ElementKind.RECORD.isClass());
        assertFalse(ElementKind.INTERFACE.isClass());

        assertTrue(ElementKind.INTERFACE.isInterface());
        assertTrue(ElementKind.ANNOTATION_TYPE.isInterface());
        assertFalse(ElementKind.CLASS.isInterface());

        assertTrue(ElementKind.CLASS.isDeclaredType());
        assertTrue(ElementKind.INTERFACE.isDeclaredType());
        assertFalse(ElementKind.METHOD.isDeclaredType());

        assertTrue(ElementKind.FIELD.isField());
        assertTrue(ElementKind.ENUM_CONSTANT.isField());
        assertFalse(ElementKind.METHOD.isField());

        assertTrue(ElementKind.METHOD.isExecutable());
        assertTrue(ElementKind.CONSTRUCTOR.isExecutable());
        assertFalse(ElementKind.FIELD.isExecutable());

        assertTrue(ElementKind.STATIC_INIT.isInitializer());
        assertTrue(ElementKind.INSTANCE_INIT.isInitializer());
        assertFalse(ElementKind.METHOD.isInitializer());
    }

    @Test
    void elementKindIsVariable() {
        assertTrue(ElementKind.FIELD.isVariable());
        assertTrue(ElementKind.PARAMETER.isVariable());
        assertTrue(ElementKind.LOCAL_VARIABLE.isVariable());
        assertTrue(ElementKind.EXCEPTION_PARAMETER.isVariable());
        assertTrue(ElementKind.ENUM_CONSTANT.isVariable());
        assertTrue(ElementKind.RESOURCE_VARIABLE.isVariable());
        assertTrue(ElementKind.BINDING_VARIABLE.isVariable());
        assertFalse(ElementKind.METHOD.isVariable());
        assertFalse(ElementKind.CLASS.isVariable());
    }

    // ==================== Helpers ====================

    private ModuleSymbol createTestModuleSymbol(long flags, String name) {
        return new ModuleSymbol(flags, name);
    }
}
