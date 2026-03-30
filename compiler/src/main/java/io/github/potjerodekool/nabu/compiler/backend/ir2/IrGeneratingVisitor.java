package io.github.potjerodekool.nabu.compiler.backend.ir2;

import io.github.potjerodekool.nabu.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.ir.IRBuilder;
import io.github.potjerodekool.nabu.ir.IRModule;
import io.github.potjerodekool.nabu.ir.instructions.IRInstruction.BinaryOp.Op;
import io.github.potjerodekool.nabu.ir.types.IRType;
import io.github.potjerodekool.nabu.ir.values.IRValue;
import io.github.potjerodekool.nabu.lang.model.element.Element;
import io.github.potjerodekool.nabu.lang.model.element.ElementKind;
import io.github.potjerodekool.nabu.lang.model.element.ExecutableElement;
import io.github.potjerodekool.nabu.lang.model.element.VariableElement;
import io.github.potjerodekool.nabu.tree.*;
import io.github.potjerodekool.nabu.tree.element.ClassDeclaration;
import io.github.potjerodekool.nabu.tree.element.Function;
import io.github.potjerodekool.nabu.tree.expression.*;
import io.github.potjerodekool.nabu.tree.expression.impl.CIdentifierTree;
import io.github.potjerodekool.nabu.tree.statement.*;
import io.github.potjerodekool.nabu.type.ExecutableType;
import io.github.potjerodekool.nabu.type.TypeMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * Vertaalt de Nabu AST naar een IRModule.
 *
 * Implementeert TreeVisitor<IRValue, IRBuilder>:
 *   - R = IRValue — elke visit-methode geeft de IR-waarde terug
 *     die de expressie vertegenwoordigt (null voor statements)
 *   - P = IRBuilder — de actieve builder wordt als parameter doorgegeven
 *
 * Gebruik:
 *   var visitor = new IrGeneratingVisitor();
 *   compilationUnit.accept(visitor, null);
 *   IRModule module = visitor.getModule();
 */
public class IrGeneratingVisitor extends AbstractTreeVisitor<IRValue, IRBuilder> {

    private IRBuilder builder;
    private IRModule  module;

    private final ScopeTracker scope = new ScopeTracker();

    // Naam van de klasse die nu verwerkt wordt (voor this-verwijzingen)
    private String currentClassName;

    // -------------------------------------------------------
    // Resultaat
    // -------------------------------------------------------

    public IRModule getModule() { return module; }

    // -------------------------------------------------------
    // Fallback
    // -------------------------------------------------------

    @Override
    public IRValue visitUnknown(Tree tree, IRBuilder param) {
        // Onbekende nodes stilzwijgend overslaan
        return null;
    }

    // -------------------------------------------------------
    // CompilationUnit
    // -------------------------------------------------------

    @Override
    public IRValue visitCompilationUnit(CompilationUnit compilationUnit,
                                        IRBuilder param) {
        String fileName = compilationUnit.getFileObject() != null
                ? compilationUnit.getFileObject().getFileName()
                : "<onbekend>";

        String moduleName = fileName.replace('/', '.').replace(".nabu", "");
        builder = new IRBuilder(moduleName);
        module  = builder.build();

        // Bronbestand registreren voor debuginfo
        int lastSlash = fileName.lastIndexOf('/');
        String dir  = lastSlash >= 0 ? fileName.substring(0, lastSlash) : ".";
        String file = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
        module.setSourceFile(file, dir);

        // Traverseer alle klassen
        for (ClassDeclaration cls : compilationUnit.getClasses()) {
            acceptTree(cls, builder);
        }

        return null;
    }

    // -------------------------------------------------------
    // Klasse-declaratie
    // -------------------------------------------------------

    @Override
    public IRValue visitClass(ClassDeclaration classDeclaration,
                              IRBuilder param) {
        currentClassName = classDeclaration.getSimpleName();

        // Traverseer methoden en constructors
        for (Tree member : classDeclaration.getEnclosedElements()) {
            if (member instanceof Function function) {
                acceptTree(function, builder);
            }
        }
        return null;
    }

    // -------------------------------------------------------
    // Methode / constructor declaratie
    // -------------------------------------------------------

