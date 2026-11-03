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
import io.github.potjerodekool.nabu.tree.expression.builder.SwitchExpressionTreeBuilder;
import io.github.potjerodekool.nabu.tree.CaseLabel;
import io.github.potjerodekool.nabu.tree.ConstantCaseLabel;
import io.github.potjerodekool.nabu.tree.DefaultCaseLabel;
import io.github.potjerodekool.nabu.tree.Pattern;
import io.github.potjerodekool.nabu.tree.PatternCaseLabel;
import io.github.potjerodekool.nabu.tree.RecordPattern;
import io.github.potjerodekool.nabu.tree.TypePattern;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.InstanceOfExpression;
import io.github.potjerodekool.nabu.tree.expression.LiteralExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.IfStatementTree;
import io.github.potjerodekool.nabu.lang.model.element.ElementFilter;
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

        final var bindingName = symbol != null ? bindingNames.get(symbol) : null;
        if (bindingName != null) {
            final var renamed = IdentifierTree.create(bindingName);
            renamed.setSymbol(symbol);
            renamed.setType(identifier.getType());
            return renamed;
        }

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

    /** The body of a case is lowered too, not only its labels. */
    @Override
    public Tree visitCaseStatement(final CaseStatement caseStatement,
                                   final LowerScope scope) {
        final var body = caseStatement.getBody();
        if (body == null) {
            return caseStatement;
        }
        final var newBody = acceptTree(body, scope);
        if (newBody == null || newBody == body) {
            return caseStatement;
        }
        return CaseStatement.create(caseStatement.getCaseKind(), caseStatement.getLabels(), newBody,
                caseStatement.getLineNumber(), caseStatement.getColumnNumber());
    }

    @Override
    public Tree visitSwitchExpression(final SwitchExpressionTree switchExpression,
                                      final LowerScope scope) {
        if (isPatternSwitch(switchExpression.getCases())) {
            return lowerPatternSwitchInPlace(switchExpression, scope);
        }
        return super.visitSwitchExpression(switchExpression, scope);
    }

    /**
     * A switch expression with patterns where an expression can be: the statements that find the matching case
     * move into the body of a switch expression that stays in place, so the operands around it are evaluated in the
     * same order and short-circuiting is kept:
     * <pre>
     * switch (0) { default -> { &lt;statements that set the index&gt; yield switch (index) { ... }; } }
     * </pre>
     */
    private ExpressionTree lowerPatternSwitchInPlace(final SwitchExpressionTree switchExpression,
                                                     final LowerScope scope) {
        final int line = switchExpression.getLineNumber();
        final int column = switchExpression.getColumnNumber();
        final var selector = (ExpressionTree) acceptTree(switchExpression.getSelector(), scope);
        final var parts = lowerPatternSwitch(selector, switchExpression.getCases(), true, scope, line, column);
        final var inner = switchExpression.builder()
                .selector(parts.index())
                .cases(parts.cases())
                .type(switchExpression.getType())
                .build();
        final var statements = new ArrayList<>(parts.statements());
        statements.add(YieldStatement.create(inner, line, column));
        final var body = CaseStatement.create(CaseStatement.CaseKind.RULE,
                List.of(DefaultCaseLabel.create(line, column)),
                TreeMaker.blockStatement(statements, line, column), line, column);
        return switchExpression.builder()
                .selector(intLiteral(0, line, column))
                .cases(List.of(body))
                .type(switchExpression.getType())
                .build();
    }

    @Override
    public Tree visitInstanceOfExpression(final InstanceOfExpression instanceOfExpression,
                                          final LowerScope scope) {
        if (instanceOfExpression.getTypeExpression() instanceof RecordPattern) {
            throw new IllegalStateException("a record pattern in instanceof is not supported in the "
                    + "initializer of a field, line " + instanceOfExpression.getLineNumber());
        }
        return super.visitInstanceOfExpression(instanceOfExpression, scope);
    }

    /**
     * The statements of a lowered expression and what is left of it. The declarations come first and stay in
     * scope for the statements after the expression: the bindings of a pattern.
     */
    private record Lowered(List<StatementTree> decls, List<StatementTree> stmts, ExpressionTree value) {
        List<StatementTree> statements() {
            final var all = new ArrayList<>(decls);
            all.addAll(stmts);
            return all;
        }
    }

    /** A local variable that the lowering adds. */
    private final class Temp {
        private final String name;
        private final VariableSymbol symbol;
        private final TypeMirror type;

        private Temp(final TypeMirror type,
                     final List<StatementTree> decls,
                     final int line,
                     final int column) {
            this.name = generateVariableName();
            this.type = type;
            this.symbol = new VariableSymbolBuilderImpl()
                    .kind(ElementKind.LOCAL_VARIABLE).simpleName(name).type(type).build();
            decls.add(TreeMaker.variableDeclarator(Kind.LOCAL_VARIABLE, new Modifiers(),
                    createIdentifierFromType(type), createIdentifier(name, symbol), null,
                    defaultInitializer(type, line, column), line, column));
        }

        ExpressionTree ref() {
            final var identifier = createIdentifier(name, symbol);
            identifier.setType(type);
            return identifier;
        }

        StatementTree assign(final ExpressionTree value,
                             final int line,
                             final int column) {
            return TreeMaker.expressionStatement(TreeMaker.assignmentExpression(ref(), value, line, column),
                    line, column);
        }
    }

    /** Is there a record pattern in this expression, in a place that {@link #lowerExpression} follows? */
    private static boolean containsRecordPattern(final ExpressionTree expression) {
        final var e = withoutParentheses(expression);
        if (e instanceof InstanceOfExpression instanceOf) {
            return instanceOf.getTypeExpression() instanceof RecordPattern;
        } else if (e instanceof BinaryExpressionTree binary
                && (binary.getTag() == Tag.AND || binary.getTag() == Tag.OR)) {
            return containsRecordPattern(binary.getLeft()) || containsRecordPattern(binary.getRight());
        } else if (e instanceof UnaryExpressionTree unary && unary.getTag() == Tag.NOT) {
            return containsRecordPattern(unary.getExpression());
        } else if (e instanceof ConditionalExpressionTree conditional) {
            return containsRecordPattern(conditional.getCondition())
                    || containsRecordPattern(conditional.getTrueExpression())
                    || containsRecordPattern(conditional.getFalseExpression());
        } else if (e instanceof SwitchExpressionTree switchExpression) {
            return isPatternSwitch(switchExpression.getCases());
        }
        return false;
    }

    /**
     * A record pattern is lowered to statements, because the optimizer does not follow assignments inside a
     * condition. The operands of {@code &&}, {@code ||}, {@code !} and {@code ?:} that hold a pattern are
     * evaluated by statements in the same order and with the same short-circuiting. For example
     * {@code a && x instanceof R(int p, S(var q)) && rest} becomes
     * <pre>
     * R t; int p; S s; var q; boolean m = false;
     * if (a) { t = x; if (t instanceof R) { p = ((R) t).p(); s = ((R) t).s(); q = s.q(); m = true; } }
     * </pre>
     * and the condition {@code m && rest}. {@code a || x instanceof R(var p)} becomes
     * {@code m = a; if (!m) { ... m = matched; }}, and {@code c ? x : y} with a pattern in it an if statement
     * that assigns one result variable.
     */
    private Lowered lowerExpression(final ExpressionTree expression,
                                    final LowerScope scope,
                                    final int line,
                                    final int column) {
        final var e = withoutParentheses(expression);
        final var booleanType = types.getPrimitiveType(TypeKind.BOOLEAN);

        if (!containsRecordPattern(e)) {
            return new Lowered(List.of(), List.of(), (ExpressionTree) acceptTree(e, scope));
        }

        if (e instanceof InstanceOfExpression instanceOf) {
            return lowerRecordPattern(instanceOf, scope, line, column);
        }

        if (e instanceof SwitchExpressionTree switchExpression) {
            final var selector = (ExpressionTree) acceptTree(switchExpression.getSelector(), scope);
            final var parts = lowerPatternSwitch(selector, switchExpression.getCases(), true, scope, line, column);
            final var lowered = switchExpression.builder()
                    .selector(parts.index())
                    .cases(parts.cases())
                    .type(switchExpression.getType())
                    .build();
            return new Lowered(List.of(), parts.statements(), lowered);
        }

        if (e instanceof UnaryExpressionTree unary) {
            final var operand = lowerExpression(unary.getExpression(), scope, line, column);
            final var negated = TreeMaker.unaryExpressionTree(Tag.NOT, operand.value(), line, column);
            negated.setType(booleanType);
            return new Lowered(operand.decls(), operand.stmts(), negated);
        }

        if (e instanceof BinaryExpressionTree binary) {
            final var left = lowerExpression(binary.getLeft(), scope, line, column);
            final var right = lowerExpression(binary.getRight(), scope, line, column);
            final var decls = new ArrayList<>(left.decls());
            decls.addAll(right.decls());
            final var stmts = new ArrayList<>(left.stmts());

            if (right.stmts().isEmpty()) {
                final var value = TreeMaker.binaryExpressionTree(left.value(), binary.getTag(), right.value(),
                        line, column);
                value.setType(booleanType);
                return new Lowered(decls, stmts, value);
            }

            final var result = new Temp(booleanType, decls, line, column);
            final var rightStatements = new ArrayList<>(right.stmts());
            rightStatements.add(result.assign(right.value(), line, column));
            final var rightBlock = TreeMaker.blockStatement(rightStatements, line, column);
            if (binary.getTag() == Tag.AND) {
                stmts.add(TreeMaker.ifStatementTree(left.value(), rightBlock, null, line, column));
            } else {
                stmts.add(result.assign(left.value(), line, column));
                final var notYet = TreeMaker.unaryExpressionTree(Tag.NOT, result.ref(), line, column);
                notYet.setType(booleanType);
                stmts.add(TreeMaker.ifStatementTree(notYet, rightBlock, null, line, column));
            }
            return new Lowered(decls, stmts, result.ref());
        }

        final var conditional = (ConditionalExpressionTree) e;
        final var resultType = conditional.getType();
        if (resultType == null || resultType.isError()) {
            throw new IllegalStateException("type of conditional expression not resolved, line "
                    + conditional.getLineNumber());
        }
        final var condition = lowerExpression(conditional.getCondition(), scope, line, column);
        final var whenTrue = lowerExpression(conditional.getTrueExpression(), scope, line, column);
        final var whenFalse = lowerExpression(conditional.getFalseExpression(), scope, line, column);
        final var decls = new ArrayList<>(condition.decls());
        decls.addAll(whenTrue.decls());
        decls.addAll(whenFalse.decls());
        final var trueValue = convertBranch(whenTrue.value(), resultType);
        final var falseValue = convertBranch(whenFalse.value(), resultType);

        final var result = new Temp(resultType, decls, line, column);
        final var trueStatements = new ArrayList<>(whenTrue.stmts());
        trueStatements.add(result.assign(trueValue, line, column));
        final var falseStatements = new ArrayList<>(whenFalse.stmts());
        falseStatements.add(result.assign(falseValue, line, column));
        final var stmts = new ArrayList<>(condition.stmts());
        stmts.add(TreeMaker.ifStatementTree(condition.value(),
                TreeMaker.blockStatement(trueStatements, line, column),
                TreeMaker.blockStatement(falseStatements, line, column), line, column));
        return new Lowered(decls, stmts, result.ref());
    }

    /** {@code x instanceof R(int p, S(var q))}: the match is a boolean variable that the statements set. */
    private Lowered lowerRecordPattern(final InstanceOfExpression instanceOf,
                                       final LowerScope scope,
                                       final int line,
                                       final int column) {
        final var booleanType = types.getPrimitiveType(TypeKind.BOOLEAN);
        final var decls = new ArrayList<StatementTree>();
        final var expression = (ExpressionTree) acceptTree(instanceOf.getExpression(), scope);
        final var expressionType = expression.getType() != null ? expression.getType() : types.getObjectType();
        final var temp = new Temp(expressionType, decls, line, column);
        final var matched = new Temp(booleanType, decls, line, column);

        final var setMatched = TreeMaker.literalExpressionTree(Boolean.TRUE, line, column);
        setMatched.setType(booleanType);
        final var statements = new ArrayList<StatementTree>();
        statements.add(temp.assign(expression, line, column));
        statements.addAll(matchPattern((Pattern) instanceOf.getTypeExpression(), temp::ref, expressionType, true,
                List.of(matched.assign(setMatched, line, column)), decls, scope, line, column));
        return new Lowered(decls, List.of(TreeMaker.blockStatement(statements, line, column)), matched.ref());
    }

    /**
     * Lowers the record patterns of a condition: the statements that come before the condition and the
     * condition itself, or null if there is no record pattern.
     */
    private record LoweredCondition(List<StatementTree> statements, ExpressionTree condition) {
    }

    private LoweredCondition lowerCondition(final ExpressionTree expression,
                                            final LowerScope scope,
                                            final int line,
                                            final int column) {
        if (!containsRecordPattern(expression)) {
            return null;
        }
        final var lowered = lowerExpression(expression, scope, line, column);
        return new LoweredCondition(lowered.statements(), lowered.value());
    }

    /** {@code x = c instanceof R(var a) ? a : 0;} */
    @Override
    public Tree visitExpressionStatement(final ExpressionStatementTree expressionStatement,
                                         final LowerScope scope) {
        final ExpressionTree target;
        final ExpressionTree value;
        if (expressionStatement.getExpression() instanceof AssignmentExpressionTree assignment) {
            target = assignment.getLeft();
            value = assignment.getRight();
        } else if (expressionStatement.getExpression() instanceof BinaryExpressionTree binary
                && binary.getTag() == Tag.ASSIGN) {
            target = binary.getLeft();
            value = binary.getRight();
        } else {
            return super.visitExpressionStatement(expressionStatement, scope);
        }

        if (!containsRecordPattern(value)) {
            return super.visitExpressionStatement(expressionStatement, scope);
        }

        final int line = expressionStatement.getLineNumber();
        final int column = expressionStatement.getColumnNumber();
        final var lowered = lowerExpression(value, scope, line, column);
        final var statements = lowered.statements();
        final var left = (ExpressionTree) acceptTree(target, scope);
        statements.add(TreeMaker.expressionStatement(
                TreeMaker.assignmentExpression(left, lowered.value(), line, column), line, column));
        return TreeMaker.blockStatement(statements, line, column);
    }

    /**
     * The blocks that a lowering made of one statement and whose statements belong in the block around it: the
     * bindings of {@code if (!(x instanceof R(var a))) return; use(a);} are in scope after the if statement.
     */
    private final java.util.Set<BlockStatementTree> spliced =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    @Override
    public Tree visitBlockStatement(final BlockStatementTree blockStatement,
                                    final LowerScope scope) {
        final var newStatements = new ArrayList<StatementTree>();
        for (final var statement : blockStatement.getStatements()) {
            final var lowered = (StatementTree) acceptTree(statement, scope);
            if (lowered instanceof BlockStatementTree block && spliced.contains(block)) {
                newStatements.addAll(block.getStatements());
            } else {
                newStatements.add(lowered);
            }
        }
        return blockStatement.builder()
                .statements(newStatements)
                .build();
    }

    private static ExpressionTree withoutParentheses(final ExpressionTree expression) {
        var result = expression;
        while (result instanceof ParenthesizedExpression parenthesized) {
            result = parenthesized.getExpression();
        }
        return result;
    }

    @Override
    public Tree visitIfStatement(final IfStatementTree ifStatement,
                                 final LowerScope scope) {
        final int line = ifStatement.getLineNumber();
        final int column = ifStatement.getColumnNumber();

        final var lowered = lowerCondition(ifStatement.getExpression(), scope, line, column);
        if (lowered == null) {
            return super.visitIfStatement(ifStatement, scope);
        }

        final var statements = new ArrayList<>(lowered.statements());
        final var thenStatement = (StatementTree) acceptTree(ifStatement.getThenStatement(), scope);
        final var elseStatement = ifStatement.getElseStatement() != null
                ? (StatementTree) acceptTree(ifStatement.getElseStatement(), scope)
                : null;
        statements.add(TreeMaker.ifStatementTree(lowered.condition(), thenStatement, elseStatement, line, column));
        final var block = TreeMaker.blockStatement(statements, line, column);
        if (introducedWhenFalse(ifStatement.getExpression())) {
            spliced.add(block);
        }
        return block;
    }

    /**
     * Does the condition have pattern bindings that are in scope when it is false (JLS 6.3.1)? Then they are also
     * in scope after the if statement, if that cannot complete normally.
     */
    private static boolean introducedWhenFalse(final ExpressionTree condition) {
        final var e = withoutParentheses(condition);
        if (e instanceof UnaryExpressionTree unary && unary.getTag() == Tag.NOT) {
            return introducedWhenTrue(unary.getExpression());
        } else if (e instanceof BinaryExpressionTree binary && binary.getTag() == Tag.OR) {
            return introducedWhenFalse(binary.getLeft()) || introducedWhenFalse(binary.getRight());
        }
        return false;
    }

    private static boolean introducedWhenTrue(final ExpressionTree condition) {
        final var e = withoutParentheses(condition);
        if (e instanceof InstanceOfExpression instanceOf) {
            return instanceOf.getTypeExpression() instanceof RecordPattern;
        } else if (e instanceof UnaryExpressionTree unary && unary.getTag() == Tag.NOT) {
            return introducedWhenFalse(unary.getExpression());
        } else if (e instanceof BinaryExpressionTree binary && binary.getTag() == Tag.AND) {
            return introducedWhenTrue(binary.getLeft()) || introducedWhenTrue(binary.getRight());
        }
        return false;
    }

    /** {@code return a instanceof R(var x) && x > 0;} becomes the statements of the pattern and a return. */
    @Override
    public Tree visitReturnStatement(final ReturnStatementTree returnStatement,
                                     final LowerScope scope) {
        if (returnStatement.getExpression() != null) {
            final int line = returnStatement.getLineNumber();
            final int column = returnStatement.getColumnNumber();
            final var lowered = lowerCondition(returnStatement.getExpression(), scope, line, column);
            if (lowered != null) {
                final var statements = new ArrayList<>(lowered.statements());
                statements.add(TreeMaker.returnStatement(lowered.condition(), line, column));
                return TreeMaker.blockStatement(statements, line, column);
            }
        }
        return super.visitReturnStatement(returnStatement, scope);
    }

    /**
     * {@code while (x instanceof R(var a)) body} becomes
     * {@code while (true) { <statements of the pattern> if (!m) break; body }}.
     */
    @Override
    public Tree visitWhileStatement(final WhileStatementTree whileStatement,
                                    final LowerScope scope) {
        final int line = whileStatement.getLineNumber();
        final int column = whileStatement.getColumnNumber();
        final var lowered = lowerCondition(whileStatement.getCondition(), scope, line, column);
        if (lowered == null) {
            return super.visitWhileStatement(whileStatement, scope);
        }

        final var booleanType = types.getPrimitiveType(TypeKind.BOOLEAN);
        final var statements = new ArrayList<>(lowered.statements());
        final var negated = TreeMaker.unaryExpressionTree(Tag.NOT, lowered.condition(), line, column);
        negated.setType(booleanType);
        statements.add(TreeMaker.ifStatementTree(negated, BreakStatement.create(null, line, column), null, line, column));
        statements.add((StatementTree) acceptTree(whileStatement.getBody(), scope));

        final var always = TreeMaker.literalExpressionTree(Boolean.TRUE, line, column);
        always.setType(booleanType);
        return TreeMaker.whileStatement(always, TreeMaker.blockStatement(statements, line, column), line, column);
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

        if (isPatternSwitch(switchStatement.getCases())) {
            final int line = switchStatement.getLineNumber();
            final int column = switchStatement.getColumnNumber();
            final var parts = lowerPatternSwitch(selector, switchStatement.getCases(), false, scope, line, column);
            final var statements = new ArrayList<>(parts.statements());
            statements.add(switchStatement.builder().selector(parts.index()).cases(parts.cases()).build());
            return TreeMaker.blockStatement(statements, line, column);
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

    private static final int PATTERN_DEFAULT_ORDINAL = -1;

    /**
     * The new name of a pattern variable. All the variables of the patterns of a switch are declared in the method,
     * so two with the same name (<code>case Square(int s)</code> and <code>case Square s</code>) get different names.
     */
    private final Map<Object, String> bindingNames = new IdentityHashMap<>();

    private void renameBindings(final List<CaseStatement> cases) {
        final var used = new HashSet<String>();
        for (final var caseStatement : cases) {
            for (final var label : caseStatement.getLabels()) {
                if (label instanceof PatternCaseLabel patternCaseLabel) {
                    renameBindings(patternCaseLabel.getPattern(), used);
                }
            }
        }
    }

    private void renameBindings(final Pattern pattern,
                                final Set<String> used) {
        if (pattern instanceof TypePattern typePattern) {
            final var name = typePattern.getVariableDeclarator().getName();
            if (!used.add(name.getName()) && name.getSymbol() != null) {
                final var newName = name.getName() + "$" + generateVariableName();
                bindingNames.put(name.getSymbol(), newName);
            }
        } else if (pattern instanceof RecordPattern recordPattern) {
            recordPattern.getPatterns().forEach(nested -> renameBindings(nested, used));
        }
    }

    private boolean isNullConstantLabel(final CaseLabel label) {
        if (label instanceof ConstantCaseLabel constantCaseLabel) {
            final var expression = constantCaseLabel.getExpression();
            if (expression instanceof LiteralExpressionTree literalExpressionTree) {
                return literalExpressionTree.getLiteralKind() == LiteralExpressionTree.Kind.NULL
                        || literalExpressionTree.getLiteral() == null;
            }
        }
        return false;
    }

    private ExpressionTree castExpressionTree(final ExpressionTree value,
                                              final TypeMirror type,
                                              final int lineNumber,
                                              final int columnNumber) {
        final var cast = TreeMaker.castExpressionTree(createIdentifierFromType(type), value, lineNumber, columnNumber);
        cast.setType(type);
        return cast;
    }

    /**
     * What the lowering of a switch with patterns leaves: the statements that find the matching case and set the
     * index variable, the index variable, and the cases with that index as label.
     */
    private record PatternSwitchParts(List<StatementTree> statements,
                                      IdentifierTree index,
                                      List<CaseStatement> cases) {
    }

    private static boolean isPatternSwitch(final List<CaseStatement> cases) {
        return cases.stream()
                .flatMap(caseStatement -> caseStatement.getLabels().stream())
                .anyMatch(label -> label instanceof PatternCaseLabel || isNullLabel(label));
    }

    /**
     * A switch with patterns: the selector is stored once, every label gets an if statement that tests it in order
     * (also the guard) and stores the index of the first label that matches, and the switch itself then switches on
     * that index. A label that is not matched leaves the index at -1, the default.
     */
    private PatternSwitchParts lowerPatternSwitch(final ExpressionTree selector,
                                                  final List<CaseStatement> cases,
                                                  final boolean expression,
                                                  final LowerScope scope,
                                                  final int line,
                                                  final int column) {
        renameBindings(cases);
        final var loweredCases = cases.stream()
                .map(caseStatement -> (CaseStatement) acceptTree(caseStatement, scope))
                .toList();

        final var intType = types.getPrimitiveType(TypeKind.INT);
        final var decls = new ArrayList<StatementTree>();
        final var statements = new ArrayList<StatementTree>();

        final var selectorType = selector.getType() != null ? selector.getType() : types.getObjectType();
        final var selected = new Temp(selectorType, decls, line, column);
        statements.add(selected.assign(selector, line, column));

        // A selector that is null throws a NullPointerException, unless there is a case null (JLS 14.11.3).
        if (!(selectorType instanceof PrimitiveType)
                && loweredCases.stream().flatMap(c -> c.getLabels().stream()).noneMatch(Lower::isNullLabel)) {
            statements.add(TreeMaker.expressionStatement(
                    requireNonNullCall(selected.ref(), scope, line, column), line, column));
        }

        final var index = new Temp(intType, decls, line, column);
        statements.add(index.assign(intLiteral(PATTERN_DEFAULT_ORDINAL, line, column), line, column));

        int entryIndex = 0;
        for (final var caseStatement : loweredCases) {
            for (final var label : caseStatement.getLabels()) {
                final var choose = index.assign(intLiteral(entryIndex, line, column), line, column);
                final List<StatementTree> test;
                if (label instanceof DefaultCaseLabel) {
                    continue;
                } else if (isNullLabel(label)) {
                    final var isNull = TreeMaker.binaryExpressionTree(selected.ref(), Tag.EQ,
                            TreeMaker.literalExpressionTree(null, line, column), line, column);
                    isNull.setType(types.getPrimitiveType(TypeKind.BOOLEAN));
                    test = List.of(TreeMaker.ifStatementTree(isNull, choose, null, line, column));
                } else if (label instanceof PatternCaseLabel patternCaseLabel) {
                    final StatementTree onMatch;
                    if (patternCaseLabel.getWhen() != null) {
                        final var guard = (ExpressionTree) acceptTree(patternCaseLabel.getWhen(), scope);
                        onMatch = TreeMaker.ifStatementTree(guard, choose, null, line, column);
                    } else {
                        onMatch = choose;
                    }
                    test = matchPattern(patternCaseLabel.getPattern(), selected::ref, selectorType, true,
                            List.of(onMatch), decls, scope, line, column);
                } else if (label instanceof ConstantCaseLabel constantLabel) {
                    test = List.of(constantTest(constantLabel.getExpression(), selected, selectorType, choose, scope,
                            line, column));
                } else {
                    throw new IllegalStateException("unsupported case label, line " + caseStatement.getLineNumber());
                }
                final var notMatchedYet = TreeMaker.binaryExpressionTree(index.ref(), Tag.EQ,
                        intLiteral(PATTERN_DEFAULT_ORDINAL, line, column), line, column);
                notMatchedYet.setType(types.getPrimitiveType(TypeKind.BOOLEAN));
                statements.add(TreeMaker.ifStatementTree(notMatchedYet,
                        TreeMaker.blockStatement(test, line, column), null, line, column));
                entryIndex++;
            }
        }

        // The labels become the index of their entry.
        final var hasDefault = loweredCases.stream()
                .flatMap(caseStatement -> caseStatement.getLabels().stream())
                .anyMatch(label -> label instanceof DefaultCaseLabel);
        final var rewrittenCases = new ArrayList<CaseStatement>();
        entryIndex = 0;
        for (int i = 0; i < loweredCases.size(); i++) {
            final var caseStatement = loweredCases.get(i);
            final var newLabels = new ArrayList<CaseLabel>();
            for (final var label : caseStatement.getLabels()) {
                if (label instanceof DefaultCaseLabel) {
                    // Nothing matched: the index is -1 and the switch takes its default.
                    newLabels.add(label);
                } else {
                    newLabels.add(ConstantCaseLabel.create(intLiteral(entryIndex++, line, column), line, column));
                }
            }
            // A switch expression without default is exhaustive (a sealed type, JLS 15.28.1): the last case also
            // takes what no label matched, so that the result is always set.
            if (expression && !hasDefault && i == loweredCases.size() - 1) {
                newLabels.add(DefaultCaseLabel.create(line, column));
            }
            rewrittenCases.add(CaseStatement.create(caseStatement.getCaseKind(), newLabels, caseStatement.getBody(),
                    caseStatement.getLineNumber(), caseStatement.getColumnNumber()));
        }

        final var all = new ArrayList<StatementTree>(decls);
        all.addAll(statements);
        return new PatternSwitchParts(all, (IdentifierTree) index.ref(), rewrittenCases);
    }

    /**
     * {@code case "a"}, {@code case 1} and {@code case RED} next to {@code case null} or patterns: the selector is
     * compared with the constant. A selector that is null matches no constant.
     */
    private StatementTree constantTest(final ExpressionTree constant,
                                       final Temp selected,
                                       final TypeMirror selectorType,
                                       final StatementTree choose,
                                       final LowerScope scope,
                                       final int line,
                                       final int column) {
        final var booleanType = types.getPrimitiveType(TypeKind.BOOLEAN);
        final var notNull = TreeMaker.binaryExpressionTree(selected.ref(), Tag.NE,
                TreeMaker.literalExpressionTree(null, line, column), line, column);
        notNull.setType(booleanType);

        final var module = scope.findModuleElement();
        final var stringType = loader.loadClass(module, Constants.STRING).asType();
        final ExpressionTree equal;
        if (types.isSameType(selectorType, stringType)) {
            return TreeMaker.ifStatementTree(
                    objectCall(constant, "equals", List.of(selected.ref()), scope, line, column),
                    choose, null, line, column);
        } else if (selectorType.asElement() instanceof TypeElement enumElement
                && enumElement.getKind() == ElementKind.ENUM) {
            final var constants = ElementFilter.enumValuesIn(enumElement.getEnclosedElements()).stream()
                    .map(Element::getSimpleName)
                    .toList();
            final var name = enumConstantName(constant);
            if (name == null || !constants.contains(name)) {
                throw new IllegalStateException("unknown enum constant label, line " + line);
            }
            equal = TreeMaker.binaryExpressionTree(enumOrdinalSelector(selected.ref(), enumElement), Tag.EQ,
                    intLiteral(constants.indexOf(name), line, column), line, column);
        } else if (constant.getType() != null
                && constant.getType().asElement() instanceof TypeElement constantEnum
                && constantEnum.getKind() == ElementKind.ENUM) {
            // case Suit.CLUBS for a selector of a sealed interface that the enum implements.
            equal = TreeMaker.binaryExpressionTree(selected.ref(), Tag.EQ,
                    (ExpressionTree) acceptTree(constant, scope), line, column);
            equal.setType(booleanType);
            return TreeMaker.ifStatementTree(equal, choose, null, line, column);
        } else if (types.isBoxType(selectorType)) {
            final var value = (ExpressionTree) acceptTree(constant, scope);
            equal = TreeMaker.binaryExpressionTree(unboxIfNeeded(selected.ref(), value), Tag.EQ, value, line, column);
        } else {
            throw new IllegalStateException("a constant label for a selector of this type is not supported, line "
                    + line);
        }
        equal.setType(booleanType);
        return TreeMaker.ifStatementTree(notNull,
                TreeMaker.ifStatementTree(equal, choose, null, line, column), null, line, column);
    }

    /** {@code java.util.Objects.requireNonNull(value)}: the NullPointerException of a null selector. */
    private ExpressionTree requireNonNullCall(final ExpressionTree value,
                                              final LowerScope scope,
                                              final int line,
                                              final int column) {
        final var className = "java.util.Objects";
        final var target = IdentifierTree.create(className);
        target.setSymbol(loader.loadClass(scope.findModuleElement(), className));
        final var call = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(target, IdentifierTree.create("requireNonNull")),
                List.of(), List.of(value), line, column);
        final var resolved = methodResolver.resolveMethod(call, scope)
                .orElseThrow(() -> new IllegalStateException("Objects.requireNonNull not found"));
        call.getMethodSelector().setType(resolved.getOwner().asType());
        call.setMethodType(resolved);
        call.setType(resolved.getReturnType());
        return call;
    }

    /** A call of a method of java.lang.Object (or the override in the type of the receiver). */
    private ExpressionTree objectCall(final ExpressionTree receiver,
                                      final String name,
                                      final List<ExpressionTree> arguments,
                                      final LowerScope scope,
                                      final int line,
                                      final int column) {
        final var objectElement = types.getObjectType().asTypeElement();
        final var method = ElementFilter.methodsIn(objectElement.getEnclosedElements()).stream()
                .filter(m -> m.getSimpleName().contentEquals(name) && m.getParameters().size() == arguments.size())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("method of Object not found: " + name));
        final var methodType = (ExecutableType) method.asType();
        final var call = TreeMaker.methodInvocationTree(
                new CFieldAccessExpressionTree(receiver, IdentifierTree.create(name)),
                List.of(), arguments, line, column);
        call.getMethodSelector().setType(objectElement.asType());
        call.setMethodType(methodType);
        call.setType(methodType.getReturnType());
        try {
            methodResolver.resolveMethod(call, scope).ifPresent(resolved -> {
                call.getMethodSelector().setType(resolved.getOwner().asType());
                call.setMethodType(resolved);
                call.setType(resolved.getReturnType());
            });
        } catch (final Exception ignored) {
            // The call keeps the method of Object.
        }
        return call;
    }

    private LiteralExpressionTree intLiteral(final int value,
                                             final int line,
                                             final int column) {
        final var literal = TreeMaker.literalExpressionTree(value, line, column);
        literal.setType(types.getPrimitiveType(TypeKind.INT));
        return literal;
    }

    private static boolean isNullLabel(final CaseLabel label) {
        return label instanceof ConstantCaseLabel constantCaseLabel
                && constantCaseLabel.getExpression() instanceof LiteralExpressionTree literal
                && (literal.getLiteralKind() == LiteralExpressionTree.Kind.NULL || literal.getLiteral() == null);
    }

    /**
     * The statements that run {@code onMatch} if the value matches the pattern, and bind the variables of the
     * pattern before that. The variables and the temporaries are declared in {@code decls}. A record pattern
     * tests the type, reads the components with the accessors and matches them with the nested patterns, in
     * order. A nested pattern whose type is the type of the component matches always, also null.
     *
     * @param value Gives the expression of the value, it is called for every use.
     * @param mustTest Is true if the type of a type pattern is tested even if the value has that type: null does not
     * match the pattern of a switch or an instanceof.
     */
    private List<StatementTree> matchPattern(final Pattern pattern,
                                             final java.util.function.Supplier<ExpressionTree> value,
                                             final TypeMirror valueType,
                                             final boolean mustTest,
                                             final List<StatementTree> onMatch,
                                             final List<StatementTree> decls,
                                             final LowerScope scope,
                                             final int line,
                                             final int column) {
        if (pattern instanceof TypePattern typePattern) {
            final var declarator = typePattern.getVariableDeclarator();
            if (declarator == null) {
                throw new IllegalStateException("pattern without declarator, line " + line);
            }
            // 'var x' and '_': the type of the binding is the type of the value.
            final boolean inferred = declarator.getVariableType() == null
                    || declarator.getVariableType() instanceof VariableTypeTree
                    || declarator.getVariableType().getType() == null
                    || declarator.getVariableType().getType().getKind() == TypeKind.ERROR;
            final var bindingType = inferred ? valueType : declarator.getVariableType().getType();
            final var declaratorBuilder = declarator.builder()
                    .value(defaultInitializer(bindingType, line, column));
            final var newName = declarator.getName().getSymbol() != null
                    ? bindingNames.get(declarator.getName().getSymbol())
                    : null;
            if (newName != null) {
                final var renamed = IdentifierTree.create(newName);
                renamed.setSymbol(declarator.getName().getSymbol());
                declaratorBuilder.name(renamed);
            }
            if (inferred) {
                declaratorBuilder.variableType(createIdentifierFromType(bindingType));
            }
            decls.add(declaratorBuilder.build());

            final var bindingIdent = IdentifierTree.create(newName != null
                    ? newName
                    : declarator.getName().getName());
            if (declarator.getName().getSymbol() != null) {
                bindingIdent.setSymbol(declarator.getName().getSymbol());
            }
            ExpressionTree bound = value.get();
            if (!types.isSameType(bindingType, valueType)) {
                bound = castExpressionTree(bound, bindingType, line, column);
                bound.setType(bindingType);
            }
            final var statements = new ArrayList<StatementTree>();
            statements.add(TreeMaker.expressionStatement(
                    TreeMaker.assignmentExpression(bindingIdent, bound, line, column), line, column));
            statements.addAll(onMatch);

            final boolean always = !mustTest
                    && (inferred
                    || valueType instanceof PrimitiveType
                    || types.isSubType(types.erasure(valueType), types.erasure(bindingType)));
            if (always) {
                return statements;
            }
            if (bindingType instanceof PrimitiveType) {
                throw new IllegalStateException("primitive type patterns are not supported, line " + line);
            }
            final var test = TreeMaker.instanceOfExpression(value.get(), createIdentifierFromType(bindingType),
                    line, column);
            test.setType(types.getPrimitiveType(TypeKind.BOOLEAN));
            return List.of(TreeMaker.ifStatementTree(test,
                    TreeMaker.blockStatement(statements, line, column), null, line, column));
        }

        if (!(pattern instanceof RecordPattern recordPattern)) {
            throw new IllegalStateException("unsupported pattern, line " + line);
        }

        final TypeMirror recordType = recordPattern.getRecordType().getType() != null
                ? recordPattern.getRecordType().getType()
                : (recordPattern.getRecordType() instanceof IdentifierTree it && it.getType() != null ? it.getType() : null);
        final TypeElement recordElement = recordType instanceof DeclaredType declaredType
                ? declaredType.asTypeElement()
                : null;
        if (recordElement == null) {
            throw new IllegalStateException("record pattern type not resolved, line " + line);
        }
        // The record components are RECORD_COMPONENT variable symbols, not RecordComponentElements.
        final var components = ElementFilter.elements(recordElement,
                element -> element.getKind() == ElementKind.RECORD_COMPONENT,
                VariableElement.class);
        if (recordPattern.getPatterns().size() != components.size()) {
            throw new IllegalStateException("record pattern does not match the record, line " + line);
        }

        final var record = new Temp(recordType, decls, line, column);
        final var steps = new ArrayList<StatementTree>();
        steps.add(record.assign(castExpressionTree(value.get(), recordType, line, column), line, column));

        final var componentTemps = new ArrayList<Temp>();
        for (final var component : components) {
            final var accessorName = component.getSimpleName().toString();
            final var accessor = ElementFilter.methodsIn(recordElement.getEnclosedElements()).stream()
                    .filter(m -> m.getSimpleName().contentEquals(accessorName))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("record accessor not found: " + accessorName));
            final var accessorType = recordType instanceof DeclaredType dt
                    ? (ExecutableType) types.asMemberOf(dt, accessor)
                    : (ExecutableType) accessor.asType();
            final var call = TreeMaker.methodInvocationTree(
                    new CFieldAccessExpressionTree(record.ref(), IdentifierTree.create(accessorName)),
                    List.of(), List.of(), line, column);
            call.getMethodSelector().setType(recordElement.asType());
            call.setMethodType(accessorType);
            call.setType(accessorType.getReturnType());
            try {
                methodResolver.resolveMethod(call, scope).ifPresent(resolved -> {
                    call.getMethodSelector().setType(resolved.getOwner().asType());
                    call.setMethodType(resolved);
                    call.setType(resolved.getReturnType());
                });
            } catch (final Exception ignored) {
                // The call keeps the type of the accessor.
            }
            final var componentType = accessorType.getReturnType() != null
                    ? accessorType.getReturnType()
                    : types.getObjectType();
            final var temp = new Temp(componentType, decls, line, column);
            steps.add(temp.assign(call, line, column));
            componentTemps.add(temp);
        }

        var body = onMatch;
        for (int i = components.size() - 1; i >= 0; i--) {
            final var temp = componentTemps.get(i);
            body = matchPattern(recordPattern.getPatterns().get(i), temp::ref, temp.type, false, body, decls, scope,
                    line, column);
        }
        steps.addAll(body);

        final var test = TreeMaker.instanceOfExpression(value.get(), createIdentifierFromType(recordType),
                line, column);
        test.setType(types.getPrimitiveType(TypeKind.BOOLEAN));
        return List.of(TreeMaker.ifStatementTree(test,
                TreeMaker.blockStatement(steps, line, column), null, line, column));
    }

    private ExpressionTree defaultInitializer(final TypeMirror type,
                                              final int lineNumber,
                                              final int columnNumber) {
        final Object value;
        if (type instanceof PrimitiveType primitiveType) {
            value = switch (primitiveType.getKind()) {
                case BOOLEAN -> Boolean.FALSE;
                case LONG -> 0L;
                case FLOAT -> 0f;
                case DOUBLE -> 0d;
                case CHAR -> '\0';
                default -> 0;
            };
        } else {
            value = null;
        }
        final var lit = TreeMaker.literalExpressionTree(value, lineNumber, columnNumber);
        lit.setType(type);
        return lit;
    }

    @Override
    public Tree visitFunction(final Function function,
                              final LowerScope scope) {
        final var body = function.getBody();
        final var newBody = patternSwitchRewriter(scope).rewriteBody(body);
        if (newBody != body) {
            return super.visitFunction(function.builder().body(newBody).build(), scope);
        }
        return super.visitFunction(function, scope);
    }

    /** Replaces the switch expressions with patterns, wherever they are in the tree. */
    private PatternSwitchRewriter patternSwitchRewriter(final LowerScope scope) {
        return new PatternSwitchRewriter(
                switchExpression -> isPatternSwitch(switchExpression.getCases()),
                switchExpression -> lowerPatternSwitchInPlace(switchExpression, scope),
                (instanceOf, hoisted) -> lowerRecordPatternInPlace(instanceOf, hoisted, scope));
    }

    /**
     * {@code x instanceof R(var a)} where an expression can be: the statements that match run in the body of a
     * switch expression that yields whether it matched. The variables are declared in {@code hoisted}, before the
     * statement that holds the expression, or, if that is null, in the body.
     */
    private ExpressionTree lowerRecordPatternInPlace(final InstanceOfExpression instanceOf,
                                                     final List<StatementTree> hoisted,
                                                     final LowerScope scope) {
        final int line = instanceOf.getLineNumber();
        final int column = instanceOf.getColumnNumber();
        final var lowered = lowerRecordPattern(instanceOf, scope, line, column);
        final var statements = new ArrayList<StatementTree>();
        if (hoisted != null) {
            hoisted.addAll(lowered.decls());
        } else {
            statements.addAll(lowered.decls());
        }
        // The variable is declared once, before the statement: it must not stay true from an earlier evaluation.
        final var notMatched = TreeMaker.literalExpressionTree(Boolean.FALSE, line, column);
        notMatched.setType(types.getPrimitiveType(TypeKind.BOOLEAN));
        statements.add(TreeMaker.expressionStatement(
                TreeMaker.assignmentExpression(lowered.value(), notMatched, line, column), line, column));
        statements.addAll(lowered.stmts());
        statements.add(YieldStatement.create(lowered.value(), line, column));
        final var body = CaseStatement.create(CaseStatement.CaseKind.RULE,
                List.of(DefaultCaseLabel.create(line, column)),
                TreeMaker.blockStatement(statements, line, column), line, column);
        final var result = new SwitchExpressionTreeBuilder()
                .selector(intLiteral(0, line, column))
                .cases(List.of(body))
                .build();
        result.setType(types.getPrimitiveType(TypeKind.BOOLEAN));
        return result;
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
        final var initializer = variableDeclaratorStatement.getValue() instanceof ExpressionTree value
                ? patternSwitchRewriter(scope).rewriteExpression(value)
                : (ExpressionTree) variableDeclaratorStatement.getValue();
        final var loweredInitializer = variableDeclaratorStatement.getKind() == Kind.LOCAL_VARIABLE
                && initializer != null
                && containsRecordPattern(initializer)
                ? lowerExpression(initializer, scope, variableDeclaratorStatement.getLineNumber(),
                        variableDeclaratorStatement.getColumnNumber())
                : null;
        ExpressionTree newValue;
        if (loweredInitializer != null) {
            newValue = loweredInitializer.value();
        } else {
            newValue = (ExpressionTree) accept(initializer, scope);
        }
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
        final var declarator = variableDeclaratorStatement.builder()
                .value(newValue)
                .build();
        if (loweredInitializer == null) {
            return declarator;
        }
        // The variable stays in scope after the statements of the pattern.
        final var statements = loweredInitializer.statements();
        statements.add(declarator);
        final var block = TreeMaker.blockStatement(statements, variableDeclaratorStatement.getLineNumber(),
                variableDeclaratorStatement.getColumnNumber());
        spliced.add(block);
        return block;
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

