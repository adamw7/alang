/*
 * exp, log, log10, the sin and cos kernels and their argument reduction for moderate arguments, asin, acos,
 * atan and atan2 follow fdlibm 5.3:
 *
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 */
package alang.interp;

/**
 * The elementary functions of the standard library: exp, log, log2, log10, sin, cos, tan, asin, acos, atan,
 * atan2 and a float pow. Math libraries round these differently, so ALang computes them itself, here and in
 * the C backend's prelude, which does the same operations in the same order: only correctly rounded additions,
 * subtractions, multiplications, divisions and square roots, and integer operations on the bits of a double.
 * The results are then the same on every platform, compiler and optimization level.
 *
 * <p>exp, log, log10, sin, cos, asin, acos, atan and atan2 follow fdlibm 5.3 (see THIRD-PARTY-NOTICES), whose
 * results are within one unit in the last place; the reduction of large arguments of sin, cos and tan,
 * log2, tan and pow are ALang's own, within one or two units in the last place.
 */
public final class FloatMath {
    private FloatMath() {}

    // =================================================================== bits

    private static long bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    private static double fromBits(long b) {
        return Double.longBitsToDouble(b);
    }

    /** The high 32 bits of x: sign, exponent and the top 20 bits of the fraction. */
    private static int hi(double x) {
        return (int) (bits(x) >>> 32);
    }

    private static int lo(double x) {
        return (int) bits(x);
    }

    private static double withHi(double x, int h) {
        return fromBits(((long) h << 32) | (bits(x) & 0xffffffffL));
    }

    private static double words(int h, int l) {
        return fromBits(((long) h << 32) | (l & 0xffffffffL));
    }

    /** 2^e as a double, for e from -1022 to 1023. */
    private static double pow2(int e) {
        return fromBits((long) (e + 1023) << 52);
    }

    private static final double NAN = Double.NaN;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double TWO54 = 1.80143985094819840000e+16;

    // =================================================================== double-double helpers

    /** Splits a into a high half of 26 bits and the rest (Dekker); |a| must be below 2^995. */
    private static final double SPLITTER = 134217729.0;

    /** The rounding error of p = a * b, which a + b * ... cannot express: a * b - p exactly. */
    private static double prodErr(double a, double b, double p) {
        double c = SPLITTER * a;
        double ah = c - (c - a);
        double al = a - ah;
        c = SPLITTER * b;
        double bh = c - (c - b);
        double bl = b - bh;
        return ((ah * bh - p) + ah * bl + al * bh) + al * bl;
    }

    /** The rounding error of s = a + b, for any a and b (Knuth). */
    private static double sumErr(double a, double b, double s) {
        double bb = s - a;
        return (a - (s - bb)) + (b - bb);
    }

    // =================================================================== exp

    private static final double[] HALF = {0.5, -0.5};
    private static final double TWOM1000 = 9.33263618503218878990e-302;
    private static final double O_THRESHOLD = 7.09782712893383973096e+02;
    private static final double U_THRESHOLD = -7.45133219101941108420e+02;
    private static final double[] LN2HI = {6.93147180369123816490e-01, -6.93147180369123816490e-01};
    private static final double[] LN2LO = {1.90821492927058770002e-10, -1.90821492927058770002e-10};
    private static final double INVLN2 = 1.44269504088896338700e+00;
    private static final double P1 = 1.66666666666666019037e-01;
    private static final double P2 = -2.77777777770155933842e-03;
    private static final double P3 = 6.61375632143793436117e-05;
    private static final double P4 = -1.65339022054652515390e-06;
    private static final double P5 = 4.13813679705723846039e-08;

