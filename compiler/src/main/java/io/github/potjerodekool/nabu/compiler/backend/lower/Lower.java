package io.github.potjerodekool.nabu.compiler.backend.lower;

import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.VariableSymbol;
import io.github.potjerodekool.nabu.compiler.backend.lower.codegen.*;
import io.github.potjerodekool.nabu.compiler.backend.lower.widen.WideningConverter;
import io.github.potjerodekool.nabu.compiler.impl.CompilerContextImpl;
import io.github.potjerodekool.nabu.lang.model.element.*;
import io.github.potjerodekool.nabu.compiler.resolve.impl.Boxer;
import io.github.potjerodekool.nabu.resolve.ClassElementLoader;
import io.github.potjerodekool.nabu.compiler.resolve.method.MethodResolver;
import io.github.potjerodekool.nabu.resolve.scope.Scope;
import io.github.potjerodekool.nabu.tools.CompilerContext;
import io.github.potjerodekool.nabu.tools.Constants;
import io.github.potjerodekool.nabu.tree.CompilationUnit;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.ModuleDeclaration;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CArrayAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.impl.CFieldAccessExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.tree.CaseLabel;
import io.github.potjerodekool.nabu.tree.ConstantCaseLabel;
import io.github.potjerodekool.nabu.tree.statement.builder.VariableDeclaratorTreeBuilder;
import io.github.potjerodekool.nabu.type.ArrayType;
import io.github.potjerodekool.nabu.type.TypeKind;
import io.github.potjerodekool.nabu.tree.Tag;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.PrimitiveType;
import io.github.potjerodekool.nabu.type.TypeMirror;
import io.github.potjerodekool.nabu.type.TypeVariable;
import io.github.potjerodekool.nabu.util.Types;

import java.util.*;


/**
 * Add code like:
 * Generate code to init fields (both in constructor and client init).
 * Generate code for enum and record classes.
 * Add boxing and unboxing code.
 * Add code to enhanced for statements.
 */
public class Lower extends AbstractTreeTranslator<Lower.LowerScope> {

    private final CompilerContextImpl compilerContext;
    private final WideningConverter wideningConverter;
    private final Boxer boxer;
    private final Caster caster;
    private final MethodResolver methodResolver;
    private final Types types;
    private final ClassElementLoader loader;
    private int varCounter = 0;

    private final DefaultCodeGenerator defaultCodeGenerator;
    private final Map<Kind, AbstractCodeGenerator> codeGenerators = new HashMap<>();
    private final EnumUserCodeGenerator enumUserCodeGenerator;

    public Lower(final CompilerContextImpl compilerContext) {
        this.compilerContext = compilerContext;
        this.loader = compilerContext.getClassElementLoader();
        this.types = compilerContext.getTypes();
        this.boxer = new Boxer(compilerContext);
        this.caster = new Caster();
        this.wideningConverter = new WideningConverter(compilerContext);
        this.methodResolver = compilerContext.getMethodResolver();

        this.defaultCodeGenerator = new DefaultCodeGenerator(compilerContext);
        this.enumUserCodeGenerator = new EnumUserCodeGenerator(compilerContext);
        initCodeGenerators();
    }

    public static CompilationUnit lower(final CompilationUnit compilationUnit,
                                        final CompilerContext compilerContext) {
        final var lower = new Lower((CompilerContextImpl) compilerContext);
        lower.process(compilationUnit);
        return compilationUnit;
    }

    private void initCodeGenerators() {
        codeGenerators.put(Kind.ENUM, new EnumCodeGenerator(compilerContext));
        codeGenerators.put(Kind.RECORD, new RecordCodeGenerator(compilerContext));
    }

    public void process(final CompilationUnit compilationUnit) {
        final var scope = new LowerScope(compilationUnit);
        acceptTree(compilationUnit, scope);
    }

    private AbstractCodeGenerator getGenerator(final ClassDeclaration classDeclaration) {
        return this.codeGenerators.getOrDefault(classDeclaration.getKind(), defaultCodeGenerator);
    }

    @Override
    public Tree visitUnknown(final Tree tree,
                             final LowerScope context) {
        return tree;
    }

    public Tree defaultAnswer(final Tree tree,
                              final LowerScope scope) {
        return tree;
    }

