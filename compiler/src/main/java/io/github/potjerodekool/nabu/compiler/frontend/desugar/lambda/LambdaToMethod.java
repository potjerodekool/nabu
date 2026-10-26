package io.github.potjerodekool.nabu.compiler.frontend.desugar.lambda;

import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.VariableSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.builder.impl.MethodSymbolBuilderImpl;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.ClassSymbol;
import io.github.potjerodekool.nabu.compiler.ast.symbol.impl.MethodSymbol;
import io.github.potjerodekool.nabu.lang.Flags;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.TypeElement;
import io.github.potjerodekool.nabu.tree.AbstractTreeVisitor;
import io.github.potjerodekool.nabu.tree.Modifiers;
import io.github.potjerodekool.nabu.tree.Tree;
import io.github.potjerodekool.nabu.tree.TreeMaker;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.element.Kind;
import io.github.potjerodekool.nabu.tree.element.builder.FunctionBuilder;
import io.github.potjerodekool.nabu.tree.expression.ExpressionTree;
import io.github.potjerodekool.nabu.tree.expression.IdentifierTree;
import io.github.potjerodekool.nabu.tree.expression.LambdaExpressionTree;
import io.github.potjerodekool.nabu.tree.statement.BlockStatementTree;
import io.github.potjerodekool.nabu.tree.statement.StatementTree;
import io.github.potjerodekool.nabu.tree.statement.VariableDeclaratorTree;
import io.github.potjerodekool.nabu.type.DeclaredType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;

public class LambdaToMethod extends AbstractTreeVisitor<Object, LambdaScope> {

    /**
     * Of de veld-initializer die nu bezocht wordt statisch is; null buiten een
     * veld. Een lambda daarin heeft geen omringende functie: javac noemt hem
     * {@code lambda$static$N} of {@code lambda$new$N} (met {@code this}).
     */
    private Boolean fieldInitializerStatic;

    public LambdaToMethod() {
    }

    @Override
    public Object visitClass(final ClassDeclaration classDeclaration,
                             final LambdaScope scope) {
        final var classContext = new LambdaContext();
        final var classScope = new SimpleScope(classDeclaration, classContext);

        final var enclosedElements = new ArrayList<>(classDeclaration.getEnclosedElements());

        enclosedElements.forEach(enclosedElement -> acceptTree(enclosedElement, classScope));

        return null;
    }

    @Override
    public Object visitFunction(final Function function,
                                final LambdaScope scope) {
        final var functionScope = scope.childScope(function);
        super.visitFunction(function, functionScope);
        return null;
    }

