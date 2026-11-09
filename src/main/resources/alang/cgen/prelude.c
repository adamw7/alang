/*
 * ALang C runtime prelude.
 *
 * Heap-free: no malloc/calloc/realloc/free, no stdio. Output goes through a static
 * buffer to write(2). Numbers are formatted here, not by printf, so the bytes are
 * identical to the reference interpreter on every libc and in every locale.
 *
 * Deterministic floating point requires IEEE 754 binary32/binary64 arithmetic with
 * round-to-nearest-even, no excess precision and no contraction into fused
 * multiply-add. Compile with: cc -std=c99 -O2 -ffp-contract=off file.c -lm
 */
#define _POSIX_C_SOURCE 200112L
#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <float.h>
#include <math.h>

#if defined(__FAST_MATH__)
#error "ALang requires strict IEEE 754 arithmetic: do not compile with -ffast-math"
#endif
/*
 * f32/f64 operations must be evaluated in their own precision. FLT_EVAL_METHOD 0 means
 * exactly that; 16 and 32 (ISO/IEC TS 18661-3) only widen narrower _FloatN types, so float
 * and double are unaffected. 1, 2 and 64 would widen f32 and/or f64 and are rejected.
 */
#if !defined(FLT_EVAL_METHOD) || (FLT_EVAL_METHOD != 0 && FLT_EVAL_METHOD != 16 && FLT_EVAL_METHOD != 32)
#error "ALang requires float/double to be evaluated in their own precision (use SSE2 on x86, not x87)"
#endif
#if defined(__clang__)
#pragma STDC FP_CONTRACT OFF
#elif defined(__GNUC__)
#pragma GCC optimize ("fp-contract=off")
#endif

#ifdef AL_WRITE
/* Freestanding targets: define AL_WRITE(fd, ptr, len) to emit bytes (e.g. to a UART). */
static void al_write(int fd, const uint8_t *p, uint32_t n) { AL_WRITE(fd, p, n); }
#else
#include <unistd.h>
static void al_write(int fd, const uint8_t *p, uint32_t n) {
    while (n > 0) {
        ssize_t w = write(fd, p, n);
        if (w <= 0) _Exit(2);
        p += w;
        n -= (uint32_t)w;
    }
}
#endif

/* ---------------------------------------------------------------- output sinks */

/* kind 0: a str buffer (len counts past cap so overflow can be reported), 1: stdout, 2: stderr */
typedef struct {
    int kind;
    uint8_t *buf;
    uint32_t cap;
    uint32_t len;
} al_out;

static uint8_t al_stdout_buf[4096];
static uint32_t al_stdout_len;

static void al_flush(void) {
    al_write(1, al_stdout_buf, al_stdout_len);
    al_stdout_len = 0;
}

static void al_put(al_out *o, const void *data, uint32_t n) {
    const uint8_t *p = (const uint8_t *)data;
    uint32_t i;
    if (o->kind == 1) {
        for (i = 0; i < n; i++) {
            if (al_stdout_len == sizeof al_stdout_buf) al_flush();
            al_stdout_buf[al_stdout_len++] = p[i];
        }
    } else if (o->kind == 2) {
        al_write(2, p, n);
    } else {
        for (i = 0; i < n; i++) {
            if (o->len < o->cap) o->buf[o->len] = p[i];
            o->len++;
        }
    }
}

#define AL_PUTS(o, s) al_put((o), (s), (uint32_t)(sizeof(s) - 1))

static al_out al_stdout_sink(void) {
    al_out o = {1, 0, 0, 0};
    return o;
}

static al_out al_stderr_sink(void) {
    al_out o = {2, 0, 0, 0};
    return o;
}

/* ---------------------------------------------------------------- formatting */

static void al_fmt_u64(al_out *o, uint64_t v) {
    uint8_t d[20];
    uint32_t n = 0;
    do {
        d[19 - n] = (uint8_t)('0' + v % 10);
        v /= 10;
        n++;
    } while (v != 0);
    al_put(o, d + 20 - n, n);
}

