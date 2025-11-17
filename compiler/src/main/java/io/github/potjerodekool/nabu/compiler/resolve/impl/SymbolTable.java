package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.compiler.internal.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.builder.ElementBuilder;
import io.github.potjerodekool.nabu.resolve.scope.WritableScope;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.*;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl.Modules;
import io.github.potjerodekool.nabu.compiler.resolve.internal.ClassFinder;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.compiler.type.impl.CNoType;
import io.github.potjerodekool.nabu.lang.model.element.ElementVisitor;
import io.github.potjerodekool.nabu.lang.model.element.ModuleElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tools.StandardLocation;
import io.github.potjerodekool.nabu.type.NullType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVisitor;

import java.util.*;

public class SymbolTable {

    private static final CompilerContextImpl.Key<SymbolTable> KEY = new CompilerContextImpl.Key<>();

    private final Map<String, ModuleSymbol> modulesMap = new HashMap<>();
    private final Map<String, Map<ModuleElement, PackageSymbol>> packagesMap = new HashMap<>();
    private final Map<String, Map<ModuleElement, ClassSymbol>> classesMap = new HashMap<>();

    private final PackageSymbol rootPackage = new PackageSymbol(
            null,
            ""
    );

    public static final ModuleSymbol NO_MODULE = new ModuleSymbol(0, "") {
        @Override
        public boolean isNoModule() {
            return true;
        }
    };

    private final Completer initialCompleter;

    private final Completer moduleCompleter;

    private final String JAVA_BASE_NAME = "java.base";
    private final ModuleSymbol javaBase;

    private final ModuleSymbol unnamedModule;

    private TypeMirror objectType;
    private TypeMirror classType;
    private TypeMirror stringType;
    private TypeMirror enumType;
    private TypeMirror recordType;
    private TypeMirror inheritedType;

    private final CNoType noType = new CNoType();
    private final BottomType bottomType = new BottomType();

    private final TypeSymbol noSymbol;
    private final ClassSymbol boundClass;

    public SymbolTable(final CompilerContextImpl compilerContext) {
        compilerContext.put(KEY, this);

        final var classFinder = ClassFinder.getInstance(compilerContext);
        this.initialCompleter = classFinder.getCompleter();
        final var modules = Modules.getInstance(compilerContext);
        this.moduleCompleter = modules.getModuleCompleter();

        this.unnamedModule = createUnnamedModule();
        unnamedModule.setCompleter(modules.getUnnamedModuleCompleter());

        this.noSymbol = createNoSymbol();
        this.boundClass = new ClassSymbol(Flags.PUBLIC, "Bound", noSymbol);
        this.boundClass.setMembers(new WritableScope());

        getUnnamedModule().setSourceLocation(StandardLocation.SOURCE_PATH);
        getUnnamedModule().setClassLocation(StandardLocation.CLASS_PATH);
        this.javaBase = enterModule(JAVA_BASE_NAME);
        modules.initAllModules();
        loadPredefinedClasses();
        loadBoxes();
    }

    private ModuleSymbol createUnnamedModule() {
        return new ModuleSymbol(0, "<unnamed>") {
            {
                final var baseModule = enterModule(JAVA_BASE_NAME);
                final var required = new io.github.potjerodekool.nabu.lang.model.element.Directive.RequiresDirective(
                        baseModule,
                        EnumSet.of(io.github.potjerodekool.nabu.lang.model.element.Directive.RequiresFlag.MANDATED)
                );
                setRequires(List.of(required));
            }

            @Override
            public boolean isUnnamed() {
                return true;
            }
        };
    }

    public static SymbolTable getInstance(final CompilerContextImpl compilerContext) {
        var symbolTable = compilerContext.get(KEY);

        if (symbolTable == null) {
            symbolTable = new SymbolTable(compilerContext);
        }

        return symbolTable;
    }

    private TypeSymbol createNoSymbol() {
        return new TypeSymbol(
                null,
                0,
                "",
                noType,
                rootPackage
        ) {

            @Override
            public <R, P> R accept(final ElementVisitor<R, P> v, final P p) {
                return v.visitUnknown(this, p);
            }

            @Override
            public ElementBuilder<?> builder() {
                return null;
            }

            @Override
            public <R, P> R accept(final SymbolVisitor<R, P> v, final P p) {
                return v.visitUnknown(this, p);
            }
        };
    }

    public ModuleSymbol getJavaBase() {
        return javaBase;
    }

    public ModuleSymbol getUnnamedModule() {
        return unnamedModule;
    }