    @Override
    public Object visitLambdaExpression(final LambdaExpressionTree lambdaExpression,
                                        final LambdaScope scope) {
        final var classDeclaration = scope.getCurrentClassDeclaration();
        final var currentFunction = scope.getCurrentFunctionDeclaration();
        final var classSymbol = (ClassSymbol) classDeclaration.getClassSymbol();

        // De eigen parameters van de lambda. Ze werden in de OMRINGENDE scope
        // gedefinieerd, zodat een volgende lambda ze als capture meekreeg; een
        // impliciete parameter (`(a, b) -> ...`) werd helemaal niet
        // gedefinieerd en ontbrak in de signatuur.
        final List<Element> lambdaParameters = new ArrayList<>();
        for (final var variable : lambdaExpression.getVariables()) {
            if (variable instanceof IdentifierTree identifier) {
                // Het symbool heeft nog het voorlopige onbekende type; het
                // echte type staat op de identifier.
                lambdaParameters.add(new VariableSymbolBuilderImpl()
                        .kind(ElementKind.PARAMETER)
                        .simpleName(identifier.getName())
                        .type(identifier.getType())
                        .build());
            } else if (variable instanceof VariableDeclaratorTree declarator
                    && declarator.getName().getSymbol() != null) {
                lambdaParameters.add(declarator.getName().getSymbol());
            }
        }

        // Alleen de locals die de body gebruikt worden gecaptured (JLS 15.27.2),
        // niet alles wat in scope is: een ongebruikte, later of conditioneel
        // toegewezen local werd anders uit een nooit beschreven slot geladen.
        // The body is visited in a scope of its own that has the lambda's
        // parameters (and, through the recording scope, the body's locals):
        // a lambda inside this one captures them from here. Before, an inner
        // lambda (`a -> b -> a + b`) did not see `a`.
        final var bodyScope = new SimpleScope(scope, lambdaExpression, scope.getLambdaContext());
        lambdaParameters.forEach(bodyScope::define);
        final var recording = new RecordingScope(bodyScope);
        acceptTree(lambdaExpression.getBody(), recording);
        final var parameterNames = lambdaParameters.stream()
                .map(Element::getSimpleName)
                .collect(Collectors.toSet());
        final List<Element> captured = new ArrayList<>();
        // A lambda made from a bound method reference (`expr::m`) captures
        // the receiver's value under a name of its own; the receiver
        // expression itself belongs to the enclosing code (so an enclosing
        // lambda captures what it uses).
        if (lambdaExpression.getBoundReceiver() != null) {
            acceptTree(lambdaExpression.getBoundReceiver(), scope);
            captured.add(new VariableSymbolBuilderImpl()
                    .kind(ElementKind.PARAMETER)
                    .simpleName(lambdaExpression.getBoundReceiverName())
                    .type(typeOf(lambdaExpression.getBoundReceiver()))
                    .build());
        }
        for (final var local : visibleLocals(scope)) {
            if (recording.referenced.contains(local.getSimpleName())
                    && !parameterNames.contains(local.getSimpleName())) {
                captured.add(local);
            }
        }

        final String enclosingName = currentFunction != null
                ? currentFunction.getSimpleName()
                : Boolean.TRUE.equals(fieldInitializerStatic) ? "static" : "new";
        final var method = (MethodSymbol) createLambdaMethod(
                scope,
                enclosingName,
                lambdaExpression,
                classSymbol,
                captured,
                lambdaParameters
        );

        classSymbol.addEnclosedElement(method);

        final var lambdaFunction = createLambdaFunction(
                method,
                lambdaExpression
        );

        classDeclaration.enclosedElement(lambdaFunction);
        return null;
    }

