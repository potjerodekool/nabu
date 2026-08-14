package io.github.potjerodekool.nabu.compiler.backend.ir.optimize;

import io.github.potjerodekool.nabu.compiler.ir.IRBasicBlock;
import io.github.potjerodekool.nabu.compiler.ir.IRFunction;
import io.github.potjerodekool.nabu.compiler.ir.instructions.IRInstruction;
import io.github.potjerodekool.nabu.compiler.ir.types.IRType;
import io.github.potjerodekool.nabu.compiler.ir.values.IRValue;

import java.util.HashMap;
import java.util.Map;

/**
 * IR Type Inference: propageert en infereert IRTypes over het IR.
 *
 * Dit is nodig na SSA-constructie en optimalisatiepasses, waarbij
 * de types van sommige waarden niet correct hoeven te zijn.
 *
 * Regels:
 *   - BinaryOp: resultaattype = commonType(left, right)
 *   - Phi: resultaattype = commonType(van alle incoming waarden)
 *   - Load: resultaattype = pointee-type van de pointer
 *   - Move: resultaattype = type van de bronwaarde
 *   - Call: resultaattype = return-type van de functie
 *   - Store: geen resultaat, maar waarde moet compatibel zijn met ptr-pointee
 *   - Return: waarde moet compatibel zijn met functie-returntype
 *
 * De pass loopt iteratief tot geen types meer veranderen (fixpoint).
 */
public class TypeInference implements OptimizationPass {

    @Override
    public boolean run(final IRFunction function) {
        boolean changed = false;
        boolean iterationChanged;

        // TypeMap wordt één keer gebouwd en bijgewerkt per iteratie
        final var typeMap = buildTypeMap(function);

        do {
            iterationChanged = false;

            for (final var block : function.blocks()) {
                for (final var instr : block.instructions()) {
                    iterationChanged |= inferTypes(instr, typeMap, block);
                }
            }

            changed |= iterationChanged;
        } while (iterationChanged);

        return changed;
    }

    private Map<String, IRType> buildTypeMap(final IRFunction function) {
        final Map<String, IRType> typeMap = new HashMap<>();

        // Registreer functieparameters
        for (final var param : function.params) {
            if (param instanceof IRValue.Temp temp) {
                typeMap.put(temp.name(), temp.type());
            } else if (param instanceof IRValue.Named named) {
                typeMap.put(named.name(), named.type());
            }
        }

        // Registreer alle bekende types
        for (final var block : function.blocks()) {
            for (final var instr : block.instructions()) {
                final var result = instr.result();
                if (result != null) {
                    registerType(result, typeMap);
                }
            }
        }

        return typeMap;
    }

    private void registerType(final IRValue value,
                              final Map<String, IRType> typeMap) {
        if (value instanceof IRValue.Temp temp) {
            typeMap.putIfAbsent(temp.name(), temp.type());
        } else if (value instanceof IRValue.Named named) {
            typeMap.putIfAbsent(named.name(), named.type());
        }
    }

    private boolean inferTypes(final IRInstruction instr,
                               final Map<String, IRType> typeMap,
                               final IRBasicBlock block) {
        return switch (instr) {
            case IRInstruction.BinaryOp op -> inferBinaryOp(op, typeMap, block);
            case IRInstruction.Phi phi -> inferPhi(phi, typeMap, block);
            case IRInstruction.Load ld -> inferLoad(ld, typeMap, block);
            case IRInstruction.Move move -> inferMove(move, typeMap, block);
            case IRInstruction.Call call -> inferCall(call, typeMap, block);
            default -> false;
        };
    }

    private boolean inferBinaryOp(final IRInstruction.BinaryOp op,
                                  final Map<String, IRType> typeMap,
                                  final IRBasicBlock block) {
        final var leftType = resolveType(op.left(), typeMap);
        final var rightType = resolveType(op.right(), typeMap);

        if (leftType == null || rightType == null) {
            return false;
        }

        final var resultType = commonType(leftType, rightType);
        if (resultType == null) {
            return false;
        }

        final var currentType = resolveType(op.result(), typeMap);
        if (!resultType.equals(currentType)) {
            typeMap.put(IRValue.nameOf(op.result()), resultType);
            return true;
        }

        return false;
    }