    @Override
    public Tree visitModuleDeclaration(final ModuleDeclaration moduleDeclaration,
                                       final LowerScope scope) {
        scope.setModuleElement(moduleDeclaration.getModuleSymbol());
        return super.visitModuleDeclaration(moduleDeclaration, scope);
    }

    @Override
    public Tree visitClass(final ClassDeclaration classDeclaration, final LowerScope scope) {
        final var oldClass = scope.getCurrentClassDeclaration();
        scope.setCurrentClassDeclaration(classDeclaration);

        final var result = super.visitClass(classDeclaration, scope);
        final var generator = getGenerator(classDeclaration);
        generator.generateCode(classDeclaration);
        scope.setCurrentClassDeclaration(oldClass);
        return result;
    }

    @Override
    public Tree visitBinaryExpression(final BinaryExpressionTree binaryExpression,
                                      final LowerScope scope) {
        var left = binaryExpression.getLeft();
        var right = binaryExpression.getRight();

        if (scope != null) {
            final var leftType = compilerContext.getTreeUtils().typeOf(left);
            final var rightType = compilerContext.getTreeUtils().typeOf(right);
            final var module = scope.findModuleElement();
            final var stringType = loader.loadClass(module, Constants.STRING).asType();

            if ((leftType != null && types.isSameType(stringType, leftType))
                    || (rightType != null && types.isSameType(stringType, rightType))) {
                return binaryExpression;
            }
        }

        left = wideningConverter.convert(left, right);
        right = wideningConverter.convert(right, left);

        final var rightTypeAfterConversion = compilerContext.getTreeUtils().typeOf(right);
        final var leftTypeAfterConversion = compilerContext.getTreeUtils().typeOf(left);

        if (rightTypeAfterConversion == null || leftTypeAfterConversion == null) {
            return binaryExpression;
        }

        left = rightTypeAfterConversion.accept(caster, left);
        right = leftTypeAfterConversion.accept(caster, right);

        left = unboxIfNeeded(left, right);
        right = unboxIfNeeded(right, left);

        // Het doel van een toewijzing is een variabele, geen waarde: nooit
        // converteren. `cached = calc()` (Boolean = boolean) werd anders
        // `cached.booleanValue() = calc()`, en de store verdween.
        if (binaryExpression.getTag() == Tag.ASSIGN && left != binaryExpression.getLeft()) {
            left = binaryExpression.getLeft();
        }

        if (left != binaryExpression.getLeft()
                || right != binaryExpression.getRight()) {
            return binaryExpression.builder()
                    .left(left)
                    .right(right)
                    .type(left.getType())
                    .build();
        }

        return binaryExpression;
    }

    @Override
    public Tree visitConditionalExpression(final ConditionalExpressionTree conditionalExpression,
                                           final LowerScope scope) {
        final var condition = (ExpressionTree) acceptTree(conditionalExpression.getCondition(), scope);

        var trueExpression = (ExpressionTree) acceptTree(conditionalExpression.getTrueExpression(), scope);
        var falseExpression = (ExpressionTree) acceptTree(conditionalExpression.getFalseExpression(), scope);

        final var resultType = conditionalExpression.getType();

        boolean usable = false;
        if (resultType != null && scope != null) {
            try {
                usable = !resultType.isError();
            } catch (NullPointerException e) {
                usable = false;
            }
        }
        if (usable) {
            trueExpression = convertBranch(trueExpression, resultType);
            falseExpression = convertBranch(falseExpression, resultType);
        }

        return conditionalExpression.builder()
                .condition(condition)
                .trueExpression(trueExpression)
                .falseExpression(falseExpression)
                .type(resultType)
                .build();
    }

    private ExpressionTree convertBranch(final ExpressionTree branch,
                                         final io.github.potjerodekool.nabu.type.TypeMirror targetType) {
        if (branch == null) {
            return branch;
        }
        final var branchType = compilerContext.getTreeUtils().typeOf(branch);
        if (branchType == null || types.isSameType(branchType, targetType)) {
            return branch;
        }
        final var converted = targetType.accept(caster, branch);
        return converted != null ? converted : branch;
    }