    @Override
    public IRValue visitFunction(Function function, IRBuilder param) {
        ExecutableElement methodSymbol = function.getMethodSymbol();
        if (methodSymbol == null) return null;

        // Locatie registreren
        builder.setLocation(
                currentClassName + ".nabu",
                function.getLineNumber(),
                function.getColumnNumber()
        );

        // Returntype
        IRType returnType = TypeMirrorToIRType.mapReturnType(
                methodSymbol.getReturnType());

        // Parameters
        List<IRValue> params = new ArrayList<>();
        for (VariableElement param2 : methodSymbol.getParameters()) {
            IRType paramType = TypeMirrorToIRType.map(param2.asType());
            params.add(new IRValue.Temp(
                    "%" + param2.getSimpleName(), paramType));
        }

        // Voor instantie-methoden: 'this' als eerste parameter
        boolean isStatic = methodSymbol.isStatic();
        if (!isStatic && methodSymbol.getKind() != ElementKind.CONSTRUCTOR) {
            IRType thisType = new IRType.Ptr(IRType.I8);
            params.add(0, new IRValue.Temp("%this", thisType));
        }

        // Functienaam: klasse + methode (JVM-stijl intern)
        String fnName = currentClassName + "_" + function.getSimpleName();
        if (methodSymbol.getKind() == ElementKind.CONSTRUCTOR) {
            fnName = currentClassName + "_init";
        }

        // Begin functie
        scope.reset();
        builder.beginFunction(fnName, returnType, params, isStatic);

        // Parameters registreren in scope
        scope.pushScope();
        for (IRValue p : params) {
            final var name = IRValue.nameOf(p);
            String pname = name.startsWith("%")
                    ? name.substring(1) : name;
            scope.define(pname, p);
        }

        // Body
        if (function.getBody() != null) {
            acceptTree(function.getBody(), builder);
        }

        // Impliciete void-return als het blok niet beëindigd is
        if (!builder.currentBlockTerminated()) {
            if (returnType == IRType.VOID) {
                builder.emitReturn(null);
            }
        }

        scope.popScope();
        builder.endFunction();
        return null;
    }

    // -------------------------------------------------------
    // Statements
    // -------------------------------------------------------

    @Override
    public IRValue visitBlockStatement(BlockStatementTree blockStatement,
                                       IRBuilder param) {
        scope.pushScope();
        for (StatementTree stmt : blockStatement.getStatements()) {
            acceptTree(stmt, builder);
        }
        scope.popScope();
        return null;
    }

    @Override
    public IRValue visitReturnStatement(ReturnStatementTree returnStatement,
                                        IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                returnStatement.getLineNumber(),
                returnStatement.getColumnNumber()
        );

