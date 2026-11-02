package io.github.potjerodekool.nabu.compiler.resolve.impl;

import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
import io.github.potjerodekool.nabu.tree.ConstantCaseLabel;
import io.github.potjerodekool.nabu.tree.DefaultCaseLabel;
import io.github.potjerodekool.nabu.tree.Pattern;
import io.github.potjerodekool.nabu.tree.PatternCaseLabel;
import io.github.potjerodekool.nabu.tree.RecordPattern;
import io.github.potjerodekool.nabu.tree.TypePattern;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.FieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.VariableTypeTree;
import io.github.potjerodekool.nabu.tree.statement.CaseStatement;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.util.Types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Does a switch cover all possible values of its selector (JLS 14.11.1.1)? A default label does, so does a
 * pattern that covers the type of the selector, the constants of an enum, or the permitted subtypes of a sealed
 * type, and the record patterns that together cover the components of a record.
 * <p>
 * A type that is only known partly (a sealed class without {@code permits} clause, an unresolved type) counts as
 * covered, so that an unknown is never reported as an error.
 */
final class SwitchExhaustiveness {

    private final Types types;
    private boolean unknown = false;
    private final Set<String> coveredEnums = new HashSet<>();

    SwitchExhaustiveness(final Types types) {
        this.types = types;
    }

    boolean isExhaustive(final ExpressionTree selector,
                         final List<CaseStatement> cases) {
        final var selectorType = selector.getType();
        final var labels = cases.stream().flatMap(caseStatement -> caseStatement.getLabels().stream()).toList();

        if (labels.stream().anyMatch(label -> label instanceof DefaultCaseLabel)) {
            return true;
        }
        if (selectorType == null || selectorType.getKind() == TypeKind.ERROR) {
            return true;
        }
        if (!(selectorType instanceof DeclaredType) && !(selectorType instanceof PrimitiveType)) {
            return true;
        }

        if (selectorType instanceof DeclaredType declaredType
                && declaredType.asTypeElement() instanceof TypeElement enumElement
                && enumElement.getKind() == ElementKind.ENUM) {
            final var constants = ElementFilter.enumValuesIn(enumElement.getEnclosedElements()).stream()
                    .map(element -> element.getSimpleName().toString())
                    .collect(java.util.stream.Collectors.toSet());
            final var used = new HashSet<String>();
            for (final var label : labels) {
                if (label instanceof ConstantCaseLabel constantLabel
                        && constantName(constantLabel.getExpression()) instanceof String name) {
                    used.add(name);
                }
            }
                if (used.containsAll(constants)) {
                return true;
            }
        }

        final var rows = new ArrayList<List<Pattern>>();
        for (final var label : labels) {
            if (label instanceof PatternCaseLabel patternLabel && patternLabel.getWhen() == null) {
                final var row = new ArrayList<Pattern>();
                row.add(patternLabel.getPattern());
                rows.add(row);
            }
        }

        // case Suit.CLUBS, case Suit.HEARTS: an enum that is a permitted subtype is covered by all its constants.
        final var usedConstants = new java.util.HashMap<String, Set<String>>();
        for (final var label : labels) {
            if (label instanceof ConstantCaseLabel constantLabel
                    && constantLabel.getExpression().getType() != null
                    && constantLabel.getExpression().getType().asElement() instanceof TypeElement constantEnum
                    && constantEnum.getKind() == ElementKind.ENUM
                    && constantName(constantLabel.getExpression()) instanceof String name) {
                usedConstants.computeIfAbsent(constantEnum.getQualifiedName(), key -> new HashSet<>()).add(name);
            }
        }
        coveredEnums.clear();
        usedConstants.forEach((enumName, used) -> {
            final var enumType = findEnum(selectorType, enumName);
            if (enumType != null && used.containsAll(ElementFilter.enumValuesIn(enumType.getEnclosedElements()).stream()
                    .map(element -> element.getSimpleName().toString())
                    .collect(java.util.stream.Collectors.toSet()))) {
                coveredEnums.add(enumName);
            }
        });

        final var covered = covers(List.of(selectorType), rows, true);
        return covered || unknown;
    }

    private static String constantName(final ExpressionTree expression) {
        if (expression instanceof IdentifierTree identifier) {
            return identifier.getName();
        }
        if (expression instanceof FieldAccessExpressionTree fieldAccess) {
            return fieldAccess.getField().getName();
        }
        return null;
    }

    /**
     * @param columns The types of the values that are matched.
     * @param rows For every case one pattern per column; null matches everything.
     */
    private boolean covers(final List<TypeMirror> columns,
                           final List<List<Pattern>> rows,
                           final boolean topLevel) {
        if (columns.isEmpty()) {
            return !rows.isEmpty();
        }
        if (rows.isEmpty() && !topLevel) {
            return false;
        }

        for (final var leaf : leaves(columns.getFirst())) {
            if (topLevel && elementOf(leaf) != null && coveredEnums.contains(elementOf(leaf).getQualifiedName())) {
                continue;
            }
            if (!coversLeaf(leaf, columns, rows)) {
                return false;
            }
        }
        return true;
    }