static void al_fmt_i64(al_out *o, int64_t v) {
    if (v < 0) {
        AL_PUTS(o, "-");
        al_fmt_u64(o, (uint64_t)0 - (uint64_t)v);
    } else {
        al_fmt_u64(o, (uint64_t)v);
    }
}

static void al_fmt_bool(al_out *o, bool b) {
    if (b) AL_PUTS(o, "true");
    else AL_PUTS(o, "false");
}

/* Fixed-size decimal big number (base 1e9, little endian), large enough for DBL_MAX * 1e6. */
typedef struct {
    uint32_t d[40];
    int n;
} al_big;

static void al_big_mul(al_big *b, uint32_t k) {
    uint64_t carry = 0;
    int i;
    for (i = 0; i < b->n; i++) {
        uint64_t v = (uint64_t)b->d[i] * k + carry;
        b->d[i] = (uint32_t)(v % 1000000000u);
        carry = v / 1000000000u;
    }
    while (carry != 0) {
        b->d[b->n++] = (uint32_t)(carry % 1000000000u);
        carry /= 1000000000u;
    }
}

/* Halves the number; returns the bit shifted out. */
static int al_big_half(al_big *b) {
    uint64_t rem = 0;
    int i;
    for (i = b->n - 1; i >= 0; i--) {
        uint64_t cur = rem * 1000000000u + b->d[i];
        b->d[i] = (uint32_t)(cur / 2);
        rem = cur % 2;
    }
    while (b->n > 0 && b->d[b->n - 1] == 0) b->n--;
    return (int)rem;
}

static void al_big_inc(al_big *b) {
    int i = 0;
    for (;;) {
        if (i == b->n) {
            b->d[b->n++] = 1;
            return;
        }
        if (++b->d[i] < 1000000000u) return;
        b->d[i] = 0;
        i++;
    }
}

/*
 * Prints |d| rounded to 6 decimals (round-half-even on the exact binary value), with
 * trailing zeros removed but at least one decimal kept: 1.5, 2.0, 0.333333, -0.0, nan, inf.
 */
static void al_fmt_f64(al_out *o, double d) {
    al_big b;
    int e, i, half = 0, sticky = 0, len = 0, end;
    double fr;
    uint64_t m;
    char digits[400];
    if (isnan(d)) {
        AL_PUTS(o, "nan");
        return;
    }
    if (isinf(d)) {
        if (d > 0) AL_PUTS(o, "inf");
        else AL_PUTS(o, "-inf");
        return;
    }
    if (signbit(d)) AL_PUTS(o, "-");
    fr = frexp(fabs(d), &e);
    m = (uint64_t)ldexp(fr, 53);
    e -= 53;
    b.n = 0;
    while (m != 0) {
        b.d[b.n++] = (uint32_t)(m % 1000000000u);
        m /= 1000000000u;
    }
    al_big_mul(&b, 1000000u);
    if (e >= 0) {
        for (i = 0; i < e; i++) al_big_mul(&b, 2);
    } else {
        for (i = 0; i < -e; i++) {
            if (b.n == 0) {
                sticky |= half;
                half = 0;
                break;
            }
            sticky |= half;
            half = al_big_half(&b);
        }
        if (half && (sticky || (b.n > 0 && (b.d[0] & 1)))) al_big_inc(&b);
    }
    /* decimal digits, most significant first */
    if (b.n == 0) {
        digits[len++] = '0';
    } else {
        uint32_t top = b.d[b.n - 1];
        char tmp[10];
        int t = 0;
        do {
            tmp[t++] = (char)('0' + top % 10);
            top /= 10;
        } while (top != 0);
        while (t > 0) digits[len++] = tmp[--t];
        for (i = b.n - 2; i >= 0; i--) {
            uint32_t limb = b.d[i];
            int k;
            for (k = 8; k >= 0; k--) {
                digits[len + k] = (char)('0' + limb % 10);
                limb /= 10;
            }
            len += 9;
        }
    }
    /* at least 7 digits: one before the point and six after */
    if (len < 7) {
        int pad = 7 - len;
        for (i = len - 1; i >= 0; i--) digits[i + pad] = digits[i];
        for (i = 0; i < pad; i++) digits[i] = '0';
        len = 7;
    }
    al_put(o, digits, (uint32_t)(len - 6));
    AL_PUTS(o, ".");
    end = len;
    while (end > len - 5 && digits[end - 1] == '0') end--;
    al_put(o, digits + len - 6, (uint32_t)(end - (len - 6)));
}