        ExpressionTree expr = returnStatement.getExpression();
        if (expr == null) {
            builder.emitReturn(null);
        } else {
            IRValue value = acceptTree(expr, builder);
            builder.emitReturn(value);
        }
        return null;
    }

    @Override
    public IRValue visitVariableDeclaratorStatement(
            VariableDeclaratorTree varDecl, IRBuilder param) {

        builder.setLocation(
                currentClassName + ".nabu",
                varDecl.getLineNumber(),
                varDecl.getColumnNumber()
        );

        // Type bepalen via TypeMirror op de type-expressie
        TypeMirror typeMirror = varDecl.getType();
        IRType irType = TypeMirrorToIRType.map(typeMirror);

        String name = varDecl.getName().getName();

        // Alloca voor de variabele
        IRValue ptr = builder.emitAlloca(name, irType);
        scope.define(name, ptr);

        // Initialisator
        if (varDecl.getValue() != null) {
            IRValue initValue = acceptTree(varDecl.getValue(), builder);
            if (initValue != null) {
                builder.emitStore(ptr, initValue);
            }
        }
        return null;
    }

    @Override
    public IRValue visitExpressionStatement(
            ExpressionStatementTree expressionStatement, IRBuilder param) {
        // Voer de expressie uit, gooi het resultaat weg
        acceptTree(expressionStatement.getExpression(), builder);
        return null;
    }

    @Override
    public IRValue visitIfStatement(IfStatementTree ifStatement,
                                    IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                ifStatement.getLineNumber(),
                ifStatement.getColumnNumber()
        );

        // Sla het huidige blok op vóór beginBlock de cursor verplaatst
        IRBasicBlock entryBlk = builder.currentBlock();

        IRBasicBlock thenBlk  = builder.beginBlock("if.then");
        IRBasicBlock elseBlk  = ifStatement.getElseStatement() != null
                ? builder.beginBlock("if.else") : null;
        IRBasicBlock mergeBlk = builder.beginBlock("if.merge");

        // Conditie in entry-blok
        builder.setCurrentBlock(entryBlk);
        IRValue cond = acceptTree(ifStatement.getExpression(), builder);
        builder.emitCondBranch(cond, thenBlk,
                elseBlk != null ? elseBlk : mergeBlk);

        // Then
        builder.setCurrentBlock(thenBlk);
        acceptTree(ifStatement.getThenStatement(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(mergeBlk);

        // Else (optioneel)
        if (elseBlk != null) {
            builder.setCurrentBlock(elseBlk);
            acceptTree(ifStatement.getElseStatement(), builder);
            if (!builder.currentBlockTerminated())
                builder.emitBranch(mergeBlk);
        }

        builder.setCurrentBlock(mergeBlk);
        return null;
    }

    @Override
    public IRValue visitWhileStatement(WhileStatementTree whileStatement,
                                       IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock condBlk  = builder.beginBlock("while.cond");
        IRBasicBlock bodyBlk  = builder.beginBlock("while.body");
        IRBasicBlock exitBlk  = builder.beginBlock("while.exit");

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        IRValue cond = acceptTree(whileStatement.getCondition(), builder);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(whileStatement.getBody(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(condBlk);

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    @Override
    public IRValue visitDoWhileStatement(DoWhileStatementTree doWhileStatement,
                                         IRBuilder param) {
        IRBasicBlock entryBlk = builder.currentBlock();
        IRBasicBlock bodyBlk  = builder.beginBlock("dowhile.body");
        IRBasicBlock condBlk  = builder.beginBlock("dowhile.cond");
        IRBasicBlock exitBlk  = builder.beginBlock("dowhile.exit");

        builder.setCurrentBlock(entryBlk);
        builder.emitBranch(bodyBlk);

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(doWhileStatement.getBody(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        IRValue cond = acceptTree(doWhileStatement.getCondition(), builder);
        builder.emitCondBranch(cond, bodyBlk, exitBlk);

        builder.setCurrentBlock(exitBlk);
        return null;
    }

    @Override
    public IRValue visitForStatement(ForStatementTree forStatement,
                                     IRBuilder param) {
        IRBasicBlock entryBlk  = builder.currentBlock();
        IRBasicBlock condBlk   = builder.beginBlock("for.cond");
        IRBasicBlock bodyBlk   = builder.beginBlock("for.body");
        IRBasicBlock updateBlk = builder.beginBlock("for.update");
        IRBasicBlock exitBlk   = builder.beginBlock("for.exit");

        // Init in entry-blok
        builder.setCurrentBlock(entryBlk);
        scope.pushScope();
        for (StatementTree init : forStatement.getForInit()) {
            acceptTree(init, builder);
        }
        builder.emitBranch(condBlk);

        // Conditie
        builder.setCurrentBlock(condBlk);
        if (forStatement.getCondition() != null) {
            IRValue cond = acceptTree(forStatement.getCondition(), builder);
            builder.emitCondBranch(cond, bodyBlk, exitBlk);
        } else {
            // for (;;) — oneindige lus
            builder.emitBranch(bodyBlk);
        }

        // Body
        builder.setCurrentBlock(bodyBlk);
        acceptTree(forStatement.getStatement(), builder);
        if (!builder.currentBlockTerminated())
            builder.emitBranch(updateBlk);

        // Update
        builder.setCurrentBlock(updateBlk);
        for (StatementTree update : forStatement.getForUpdate()) {
            acceptTree(update, builder);
        }
        builder.emitBranch(condBlk);

        scope.popScope();
        builder.setCurrentBlock(exitBlk);
        return null;
    }

    // -------------------------------------------------------
    // Expressies
    // -------------------------------------------------------

    @Override
    public IRValue visitLiteralExpression(LiteralExpressionTree literal,
                                          IRBuilder param) {
        Object value = literal.getLiteral();

        return switch (literal.getLiteralKind()) {
            case INTEGER -> builder.constInt(((Number) value).longValue());
            case LONG    -> builder.constInt(((Long) value), 64);
            case BOOLEAN -> builder.constBool((Boolean) value);
            case FLOAT   -> IRValue.ofF32(((Float) value).doubleValue());
            case DOUBLE  -> builder.constFloat((Double) value);
            case BYTE    -> builder.constInt(((Byte) value).longValue(), 8);
            case SHORT   -> builder.constInt(((Short) value).longValue(), 16);
            case CHAR    -> builder.constInt(((Character) value), 16);
            case STRING  -> builder.constString((String) value);
            case NULL    -> IRValue.nullPtr(IRType.I8);
            case CLASS   -> IRValue.nullPtr(IRType.I8); // class-literal als opaque ptr
        };
    }

    @Override
    public IRValue visitBinaryExpression(BinaryExpressionTree binaryExpr,
                                         IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                binaryExpr.getLineNumber(),
                binaryExpr.getColumnNumber()
        );

        Tag tag = binaryExpr.getTag();

        // Compound assignments: uitvouwen naar load + op + store
        if (TagToIROp.isCompoundAssignment(tag)) {
            return emitCompoundAssignment(binaryExpr);
        }

        IRValue left  = acceptTree(binaryExpr.getLeft(), builder);
        IRValue right = acceptTree(binaryExpr.getRight(), builder);
        Op op = TagToIROp.map(tag);

        return builder.emitBinaryOp(op, left, right);
    }

    @Override
    public IRValue visitUnaryExpression(UnaryExpressionTree unary,
                                        IRBuilder param) {
        Tag tag = unary.getTag();
        IRValue operand = acceptTree(unary.getExpression(), builder);

        return switch (tag) {
            case SUB -> {
                // Negatie: 0 - operand
                IRValue zero = builder.constInt(0);
                yield builder.emitBinaryOp(Op.SUB, zero, operand);
            }
            case NOT -> {
                // Logische negatie: operand == false (0)
                IRValue zero = builder.constBool(false);
                yield builder.emitBinaryOp(Op.EQ, operand, zero);
            }
            case POST_INC -> {
                // i++ : laad, verhoog, sla op, geef originele waarde terug
                IRValue one = builder.constInt(1);
                IRValue incremented = builder.emitBinaryOp(Op.ADD, operand, one);
                emitStoreBack(unary.getExpression(), incremented);
                yield operand; // post = geef originele waarde terug
            }
            case POST_DEC -> {
                IRValue one = builder.constInt(1);
                IRValue decremented = builder.emitBinaryOp(Op.SUB, operand, one);
                emitStoreBack(unary.getExpression(), decremented);
                yield operand;
            }
            default -> operand;
        };
    }

    @Override
    public IRValue visitIdentifier(IdentifierTree identifier, IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                identifier.getLineNumber(),
                identifier.getColumnNumber()
        );

        String name = identifier.getName();

        // this / super — geef de this-parameter terug
        if ("this".equals(name) || "super".equals(name)) {
            return scope.lookup("this").orElse(null);
        }

        // Lokale variabele of parameter
        var local = scope.lookup(name);
        if (local.isPresent()) {
            IRValue ptr = local.get();
            // Parameters zijn directe waarden (geen pointer), locals zijn pointers
            if (ptr.type() instanceof IRType.Ptr) {
                return builder.emitLoad(ptr);
            }
            return ptr;
        }

        // Symbool opzoeken via het element
        Element symbol = (identifier instanceof CIdentifierTree ci)
                ? ci.getSymbol() : null;

        if (symbol != null) {
            ElementKind kind = symbol.getKind();

            if (kind.isField()) {
                // Statisch veld
                if (symbol.isStatic()) {
                    String globalName = symbol.getEnclosingElement().getSimpleName()
                            + "_" + name;
                    var globalPtr = builder.lookup(globalName);
                    return builder.emitLoad(globalPtr);
                }
                // Instantieveld — laad via this
                IRValue thisVal = scope.lookup("this").orElse(null);
                if (thisVal != null) {
                    return emitFieldLoad(thisVal, symbol);
                }
            }

            // Klasse-naam of type-referentie — geen directe waarde
            if (kind.isDeclaredType()) {
                return null;
            }
        }

        return null;
    }

    @Override
    public IRValue visitFieldAccessExpression(
            FieldAccessExpressionTree fieldAccess, IRBuilder param) {

        builder.setLocation(
                currentClassName + ".nabu",
                fieldAccess.getLineNumber(),
                fieldAccess.getColumnNumber()
        );

        ExpressionTree selected = fieldAccess.getSelected();
        IdentifierTree field    = fieldAccess.getField();

        Element fieldSymbol = (field instanceof CIdentifierTree ci)
                ? ci.getSymbol() : null;

        // Statisch veld (bijv. System.out)
        if (fieldSymbol != null && fieldSymbol.isStatic()) {
            String globalName = fieldSymbol.getEnclosingElement().getSimpleName()
                    + "_" + field.getName();
            // Declareer als extern als nog niet aanwezig
            IRType fieldType = TypeMirrorToIRType.map(
                    fieldSymbol.asType());
            builder.declareExternalGlobal(globalName, fieldType);
            IRValue globalPtr = builder.lookup(globalName);
            return builder.emitLoad(globalPtr);
        }

        // Instantieveld
        if (selected != null) {
            IRValue obj = acceptTree(selected, builder);
            if (obj != null && fieldSymbol != null) {
                return emitFieldLoad(obj, fieldSymbol);
            }
        }

        return null;
    }

    @Override
    public IRValue visitMethodInvocation(MethodInvocationTree invocation,
                                         IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                invocation.getLineNumber(),
                invocation.getColumnNumber()
        );

        ExecutableType methodType = invocation.getMethodType();
        if (methodType == null) return null;

        IRType returnType = TypeMirrorToIRType.mapReturnType(
                methodType.getReturnType());

        CallKindResolver.CallKind callKind = CallKindResolver.resolve(invocation);
        String methodName = CallKindResolver.resolveName(invocation);
        ExpressionTree target = CallKindResolver.resolveTarget(invocation);

        // Argumenten verwerken
        List<IRValue> args = new ArrayList<>();

        // Voor instantie-aanroepen: voeg 'this' toe als eerste argument
        if (callKind != CallKindResolver.CallKind.STATIC && target != null) {
            IRValue receiver = acceptTree(target, builder);
            if (receiver != null) args.add(receiver);
        } else if (callKind != CallKindResolver.CallKind.STATIC) {
            // Impliciete this
            scope.lookup("this").ifPresent(args::add);
        }

        for (ExpressionTree arg : invocation.getArguments()) {
            IRValue argVal = acceptTree(arg, builder);
            if (argVal != null) args.add(argVal);
        }

        // Volledig gekwalificeerde naam
        String ownerName = methodType.getOwner() != null
                ? methodType.getOwner().getSimpleName() + "_"
                : "";
        String fullName = ownerName + methodName;

        return switch (callKind) {
            case STATIC, VIRTUAL, INTERFACE ->
                    builder.emitCall(fullName, returnType, args);
            case SPECIAL ->
                // Constructor of super — gebruik ook emitCall
                    builder.emitCall(fullName, returnType, args);
        };
    }

    @Override
    public IRValue visitNewClass(NewClassExpression newClass, IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                newClass.getLineNumber(),
                newClass.getColumnNumber()
        );

        TypeMirror classMirror = newClass.getType();
        IRType objectType = TypeMirrorToIRType.map(classMirror);

        // Alloceer het object op de heap
        IRValue obj = builder.emitHeapAlloc("new_obj", objectType);

        // Roep de constructor aan
        String className = classMirror != null && classMirror.getClassName() != null
                ? classMirror.getClassName().replace('.', '_')
                : "unknown";
        String initName = className + "_init";

        List<IRValue> args = new ArrayList<>();
        args.add(obj); // this

        for (ExpressionTree arg : newClass.getArguments()) {
            IRValue argVal = acceptTree(arg, builder);
            if (argVal != null) args.add(argVal);
        }

        builder.emitCall(initName, IRType.VOID, args);
        return obj;
    }

    @Override
    public IRValue visitAssignment(AssignmentExpressionTree assignment,
                                   IRBuilder param) {
        builder.setLocation(
                currentClassName + ".nabu",
                assignment.getLineNumber(),
                assignment.getColumnNumber()
        );

        IRValue value = acceptTree(assignment.getRight(), builder);

        // Sla de waarde op via de linker kant
        emitStoreBack(assignment.getLeft(), value);
        return value;
    }

    @Override
    public IRValue visitCastExpression(CastExpressionTree cast, IRBuilder param) {
        IRValue source   = acceptTree(cast.getExpression(), builder);
        IRType  targetType = TypeMirrorToIRType.map(cast.getType());
        return builder.emitCast(source, targetType);
    }

    @Override
    public IRValue visitArrayAccess(ArrayAccessExpressionTree arrayAccess,
                                    IRBuilder param) {
        IRValue array = acceptTree(arrayAccess.getExpression(), builder);
        IRValue index = acceptTree(arrayAccess.getIndex(), builder);

        // GEP op de array-pointer
        if (array != null && array.type() instanceof IRType.Ptr ptrType) {
            IRValue elemPtr = builder.emitGEP(array, ptrType.pointee(), index);
            return builder.emitLoad(elemPtr);
        }
        return null;
    }

    // -------------------------------------------------------
    // Hulpmethoden
    // -------------------------------------------------------

    /**
     * Slaat een waarde terug op in de variabele die door een expressie
     * wordt aangeduid (voor assignments en post-increment/decrement).
     */
    private void emitStoreBack(ExpressionTree target, IRValue value) {
        if (target instanceof IdentifierTree id) {
            String name = id.getName();
            var ptr = scope.lookup(name);
            if (ptr.isPresent()) {
                builder.emitStore(ptr.get(), value);
            }
        } else if (target instanceof FieldAccessExpressionTree fieldAccess) {
            // Veld-toewijzing
            ExpressionTree selected = fieldAccess.getSelected();
            IdentifierTree field    = fieldAccess.getField();

            Element fieldSymbol = (field instanceof CIdentifierTree ci)
                    ? ci.getSymbol() : null;

            if (fieldSymbol != null && fieldSymbol.isStatic()) {
                String globalName = fieldSymbol.getEnclosingElement().getSimpleName()
                        + "_" + field.getName();
                IRValue globalPtr = builder.lookup(globalName);
                if (globalPtr != null) builder.emitStore(globalPtr, value);
            } else if (selected != null) {
                IRValue obj = acceptTree(selected, builder);
                if (obj != null && fieldSymbol != null) {
                    emitFieldStore(obj, fieldSymbol, value);
                }
            }
        }
    }

    /**
     * Emitteert compound assignments (+=, -=, etc.) als load + op + store.
     */
    private IRValue emitCompoundAssignment(BinaryExpressionTree binaryExpr) {
        IRValue current = acceptTree(binaryExpr.getLeft(), builder);
        IRValue right   = acceptTree(binaryExpr.getRight(), builder);
        Op op = TagToIROp.compoundAssignmentOp(binaryExpr.getTag());
        IRValue result  = builder.emitBinaryOp(op, current, right);
        emitStoreBack(binaryExpr.getLeft(), result);
        return result;
    }

    /**
     * Laadt een veld van een object.
     * Vereist GEP — veldindex wordt opgezocht via het Element.
     * Voorlopig: laad als opaque pointer (veldoffsets worden bepaald bij codegen).
     */
    private IRValue emitFieldLoad(IRValue obj, Element fieldSymbol) {
        // TODO: veldindex bepalen via TypeElement.getEnclosedElements()
        // Voorlopig: geef het object zelf terug als placeholder
        return obj;
    }

    /**
     * Slaat een waarde op in een veld van een object.
     */
    private void emitFieldStore(IRValue obj, Element fieldSymbol, IRValue value) {
        // TODO: veldindex bepalen en GEP emitteren
    }
}
