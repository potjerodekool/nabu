package io.github.potjerodekool.nabu.compiler.util.impl;

import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ModuleSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.PackageSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.module.impl.Modules;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.tools.FileObject;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.compiler.resolve.impl.SymbolTable;
import io.github.potjerodekool.nabu.compiler.type.impl.AbstractType;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.CollectionUtils;
import io.github.potjerodekool.nabu.util.Elements;
import io.github.potjerodekool.nabu.util.Pair;
import io.github.potjerodekool.nabu.util.Types;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

public class ElementsImpl implements Elements {

    private final CompilerContextImpl compilerContext;
    private final SymbolTable symbolTable;
    private final Types types;
    private final Modules modules;
    private final Map<Pair<String, String>, Optional<Symbol>> resultCache = new HashMap<>();

    public ElementsImpl(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.symbolTable = SymbolTable.getInstance(compilerContext);
        this.types = compilerContext.getTypes();
        this.modules = Modules.getInstance(compilerContext);
    }

    @Override
    public PackageElement getPackageElement(final CharSequence name) {
        return getPackageElement(null, name);
    }

    @Override
    public PackageElement getPackageElement(final ModuleElement module,
                                            final CharSequence name) {
        final ModuleSymbol moduleSymbol;

        if (module == null) {
            moduleSymbol = symbolTable.getUnnamedModule();
        } else {
            moduleSymbol = (ModuleSymbol) module;
        }

        return nameToSymbol(moduleSymbol, name.toString(), PackageSymbol.class);
    }

    @Override
    public TypeElement getTypeElement(final CharSequence name) {
        return getTypeElement(null, name);
    }

    @Override
    public TypeElement getTypeElement(final ModuleElement module, final CharSequence name) {
        return getGetSymbol("getTypeElement", module, name, ClassSymbol.class);
    }

    private <S extends Symbol> S getGetSymbol(final String methodName,
                                              final ModuleElement module,
                                              final CharSequence name,
                                              final Class<S> clazz) {
        final var nameString = name.toString();

        if (module == null) {
            return unboundNameToSymbol(methodName, nameString, clazz);
        } else {
            return nameToSymbol((ModuleSymbol) module, nameString, clazz);
        }
    }

    private <S extends Symbol> S unboundNameToSymbol(final String methodName,
                                                     final String name,
                                                     final Class<S> clazz) {
        final var result = resultCache.computeIfAbsent(new Pair<>(methodName, name), p -> {
            final var allModules = new HashSet<>(Modules.getInstance(compilerContext).allModules());
            final var foundSymbols = allModules.stream()
                    .map(module -> nameToSymbol(module, name, clazz))
                    .filter(Objects::nonNull)
                    .filter(s -> {
                        if (clazz == ClassSymbol.class) {
                            return true;
                        } else if (clazz == PackageSymbol.class) {
                            final var packageSymbol = (PackageSymbol) s;
                            return !packageSymbol.getMembers().isEmpty() || packageSymbol.getPackageInfo() != null;
                        } else {
                            return false;
                        }
                    }).collect(Collectors.toSet());

            if (foundSymbols.size() == 1) {
                return Optional.of(foundSymbols.iterator().next());
            } else {
                return Optional.empty();
            }
        }).orElse(null);
        return (S) result;
    }

    @Override
    public Map<? extends ExecutableElement, ? extends AnnotationValue> getElementValuesWithDefaults(final AnnotationMirror a) {
        final Map<ExecutableElement, AnnotationValue> elementValues = new HashMap<>(a.getElementValues());

        final var annotationType = a.getAnnotationType();

        for (final var method : ElementFilter.methodsIn(annotationType.asTypeElement().getEnclosedElements())) {
            final var defaultValue = method.getDefaultValue();

            if (defaultValue != null && !elementValues.containsKey(method)) {
                elementValues.put(method, defaultValue);
            }
        }

        return elementValues;
    }

    @Override
    public String getDocComment(final Element e) {
        return "";
    }

    @Override
    public boolean isDeprecated(final Element e) {
        return hasAnnotation(e, "java.lang.Deprecated");
    }

    private boolean hasAnnotation(final Element e,
                                  final String annotationClassName) {
        return e.getAnnotationMirrors().stream()
                .anyMatch(annotation ->
                        annotationClassName.equals(annotation.getAnnotationType()
                                .asTypeElement()
                                .getQualifiedName())
                );
    }

    @Override
    public String getBinaryName(final TypeElement type) {
        final var classSymbol = (ClassSymbol) type;
        return classSymbol.getBinaryName();
    }

    @Override
    public PackageElement getPackageOf(final Element e) {
        final var enclosingElement = e.getEnclosingElement();

        if (enclosingElement instanceof PackageElement packageElement) {
            return packageElement;
        } else if (enclosingElement != null) {
            return getPackageOf(enclosingElement);
        } else {
            return null;
        }
    }

