package io.github.potjerodekool.nabu.compiler.resolve;

import io.github.potjerodekool.nabu.compiler.TodoException;
import io.github.potjerodekool.nabu.compiler.ast.element.*;
import io.github.potjerodekool.nabu.compiler.ast.element.builder.ClassBuilder;
import io.github.potjerodekool.nabu.compiler.ast.element.impl.Symbol;
import io.github.potjerodekool.nabu.compiler.backend.ir.Constants;
import io.github.potjerodekool.nabu.compiler.resolve.types.*;
import io.github.potjerodekool.nabu.compiler.type.*;
import io.github.potjerodekool.nabu.compiler.type.impl.*;

import java.util.*;

public class TypesImpl implements Types {

    private final Set<String> BOXED_TYPES = Set.of(
            "java.lang.Boolean",
            "java.lang.Long",
            "java.lang.Character",
            "java.lang.Integer",
            "java.lang.Byte",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Short"
    );

    private final SymbolTable symbolTable;

    private final IsSameType isSameType = new IsSameType();

    private final IsSubType isSubType = new IsSubType(this);

    private final IsAssignableMatcher isAssignableMatcher = new IsAssignableMatcher(
            isSubType,
            this
    );

    private final ErasureTypeVisitor erasureTypeVisitor = new ErasureTypeVisitor(this);

    private static final Set<TypeKind> EXEC_PACKAGE_MODULE = Set.of(
            TypeKind.EXECUTABLE,
            TypeKind.PACKAGE,
            TypeKind.MODULE
    );

    private final ContainsType containsType = new ContainsType(isSubType);

    private final HasSameArguments hasSameArgumentsNonStrict = new HasSameArguments(
            isSameType,
            containsType,
            false
    );

    private final HasSameArguments hasSameArgumentsStrict = new HasSameArguments(
            isSameType,
            containsType,
            true
    );

    private final MemberType memberType = new MemberType();

    private final Substitute substitute = new Substitute();

    private final ClassElementLoader loader;

    public TypesImpl(final ClassElementLoader loader,
                     final SymbolTable symbolTable) {
        this.loader = loader;
        this.symbolTable = symbolTable;
    }

    @Override
    public TypeElement boxedClass(final io.github.potjerodekool.nabu.compiler.type.PrimitiveType p) {
        final var className = switch (p.getKind()) {
            case BOOLEAN -> Constants.BOOLEAN;
            case LONG -> Constants.LONG;
            case CHAR -> Constants.CHARACTER;
            case INT -> Constants.INTEGER;
            case BYTE -> Constants.BYTE;
            case DOUBLE -> Constants.DOUBLE;
            case FLOAT -> Constants.FLOAT;
            case SHORT -> Constants.SHORT;
            default -> null;
        };

        if (className == null) {
            throw new IllegalArgumentException("Not a primitive type " + p.getKind());
        }

        return symbolTable.getClassSymbol(ClassUtils.getInternalName(className));
    }

    @Override
    public TypeMirror unboxedType(final TypeMirror typeMirror) {
        if (!isBoxType(typeMirror)) {
            throw new IllegalArgumentException("Not a box type");
        }

        final var classType = (DeclaredType) typeMirror;
        final var clazz = (TypeElement) classType.asElement();
        final var className = clazz.getQualifiedName();

        return switch (className) {
            case Constants.BOOLEAN -> getPrimitiveType(TypeKind.BOOLEAN);
            case Constants.BYTE -> getPrimitiveType(TypeKind.BYTE);
            case Constants.SHORT -> getPrimitiveType(TypeKind.SHORT);
            case Constants.INTEGER -> getPrimitiveType(TypeKind.INT);
            case Constants.LONG -> getPrimitiveType(TypeKind.LONG);
            case Constants.CHARACTER -> getPrimitiveType(TypeKind.CHAR);
            case Constants.FLOAT -> getPrimitiveType(TypeKind.FLOAT);
            case Constants.DOUBLE -> getPrimitiveType(TypeKind.DOUBLE);
            default -> throw new TodoException();
        };
    }

    @Override
    public boolean isBoxType(final TypeMirror typeMirror) {
        if (typeMirror instanceof DeclaredType declaredType) {
            final var clazz = (TypeElement) declaredType.asElement();
            return BOXED_TYPES.contains(clazz.getQualifiedName());
        }
        return false;
    }

    @Override
    public TypeMirror getNullType() {
        return new CNullType();
    }