    public static double exp(double x) {
        int hx = hi(x);
        int xsb = hx >>> 31;
        hx &= 0x7fffffff;
        double hi = 0.0;
        double lo = 0.0;
        int k = 0;
        if (hx >= 0x40862E42) {
            if (hx >= 0x7ff00000) {
                if (((hx & 0xfffff) | lo(x)) != 0) return x + x;
                return xsb == 0 ? x : 0.0;
            }
            if (x > O_THRESHOLD) return INF;
            if (x < U_THRESHOLD) return 0.0;
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                hi = x - LN2HI[xsb];
                lo = LN2LO[xsb];
                k = 1 - xsb - xsb;
            } else {
                k = (int) (INVLN2 * x + HALF[xsb]);
                double t = k;
                hi = x - t * LN2HI[0];
                lo = t * LN2LO[0];
            }
            x = hi - lo;
        } else if (hx < 0x3e300000) {
            return 1.0 + x;
        }
        double t = x * x;
        double c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
        if (k == 0) return 1.0 - ((x * c) / (c - 2.0) - x);
        double y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
        if (k >= -1021) return withHi(y, hi(y) + k * 0x100000);
        return withHi(y, hi(y) + (k + 1000) * 0x100000) * TWOM1000;
    }

    // =================================================================== log

    private static final double LN2_HI = 6.93147180369123816490e-01;
    private static final double LN2_LO = 1.90821492927058770002e-10;
    private static final double LG1 = 6.666666666666735130e-01;
    private static final double LG2 = 3.999999999940941908e-01;
    private static final double LG3 = 2.857142874366239149e-01;
    private static final double LG4 = 2.222219843214978396e-01;
    private static final double LG5 = 1.818357216161805012e-01;
    private static final double LG6 = 1.531383769920937332e-01;
    private static final double LG7 = 1.479819860511658591e-01;

    public static double log(double x) {
        int hx = hi(x);
        int lx = lo(x);
        int k = 0;
        if (hx < 0x00100000) {
            if (((hx & 0x7fffffff) | lx) == 0) return -INF;
            if (hx < 0) return NAN;
            k -= 54;
            x *= TWO54;
            hx = hi(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >>> 20) - 1023;
        hx &= 0x000fffff;
        int i = (hx + 0x95f64) & 0x100000;
        x = withHi(x, hx | (i ^ 0x3ff00000));
        k += i >>> 20;
        double f = x - 1.0;
        if ((0x000fffff & (2 + hx)) < 3) {
            if (f == 0.0) {
                if (k == 0) return 0.0;
                double dk = k;
                return dk * LN2_HI + dk * LN2_LO;
            }
            double r = f * f * (0.5 - 0.33333333333333333 * f);
            if (k == 0) return f - r;
            double dk = k;
            return dk * LN2_HI - ((r - dk * LN2_LO) - f);
        }
        double s = f / (2.0 + f);
        double dk = k;
        double z = s * s;
        int i2 = hx - 0x6147a;
        double w = z * z;
        int j = 0x6b851 - hx;
        double t1 = w * (LG2 + w * (LG4 + w * LG6));
        double t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)));
        i2 |= j;
        double r = t2 + t1;
        if (i2 > 0) {
            double hfsq = 0.5 * f * f;
            if (k == 0) return f - (hfsq - s * (hfsq + r));
            return dk * LN2_HI - ((hfsq - (s * (hfsq + r) + dk * LN2_LO)) - f);
        }
        if (k == 0) return f - s * (f - r);
        return dk * LN2_HI - ((s * (f - r) - dk * LN2_LO) - f);
    }

    private static final double IVLN10 = 4.34294481903251816668e-01;
    private static final double LOG10_2HI = 3.01029995663611771306e-01;
    private static final double LOG10_2LO = 3.69423907715893078616e-13;

    /** k * log10(2) + log10(m), with x = 2^k * m: fdlibm's log10. */
    public static double log10(double x) {
        int hx = hi(x);
        int lx = lo(x);
        int k = 0;
        if (hx < 0x00100000) {
            if (((hx & 0x7fffffff) | lx) == 0) return -INF;
            if (hx < 0) return NAN;
            k -= 54;
            x *= TWO54;
            hx = hi(x);
            lx = lo(x);
        }
        if (hx >= 0x7ff00000) return x + x;
        k += (hx >>> 20) - 1023;
        // For k < 0, m is in [0.5, 1) and y = k + 1, so that y and log(m) have the same sign near x = 1.
        int i = k >>> 31;
        hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
        double y = k + i;
        x = withHi(x, hx);
        double z = y * LOG10_2LO + IVLN10 * log(x);
        return z + y * LOG10_2HI;
    }

    private static final double IVLN2_HI = 0x1.71547652b82fep0;
    private static final double IVLN2_LO = 0x1.777d0ffda0d24p-56;

    /** log(x) / log(2): log(x) in double-double times 1 / log(2) in double-double, and exact for a power of two. */
    public static double log2(double x) {
        if (Double.isNaN(x) || x < 0.0) return NAN;
        if (x == 0.0) return -INF;
        if (Double.isInfinite(x)) return x;
        int k = exponentOf(x);
        if (mulPow2(x, -k) == 1.0) return k;
        double[] l = new double[2];
        logDD(x, l);
        double p = l[0] * IVLN2_HI;
        return p + (prodErr(l[0], IVLN2_HI, p) + (l[0] * IVLN2_LO + l[1] * IVLN2_HI));
    }

    // =================================================================== sin, cos, tan

    private static final double S1 = -1.66666666666666324348e-01;
    private static final double S2 = 8.33333333332248946124e-03;
    private static final double S3 = -1.98412698298579493134e-04;
    private static final double S4 = 2.75573137070700676789e-06;
    private static final double S5 = -2.50507602534068634195e-08;
    private static final double S6 = 1.58969099521155010221e-10;

    /** sin(x + y) for |x| <= pi/4 and |y| tiny; y is 0 when tail is false. */
    private static double kernelSin(double x, double y, boolean tail) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000) return x;
        double z = x * x;
        double v = z * x;
        double r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
        if (!tail) return x + v * (S1 + z * r);
        return x - ((z * (0.5 * y - v * r) - y) - v * S1);
    }

    private static final double C1 = 4.16666666666666019037e-02;
    private static final double C2 = -1.38888888888741095749e-03;
    private static final double C3 = 2.48015872894767294178e-05;
    private static final double C4 = -2.75573143513906633035e-07;
    private static final double C5 = 2.08757232129817482790e-09;
    private static final double C6 = -1.13596475577881948265e-11;

    /** cos(x + y) for |x| <= pi/4 and |y| tiny. */
    private static double kernelCos(double x, double y) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000) return 1.0;
        double z = x * x;
        double r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
        if (ix < 0x3FD33333) return 1.0 - (0.5 * z - (z * r - x * y));
        double qx = ix > 0x3fe90000 ? 0.28125 : words(ix - 0x00200000, 0);
        double hz = 0.5 * z - qx;
        double a = 1.0 - qx;
        return a - (hz - (z * r - x * y));
    }

    private static final double INVPIO2 = 6.36619772367581382433e-01;
    private static final double PIO2_1 = 1.57079632673412561417e+00;
    private static final double PIO2_1T = 6.07710050650619224932e-11;
    private static final double PIO2_2 = 6.07710050630396597660e-11;
    private static final double PIO2_2T = 2.02226624879595063154e-21;
    private static final double PIO2_3 = 2.02226624871116645580e-21;
    private static final double PIO2_3T = 8.47842766036889956997e-32;

    /**
     * x reduced by a multiple n of pi/2: returns n and leaves x - n * pi/2 in y[0] + y[1], with |y[0]| at most
     * about pi/4.
     */
    private static int remPio2(double x, double[] y) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix <= 0x3fe921fb) {
            y[0] = x;
            y[1] = 0.0;
            return 0;
        }
        if (ix < 0x4002d97c) {
            if (hx > 0) {
                double z = x - PIO2_1;
                if (ix != 0x3ff921fb) {
                    y[0] = z - PIO2_1T;
                    y[1] = (z - y[0]) - PIO2_1T;
                } else {
                    z -= PIO2_2;
                    y[0] = z - PIO2_2T;
                    y[1] = (z - y[0]) - PIO2_2T;
                }
                return 1;
            }
            double z = x + PIO2_1;
            if (ix != 0x3ff921fb) {
                y[0] = z + PIO2_1T;
                y[1] = (z - y[0]) + PIO2_1T;
            } else {
                z += PIO2_2;
                y[0] = z + PIO2_2T;
                y[1] = (z - y[0]) + PIO2_2T;
            }
            return -1;
        }
        if (ix <= 0x413921fb) {
            double t = Math.abs(x);
            int n = (int) (t * INVPIO2 + 0.5);
            double fn = n;
            double r = t - fn * PIO2_1;
            double w = fn * PIO2_1T;
            int j = ix >>> 20;
            y[0] = r - w;
            int i = j - ((hi(y[0]) >>> 20) & 0x7ff);
            if (i > 16) {
                t = r;
                w = fn * PIO2_2;
                r = t - w;
                w = fn * PIO2_2T - ((t - r) - w);
                y[0] = r - w;
                i = j - ((hi(y[0]) >>> 20) & 0x7ff);
                if (i > 49) {
                    t = r;
                    w = fn * PIO2_3;
                    r = t - w;
                    w = fn * PIO2_3T - ((t - r) - w);
                    y[0] = r - w;
                }
            }
            y[1] = (r - y[0]) - w;
            if (hx < 0) {
                y[0] = -y[0];
                y[1] = -y[1];
                return -n;
            }
            return n;
        }
        if (ix >= 0x7ff00000) {
            y[0] = x - x;
            y[1] = y[0];
            return 0;
        }
        int n = remPio2Large(Math.abs(x), y);
        if (hx < 0) {
            y[0] = -y[0];
            y[1] = -y[1];
            return -n;
        }
        return n;
    }

    /**
     * The bits of 2/pi after the binary point, 32 to a word: word j holds the bits of weight 2^-(32j+1) to
     * 2^-(32j+32). 40 words reach past every bit the reduction of a finite double needs.
     */
    private static final int[] TWO_OVER_PI = {
        0xa2f9836e, 0x4e441529, 0xfc2757d1, 0xf534ddc0, 0xdb629599, 0x3c439041, 0xfe5163ab, 0xdebbc561,
        0xb7246e3a, 0x424dd2e0, 0x06492eea, 0x09d1921c, 0xfe1deb1c, 0xb129a73e, 0xe88235f5, 0x2ebb4484,
        0xe99c7026, 0xb45f7e41, 0x3991d639, 0x835339f4, 0x9c845f8b, 0xbdf9283b, 0x1ff897ff, 0xde05980f,
        0xef2f118b, 0x5a0a6d1f, 0x6d367ecf, 0x27cb09b7, 0x4f463f66, 0x9e5fea2d, 0x7527bac7, 0xebe5f17b,
        0x3d0739f7, 0x8a5292ea, 0x6bfb5fb1, 0x1f8d5d08, 0x56033046, 0xfc7b6bab, 0xf0cfbc20, 0x9af4361d,
    };

    /** pi/2 as the sum of two doubles. */
    private static final double PIO2_HI = 1.57079632679489655800e+00;
    private static final double PIO2_LO = 6.12323399573676603587e-17;

    /** The 32 bits of word i (from 0, least significant) of a little-endian number in 32-bit limbs, or 0. */
    private static long limb(long[] r, int i) {
        return i < r.length ? r[i] : 0L;
    }

    /** Bits q to q + 63 of the number in r (32-bit limbs, least significant first). */
    private static long bitsAt(long[] r, int q) {
        int i = q >>> 5;
        int off = q & 31;
        if (off == 0) return limb(r, i) | (limb(r, i + 1) << 32);
        return (limb(r, i) >>> off) | (limb(r, i + 1) << (32 - off)) | (limb(r, i + 2) << (64 - off));
    }

    /**
     * x mod pi/2 for a large positive x (Payne and Hanek): x = m * 2^e, and the product of m with the 256 bits
     * of 2/pi that can reach the last two bits of the quotient and its fraction is computed exactly. Bits of 2/pi
     * of weight above 2^(1 - e) add multiples of 4 to the quotient, and those past the window less than 2^-170 to
     * its fraction, which is never smaller than about 2^-62.
     */
    private static int remPio2Large(double x, double[] y) {
        long b = bits(x);
        int e = (int) ((b >>> 52) & 0x7ff) - 1075;
        long m = (b & 0xfffffffffffffL) | 0x10000000000000L;
        int p0 = Math.max(1, e - 1);
        int w = (p0 - 1) >>> 5;
        long[] r = new long[10];
        long[] mw = {m & 0xffffffffL, m >>> 32};
        for (int j = 0; j < 2; j++) {
            long carry = 0;
            for (int k = 0; k < 8; k++) {
                long t = (TWO_OVER_PI[w + 7 - k] & 0xffffffffL) * mw[j] + r[k + j] + carry;
                r[k + j] = t & 0xffffffffL;
                carry = t >>> 32;
            }
            r[8 + j] += carry;
        }
        // x * 2/pi = r * 2^-sh (mod 4).
        int sh = 32 * (w + 8) - e;
        int n = (int) (bitsAt(r, sh) & 7);
        long fhi = bitsAt(r, sh - 64);
        long flo = bitsAt(r, sh - 128);
        boolean neg = fhi < 0;
        if (neg) {
            // A fraction of at least 1/2 rounds the quotient up: what remains is minus (1 - fraction).
            n++;
            flo = -flo;
            fhi = ~fhi + (flo == 0 ? 1 : 0);
        }
        double a1 = (double) (fhi >>> 32) * 0x1p-32;
        double a2 = (double) (fhi & 0xffffffffL) * 0x1p-64;
        double a3 = (double) (flo >>> 32) * 0x1p-96;
        double a4 = (double) (flo & 0xffffffffL) * 0x1p-128;
        double s = a1 + a2;
        double err = sumErr(a1, a2, s);
        double s2 = s + a3;
        err += sumErr(s, a3, s2);
        double s3 = s2 + a4;
        err += sumErr(s2, a4, s3);
        double fh = s3 + err;
        double fl = err - (fh - s3);
        // (fh + fl) * pi/2
        double p = fh * PIO2_HI;
        double q = prodErr(fh, PIO2_HI, p) + (fh * PIO2_LO + fl * PIO2_HI);
        double y0 = p + q;
        double y1 = q - (y0 - p);
        y[0] = neg ? -y0 : y0;
        y[1] = neg ? -y1 : y1;
        return n & 7;
    }

    public static double sin(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return kernelSin(x, 0.0, false);
        if (ix >= 0x7ff00000) return NAN;
        double[] y = new double[2];
        int n = remPio2(x, y);
        return switch (n & 3) {
            case 0 -> kernelSin(y[0], y[1], true);
            case 1 -> kernelCos(y[0], y[1]);
            case 2 -> -kernelSin(y[0], y[1], true);
            default -> -kernelCos(y[0], y[1]);
        };
    }

    public static double cos(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix <= 0x3fe921fb) return kernelCos(x, 0.0);
        if (ix >= 0x7ff00000) return NAN;
        double[] y = new double[2];
        int n = remPio2(x, y);
        return switch (n & 3) {
            case 0 -> kernelCos(y[0], y[1]);
            case 1 -> -kernelSin(y[0], y[1], true);
            case 2 -> -kernelCos(y[0], y[1]);
            default -> kernelSin(y[0], y[1], true);
        };
    }

    /** sin over cos of the reduced argument: -cos / sin in the odd quadrants. */
    public static double tan(double x) {
        int ix = hi(x) & 0x7fffffff;
        if (ix < 0x3e400000) return x;
        if (ix >= 0x7ff00000) return NAN;
        double[] y = new double[2];
        int n = remPio2(x, y);
        double s = kernelSin(y[0], y[1], true);
        double c = kernelCos(y[0], y[1]);
        return (n & 1) == 0 ? s / c : -c / s;
    }

    // =================================================================== atan, atan2, asin, acos

    private static final double[] ATANHI = {
        4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01, 1.57079632679489655800e+00,
    };
    private static final double[] ATANLO = {
        2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17, 6.12323399573676603587e-17,
    };
    private static final double[] AT = {
        3.33333333333329318027e-01, -1.99999999998764832476e-01, 1.42857142725034663711e-01, -1.11111104054623557880e-01,
        9.09088713343650656196e-02, -7.69187620504482999495e-02, 6.66107313738753120669e-02, -5.83357013379057348645e-02,
        4.97687799461593236017e-02, -3.65315727442169155270e-02, 1.62858201153657823623e-02,
    };

    public static double atan(double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        int id;
        if (ix >= 0x44100000) {
            if (ix > 0x7ff00000 || (ix == 0x7ff00000 && lo(x) != 0)) return x + x;
            return hx > 0 ? ATANHI[3] + ATANLO[3] : -ATANHI[3] - ATANLO[3];
        }
        if (ix < 0x3fdc0000) {
            if (ix < 0x3e200000) return x;
            id = -1;
        } else {
            x = Math.abs(x);
            if (ix < 0x3ff30000) {
                if (ix < 0x3fe60000) {
                    id = 0;
                    x = (2.0 * x - 1.0) / (2.0 + x);
                } else {
                    id = 1;
                    x = (x - 1.0) / (x + 1.0);
                }
            } else if (ix < 0x40038000) {
                id = 2;
                x = (x - 1.5) / (1.0 + 1.5 * x);
            } else {
                id = 3;
                x = -1.0 / x;
            }
        }
        double z = x * x;
        double w = z * z;
        double s1 = z * (AT[0] + w * (AT[2] + w * (AT[4] + w * (AT[6] + w * (AT[8] + w * AT[10])))));
        double s2 = w * (AT[1] + w * (AT[3] + w * (AT[5] + w * (AT[7] + w * AT[9]))));
        if (id < 0) return x - x * (s1 + s2);
        z = ATANHI[id] - ((x * (s1 + s2) - ATANLO[id]) - x);
        return hx < 0 ? -z : z;
    }

    private static final double PI_O_4 = 7.8539816339744827900e-01;
    private static final double PI_O_2 = 1.5707963267948965580e+00;
    private static final double PI = 3.1415926535897931160e+00;
    private static final double PI_LO = 1.2246467991473531772e-16;

    /** The angle of the point (x, y), from -pi to pi. */
    public static double atan2(double y, double x) {
        if (Double.isNaN(x) || Double.isNaN(y)) return x + y;
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        int lx = lo(x);
        int hy = hi(y);
        int iy = hy & 0x7fffffff;
        int ly = lo(y);
        if (x == 1.0) return atan(y);
        int m = (hy >>> 31) | ((hx >>> 30) & 2);
        if ((iy | ly) == 0) {
            return switch (m) {
                case 0, 1 -> y;
                case 2 -> PI;
                default -> -PI;
            };
        }
        if ((ix | lx) == 0) return hy < 0 ? -PI_O_2 : PI_O_2;
        if (ix == 0x7ff00000) {
            if (iy == 0x7ff00000) {
                return switch (m) {
                    case 0 -> PI_O_4;
                    case 1 -> -PI_O_4;
                    case 2 -> 3.0 * PI_O_4;
                    default -> -3.0 * PI_O_4;
                };
            }
            return switch (m) {
                case 0 -> 0.0;
                case 1 -> -0.0;
                case 2 -> PI;
                default -> -PI;
            };
        }
        if (iy == 0x7ff00000) return hy < 0 ? -PI_O_2 : PI_O_2;
        int k = (iy - ix) >> 20;
        double z;
        if (k > 60) z = PI_O_2 + 0.5 * PI_LO;
        else if (hx < 0 && k < -60) z = 0.0;
        else z = atan(Math.abs(y / x));
        return switch (m) {
            case 0 -> z;
            case 1 -> -z;
            case 2 -> PI - (z - PI_LO);
            default -> (z - PI_LO) - PI;
        };
    }

    private static final double PS0 = 1.66666666666666657415e-01;
    private static final double PS1 = -3.25565818622400915405e-01;
    private static final double PS2 = 2.01212532134862925881e-01;
    private static final double PS3 = -4.00555345006794114027e-02;
    private static final double PS4 = 7.91534994289814532176e-04;
    private static final double PS5 = 3.47933107596021167570e-05;
    private static final double QS1 = -2.40339491173441421878e+00;
    private static final double QS2 = 2.02094576023350569471e+00;
    private static final double QS3 = -6.88283971605453293030e-01;
    private static final double QS4 = 7.70381505559019352791e-02;
    private static final double PIO4_HI = 7.85398163397448278999e-01;

    /** The rational approximation fdlibm's asin and acos share, at t: p(t) / q(t). */
    private static double asinRatio(double t) {
        double p = t * (PS0 + t * (PS1 + t * (PS2 + t * (PS3 + t * (PS4 + t * PS5)))));
        double q = 1.0 + t * (QS1 + t * (QS2 + t * (QS3 + t * QS4)));
        return p / q;
    }

    public static double asin(double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000) {
            if (((ix - 0x3ff00000) | lo(x)) == 0) return x * PIO2_HI + x * PIO2_LO;
            return NAN;
        }
        if (ix < 0x3fe00000) {
            if (ix < 0x3e400000) return x;
            return x + x * asinRatio(x * x);
        }
        double w = 1.0 - Math.abs(x);
        double t = w * 0.5;
        double s = Math.sqrt(t);
        if (ix >= 0x3FEF3333) {
            t = PIO2_HI - (2.0 * (s + s * asinRatio(t)) - PIO2_LO);
        } else {
            double r = asinRatio(t);
            double ws = words(hi(s), 0);
            double c = (t - ws * ws) / (s + ws);
            double p = 2.0 * s * r - (PIO2_LO - 2.0 * c);
            double q = PIO4_HI - 2.0 * ws;
            t = PIO4_HI - (p - q);
        }
        return hx > 0 ? t : -t;
    }

    public static double acos(double x) {
        int hx = hi(x);
        int ix = hx & 0x7fffffff;
        if (ix >= 0x3ff00000) {
            if (((ix - 0x3ff00000) | lo(x)) == 0) return hx > 0 ? 0.0 : PI + 2.0 * PIO2_LO;
            return NAN;
        }
        if (ix < 0x3fe00000) {
            if (ix <= 0x3c600000) return PIO2_HI + PIO2_LO;
            return PIO2_HI - (x - (PIO2_LO - x * asinRatio(x * x)));
        }
        if (hx < 0) {
            double z = (1.0 + x) * 0.5;
            double s = Math.sqrt(z);
            double w = asinRatio(z) * s - PIO2_LO;
            return PI - 2.0 * (s + w);
        }
        double z = (1.0 - x) * 0.5;
        double s = Math.sqrt(z);
        double df = words(hi(s), 0);
        double c = (z - df * df) / (s + df);
        double w = asinRatio(z) * s + c;
        return 2.0 * (df + w);
    }

    // =================================================================== pow

    private static final double TWO53 = 9007199254740992.0;
    private static final double TWO900 = 0x1p900;

    /** x * 2^e for a nonzero finite x, rounded once. */
    private static double scale(double x, long e) {
        int k = exponentOf(x);
        x = mulPow2(x, -k);
        e += k;
        if (e > 2100) return x * INF;
        while (e > 1023) {
            x *= pow2(1023);
            e -= 1023;
        }
        if (e >= -1022) return x * pow2((int) e);
        if (e < -2044) return x * 0.0;
        // x * 2^(e + 1022) is still normal, so only the last multiplication rounds.
        return x * pow2((int) (e + 1022)) * pow2(-1022);
    }

    /** x * 2^k for any k from -2044 to 2046 where the result is normal: exact. */
    private static double mulPow2(double x, int k) {
        int half = k / 2;
        return x * pow2(half) * pow2(k - half);
    }

    /** The exponent of a nonzero finite x: x = m * 2^e with 1 <= |m| < 2. */
    private static int exponentOf(double x) {
        int e = (int) ((bits(x) >>> 52) & 0x7ff);
        if (e != 0) return e - 1023;
        return exponentOf(x * TWO54) - 54;
    }

    /**
     * |x|^n for an integer n of at most 2^53 in magnitude: binary powering in double-double arithmetic, with the
     * exponents kept apart so that nothing overflows on the way. A negative n powers 1/|x|.
     */
    private static double powInt(double ax, double y) {
        // ax = m * 2^e0 with m from 1 to 2, which the products below can split without overflow.
        int e0 = exponentOf(ax);
        double m = mulPow2(ax, -e0);
        double bh = m;
        double bl = 0.0;
        long be = e0;
        if (y < 0) {
            bh = 1.0 / m;
            double p = bh * m;
            bl = ((1.0 - p) - prodErr(bh, m, p)) / m;
            int k = exponentOf(bh);
            bh *= pow2(-k);
            bl *= pow2(-k);
            be = k - e0;
        }
        double rh = 1.0;
        double rl = 0.0;
        long re = 0;
        long n = (long) Math.abs(y);
        while (true) {
            if ((n & 1) != 0) {
                double p = rh * bh;
                double q = prodErr(rh, bh, p) + (rh * bl + rl * bh);
                rh = p + q;
                rl = q - (rh - p);
                int k = exponentOf(rh);
                rh *= pow2(-k);
                rl *= pow2(-k);
                re += be + k;
            }
            n >>>= 1;
            if (n == 0) break;
            double p = bh * bh;
            double q = prodErr(bh, bh, p) + 2.0 * (bh * bl);
            bh = p + q;
            bl = q - (bh - p);
            int k = exponentOf(bh);
            bh *= pow2(-k);
            bl *= pow2(-k);
            be = 2 * be + k;
            // Far past the range of a double: the rest only moves further, the same way.
            if (be > 4000) return INF;
            if (be < -4000) return 0.0;
        }
        return scale(rh + rl, re);
    }

    private static final double C3H = 6.66666666666666629659e-01;
    private static final double C3L = 0x1.5555555555555p-55;

    /** log(x) for a positive finite x, as hi + lo, to about 2^-65 relative. */
    private static void logDD(double x, double[] out) {
        int k = 0;
        if (x < 0x1p-1022) {
            x *= TWO54;
            k = -54;
        }
        int hx = hi(x);
        k += (hx >>> 20) - 1023;
        hx &= 0x000fffff;
        int i = (hx + 0x95f64) & 0x100000;
        x = withHi(x, hx | (i ^ 0x3ff00000));
        k += i >>> 20;
        double f = x - 1.0;
        // s = f / (2 + f) as sh + sl; log(1 + f) = 2s + 2s^3/3 + 2s^5/5 + ...
        double dh = 2.0 + f;
        double dl = f - (dh - 2.0);
        double sh = f / dh;
        double p = sh * dh;
        double sl = (((f - p) - prodErr(sh, dh, p)) - sh * dl) / dh;
        // s^2 and s^3 in double-double.
        double zh = sh * sh;
        double zl = prodErr(sh, sh, zh) + 2.0 * (sh * sl);
        double z2 = zh + zl;
        zl = zl - (z2 - zh);
        zh = z2;
        double ch = zh * sh;
        double cl = prodErr(zh, sh, ch) + (zh * sl + zl * sh);
        double c2 = ch + cl;
        cl = cl - (c2 - ch);
        ch = c2;
        // 2s^3/3 in double-double; the rest of the series in double.
        double th = ch * C3H;
        double tl = prodErr(ch, C3H, th) + (ch * C3L + cl * C3H);
        double z = zh;
        double rest = ch * z * (0.4 + z * (2.0 / 7 + z * (2.0 / 9 + z * (2.0 / 11 + z * (2.0 / 13 + z * (2.0 / 15
            + z * (2.0 / 17 + z * (2.0 / 19 + z * (2.0 / 21 + z * (2.0 / 23 + z * (2.0 / 25 + z * (2.0 / 27))))))))))));
        double a = 2.0 * sh;
        double h = a + th;
        double l = (th - (h - a)) + (2.0 * sl + tl + rest);
        double dk = k;
        double kh = dk * LN2_HI;
        double hh = kh + h;
        double ll = sumErr(kh, h, hh) + (l + dk * LN2_LO);
        out[0] = hh + ll;
        out[1] = ll - (out[0] - hh);
    }

    /**
     * exp(xh + xl) for |xh| below 746 and a tiny xl: fdlibm's exp with xl taken into the reduced argument, and
     * the scaling by 2^k rounded once.
     */
    private static double expDD(double xh, double xl) {
        int xsb = hi(xh) >>> 31;
        int k = (int) (INVLN2 * xh + HALF[xsb]);
        double t = k;
        double hi = xh - t * LN2HI[0];
        double lo = t * LN2LO[0] - xl;
        double x = hi - lo;
        double z = x * x;
        double c = x - z * (P1 + z * (P2 + z * (P3 + z * (P4 + z * P5))));
        double y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
        return scale(y, k);
    }

    /** x^y for a positive finite x and a finite y: exp(y * log(x)), with log(x) and the product in double-double. */
    private static double powGeneral(double ax, double y) {
        if (Math.abs(y) > TWO900) {
            // |y * log(x)| is then far past 745 for every x but 1, which pow handled.
            return (ax > 1.0) == (y > 0) ? INF : 0.0;
        }
        double[] l = new double[2];
        logDD(ax, l);
        double th = y * l[0];
        if (th > 710.0) return INF;
        if (th < -746.0) return 0.0;
        double tl = prodErr(y, l[0], th) + y * l[1];
        double t = th + tl;
        return expDD(t, tl - (t - th));
    }

    private static boolean isInteger(double y) {
        return Math.floor(y) == y;
    }

    /** Whether the finite integer y is odd. */
    private static boolean isOdd(double y) {
        return Math.abs(y) < TWO53 && Math.floor(y * 0.5) * 2.0 != y;
    }

    /** x to the power y, with the special cases of C99 Annex F. */
    public static double pow(double x, double y) {
        if (y == 0.0 || x == 1.0) return 1.0;
        if (Double.isNaN(x) || Double.isNaN(y)) return NAN;
        double ax = Math.abs(x);
        if (Double.isInfinite(y)) {
            if (ax == 1.0) return 1.0;
            return (ax < 1.0) == (y < 0) ? INF : 0.0;
        }
        boolean odd = isInteger(y) && isOdd(y);
        boolean negX = hi(x) < 0;
        if (x == 0.0 || Double.isInfinite(x)) {
            boolean big = x != 0.0;
            boolean inf = (y < 0) != big;
            double r = inf ? INF : 0.0;
            return odd && negX ? -r : r;
        }
        if (negX && !isInteger(y)) return NAN;
        // pow(x, 0.5) is sqrt(x), which is correctly rounded.
        if (y == 0.5) return Math.sqrt(x);
        double r = isInteger(y) && Math.abs(y) <= TWO53 ? powInt(ax, y) : powGeneral(ax, y);
        return odd && negX ? -r : r;
    }
}
