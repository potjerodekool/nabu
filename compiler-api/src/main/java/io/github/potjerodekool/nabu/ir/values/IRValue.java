package io.github.potjerodekool.nabu.ir.values;

import io.github.potjerodekool.nabu.ir.types.IRType;

import java.util.List;

public sealed interface IRValue permits IRValue.ConstBool, IRValue.ConstFloat, IRValue.ConstInt, IRValue.ConstNull, IRValue.ConstString, IRValue.ConstUndef, IRValue.FunctionRef, IRValue.Named, IRValue.Temp {

    static String nameOf(final IRValue value) {
        if (value instanceof Temp t) {
            return t.name;
        } else if (value instanceof Named named) {
            return named.name;
        } else {
            throw new IllegalArgumentException();
        }
    }

    IRType type();

    // -------------------------------------------------------
    // Registerwaarden
    // -------------------------------------------------------

    record Temp(String name, IRType type) implements IRValue {}

    record Named(String name, IRType type) implements IRValue {}

    // -------------------------------------------------------
    // Constanten
    // -------------------------------------------------------

    record ConstInt(long value, IRType type) implements IRValue {
        public ConstInt {
            if (!(type instanceof IRType.Int))
                throw new IllegalArgumentException("ConstInt vereist Int-type, kreeg: " + type);
        }
    }

    record ConstFloat(double value, IRType type) implements IRValue {
        public ConstFloat {
            if (!(type instanceof IRType.Float))
                throw new IllegalArgumentException("ConstFloat vereist Float-type, kreeg: " + type);
        }
    }

    record ConstBool(boolean value) implements IRValue {
        public IRType type() { return IRType.BOOL; }
    }

    record ConstString(String value) implements IRValue {
        public IRType type() { return new IRType.Ptr(IRType.I8); }
    }

    record ConstNull(IRType type) implements IRValue {
        public ConstNull {
            if (!(type instanceof IRType.Ptr))
                throw new IllegalArgumentException("ConstNull vereist Ptr-type, kreeg: " + type);
        }
    }

    record ConstUndef(IRType type) implements IRValue {}

    // -------------------------------------------------------
    // Functiereferentie
    // -------------------------------------------------------

    record FunctionRef(String name, IRType.Function fnType) implements IRValue {
        public IRType type() { return fnType.ptr(); }
    }

    // -------------------------------------------------------
    // Fabrieksmethoden
    // -------------------------------------------------------

    static IRValue ofInt(long value)              { return new ConstInt(value, IRType.I64); }
    static IRValue ofInt(long value, int bits)    { return new ConstInt(value, new IRType.Int(bits)); }
    static IRValue ofI32(long value)              { return new ConstInt(value, IRType.I32); }
    static IRValue ofFloat(double value)          { return new ConstFloat(value, IRType.F64); }
    static IRValue ofF32(double value)            { return new ConstFloat(value, IRType.F32); }
    static IRValue ofBool(boolean value)          { return new ConstBool(value); }
    static IRValue ofString(String value)         { return new ConstString(value); }
    static IRValue nullPtr(IRType pointee)        { return new ConstNull(new IRType.Ptr(pointee)); }
    static IRValue undef(IRType type)             { return new ConstUndef(type); }
    static IRValue fnRef(String name, IRType.Function t) { return new FunctionRef(name, t); }
}