static const char al_hex[] = "0123456789abcdef";

static void al_fmt_str(al_out *o, const uint8_t *b, uint32_t n, int nested) {
    uint32_t i;
    if (!nested) {
        al_put(o, b, n);
        return;
    }
    AL_PUTS(o, "\"");
    for (i = 0; i < n; i++) {
        uint8_t c = b[i];
        if (c == '"') AL_PUTS(o, "\\\"");
        else if (c == '\\') AL_PUTS(o, "\\\\");
        else if (c == '\n') AL_PUTS(o, "\\n");
        else if (c == '\t') AL_PUTS(o, "\\t");
        else if (c == '\r') AL_PUTS(o, "\\r");
        else if (c < 0x20 || c == 0x7f) {
            uint8_t esc[4];
            esc[0] = '\\';
            esc[1] = 'x';
            esc[2] = (uint8_t)al_hex[c >> 4];
            esc[3] = (uint8_t)al_hex[c & 15];
            al_put(o, esc, 4);
        } else {
            al_put(o, &c, 1);
        }
    }
    AL_PUTS(o, "\"");
}

static bool al_eq_bytes(const uint8_t *a, uint32_t an, const uint8_t *b, uint32_t bn) {
    uint32_t i;
    if (an != bn) return false;
    for (i = 0; i < an; i++) {
        if (a[i] != b[i]) return false;
    }
    return true;
}

/* ---------------------------------------------------------------- traps */

static const char *al_source_path;

static al_out al_trap_begin(const char *code) {
    al_out e = al_stderr_sink();
    al_flush();
    AL_PUTS(&e, "trap[");
    al_put(&e, code, 5);
    AL_PUTS(&e, "]: ");
    return e;
}

static void al_trap_end(al_out *e, int line, int col) {
    const char *p = al_source_path;
    uint32_t n = 0;
    while (p[n] != 0) n++;
    AL_PUTS(e, "\n  --> ");
    al_put(e, p, n);
    AL_PUTS(e, ":");
    al_fmt_i64(e, line);
    AL_PUTS(e, ":");
    al_fmt_i64(e, col);
    AL_PUTS(e, "\n");
    _Exit(1);
}

/* The message is a string literal; its length comes from sizeof, so a NUL byte in it is kept. */
static void al_trap_n(const char *code, const char *msg, uint32_t n, int line, int col) {
    al_out e = al_trap_begin(code);
    al_put(&e, msg, n);
    al_trap_end(&e, line, col);
}

#define AL_TRAP(code, msg, line, col) al_trap_n((code), (msg), (uint32_t)(sizeof(msg) - 1), (line), (col))

static void al_overflow(const char *op, const char *type, int line, int col) {
    al_out e = al_trap_begin("R0001");
    const char *p;
    AL_PUTS(&e, "integer overflow: result of `");
    for (p = op; *p; p++) al_put(&e, p, 1);
    AL_PUTS(&e, "` does not fit in ");
    for (p = type; *p; p++) al_put(&e, p, 1);
    al_trap_end(&e, line, col);
}

static void al_puts_c(al_out *o, const char *s) {
    uint32_t n = 0;
    while (s[n] != 0) n++;
    al_put(o, s, n);
}