    @Override
    public List<? extends Element> getAllMembers(final TypeElement typeElement) {
        final var allMembers = new ArrayList<Element>();
        collectAllMembers(typeElement, allMembers);
        return allMembers;
    }

    private void collectAllMembers(final TypeElement typeElement,
                                   final List<Element> allMembers) {
        final var symbol = (Symbol) typeElement;

        final var elements = symbol.getMembers().elements().stream()
                .filter(this::noLocalOrAnonymous)
                .toList();
        allMembers.addAll(elements);

        addAllMembers(typeElement.getSuperclass(), allMembers);
        typeElement.getInterfaces().forEach(interfaceType ->
                addAllMembers(interfaceType, allMembers));
    }

    private boolean noLocalOrAnonymous(final Element element) {
        if (element instanceof TypeElement typeElement) {
            final var nestingKing = typeElement.getNestingKind();
            return nestingKing == NestingKind.LOCAL
                    || nestingKing == NestingKind.ANONYMOUS;
        } else {
            return true;
        }
    }

    private void addAllMembers(final TypeMirror typeMirror,
                               final List<Element> allMembers) {
        if (typeMirror == null) {
            return;
        }

        collectAllMembers(typeMirror.asTypeElement(), allMembers);
    }

    @Override
    public List<? extends AnnotationMirror> getAllAnnotationMirrors(final Element e) {
        var symbol = (Symbol) e;
        var annotationMirrors = new ArrayList<>(symbol.getAnnotationMirrors());

        while (symbol.getKind() == ElementKind.CLASS) {
            final var superclass = (AbstractType) symbol.getSuperclass();

            if (superclass.isError()
                    || superclass.asTypeElement() == symbolTable.getObjectType().asTypeElement()) {
                break;
            }

            symbol = (Symbol) superclass.asElement();
            for (final var annotation : symbol.getAnnotationMirrors()) {
                if (isInherited(annotation.getType())
                        && !containsAnnotationType(annotationMirrors, annotation.getType())) {
                    annotationMirrors.addFirst(annotation);
                }
            }
        }

        return annotationMirrors;
    }

    private boolean isInherited(final DeclaredType annotationType) {
        return annotationType.asTypeElement()
                .attribute(symbolTable.getInheritedType().asTypeElement()) != null;
    }

    private boolean containsAnnotationType(final List<CompoundAttribute> annotations,
                                           final TypeMirror type) {
        final var annotationElement = type.asElement();
        return annotations.stream()
                .anyMatch(annotation ->
                        annotation.getType().asTypeElement() == annotationElement);
    }

    @Override
    public boolean hides(final Element hider,
                         final Element hidden) {
        if (hider == hidden
                || hider.getKind() != hidden.getKind()
                || !(hider.getSimpleName().equals(hidden.getSimpleName()))) {
            return false;
        }

        if (hider.getKind() == ElementKind.METHOD) {
            if (!hider.isStatic()
                    || !types.isSubsignature((ExecutableType) hider.asType(), (ExecutableType) hidden.asType())) {
                return false;
            }
        }

        final var hiderClass = (Symbol) hider.getEnclosingElement().getClosestEnclosingClass();
        final var hiddenClass = (Symbol) hidden.getEnclosingElement().getClosestEnclosingClass();

        if (hiderClass == null
                || hiddenClass == null
                | !hiderClass.isSubClass(hiddenClass, types)) {
            return false;
        }

        final var hiddenSymbol = (Symbol) hidden;

        return hiddenSymbol.isAccessibleIn(hiderClass, types);
    }

    @Override
    public boolean overrides(final ExecutableElement overrider,
                             final ExecutableElement overridden,
                             final TypeElement type) {
        if (overrider == overridden) {
            return false;
        }

        if (!overrider.getSimpleName().equals(overridden.getSimpleName())) {
            return false;
        }

        if (overrider.getParameters().size() != overridden.getParameters().size()) {
            return false;
        }

        final var paramTypes = overrider.getParameters().stream()
                .map(Element::asType)
                .toList();

        final var otherParamTypes = overrider.getParameters().stream()
                .map(Element::asType)
                .toList();

        return CollectionUtils.pairStream(paramTypes, otherParamTypes)
                .allMatch(pair -> types.isSameType(pair.first(), pair.second()));
    }

    @Override
    public String getConstantExpression(final Object value) {
        return format(value);
    }

    private static String format(final Object value) {
        return switch (value) {
            case Byte b -> formatByte(b);
            case Short s -> formatShort(s);
            case Long l -> formatLong(l);
            case Float f -> formatFloat(f);
            case Double d -> formatDouble(d);
            case Character c -> formatChar(c);
            case String s -> formatString(s);
            case null -> throw new IllegalArgumentException("Value is not a primitive type or a string but is null");
            default ->
                    throw new IllegalArgumentException("Value is not a primitive type or a string but is " + value.getClass().getName());
        };
    }