    public void loadPredefinedClasses() {
        objectType = enterClass(Constants.OBJECT);
        classType = enterClass(Constants.CLAZZ);
        stringType = enterClass(Constants.STRING);
        enumType = enterClass(Constants.ENUM);
        recordType = enterClass(Constants.RECORD);
        inheritedType = enterClass("java.lang.annotation.Inherited");
    }

    private void loadBoxes() {
        enterClass(Constants.BOOLEAN);
        enterClass(Constants.BYTE);
        enterClass(Constants.SHORT);
        enterClass(Constants.INTEGER);
        enterClass(Constants.LONG);
        enterClass(Constants.CHARACTER);
        enterClass(Constants.FLOAT);
        enterClass(Constants.DOUBLE);
    }

    public TypeMirror getBottomType() {
        return bottomType;
    }

    public TypeSymbol getNoSymbol() {
        return noSymbol;
    }

    public ClassSymbol getBoundClass() {
        return boundClass;
    }

    public TypeMirror getObjectType() {
        return objectType;
    }

    public TypeMirror getClassType() {
        return classType;
    }

    public TypeMirror getStringType() {
        return stringType;
    }

    public TypeMirror getEnumType() {
        return enumType;
    }

    public TypeMirror getRecordType() {
        return recordType;
    }

    public TypeMirror getInheritedType() {
        return inheritedType;
    }

    private TypeMirror enterClass(final String name) {
        return enterClass(javaBase, name).asType();
    }

    private ClassSymbol enterClass(final ModuleSymbol module,
                                   final String flatname) {
        final var packageSymbol = lookupPackage(module, getPackageName(flatname));
        var classSymbol = getClassSymbol(packageSymbol.getModuleSymbol(), flatname);

        if (classSymbol == null) {
            classSymbol = defineClass(getShortName(flatname), packageSymbol);
            doEnterClass(packageSymbol.getModuleSymbol(), classSymbol);
        }

        return classSymbol;
    }

    private void doEnterClass(final ModuleSymbol module,
                              final ClassSymbol classSymbol) {
        final var flatName = classSymbol.getFlatName();

        final var map = classesMap.computeIfAbsent(
                flatName,
                n -> new HashMap<>()
        );

        if (map.containsKey(module)) {
            throw new IllegalStateException();
        }

        map.put(module, classSymbol);
    }

    private ClassSymbol defineClass(final String name,
                                    final Symbol owner) {
        final var classSymbol = new ClassSymbol(
                0,
                name,
                owner
        );

        classSymbol.setCompleter(initialCompleter);
        classSymbol.setError(true);
        return classSymbol;
    }

    public PackageSymbol lookupPackage(final ModuleSymbol moduleSymbol,
                                       final String flatName) {
        return lookupPackage(moduleSymbol, flatName, false);
    }

    private PackageSymbol lookupPackage(final ModuleSymbol moduleSymbol,
                                        final String flatName,
                                        final boolean onlyExisting) {
        if (flatName.isEmpty()) {
            return moduleSymbol.getUnnamedPackage();
        } else if (moduleSymbol == NO_MODULE) {
            return enterPackage(moduleSymbol, flatName);
        } else {
            moduleSymbol.complete();
            PackageSymbol packageSymbol = moduleSymbol.getVisiblePackages()
                    .get(flatName);

            if (packageSymbol != null) {
                return packageSymbol;
            } else {
                packageSymbol = getPackage(moduleSymbol, flatName);
            }

            if ((packageSymbol != null && packageSymbol.exists())
                    || onlyExisting) {
                return packageSymbol;
            }

            final var dependsOnUnnamed = moduleSymbol.getRequires().stream()
                    .map(ModuleElement.RequiresDirective::getDependency)
                    .anyMatch(mod -> mod == unnamedModule);

            if (dependsOnUnnamed) {
                var unnamedPackageSymbol = getPackage(unnamedModule, flatName);

                if (unnamedPackageSymbol != null && unnamedPackageSymbol.exists()) {
                    moduleSymbol.addVisiblePackage(unnamedPackageSymbol.getFullName(), unnamedPackageSymbol);
                    return unnamedPackageSymbol;
                }

                packageSymbol = enterPackage(moduleSymbol, flatName);
                packageSymbol.complete();

                if (packageSymbol.exists()) {
                    return packageSymbol;
                }

                unnamedPackageSymbol = enterPackage(unnamedModule, flatName);
                unnamedPackageSymbol.complete();

                if (unnamedPackageSymbol.exists()) {
                    moduleSymbol.addVisiblePackage(unnamedPackageSymbol.getFullName(), unnamedPackageSymbol);
                    return unnamedPackageSymbol;
                }
            }

            return enterPackage(moduleSymbol, flatName);
        }
    }