    @Override
    public PrimitiveType getPrimitiveType(final TypeKind kind) {
        return new CPrimitiveType(kind);
    }

    @Override
    public DeclaredType getDeclaredType(final TypeElement typeElem,
                                        final TypeMirror... typeArgs) {
        if (typeArgs.length == 0) {
            return (DeclaredType) typeElem.erasure(this);
        } else if (typeElem.asType().getEnclosingType() != null
                && typeElem.asType().getEnclosingType().isParameterized()) {
            throw new IllegalArgumentException("Enclosing type is parameterized");
        }

        return getDeclaredTypeImpl(
                (DeclaredType) typeElem.asType().getEnclosingType(),
                typeElem,
                typeArgs
        );
    }

    @Override
    public DeclaredType getDeclaredType(final DeclaredType enclosing,
                                        final TypeElement typeElem,
                                        final TypeMirror... typeArgs) {
        if (enclosing == null) {
            return getDeclaredType(
                    typeElem,
                    typeArgs
            );
        }

        if (enclosing.getTypeElement() != typeElem.getEnclosingElement().getClosestEnclosingClass()) {
            throw new IllegalArgumentException("Invalid enclosing " + getQualifiedName(asElement(enclosing)));
        } else if (!enclosing.isParameterized()) {
            return getDeclaredType(typeElem, typeArgs);
        } else {
            return getDeclaredTypeImpl(
                    enclosing,
                    typeElem,
                    typeArgs
            );
        }
    }

    private DeclaredType getDeclaredTypeImpl(final DeclaredType enclosing,
                                             final TypeElement typeElem,
                                             final TypeMirror... typeArgs) {
        final var typeArguments = typeElem.asType().getTypeArguments();

        if (typeArgs.length != typeArguments.size()) {
            throw new IllegalArgumentException(
                    String.format(
                            "Incorrect number of type arguments. Got %s but expected %s",
                            typeArgs.length,
                            typeArguments.size()
                    )
            );
        }

        if (Arrays.stream(typeArgs)
                .anyMatch(typeArg ->
                        !(typeArg instanceof ReferenceType
                                || typeArg instanceof WildcardType)
                )) {
            throw new IllegalArgumentException("Invalid type argument type");
        }

        return new CClassType(
                enclosing,
                typeElem,
                List.of(typeArgs)
        );
    }

    private String getQualifiedName(final Element element) {
        if (element instanceof QualifiedNameable qualifiedNameable) {
            return qualifiedNameable.getQualifiedName();
        } else {
            return element.getSimpleName();
        }
    }

    @Override
    public io.github.potjerodekool.nabu.compiler.type.ArrayType getArrayType(final TypeMirror componentType) {
        return new CArrayType(componentType);
    }

    @Override
    public boolean isSubType(final TypeMirror typeMirrorA,
                             final TypeMirror typeMirrorB) {
        if (typeMirrorA == null) {
            return false;
        }

        return typeMirrorA.accept(isSubType, typeMirrorB);
    }

    @Override
    public boolean isAssignable(final TypeMirror typeMirrorA,
                                final TypeMirror typeMirrorB) {
        if (typeMirrorA == null) {
            return false;
        }

        return typeMirrorA.accept(isAssignableMatcher, typeMirrorB);
    }

    @Override
    public DeclaredType getErrorType(final String className) {
        final var packageNameEnd = className.lastIndexOf('.');
        final String simpleName;
        final Element enclosingElement;

        if (packageNameEnd < 0) {
            simpleName = className;
            enclosingElement = null;
        } else {
            simpleName = className.substring(packageNameEnd + 1);
            enclosingElement = loader.findOrCreatePackage(className.substring(0, packageNameEnd));
        }

        return (DeclaredType) new ClassBuilder()
                .enclosingElement((Symbol) enclosingElement)
                .kind(ElementKind.CLASS)
                .name(simpleName)
                .nestingKind(NestingKind.TOP_LEVEL)
                .buildError()
                .asType();
    }

    @Override
    public boolean isSameType(final TypeMirror typeA, final TypeMirror typeB) {
        return typeA.accept(isSameType, typeB);
    }

    @Override
    public io.github.potjerodekool.nabu.compiler.type.WildcardType getWildcardType(final TypeMirror extendsBound,
                                                                                   final TypeMirror superBound) {
        return new CWildcardType(extendsBound, superBound);
    }

    @Override
    public TypeMirror asMemberOf(final DeclaredType containing, Element element) {
        final MemberOfVisitor visitor = new MemberOfVisitor(this);
        return containing.accept(visitor, element);
    }