    private static String formatByte(final byte b) {
        return String.format("(byte)0x%02x", b);
    }

    private static String formatShort(final short s) {
        return String.format("(short)%d", s);
    }

    private static String formatLong(final long lng) {
        return lng + "L";
    }

    private static String formatFloat(final float f) {
        if (Float.isNaN(f)) {
            return "0.0f/0.0f";
        } else if (Float.isInfinite(f)) {
            return (f < 0) ? "-1.0f/0.0f" : "1.0f/0.0f";
        } else {
            return f + "f";
        }
    }

    private static String formatDouble(final double d) {
        if (Double.isNaN(d)) {
            return "0.0/0.0";
        } else if (Double.isInfinite(d)) {
            return (d < 0) ? "-1.0/0.0" : "1.0/0.0";
        } else {
            return d + "";
        }
    }

    private static String formatChar(final char c) {
        return '\'' + quoteChar(c) + '\'';
    }

    private static String formatString(final String s) {
        return '"' + quoteChar(s) + '"';
    }

    private static String quoteChar(final String s) {
        final var buf = new StringBuilder();

        for (final var c : s.toCharArray()) {
            buf.append(quoteChar(s.charAt(c)));
        }

        return buf.toString();
    }

    public static String quoteChar(final char ch) {
        return switch (ch) {
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\t' -> "\\t";
            case '\'' -> "\\'";
            case '\"' -> "\\\"";
            case '\\' -> "\\\\";
            default -> isPrintableAscii(ch)
                    ? String.valueOf(ch)
                    : String.format("\\u%04x", (int) ch);
        };
    }

    private static boolean isPrintableAscii(final char ch) {
        return ch >= ' ' && ch <= '~';
    }