    private void addRootPackageFor(final ModuleSymbol module) {
        doEnterPackage(module, rootPackage);
        PackageSymbol unnamedPackage = new PackageSymbol(rootPackage, "") {
            @Override
            public String toString() {
                return "unnamedPackage";
            }
        };
        unnamedPackage.setModuleSymbol(module);
        long flags = unnamedPackage.getFlags();
        flags |= Flags.EXISTS;
        unnamedPackage.setFlags(flags);
        module.setUnnamedPackage(unnamedPackage);
    }

    public PackageSymbol enterPackage(final ModuleSymbol module,
                                      final String flatName) {
        if (flatName == null) {
            return null;
        }

        var packageSymbol = getPackage(module, flatName);

        if (packageSymbol == null) {
            packageSymbol = new PackageSymbol(
                    enterPackage(
                            module,
                            getPackageName(flatName)
                    ),
                    getShortName(flatName)
            );

            packageSymbol.setCompleter(initialCompleter);

            packageSymbol.setModuleSymbol(module);
            doEnterPackage(module, packageSymbol);
        }

        return packageSymbol;
    }

    private String getShortName(final String name) {
        final var start = name.lastIndexOf('.') + 1;
        final var end = name.length();

        return start == 0
                ? name
                : name.substring(start, end);
    }

    private String getPackageName(final String className) {
        var end = className.lastIndexOf('.');

        return end > -1
                ? className.substring(0, end)
                : null;
    }

    private PackageSymbol getPackage(final ModuleSymbol module,
                                     final String flatName) {
        final var map = this.packagesMap.getOrDefault(flatName, Collections.emptyMap());
        PackageSymbol result;

        if (module == NO_MODULE) {
            if (map.size() == 1) {
                result = map.entrySet().iterator().next()
                        .getValue();
            } else {
                result = null;
            }
        } else {
            result = map.get(module);

            if (result == null && map.size() == 1) {
                result = map.entrySet().iterator().next()
                        .getValue();
            }
        }

        return result;
    }

    public PackageSymbol findPackage(final ModuleSymbol module,
                                     final String flatName) {
        return this.packagesMap.getOrDefault(flatName, Collections.emptyMap())
                .get(module);
    }

    private void doEnterPackage(final ModuleSymbol module,
                                final PackageSymbol packageSymbol) {
        final var map = packagesMap.computeIfAbsent(
                packageSymbol.getFlatName(),
                n -> new HashMap<>()
        );

        map.put(module, packageSymbol);
        module.addEnclosedPackage(0, packageSymbol);
    }

    public TypeElement getClassSymbol(final String flatName) {
        return getClassSymbol(javaBase, flatName);
    }

    public ClassSymbol getClassSymbol(final ModuleElement moduleSymbol,
                                      final String flatName) {
        return classesMap.getOrDefault(flatName, Collections.emptyMap())
                .get(moduleSymbol);
    }

    public ModuleSymbol getModule(final String name) {
        return modulesMap.get(name);
    }

    public ModuleSymbol enterModule(final String name) {
        return this.modulesMap.computeIfAbsent(name, k -> {
            final var module = ModuleSymbol.create(name, "module-info");
            addRootPackageFor(module);
            module.setCompleter(moduleCompleter);
            return module;
        });
    }

    public ClassSymbol enterClass(final ModuleSymbol moduleSymbol,
                                  final String className,
                                  final TypeSymbol owner) {
        final var flatName = Symbol.createFlatName(owner, className);
        var clazz = getClassSymbol(moduleSymbol, flatName);

        if (clazz == null) {
            clazz = defineClass(className, owner);
            doEnterClass(moduleSymbol, clazz);
        }

        return clazz;
    }
}

class BottomType extends AbstractType implements NullType {

    protected BottomType() {
        super(null);
    }

    @Override
    public TypeKind getKind() {
        return TypeKind.NULL;
    }

    @Override
    public <R, P> R accept(final TypeVisitor<R, P> visitor, final P param) {
        return visitor.visitNullType(this, param);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj == this;
    }

    @Override
    public String getClassName() {
        return "<bottum>";
    }

    @Override
    public int hashCode() {
        return 0;
    }
}