    /**
     * De locals van {@code scope} en de omringende scopes tot en met de
     * functie, buitenste eerst, in declaratievolgorde.
     */
    private static List<Element> visibleLocals(final LambdaScope scope) {
        final Deque<LambdaScope> chain = new ArrayDeque<>();
        for (LambdaScope current = scope; current != null; current = current.getParent()) {
            chain.push(current);
            if (current.getOwner() instanceof Function) {
                break;
            }
        }
        final Map<String, Element> result = new LinkedHashMap<>();
        for (final var current : chain) {
            for (final var name : current.locals()) {
                final var element = current.resolve(name);
                if (element != null) {
                    result.put(name, element);
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    /** The type of an expression: its own, a call's return type, or its symbol's. */
    private static TypeMirror typeOf(final ExpressionTree expression) {
        if (expression.getType() != null) {
            return expression.getType();
        }
        if (expression instanceof io.github.potjerodekool.nabu.tree.expression.MethodInvocationTree invocation
                && invocation.getMethodType() != null) {
            return invocation.getMethodType().getReturnType();
        }
        final var symbol = io.github.potjerodekool.nabu.tree.TreeUtils.getSymbol(expression);
        return symbol != null ? symbol.asType() : null;
    }

    private ExecutableElement createLambdaMethod(final LambdaScope scope,
                                                 final String functionName,
                                                 final LambdaExpressionTree lambdaExpression,
                                                 final ClassSymbol classSymbol,
                                                 final List<Element> captured,
                                                 final List<Element> lambdaParameters) {
        final var context = scope.getLambdaContext();

        final var lambdaFunctionName = context.generateLambdaMethodName(
                functionName
        );

        final var lambdaType = (DeclaredType) lambdaExpression.getType();
        final var lambdaClass = (TypeElement) lambdaType.asElement();

        // Het returntype van de SAM-methode zoals geïnstantieerd voor het
        // doeltype (UnaryOperator<String>.apply -> String), niet de kale
        // declaratie met typevariabele T.
        var returnType = lambdaExpression.getLambdaMethodType() != null
                ? lambdaExpression.getLambdaMethodType().getReturnType()
                : lambdaClass.findFunctionalMethod().getReturnType();
        if (returnType instanceof io.github.potjerodekool.nabu.type.WildcardType wildcard) {
            // Function<? super T, ? extends U>.apply returns the upper bound of the wildcard.
            returnType = wildcard.isExtendsBound()
                    ? wildcard.getExtendsBound()
                    : lambdaClass.findFunctionalMethod().getReturnType();
        }
        if (returnType instanceof io.github.potjerodekool.nabu.type.TypeVariable typeVariable) {
            returnType = typeVariable.getUpperBound();
        }

        final var method = new MethodSymbolBuilderImpl()
                .simpleName(lambdaFunctionName)
                .enclosingElement(classSymbol)
                .returnType(returnType)
                .flags(
                        Flags.PRIVATE + Flags.STATIC + Flags.SYNTHETIC
                )
                .build();

        final var currentFunction = scope.getCurrentFunctionDeclaration();
        final var enclosingMethodSymbol = currentFunction != null
                ? (MethodSymbol) currentFunction.getMethodSymbol()
                : null;
        final boolean instanceContext = currentFunction != null
                ? enclosingMethodSymbol != null && !enclosingMethodSymbol.isStatic()
                : Boolean.FALSE.equals(fieldInitializerStatic);
        if (instanceContext) {
            final var thisType = classSymbol.asType();
            final var thisParameter = new VariableSymbolBuilderImpl()
                    .kind(ElementKind.PARAMETER)
                    .simpleName("this")
                    .type(thisType)
                    .enclosingElement(method)
                    .build();
            method.addParameter(thisParameter);
        }

        addParameters(method, captured);
        addParameters(method, lambdaParameters);

        lambdaExpression.setLambdaMethodType(method.asType());

        return method;
    }

    private void addParameters(final MethodSymbol method,
                               final List<Element> elements) {
        for (final var element : elements) {
            method.addParameter(new VariableSymbolBuilderImpl()
                    .kind(ElementKind.PARAMETER)
                    .simpleName(element.getSimpleName())
                    .type(element.asType())
                    .enclosingElement(method)
                    .build());
        }
    }

    private Function createLambdaFunction(final ExecutableElement method,
                                          final LambdaExpressionTree lambdaExpression) {
        final var parameters = createParameters(
                method
        );

        // JLS 15.27.3: een expressie-body van een lambda met een niet-void
        // functiemethode is `return <expr>;`. Als expression-statement viel de
        // methode zonder return van het einde en gooide ze een RuntimeException.
        var body = lambdaExpression.getBody();
        if (body instanceof io.github.potjerodekool.nabu.tree.statement.ExpressionStatementTree expressionStatement) {
            body = expressionStatement.getExpression();
        }
        final var lambdaBody = body instanceof ExpressionTree expression
                && method.getReturnType().getKind() != io.github.potjerodekool.nabu.type.TypeKind.VOID
                ? asBlockStatement(TreeMaker.returnStatement(expression,
                        expression.getLineNumber(), expression.getColumnNumber()))
                : asBlockStatement(body);

        return new FunctionBuilder()
                .kind(Kind.METHOD)
                .simpleName(method.getSimpleName())
                .parameters(parameters)
                .returnType(createTypeExpression(method.getReturnType()))
                .method(method)
                .body(lambdaBody)
                .build();
    }

    private List<VariableDeclaratorTree> createParameters(final ExecutableElement method) {
        final var methodParameters = method.getParameters();
        return methodParameters.stream()
                .map(parameterElement -> {
                    final var parameter = TreeMaker.variableDeclarator(
                            Kind.PARAMETER,
                            new Modifiers(),
                            createTypeExpression(parameterElement.asType()),
                            IdentifierTree.create(parameterElement.getSimpleName()),
                            null,
                            null,
                            -1,
                            -1
                    );

                    parameter.getVariableType().setType(parameterElement.asType());
                    parameter.getName().setSymbol(parameterElement);
                    return parameter;
                })
                .toList();
    }

    private BlockStatementTree asBlockStatement(final Tree tree) {
        if (tree instanceof StatementTree statement) {
            return asBlockStatement(statement);
        } else {
            return asBlockStatement(TreeMaker.expressionStatement((ExpressionTree) tree, tree.getLineNumber(), tree.getColumnNumber()));
        }
    }

    private BlockStatementTree asBlockStatement(final StatementTree statement) {
        if (statement instanceof BlockStatementTree blockStatement) {
            return blockStatement;
        } else {
            return TreeMaker.blockStatement(List.of(statement), -1, -1);
        }
    }

    @Override
    public Object visitVariableDeclaratorStatement(final VariableDeclaratorTree variableDeclaratorStatement,
                                                   final LambdaScope scope) {
        // Eerst de initializer: een lambda in `IntSupplier s = () -> 1` werd
        // anders nooit omgezet (de IR kreeg geen lambda-methode en de
        // variabele bleef null). Daarna pas de variabele zelf in scope; de
        // initializer kan hem niet capturen.
        if (variableDeclaratorStatement.getValue() != null && scope != null) {
            final Boolean previous = fieldInitializerStatic;
            if (variableDeclaratorStatement.getKind() == Kind.FIELD) {
                final var symbol = variableDeclaratorStatement.getName().getSymbol();
                fieldInitializerStatic = symbol != null && symbol.isStatic();
            }
            try {
                acceptTree(variableDeclaratorStatement.getValue(), scope);
            } finally {
                fieldInitializerStatic = previous;
            }
        }
        if (scope != null) {
            scope.define(variableDeclaratorStatement.getName().getSymbol());
        }

        return null;
    }

    @Override
    public Object visitIdentifier(final IdentifierTree identifier,
                                  final LambdaScope scope) {
        final var name = identifier.getName();
        scope.resolve(name);
        return null;
    }

    private ExpressionTree createTypeExpression(final TypeMirror typeMirror) {
        final var creator = new TypeExpressionCreator();
        return typeMirror.accept(creator, null);
    }
}


class ImmutableScope extends LambdaScope {

    public ImmutableScope(final LambdaScope parentScope,
                          final Tree owner) {
        super(parentScope, owner, parentScope.getLambdaContext());
    }

    @Override
    public Set<String> locals() {
        return Set.of();
    }

    @Override
    public void define(final Element element) {
    }

    @Override
    public Element resolve(final String name) {
        return null;
    }

    @Override
    public LambdaScope childScope(final Tree owner) {
        return owner != getOwner()
                ? new ImmutableScope(this, owner)
                : this;
    }
}


/**
 * Scope voor de body van een lambda: zoekt op in de omringende scope en
 * onthoudt welke namen de body gebruikt, voor precieze capture.
 */
class RecordingScope extends LambdaScope {

    final Set<String> referenced = new java.util.HashSet<>();

    RecordingScope(final LambdaScope parentScope) {
        super(parentScope, parentScope.getOwner(), parentScope.getLambdaContext());
    }

    @Override
    public Set<String> locals() {
        return Set.of();
    }

    /** Locals declared in the body, for lambdas nested in it. */
    @Override
    public void define(final Element element) {
        getParent().define(element);
    }

    @Override
    public Element resolve(final String name) {
        referenced.add(name);
        return getParent().resolve(name);
    }

    @Override
    public LambdaScope childScope(final Tree owner) {
        return this;
    }
}