    /** The enum with this name among the selector type and its permitted subtypes. */
    private TypeElement findEnum(final TypeMirror type,
                                 final String name) {
        for (final var leaf : leaves(type)) {
            final var element = elementOf(leaf);
            if (element != null && element.getKind() == ElementKind.ENUM && element.getQualifiedName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private boolean coversLeaf(final TypeMirror leaf,
                               final List<TypeMirror> columns,
                               final List<List<Pattern>> rows) {
        final var restColumns = columns.subList(1, columns.size());
        final var applicable = new ArrayList<List<Pattern>>();
        boolean recordPatterns = false;

        for (final var row : rows) {
            final var pattern = row.getFirst();
            if (pattern == null || (pattern instanceof TypePattern typePattern
                    && isSubType(leaf, typePatternType(typePattern)))) {
                applicable.add(row);
            } else if (pattern instanceof RecordPattern recordPattern
                    && sameType(leaf, recordPattern.getRecordType().getType())) {
                applicable.add(row);
                recordPatterns = true;
            }
        }

        final var element = elementOf(leaf);
        if (recordPatterns && element != null && element.getKind() == ElementKind.RECORD) {
            final var patternType = applicable.stream()
                    .map(List::getFirst)
                    .filter(RecordPattern.class::isInstance)
                    .map(pattern -> ((RecordPattern) pattern).getRecordType().getType())
                    .findFirst()
                    .orElse(leaf);
            final var components = ElementFilter.elements(element,
                            e -> e.getKind() == ElementKind.RECORD_COMPONENT, VariableElement.class).stream()
                    .map(component -> componentType(element, patternType, component))
                    .toList();
            final var newColumns = new ArrayList<TypeMirror>(components);
            newColumns.addAll(restColumns);
            final var newRows = new ArrayList<List<Pattern>>();
            for (final var row : applicable) {
                final var newRow = new ArrayList<Pattern>();
                if (row.getFirst() instanceof RecordPattern recordPattern) {
                    newRow.addAll(recordPattern.getPatterns());
                } else {
                    newRow.addAll(Collections.nCopies(components.size(), null));
                }
                newRow.addAll(row.subList(1, row.size()));
                newRows.add(newRow);
            }
            return covers(newColumns, newRows, false);
        }

        final var newRows = new ArrayList<List<Pattern>>();
        for (final var row : applicable) {
            if (!(row.getFirst() instanceof RecordPattern)) {
                newRows.add(new ArrayList<>(row.subList(1, row.size())));
            }
        }
        return covers(new ArrayList<>(restColumns), newRows, false);
    }

    /** The type of a component as a member of the type of the pattern: {@code T value} of {@code Some<String>}. */
    private TypeMirror componentType(final TypeElement record,
                                     final TypeMirror recordType,
                                     final VariableElement component) {
        try {
            final var accessor = ElementFilter.methodsIn(record.getEnclosedElements()).stream()
                    .filter(m -> m.getSimpleName().contentEquals(component.getSimpleName())
                            && m.getParameters().isEmpty())
                    .findFirst()
                    .orElse(null);
            if (accessor != null && recordType instanceof DeclaredType declaredType) {
                final var returnType = ((ExecutableType) types.asMemberOf(declaredType, accessor)).getReturnType();
                if (returnType != null && returnType.getKind() != TypeKind.ERROR) {
                    return returnType;
                }
            }
        } catch (final RuntimeException ignored) {
            // The declared type of the component is used.
        }
        return component.asType();
    }

    /** The type of a type pattern, or null if it is 'var' or '_' and matches everything. */
    private TypeMirror typePatternType(final TypePattern pattern) {
        final var declarator = pattern.getVariableDeclarator();
        if (declarator == null
                || declarator.getVariableType() == null
                || declarator.getVariableType() instanceof VariableTypeTree
                || declarator.getVariableType().getType() == null
                || declarator.getVariableType().getType().getKind() == TypeKind.ERROR) {
            return null;
        }
        return declarator.getVariableType().getType();
    }

    /** The types a value of this type can have: for a sealed type the permitted subtypes, else the type itself. */
    private List<TypeMirror> leaves(final TypeMirror type) {
        final var element = elementOf(type);
        if (element == null || !Flags.hasFlag(element.getFlags(), Flags.SEALED)) {
            return List.of(type);
        }
        final var permitted = element.getPermittedSubclasses();
        if (permitted.isEmpty()) {
            unknown = true;
            return List.of(type);
        }
        final var result = new ArrayList<TypeMirror>();
        if (!element.isAbstract() && element.getKind() != ElementKind.INTERFACE) {
            result.add(type);
        }
        for (final var subtype : permitted) {
            result.addAll(leaves(subtype));
        }
        return result;
    }

    private TypeElement elementOf(final TypeMirror type) {
        return type instanceof DeclaredType declaredType ? declaredType.asTypeElement() : null;
    }

    private boolean isSubType(final TypeMirror type,
                              final TypeMirror superType) {
        if (superType == null) {
            return true;
        }
        if (type instanceof PrimitiveType || superType instanceof PrimitiveType) {
            return sameType(type, superType);
        }
        return types.isSubType(types.erasure(type), types.erasure(superType));
    }

    private boolean sameType(final TypeMirror a,
                             final TypeMirror b) {
        return a != null && b != null && types.isSameType(types.erasure(a), types.erasure(b));
    }
}