    @Override
    public void printElements(final Writer w,
                              final Element... elements) {
        try {
            for (final var element : elements) {
                ElementPrinter.print(element, w);
            }
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isFunctionalInterface(final TypeElement type) {
        return type.getKind() == ElementKind.INTERFACE
                && hasAnnotation(type, "java.lang.FunctionalInterface");
    }

    private <S extends Symbol> S nameToSymbol(final ModuleSymbol module,
                                              final String name,
                                              final Class<S> clazz) {
        Symbol symbol = (clazz == ClassSymbol.class)
                ? symbolTable.getClassSymbol(module, name)
                : symbolTable.lookupPackage(module, name);

        if (symbol == null) {
            symbol = (Symbol) this.compilerContext.getClassElementLoader().loadClass(
                    module,
                    name
            );
        }

        if (clazz.isInstance(symbol)
                && symbol.getKind() != ElementKind.OTHER
                && symbol.exists()
                && name.equals(symbol.getQualifiedName())) {
            return clazz.cast(symbol);
        }
        return null;
    }

    @Override
    public Set<? extends PackageElement> getAllPackageElements(final String name) {
        final var modules = getAllModuleElements();

        if (modules.isEmpty()) {
            final var packageElement = getPackageElement(name);
            return (packageElement != null)
                    ? Collections.singleton(packageElement)
                    : Collections.emptySet();
        } else {
            return modules.stream()
                    .map(module -> getPackageElement(module, name))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }

    @Override
    public Set<? extends TypeElement> getAllTypeElements(final String name) {
        final var modules = getAllModuleElements();
        if (modules.isEmpty()) {
            final var typeElement = getTypeElement(name);
            return (typeElement != null)
                    ? Collections.singleton(typeElement)
                    : Collections.emptySet();
        } else {
            return modules.stream()
                    .map(module -> getTypeElement(module, null))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
        }
    }

    @Override
    public ModuleElement getModuleElement(final String name) {
        if ("".equals(name)) {
            return symbolTable.getUnnamedModule();
        }
        return modules.getModuleByName(name);
    }

    @Override
    public Set<? extends ModuleElement> getAllModuleElements() {
        return Collections.unmodifiableSet(modules.allModules());
    }

    @Override
    public Origin getOrigin(final Element e) {
        if (e.hasFlag(Flags.GENERATED_DEFAULT_CONSTRUCTOR)
                || e.hasFlag(Flags.MANDATED)) {
            return Origin.MANDATED;
        } else {
            return Origin.EXPLICIT;
        }
    }

    @Override
    public Origin getOrigin(final AnnotatedConstruct c,
                            final AnnotationMirror a) {
        final var compoundAttribute = (CCompoundAttribute) a;
        return compoundAttribute.isSynthesized()
                ? Origin.MANDATED
                : Origin.EXPLICIT;
    }

    @Override
    public Origin getOrigin(final ModuleElement m,
                            final ModuleElement.Directive directive) {
        return switch (directive.getKind()) {
            case REQUIRES -> {
                final var requiresDirective = (Directive.RequiresDirective) directive;

                if (requiresDirective.getFlags().contains(Directive.RequiresFlag.MANDATED)) {
                    yield Elements.Origin.MANDATED;
                } else if (requiresDirective.getFlags().contains(Directive.RequiresFlag.SYNTHETIC)) {
                    yield Elements.Origin.SYNTHETIC;
                } else {
                    yield Elements.Origin.EXPLICIT;
                }
            }
            case EXPORTS -> {
                final var exportsDirective = (Directive.ExportsDirective) directive;

                if (exportsDirective.getFlags().contains(Directive.ExportsFlag.MANDATED)) {
                    yield Elements.Origin.MANDATED;
                } else if (exportsDirective.getFlags().contains(Directive.ExportsFlag.SYNTHETIC)) {
                    yield Elements.Origin.SYNTHETIC;
                } else {
                    yield Elements.Origin.EXPLICIT;
                }
            }
            case OPENS -> {
                final var opensDirective = (Directive.OpensDirective) directive;

                if (opensDirective.getFlags().contains(Directive.OpensFlag.MANDATED)) {
                    yield Elements.Origin.MANDATED;
                } else if (opensDirective.getFlags().contains(Directive.OpensFlag.SYNTHETIC)) {
                    yield Elements.Origin.SYNTHETIC;
                } else {
                    yield Elements.Origin.EXPLICIT;
                }
            }
            default -> Origin.EXPLICIT;
        };
    }

    @Override
    public boolean isBridge(final ExecutableElement e) {
        return e.hasFlag(Flags.BRIDGE);
    }

    @Override
    public ModuleElement getModuleOf(final Element e) {
        final var symbol = (Symbol) e;

        if (symbol.getKind() == ElementKind.MODULE) {
            return (ModuleElement) symbol;
        } else if (symbol.getKind() == ElementKind.PACKAGE) {
            final var packageSymbol = (PackageSymbol) symbol;
            return packageSymbol.getModuleSymbol();
        } else {
            final var enclosingElement = symbol.getEnclosingElement();
            return enclosingElement != null
                    ? getModuleOf(enclosingElement)
                    : null;
        }
    }

    @Override
    public TypeElement getOutermostTypeElement(final Element e) {
        if (e == null) {
            return null;
        } else if (e instanceof TypeElement typeElement) {
            final var enclosingElement = typeElement.getEnclosingElement();

            if (enclosingElement instanceof TypeElement enclosingTypeElement) {
                return getOutermostTypeElement(enclosingTypeElement);
            } else {
                return typeElement;
            }
        } else {
            final var enclosingElement = e.getEnclosingElement();
            return getOutermostTypeElement(enclosingElement);
        }
    }

    @Override
    public boolean isAutomaticModule(final ModuleElement module) {
        return module.hasFlag(Flags.AUTOMATIC_MODULE);
    }

    @Override
    public RecordComponentElement recordComponentFor(final ExecutableElement accessor) {
        if (accessor.getKind() != ElementKind.RECORD) {
            return null;
        }

        return ElementFilter.recordComponentsIn(accessor.getEnclosingElement().getEnclosedElements()).stream()
                .filter(recordComponentElement ->
                        Objects.equals(recordComponentElement.getAccessor(), accessor)
                )
                .findFirst()
                .orElse(null);

    }

    @Override
    public boolean isCanonicalConstructor(final ExecutableElement e) {
        return e.hasFlag(Flags.RECORD);
    }

    @Override
    public boolean isCompactConstructor(final ExecutableElement e) {
        return e.hasFlag(Flags.COMPACT_RECORD_CONSTRUCTOR);
    }

    @Override
    public FileObject getFileObjectOf(final Element e) {
        switch (e.getKind()) {
            case PACKAGE -> {
                final var packageSymbol = (PackageSymbol) e;
                return packageSymbol.getPackageInfo() != null
                        ? packageSymbol.getPackageInfo().getClassFile()
                        : null;
            }
            case MODULE -> {
                final var moduleSymbol = (ModuleSymbol) e;
                return moduleSymbol.getModuleInfo() != null
                        ? moduleSymbol.getModuleInfo().getClassFile()
                        : null;
            }
            case CLASS,
                 INTERFACE,
                 RECORD,
                 ANNOTATION_TYPE,
                 ENUM -> {
                final var classSymbol = (ClassSymbol) e;
                return classSymbol.getClassFile();
            }
            default -> {
                final var symbol = (ClassSymbol) e.getClosestEnclosingClass();
                return symbol.getClassFile();
            }
        }
    }
}