    private boolean inferPhi(final IRInstruction.Phi phi,
                             final Map<String, IRType> typeMap,
                             final IRBasicBlock block) {
        IRType common = null;

        for (final var incoming : phi.incomingValues()) {
            final var incomingType = resolveType(incoming.value(), typeMap);
            if (incomingType == null) continue;

            if (common == null) {
                common = incomingType;
            } else {
                common = commonType(common, incomingType);
                if (common == null) break;
            }
        }

        if (common == null) {
            return false;
        }

        final var currentType = resolveType(phi.result(), typeMap);
        if (!common.equals(currentType)) {
            typeMap.put(IRValue.nameOf(phi.result()), common);
            return true;
        }

        return false;
    }

    private boolean inferLoad(final IRInstruction.Load ld,
                              final Map<String, IRType> typeMap,
                              final IRBasicBlock block) {
        final var ptrType = resolveType(ld.ptr(), typeMap);

        if (ptrType instanceof IRType.Ptr ptr && ptr.pointee() != null) {
            final var loadType = ptr.pointee();
            final var currentType = resolveType(ld.result(), typeMap);

            if (!loadType.equals(currentType)) {
                typeMap.put(IRValue.nameOf(ld.result()), loadType);
                return true;
            }
        }

        return false;
    }

    private boolean inferMove(final IRInstruction.Move move,
                              final Map<String, IRType> typeMap,
                              final IRBasicBlock block) {
        final var valueType = resolveType(move.value(), typeMap);
        if (valueType == null) return false;

        final var currentType = resolveType(move.result(), typeMap);
        if (!valueType.equals(currentType)) {
            typeMap.put(IRValue.nameOf(move.result()), valueType);
            return true;
        }

        return false;
    }

    private boolean inferCall(final IRInstruction.Call call,
                              final Map<String, IRType> typeMap,
                              final IRBasicBlock block) {
        if (call.result() == null) return false;

        final var returnType = call.returnType();
        if (returnType == null) return false;

        final var currentType = resolveType(call.result(), typeMap);
        if (!returnType.equals(currentType)) {
            typeMap.put(IRValue.nameOf(call.result()), returnType);
            return true;
        }

        return false;
    }

    private IRType resolveType(final IRValue value,
                               final Map<String, IRType> typeMap) {
        if (value instanceof IRValue.ConstInt c) return c.type();
        if (value instanceof IRValue.ConstFloat c) return c.type();
        if (value instanceof IRValue.ConstBool c) return c.type();
        if (value instanceof IRValue.ConstString c) return c.type();
        if (value instanceof IRValue.ConstNull c) return c.type();
        if (value instanceof IRValue.ConstUndef c) return c.type();
        if (value instanceof IRValue.Temp temp) {
            return typeMap.getOrDefault(temp.name(), temp.type());
        }
        if (value instanceof IRValue.Named named) {
            return typeMap.getOrDefault(named.name(), named.type());
        }
        return value != null ? value.type() : null;
    }

    /**
     * Bepaal het gemeenschappelijke type van twee types.
     * Voor primitieven gelden standaard-promotieregels.
     * Voor pointers moeten ze gelijk zijn.
     */
    static IRType commonType(final IRType a, final IRType b) {
        if (a == null || b == null) return null;
        if (a.equals(b)) return a;

        // Promotieregels voor integer-types
        if (a instanceof IRType.Int intA && b instanceof IRType.Int intB) {
            return intA.bits() >= intB.bits() ? intA : intB;
        }

        // Float-promotie
        if (a instanceof IRType.Float floatA && b instanceof IRType.Float floatB) {
            return floatA.bits() >= floatB.bits() ? floatA : floatB;
        }

        // Int → Float promotie
        if (a instanceof IRType.Int && b instanceof IRType.Float) {
            return b;
        }
        if (a instanceof IRType.Float && b instanceof IRType.Int) {
            return a;
        }

        // Bool is een eigen type (geen promotie naar int)
        if (a instanceof IRType.Bool && b instanceof IRType.Bool) {
            return a;
        }

        // Ptr-types: pointee moet gelijk zijn
        if (a instanceof IRType.Ptr ptrA && b instanceof IRType.Ptr ptrB) {
            if (ptrA.pointee() == null || ptrB.pointee() == null) {
                return ptrA; // opaque pointer
            }
            if (ptrA.pointee().equals(ptrB.pointee())) {
                return ptrA;
            }
        }

        // Anders: geen gemeenschappelijk type gevonden
        return null;
    }
}