    @Override
    public TypeMirror erasure(final TypeMirror t) {
        return t != null
                ? t.accept(erasureTypeVisitor, null)
                : null;
    }

    @Override
    public TypeMirror getIntersectionType(final List<TypeMirror> typeBound) {
        return new CIntersectionType(typeBound);
    }

    @Override
    public Element asElement(final TypeMirror t) {
        return switch (t.getKind()) {
            case MODULE,
                 PACKAGE,
                 DECLARED,
                 INTERSECTION,
                 TYPEVAR,
                 ERROR -> {
                final var type = (AbstractType) t;
                yield type.asElement();
            }
            default -> null;
        };
    }

    @Override
    public boolean contains(final TypeMirror t1, final TypeMirror t2) {
        checkNoExecutablePackageOrModule(t1);
        checkNoExecutablePackageOrModule(t2);
        return t1.accept(containsType, t2);
    }

    private void checkNoExecutablePackageOrModule(final TypeMirror typeMirror) {
        if (EXEC_PACKAGE_MODULE.contains(typeMirror.getKind())) {
            throw new IllegalArgumentException(typeMirror.toString());
        }
    }

    @Override
    public boolean isSubsignature(final ExecutableType m1, final ExecutableType m2) {
        return hasSameArguments(m1, m2, true)
                || hasSameArguments(m2, (ExecutableType) erasure(m2), true);
    }

    private boolean hasSameArguments(final ExecutableType m1, final ExecutableType m2,
                                     final boolean strict) {
        return hasSameArguments(
                m1,
                m2,
                strict ? hasSameArgumentsStrict : hasSameArgumentsNonStrict
        );
    }

    private boolean hasSameArguments(final ExecutableType m1,
                                     final ExecutableType m2,
                                     final TypeRelation hasSameArguments) {
        return hasSameArguments.visit(m1, m2);
    }

    @Override
    public List<? extends TypeMirror> directSupertypes(final TypeMirror t) {
        if (t instanceof DeclaredType declaredType) {
            final var element = (TypeElement) declaredType.asElement();

            if (!Constants.OBJECT.equals(element.getQualifiedName())) {
                final var interfaces = element.getInterfaces();
                final var superclass = element.getSuperclass();
                final var superTypes = new ArrayList<TypeMirror>(1 + interfaces.size());
                superTypes.add(superclass);
                superTypes.addAll(interfaces);
                return superTypes;
            }
        }

        return List.of();
    }

    @Override
    public TypeMirror capture(final TypeMirror typeMirror) {
        checkNoExecutablePackageOrModule(typeMirror);
        if (typeMirror.getKind() != TypeKind.DECLARED) {
            return typeMirror;
        }

        var type = (DeclaredType) typeMirror;

        if (typeMirror.getEnclosingType() != null) {
            final var capturedEnclosingType = capture(typeMirror.getEnclosingType());

            if (capturedEnclosingType != typeMirror.getEnclosingType()) {
                final var memberType =
                        this.memberType.memberType(capturedEnclosingType, typeMirror.getTypeElement());
                type = (DeclaredType) substitute.substitute(memberType, typeMirror.getTypeElement().asType().getTypeArguments(), typeMirror.getTypeArguments());
            }
        }

        if (type.isRaw() || !type.isParameterized()) {
            return type;
        }

        throw new TodoException();
    }

    @Override
    public NoType getNoType(final TypeKind kind) {
        if (kind == TypeKind.VOID) {
            return new CVoidType();
        } else if (kind == TypeKind.NONE) {
            return new CNoType();
        } else {
            throw new IllegalArgumentException("Not a valid kind " + kind.name());
        }
    }

    @Override
    public ExecutableType getExecutableType(final ExecutableElement methodSymbol,
                                            final List<? extends TypeVariable> typeVariables,
                                            final TypeMirror returnType,
                                            final List<? extends TypeMirror> argumentTypes,
                                            final List<? extends TypeMirror> thrownTypes) {
        return new CMethodType(
                methodSymbol,
                typeVariables,
                returnType,
                argumentTypes,
                thrownTypes
        );
    }

    @Override
    public TypeVariable getTypeVariable(final String name, final TypeMirror upperBound, final TypeMirror lowerBound) {
        return new CTypeVariable(
                name,
                upperBound,
                lowerBound
        );
    }

    @Override
    public TypeMirror getVariableType(final TypeMirror interferedType) {
        return new CVariableType(interferedType);
    }
}