    @Override
    public Tree visitEnhancedForStatement(final EnhancedForStatementTree enhancedForStatement,
                                          final LowerScope scope) {
        final var expression = (ExpressionTree) acceptTree(enhancedForStatement.getExpression(), scope);
        final var localVariable = (VariableDeclaratorTree) acceptTree(enhancedForStatement.getLocalVariable(), scope);
        final var statement = (StatementTree) acceptTree(enhancedForStatement.getStatement(), scope);

        final var expressionType = expression != null ? expression.getType() : null;

        // Soms is de attribuut-type van een veldtoegang nog niet gezet
        // (bv. een bloot veld-identifier in een anonieme klasse); de
        // veld-identifier draagt dan zelf het symbool. Zonder type kan
        // Lower niet bepalen of dit een array is -> verkeerde (iterator-)
        // lowering die een ongeldige AALOAD/iterator-slot leest.
        final var resolvedExpressionType = expressionType != null
                ? expressionType
                : (expression instanceof FieldAccessExpressionTree fae
                        && fae.getField() != null
                        && fae.getField().getSymbol() != null
                        ? fae.getField().getSymbol().asType()
                        : null);

        if (resolvedExpressionType instanceof ArrayType arrayType) {
            // foreach over een array: index-gebaseerde lowering; de
            // iterator-lowering is niet van toepassing omdat ArrayType
            // geen iterator() heeft.
            return lowerArrayEnhancedFor(arrayType, expression, localVariable, statement);
        }

        var methodInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        expression,
                        IdentifierTree.create("iterator")
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        methodResolver.resolveMethod(methodInvocation, scope)
                .ifPresent(resolvedMethod -> {
                    methodInvocation.getMethodSelector().setType(resolvedMethod.getOwner().asType());
                    methodInvocation.setMethodType(resolvedMethod);
                });

        /*
        methodResolver.resolveMethod(methodInvocation).ifPresent(resolvedMethod -> {
            methodInvocation.getMethodSelector().setType(resolvedMethod.getOwner().asType());
            methodInvocation.setMethodType(resolvedMethod);
        });
        */

        final var localVariableType = localVariable.getVariableType().getType();

        TypeMirror iteratorElementType;
        if (localVariableType instanceof DeclaredType localVariableDeclaredType) {
            iteratorElementType = localVariableDeclaredType;
        } else if (localVariableType instanceof TypeVariable typeVariable) {
            // Voor type-variabelen (bv. K in `for (K key : set)`), gebruik de upper bound
            // (erasure) voor de iterator en cast.
            iteratorElementType = typeVariable.getUpperBound();
            if (iteratorElementType == null) {
                iteratorElementType = types.getObjectType();
            }
        } else {
            return enhancedForStatement;
        }

        final var iteratorName = generateVariableName();
        final var iteratorClassElement = loader.loadClass(
                scope.findModuleElement(),
                "java.util.Iterator"
        );

        final var iteratorType = types.getDeclaredType(iteratorClassElement, iteratorElementType);

        final var localVariableElement = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName(iteratorName)
                .type(iteratorType)
                .build();

        final var iteratorTypeTree = TreeMaker.typeApplyTree(
                IdentifierTree.create("java.util.Iterator"),
                List.of(),
                -1,
                -1
        );

        iteratorTypeTree.setType(iteratorType);

        final var forInit = new VariableDeclaratorTreeBuilder()
                .kind(Kind.LOCAL_VARIABLE)
                .modifiers(new Modifiers())
                .variableType(iteratorTypeTree)
                .name(createIdentifier(iteratorName, localVariableElement))
                .value(methodInvocation)
                .build();

        final var check = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        createIdentifier(iteratorName, localVariableElement),
                        IdentifierTree.create("hasNext")
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var resolvedHasNextMethod = methodResolver.resolveMethod(check, scope)
                .orElseThrow(() -> new IllegalStateException("Failed to resolve hasNext method"));

        check.getMethodSelector().setType(resolvedHasNextMethod.getOwner().asType());
        check.setMethodType(resolvedHasNextMethod);

        // Voor de loop-variable declaratie en cast gebruiken we de iterator element type
        // (de upper bound van de type-variable, of de concrete type).
        // In bytecode wordt dit geëraseerd.
        final var typeTree = createIdentifierFromType(iteratorElementType);

        final var nextInvocation = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(
                        createIdentifier(iteratorName, localVariableElement),
                        IdentifierTree.create("next")
                ),
                List.of(),
                List.of(),
                -1,
                -1
        );

