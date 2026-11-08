package alang.types;

import java.math.BigInteger;

/** Primitive types. Integers are fixed-width two's complement; floats are IEEE 754. */
public enum PrimKind {
    BOOL("bool", 0, false, false, 1),
    I8("i8", 8, true, false, 1),
    I16("i16", 16, true, false, 2),
    I32("i32", 32, true, false, 4),
    I64("i64", 64, true, false, 8),
    U8("u8", 8, false, false, 1),
    U16("u16", 16, false, false, 2),
    U32("u32", 32, false, false, 4),
    U64("u64", 64, false, false, 8),
    F32("f32", 32, true, true, 4),
    F64("f64", 64, true, true, 8);

    public final String name;
    public final int bits;
    public final boolean signed;
    public final boolean isFloat;
    public final int size;

    PrimKind(String name, int bits, boolean signed, boolean isFloat, int size) {
        this.name = name;
        this.bits = bits;
        this.signed = signed;
        this.isFloat = isFloat;
        this.size = size;
    }

    public boolean isInt() {
        return this != BOOL && !isFloat;
    }

    public boolean isNumeric() {
        return this != BOOL;
    }

    public BigInteger min() {
        if (!isInt()) throw new IllegalStateException();
        return signed ? BigInteger.ONE.shiftLeft(bits - 1).negate() : BigInteger.ZERO;
    }

    public BigInteger max() {
        if (!isInt()) throw new IllegalStateException();
        return signed ? BigInteger.ONE.shiftLeft(bits - 1).subtract(BigInteger.ONE)
                      : BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
    }

    public boolean fits(BigInteger v) {
        return v.compareTo(min()) >= 0 && v.compareTo(max()) <= 0;
    }

    public static PrimKind byName(String name) {
        for (PrimKind k : values()) {
            if (k.name.equals(name)) return k;
        }
        return null;
    }
}