static uint32_t al_idx_s(int64_t i, uint32_t len, int line, int col) {
    if (i < 0 || i >= (int64_t)len) {
        al_out e = al_trap_begin("R0003");
        AL_PUTS(&e, "index ");
        al_fmt_i64(&e, i);
        AL_PUTS(&e, " is out of bounds for length ");
        al_fmt_u64(&e, len);
        al_trap_end(&e, line, col);
    }
    return (uint32_t)i;
}

static uint32_t al_idx_u(uint64_t i, uint32_t len, int line, int col) {
    if (i >= (uint64_t)len) {
        al_out e = al_trap_begin("R0003");
        AL_PUTS(&e, "index ");
        al_fmt_u64(&e, i);
        AL_PUTS(&e, " is out of bounds for length ");
        al_fmt_u64(&e, len);
        al_trap_end(&e, line, col);
    }
    return (uint32_t)i;
}

static void al_shift_trap(int neg, int64_t s, uint64_t u, const char *type, int bits, int line, int col) {
    al_out e = al_trap_begin("R0012");
    AL_PUTS(&e, "shift amount ");
    if (neg) al_fmt_i64(&e, s);
    else al_fmt_u64(&e, u);
    AL_PUTS(&e, " is out of range for ");
    al_puts_c(&e, type);
    AL_PUTS(&e, " (must be 0..");
    al_fmt_i64(&e, bits - 1);
    AL_PUTS(&e, ")");
    al_trap_end(&e, line, col);
}

static int al_shamt_s(int64_t n, int bits, const char *type, int line, int col) {
    if (n < 0 || n >= bits) al_shift_trap(n < 0, n, (uint64_t)n, type, bits, line, col);
    return (int)n;
}

static int al_shamt_u(uint64_t n, int bits, const char *type, int line, int col) {
    if (n >= (uint64_t)bits) al_shift_trap(0, 0, n, type, bits, line, col);
    return (int)n;
}

static void al_div_zero(const char *what, int line, int col) {
    al_out e = al_trap_begin("R0002");
    al_puts_c(&e, what);
    al_trap_end(&e, line, col);
}

static void al_range_trap_s(int64_t v, const char *type, int line, int col) {
    al_out e = al_trap_begin("R0009");
    AL_PUTS(&e, "value ");
    al_fmt_i64(&e, v);
    AL_PUTS(&e, " does not fit in ");
    al_puts_c(&e, type);
    al_trap_end(&e, line, col);
}

static void al_range_trap_u(uint64_t v, const char *type, int line, int col) {
    al_out e = al_trap_begin("R0009");
    AL_PUTS(&e, "value ");
    al_fmt_u64(&e, v);
    AL_PUTS(&e, " does not fit in ");
    al_puts_c(&e, type);
    al_trap_end(&e, line, col);
}

/* float -> integer: NaN and out-of-range values trap; otherwise truncate toward zero. */
static double al_f2i_check(double d, double lo, double hi_excl, const char *type, int line, int col) {
    double t;
    if (isnan(d)) {
        al_out e = al_trap_begin("R0009");
        AL_PUTS(&e, "cannot convert NaN to ");
        al_puts_c(&e, type);
        al_trap_end(&e, line, col);
    }
    t = d < 0 ? ceil(d) : floor(d);
    if (!(t >= lo && t < hi_excl)) {
        al_out e = al_trap_begin("R0009");
        AL_PUTS(&e, "value ");
        al_fmt_f64(&e, d);
        AL_PUTS(&e, " does not fit in ");
        al_puts_c(&e, type);
        al_trap_end(&e, line, col);
    }
    return t;
}

/* u64 -> float, correctly rounded and identical to the interpreter (halve with sticky bit). */
static double al_u64_to_f64(uint64_t v) {
    if (v >> 63) return (double)(int64_t)((v >> 1) | (v & 1)) * 2.0;
    return (double)(int64_t)v;
}

static float al_u64_to_f32(uint64_t v) {
    if (v >> 63) return (float)(int64_t)((v >> 1) | (v & 1)) * 2.0f;
    return (float)(int64_t)v;
}