        final var resolvedNextMethod = methodResolver.resolveMethod(nextInvocation, scope)
                .orElseThrow(() -> new IllegalStateException("Failed to resolve next method"));

        nextInvocation.getMethodSelector().setType(resolvedNextMethod.getOwner().asType());
        nextInvocation.setMethodType(resolvedNextMethod);

        final var castTypeTree = IdentifierTree.create(typeTree.getName());

        castTypeTree.setType(typeTree.getType());

        final var cast = TreeMaker.castExpressionTree(
                castTypeTree,
                nextInvocation,
                -1,
                -1
        );

        cast.setType(iteratorElementType);

        final var statements = new ArrayList<StatementTree>();
        statements.add(localVariable.builder()
                .variableType(typeTree)
                .value(cast)
                .build());

        if (statement instanceof BlockStatementTree blockStatement) {
            statements.addAll(blockStatement.getStatements());
        } else if (statement != null) {
            statements.add(statement);
        }

        final var newBody = TreeMaker.blockStatement(
                statements,
                -1,
                -1
        );

        return TreeMaker.forStatement(
                List.of(forInit),
                check,
                List.of(),
                newBody,
                -1,
                -1
        );
    }

    /**
     * Lowering van een enhanced-for over een array: gamevariabelen worden
     * via een index-loops opgezocht (JLS §14.14).
     */
    private Tree lowerArrayEnhancedFor(final ArrayType arrayType,
                                       final ExpressionTree expression,
                                       final VariableDeclaratorTree localVariable,
                                       final StatementTree statement) {
        final var arrayComponentType = arrayType.getComponentType();
        final var counterName = generateVariableName();
        final var intType = types.getPrimitiveType(TypeKind.INT);

        final var counterElement = new VariableSymbolBuilderImpl()
                .kind(ElementKind.LOCAL_VARIABLE)
                .simpleName(counterName)
                .type(intType)
                .build();

        final var forInitCounter = createIdentifier(counterName, counterElement);
        forInitCounter.setType(intType);

        final var forInitValue = TreeMaker.identifier(counterName, -1, -1);
        forInitValue.setSymbol(counterElement);
        forInitValue.setType(intType);

        final var forInit = new VariableDeclaratorTreeBuilder()
                .kind(Kind.LOCAL_VARIABLE)
                .modifiers(new Modifiers())
                .variableType(forInitCounter)
                .name(forInitValue)
                .value(TreeMaker.literalExpressionTree(0, -1, -1))
                .build();

        // JLS §14.14.2: de array-expressie wordt één keer geëvalueerd. Zonder
        // tijdelijke variabele kwam ze zowel in de conditie (`.length`) als in
        // de element-toegang terecht: `for (Field f : cls.getDeclaredFields())`
        // riep getDeclaredFields twee keer per iteratie aan.
        final List<StatementTree> forInits = new ArrayList<>();
        final ExpressionTree array;
        if (isLocalIdentifier(expression)) {
            array = expression;
        } else {
            final var arrayName = generateVariableName();
            final var arrayElement = new VariableSymbolBuilderImpl()
                    .kind(ElementKind.LOCAL_VARIABLE)
                    .simpleName(arrayName)
                    .type(arrayType)
                    .build();
            final var arrayTypeTree = IdentifierTree.create(arrayName);
            arrayTypeTree.setType(arrayType);
            final var arrayDeclName = createIdentifier(arrayName, arrayElement);
            arrayDeclName.setType(arrayType);
            forInits.add(new VariableDeclaratorTreeBuilder()
                    .kind(Kind.LOCAL_VARIABLE)
                    .modifiers(new Modifiers())
                    .variableType(arrayTypeTree)
                    .name(arrayDeclName)
                    .value(expression)
                    .build());
            final var arrayRef = createIdentifier(arrayName, arrayElement);
            arrayRef.setType(arrayType);
            array = arrayRef;
        }
        forInits.add(forInit);

        final var lengthField = TreeMaker.fieldAccessExpressionTree(
                array,
                IdentifierTree.create("length"),
                -1,
                -1
        );
        lengthField.setType(intType);

        final var checkCounter = createIdentifier(counterName, counterElement);
        checkCounter.setType(intType);
        final var check = TreeMaker.binaryExpressionTree(checkCounter, Tag.LT, lengthField, -1, -1);
        check.setType(types.getPrimitiveType(TypeKind.BOOLEAN));

        final var updateCounter = createIdentifier(counterName, counterElement);
        updateCounter.setType(intType);
        final var update = TreeMaker.unaryExpressionTree(Tag.POST_INC, updateCounter, -1, -1);

        final var accessCounter = createIdentifier(counterName, counterElement);
        accessCounter.setType(intType);
        final var arrayAccess = new CArrayAccessExpressionTree(array, accessCounter);
        arrayAccess.setType(arrayComponentType);

        final var statements = new ArrayList<StatementTree>();
        statements.add(localVariable.builder()
                .variableType(localVariable.getVariableType())
                .value(arrayAccess)
                .build());

        if (statement instanceof BlockStatementTree blockStatement) {
            statements.addAll(blockStatement.getStatements());
        } else if (statement != null) {
            statements.add(statement);
        }

        final var newBody = TreeMaker.blockStatement(statements, -1, -1);

        return TreeMaker.forStatement(
                forInits,
                check,
                List.of(TreeMaker.expressionStatement(update, -1, -1)),
                newBody,
                -1,
                -1
        );
    }

    /** Een lokale variabele of parameter: lezen heeft geen bijwerkingen en de waarde ligt vast. */
    private static boolean isLocalIdentifier(final ExpressionTree expression) {
        return expression instanceof IdentifierTree identifier
                && identifier.getSymbol() != null
                && (identifier.getSymbol().getKind() == ElementKind.LOCAL_VARIABLE
                    || identifier.getSymbol().getKind() == ElementKind.PARAMETER);
    }

    private IdentifierTree createIdentifier(final DeclaredType declaredType) {
        final var classSymbol = (TypeElement) declaredType.asElement();
        final var className = classSymbol.getQualifiedName();
        final var identifier = IdentifierTree.create(className);
        identifier.setType(declaredType);
        return identifier;
    }

    private IdentifierTree createIdentifierFromType(final TypeMirror type) {
        if (type instanceof DeclaredType declaredType) {
            return createIdentifier(declaredType);
        } else if (type instanceof PrimitiveType primitiveType) {
            // Primitieve types hebben geen klasse; maak een identifier voor de wrapper
            final var kind = primitiveType.getKind();
            final var wrapperName = switch (kind) {
                case BYTE -> "java.lang.Byte";
                case SHORT -> "java.lang.Short";
                case INT -> "java.lang.Integer";
                case LONG -> "java.lang.Long";
                case FLOAT -> "java.lang.Float";
                case DOUBLE -> "java.lang.Double";
                case CHAR -> "java.lang.Character";
                case BOOLEAN -> "java.lang.Boolean";
                default -> "java.lang.Object";
            };
            final var identifier = IdentifierTree.create(wrapperName);
            identifier.setType(type);
            return identifier;
        } else {
            // Fallback voor TypeVariable, WildcardType, etc.: gebruik Object
            final var identifier = IdentifierTree.create("java.lang.Object");
            identifier.setType(types.getObjectType());
            return identifier;
        }
    }

    private IdentifierTree createIdentifier(final String name,
                                            final Element element) {
        final var identifier = IdentifierTree.create(name);
        identifier.setSymbol(element);
        return identifier;
    }

    private String generateVariableName() {
        return "$p" + varCounter++;
    }

    public ExpressionTree unboxIfNeeded(final ExpressionTree left,
                                        final ExpressionTree right) {
        final var leftType = compilerContext.getTreeUtils().typeOf(left);
        final var rightType = compilerContext.getTreeUtils().typeOf(right);

        if (leftType instanceof DeclaredType
                && rightType instanceof PrimitiveType primitiveType) {
            return primitiveType.accept(boxer, left);
        }

        return left;
    }

    @Override
    public Tree visitFieldAccessExpression(final FieldAccessExpressionTree fieldAccessExpression,
                                           final LowerScope scope) {
        final var selected = access(fieldAccessExpression.getSelected(), null);
        final var field = fieldAccessExpression.getField();
        return fieldAccessExpression.builder()
                .selected(selected)
                .field(field)
                .build();
    }

    @Override
    public Tree visitIdentifier(final IdentifierTree identifier,
                                final LowerScope scope) {
        final var symbol = identifier.getSymbol();

        if (symbol instanceof VariableSymbol variableSymbol
                && variableSymbol.getKind() == ElementKind.FIELD
                && !variableSymbol.isStatic()) {

            final var clazz = symbol.getEnclosingElement();
            final var thisIdentifier = TreeMaker.identifier(Constants.THIS, -1, -1);
            thisIdentifier.setSymbol(clazz);

            return TreeMaker.fieldAccessExpressionTree(
                    thisIdentifier,
                    identifier,
                    -1,
                    -1
            );
        }

        return super.visitIdentifier(identifier, scope);
    }

    private ExpressionTree access(final ExpressionTree field,
                                  final ExpressionTree selected) {
        final var symbol = field.getSymbol();

        if (selected instanceof MethodInvocationTree) {
            return field;
        }

        if (!isThisExpression(selected)
                && symbol instanceof VariableSymbol variableSymbol
                && isFieldOrEnumConstant(variableSymbol)
                && !variableSymbol.isStatic()) {
            final var clazz = symbol.getEnclosingElement();
            final var thisIdentifier = TreeMaker.identifier(Constants.THIS, -1, -1);
            thisIdentifier.setSymbol(clazz);

            return TreeMaker.fieldAccessExpressionTree(
                    thisIdentifier,
                    (IdentifierTree) field,
                    -1,
                    -1
            );
        }

        return field;
    }

    private boolean isThisExpression(final ExpressionTree expressionTree) {
        return expressionTree instanceof IdentifierTree identifierTree
                && Constants.THIS.equals(identifierTree.getName());
    }

    private boolean isFieldOrEnumConstant(final VariableSymbol variableSymbol) {
        return variableSymbol.getKind() == ElementKind.FIELD
                || variableSymbol.getKind() == ElementKind.ENUM_CONSTANT;
    }

    @Override
    public Tree visitSwitchStatement(final SwitchStatement switchStatement,
                                     final LowerScope scope) {
        var selector = (ExpressionTree) acceptTree(switchStatement.getSelector(), scope);

        final var selectorType = selector.getSymbol() != null
                ? selector.getSymbol().asType()
                : compilerContext.getTreeUtils().typeOf(selector);
        if (selectorType == null) {
            final var plainCases = switchStatement.getCases().stream()
                    .map(caseStatement -> (CaseStatement) acceptTree(caseStatement, scope))
                    .toList();
            return switchStatement.builder().selector(selector).cases(plainCases).build();
        }

        if (types.isBoxType(selectorType)) {
            final var primitiveType = types.unboxedType(selectorType);
            selector = primitiveType.accept(boxer, selector);
        } else if (selectorType.isReferenceType()
                && selectorType.asElement() instanceof TypeElement enumElement
                && enumElement.getKind() == ElementKind.ENUM) {

            return visitEnumSwitchStatement(
                    switchStatement,
                    scope,
                    enumElement);
        }

        final var cases = switchStatement.getCases().stream()
                .map(caseStatement -> acceptTree(caseStatement, scope))
                .map(it -> (CaseStatement) it)
                .toList();

        return switchStatement.builder()
                .selector(selector)
                .cases(cases)
                .build();
    }

    @Override
    public Tree visitSwitchExpression(final SwitchExpressionTree switchExpression,
                                      final LowerScope scope) {
        var selector = (ExpressionTree) acceptTree(switchExpression.getSelector(), scope);

        final var selectorType = selector.getSymbol() != null
                ? selector.getSymbol().asType()
                : compilerContext.getTreeUtils().typeOf(selector);

        final TypeElement enumElement = selectorType != null
                && selectorType.isReferenceType()
                && selectorType.asElement() instanceof TypeElement typeElement
                && typeElement.getKind() == ElementKind.ENUM
                ? typeElement
                : null;

        if (enumElement != null) {
            selector = enumOrdinalSelector(selector, enumElement);
        } else if (selectorType != null && types.isBoxType(selectorType)) {
            final var primitiveType = types.unboxedType(selectorType);
            selector = primitiveType.accept(boxer, selector);
        }

        final List<String> enumConstants = enumElement != null
                ? ElementFilter.enumValuesIn(enumElement.getEnclosedElements()).stream()
                .map(Element::getSimpleName)
                .toList()
                : List.of();

        final var loweredSelector = selector;
        final var cases = switchExpression.getCases().stream()
                .map(caseStatement -> (CaseStatement) acceptTree(caseStatement, scope))
                .map(caseStatement -> enumConstants.isEmpty()
                        ? caseStatement
                        : toOrdinalCase(caseStatement, enumConstants))
                .toList();

        return switchExpression.builder()
                .selector(loweredSelector)
                .cases(cases)
                .build();
    }

    /**
     * Maakt van {@code selector} (een enum-expressie) de
     * {@code selector.ordinal()}-aanroep voor de switch-dispatch.
     */
    private ExpressionTree enumOrdinalSelector(final ExpressionTree selector,
                                               final TypeElement enumElement) {
        final var ordinal = IdentifierTree.create("ordinal");
        final var methodInvoke = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(selector, ordinal),
                List.of(),
                List.of(),
                -1,
                -1
        );
        methodInvoke.setMethodType(ordinalMethod(enumElement));
        methodInvoke.setType(types.getPrimitiveType(TypeKind.INT));
        return methodInvoke;
    }

    /**
     * {@code switch (e) { case A: ... }} wordt {@code switch (e.ordinal()) {
     * case 0: ... }}. De hele wereld (ook de enum) wordt samen gecompileerd,
     * dus de ordinals liggen vast en de {@code $SwitchMap}-indirectie van
     * javac (voor losse compilatie) is niet nodig. Die bestond hier alleen
     * half: de map werd nooit gevuld en de case-labels bleven enum-namen,
     * waardoor geen enkele case matchte (picocli's
     * {@code TextTable.putValue} gooide "IllegalStateException: WRAP").
     */
    private SwitchStatement visitEnumSwitchStatement(final SwitchStatement switchStatement,
                                                     final LowerScope scope,
                                                     final TypeElement enumElement) {
        final var selector = enumOrdinalSelector(
                (ExpressionTree) acceptTree(switchStatement.getSelector(), scope),
                enumElement);

        final var constants = ElementFilter.enumValuesIn(enumElement.getEnclosedElements()).stream()
                .map(Element::getSimpleName)
                .toList();

        final var cases = switchStatement.getCases().stream()
                .map(caseStatement -> (CaseStatement) acceptTree(caseStatement, scope))
                .map(caseStatement -> toOrdinalCase(caseStatement, constants))
                .toList();

        return switchStatement.builder()
                .selector(selector)
                .cases(cases)
                .build();
    }

    private CaseStatement toOrdinalCase(final CaseStatement caseStatement, final List<String> constants) {
        final var labels = caseStatement.getLabels().stream()
                .map(label -> {
                    if (label instanceof ConstantCaseLabel constantLabel
                            && enumConstantName(constantLabel.getExpression()) instanceof String name
                            && constants.contains(name)) {
                        final var literal = TreeMaker.literalExpressionTree(
                                constants.indexOf(name), label.getLineNumber(), label.getColumnNumber());
                        literal.setType(types.getPrimitiveType(TypeKind.INT));
                        return (CaseLabel) ConstantCaseLabel.create(literal,
                                label.getLineNumber(), label.getColumnNumber());
                    }
                    return label;
                })
                .toList();
        return CaseStatement.create(caseStatement.getCaseKind(), labels, caseStatement.getBody(),
                caseStatement.getLineNumber(), caseStatement.getColumnNumber());
    }

    /** De naam van een enum-constante in een case-label ({@code WRAP} of {@code Overflow.WRAP}). */
    private static String enumConstantName(final ExpressionTree expression) {
        if (expression instanceof IdentifierTree identifier) {
            return identifier.getName();
        }
        if (expression instanceof FieldAccessExpressionTree fieldAccess) {
            return fieldAccess.getField().getName();
        }
        return null;
    }

    private ExecutableType ordinalMethod(final TypeElement usedEnumClass) {
        final var enumClass = usedEnumClass.getSuperclass().asTypeElement();
        final var ordinalMethod = ElementFilter.methodsIn(enumClass.getEnclosedElements()).stream()
                .filter(it -> "ordinal".equals(it.getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Failed to resolve ordinal method"));

        return (ExecutableType) ordinalMethod.asType();
    }

    @Override
    public Tree visitFunction(final Function function,
                              final LowerScope scope) {
        return super.visitFunction(function, scope);
    }

    @Override
    public Tree visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                 final LowerScope scope) {
        if (variableDeclaratorStatement.getVariableType() == null
                || variableDeclaratorStatement.getVariableType().getType() == null) {
            // Impliciet getypeerde lambda-parameter (`(a, b) -> ...`): geen
            // waarde en geen type om naar te casten.
            return variableDeclaratorStatement;
        }
        var newValue = (ExpressionTree) accept(variableDeclaratorStatement.getValue(), scope);
        // Een kale array-initializer (`int[] x = { 1, 2 };`) heeft geen
        // elementtype in de bron. Zonder type zou de IR-gen VOID-arrays
        // alloceren; de gedeclareerde variabele-type is hier het gezag.
        if (newValue instanceof NewArrayExpression newArrayExpression
                && newArrayExpression.getType() == null
                && variableDeclaratorStatement.getVariableType().getType() instanceof ArrayType) {
            newArrayExpression.setType(variableDeclaratorStatement.getVariableType().getType());
        }
        //Add cast if needed.
        newValue = variableDeclaratorStatement
                .getVariableType()
                .getType()
                .accept(caster, newValue);
        return variableDeclaratorStatement.builder()
                .value(newValue)
                .build();
    }

    @Override
    public Tree visitMethodInvocation(final MethodInvocationTree methodInvocation,
                                      final LowerScope scope) {
        final String methodName;

        if (methodInvocation.getMethodSelector() instanceof IdentifierTree
                identifierTree) {
            methodName = identifierTree.getName();
        } else if (methodInvocation.getMethodSelector() instanceof FieldAccessExpressionTree fieldAccessExpressionTree) {
            methodName = fieldAccessExpressionTree.getField().getName();
        } else {
            methodName = "";
        }

        final var methodType = methodInvocation.getMethodType();
        final var arguments = methodInvocation.getArguments();

        if (methodType == null || methodType.getParameterTypes() == null) {
            return methodInvocation;
        }

        final var paramTypes = methodType.getParameterTypes();
        final var newArguments = new ArrayList<ExpressionTree>();
        var argChanged = false;

        for (var i = 0; i < paramTypes.size(); i++) {
            if (i >= arguments.size()) {
                break;
            }
            final var paramType = paramTypes.get(i);
            final var argument = arguments.get(i);
            final var newArgument = paramType.accept(boxer, argument);

            if (newArgument != argument) {
                argChanged = true;
            }
            newArguments.add(newArgument);
        }

        if (argChanged) {
            return methodInvocation.builder()
                    .arguments(newArguments)
                    .build();
        } else {
            return methodInvocation;
        }
    }

    public static class LowerScope implements Scope {

        private final CompilationUnit compilationUnit;
        private ModuleElement moduleElement;
        private ClassDeclaration currentClassDeclaration;
        private TypeElement currentClass;

        LowerScope(final CompilationUnit compilationUnit) {
            this.compilationUnit = compilationUnit;
        }

        @Override
        public CompilationUnit getCompilationUnit() {
            return compilationUnit;
        }

        @Override
        public ModuleElement findModuleElement() {
            return moduleElement;
        }

        public void setModuleElement(final ModuleElement moduleElement) {
            this.moduleElement = moduleElement;
        }

        public ClassDeclaration getCurrentClassDeclaration() {
            return currentClassDeclaration;
        }

        public void setCurrentClassDeclaration(final ClassDeclaration currentClassDeclaration) {
            this.currentClassDeclaration = currentClassDeclaration;
        }

        @Override
        public TypeElement getCurrentClass() {
            if (currentClass == null && currentClassDeclaration != null) {
                return currentClassDeclaration.getClassSymbol();
            }

            return currentClass;
        }

        public void setCurrentClass(final TypeElement currentClass) {
            this.currentClass = currentClass;
        }

        @Override
        public void define(final Element element) {
        }
    }

}

