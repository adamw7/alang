/*
 * ALang C runtime prelude.
 *
 * Heap-free: no malloc/calloc/realloc/free, no stdio. Output goes through a static
 * buffer to write(2) (_write on Windows). Numbers are formatted here, not by printf, so the bytes are
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
/*
 * Freestanding targets: define AL_WRITE(fd, ptr, len) to emit bytes (e.g. to a UART), and AL_READ(fd, ptr,
 * len) to read the input of a fn main that takes one: it returns the bytes read, 0 at the end of the input.
 * Without AL_READ, the input is empty.
 */
static void al_write(int fd, const uint8_t *p, uint32_t n) { AL_WRITE(fd, p, n); }
static int64_t al_read(int fd, uint8_t *p, uint32_t n) {
#ifdef AL_READ
    return (int64_t)AL_READ(fd, p, n);
#else
    (void)fd;
    (void)p;
    (void)n;
    return 0;
#endif
}
#elif defined(_WIN32)
/*
 * Windows: the C runtime opens stdout and stderr in text mode, which writes every "\n" as
 * "\r\n". Binary mode keeps the bytes those of the reference interpreter.
 */
#include <io.h>
#include <fcntl.h>
static void al_write(int fd, const uint8_t *p, uint32_t n) {
    _setmode(fd, _O_BINARY);
    while (n > 0) {
        int w = _write(fd, p, n);
        if (w <= 0) _Exit(2);
        p += w;
        n -= (uint32_t)w;
    }
}
static int64_t al_read(int fd, uint8_t *p, uint32_t n) {
    _setmode(fd, _O_BINARY);
    return _read(fd, p, n);
}
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
static int64_t al_read(int fd, uint8_t *p, uint32_t n) {
    return (int64_t)read(fd, p, n);
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
/* The imported files: a line in the k-th (from 1) is passed to a trap as -(1048576 * k + line). */
static const char *const *al_import_paths;

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
    if (line < 0) {
        p = al_import_paths[(-line >> 20) - 1];
        line = -line & 0xFFFFF;
    }
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

/*
 * Reads all of the standard input into the str parameter of fn main, whose name is param. Input that does
 * not fit traps as an append past the capacity does; input that cannot be read ends the program (exit 2).
 */
static void al_read_input(uint8_t *b, uint32_t *len, uint32_t cap, const char *param, int line, int col) {
    uint8_t extra;
    *len = 0;
    for (;;) {
        int64_t r = *len < cap ? al_read(0, b + *len, cap - *len) : al_read(0, &extra, 1);
        if (r < 0) _Exit(2);
        if (r == 0) return;
        if (*len == cap) {
            al_out e = al_trap_begin("R0004");
            AL_PUTS(&e, "standard input exceeds str capacity ");
            al_fmt_u64(&e, cap);
            AL_PUTS(&e, " of `");
            al_puts_c(&e, param);
            AL_PUTS(&e, "`");
            al_trap_end(&e, line, col);
        }
        *len += (uint32_t)r;
    }
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

/* ---------------------------------------------------------------- standard library */
/*
 * Each function behaves exactly like its counterpart in the interpreter (alang.interp.Stdlib): the same
 * results, and the same traps with the same messages, checked in the same order.
 */

/* A position argument of any integer type: its signed or unsigned value, for checks and messages. */
typedef struct {
    int u;
    int64_t s;
    uint64_t v;
} al_pos;

static al_pos al_pos_s(int64_t s) {
    al_pos p;
    p.u = 0;
    p.s = s;
    p.v = 0;
    return p;
}

static al_pos al_pos_u(uint64_t v) {
    al_pos p;
    p.u = 1;
    p.s = 0;
    p.v = v;
    return p;
}

/* Whether 0 <= p <= len. */
static int al_pos_le(al_pos p, uint32_t len) {
    return p.u ? p.v <= (uint64_t)len : (p.s >= 0 && p.s <= (int64_t)len);
}

/* The value of a position already checked to be within 0..len. */
static uint32_t al_pos_get(al_pos p) {
    return p.u ? (uint32_t)p.v : (uint32_t)p.s;
}

static void al_fmt_pos(al_out *o, al_pos p) {
    if (p.u) al_fmt_u64(o, p.v);
    else al_fmt_i64(o, p.s);
}

/*
 * Checks a slice; returns its length, and its start in *start. The copy loop runs to this length, so a
 * compiler sees it bounded by the check (gcc warned of a loop it bounded with an unchecked length).
 */
static uint32_t al_slice(al_pos f, al_pos t, uint32_t len, uint32_t *start, int line, int col) {
    if (!al_pos_le(f, len) || !al_pos_le(t, len) || al_pos_get(f) > al_pos_get(t)) {
        al_out e = al_trap_begin("R0003");
        AL_PUTS(&e, "slice ");
        al_fmt_pos(&e, f);
        AL_PUTS(&e, "..");
        al_fmt_pos(&e, t);
        AL_PUTS(&e, " is out of range for length ");
        al_fmt_u64(&e, len);
        al_trap_end(&e, line, col);
    }
    *start = al_pos_get(f);
    return al_pos_get(t) - *start;
}

static uint32_t al_insert_pos(al_pos p, uint32_t len, int line, int col) {
    if (!al_pos_le(p, len)) {
        al_out e = al_trap_begin("R0003");
        AL_PUTS(&e, "insert position ");
        al_fmt_pos(&e, p);
        AL_PUTS(&e, " is out of range for length ");
        al_fmt_u64(&e, len);
        al_trap_end(&e, line, col);
    }
    return al_pos_get(p);
}

/* ---- math */

static int al_mul_ovf_s(int64_t a, int64_t b) {
    if (a > 0) return b > 0 ? a > INT64_MAX / b : b < INT64_MIN / a;
    return b > 0 ? a < INT64_MIN / b : (a != 0 && b < INT64_MAX / a);
}

static uint64_t al_pow_exp(int64_t n, int line, int col) {
    if (n < 0) {
        al_out e = al_trap_begin("R0013");
        AL_PUTS(&e, "pow exponent ");
        al_fmt_i64(&e, n);
        AL_PUTS(&e, " is negative");
        al_trap_end(&e, line, col);
    }
    return (uint64_t)n;
}

/*
 * x to the power n within lo..hi. |x| <= 1 needs no loop; otherwise every step at least doubles |r|, so
 * the loop overflows within 64 steps, and since |r| only grows, a step overflows exactly when the result
 * would.
 */
static int64_t al_pow_s(int64_t x, uint64_t n, int64_t lo, int64_t hi, const char *type, int line, int col) {
    int64_t r = 1;
    if (x == 0) return n == 0 ? 1 : 0;
    if (x == 1) return 1;
    if (x == -1) return (n & 1u) ? -1 : 1;
    while (n > 0) {
        if (al_mul_ovf_s(r, x) || r * x < lo || r * x > hi) al_overflow("pow", type, line, col);
        r *= x;
        n--;
    }
    return r;
}

static uint64_t al_pow_u(uint64_t x, uint64_t n, uint64_t hi, const char *type, int line, int col) {
    uint64_t r = 1;
    if (x == 0) return n == 0 ? 1 : 0;
    if (x == 1) return 1;
    while (n > 0) {
        if (r > UINT64_MAX / x || r * x > hi) al_overflow("pow", type, line, col);
        r *= x;
        n--;
    }
    return r;
}

static uint64_t al_mag(int64_t v) {
    return v < 0 ? (uint64_t)0 - (uint64_t)v : (uint64_t)v;
}

static uint64_t al_gcd_u64(uint64_t a, uint64_t b) {
    while (b != 0) {
        uint64_t t = a % b;
        a = b;
        b = t;
    }
    return a;
}

/* gcd and lcm of magnitudes; the result must be at most hi, the largest value of the type. */
static uint64_t al_gcd(uint64_t a, uint64_t b, uint64_t hi, const char *type, int line, int col) {
    uint64_t g = al_gcd_u64(a, b);
    if (g > hi) al_overflow("gcd", type, line, col);
    return g;
}

static uint64_t al_lcm(uint64_t a, uint64_t b, uint64_t hi, const char *type, int line, int col) {
    uint64_t q;
    if (a == 0 || b == 0) return 0;
    q = a / al_gcd_u64(a, b);
    if (b > UINT64_MAX / q || q * b > hi) al_overflow("lcm", type, line, col);
    return q * b;
}

static double al_trunc(double d) {
    return d < 0 ? ceil(d) : floor(d);
}

/* Halves away from zero, as C's round(); exact, since d - trunc(d) and trunc(d) +- 1 are. */
static double al_round(double d) {
    double t, f;
    if (isnan(d) || isinf(d)) return d;
    t = al_trunc(d);
    f = d - t;
    if (f >= 0.5) return t + 1.0;
    if (f <= -0.5) return t - 1.0;
    return t;
}

/* ---- text */

static int al_is_space(uint32_t c) {
    return c == 32 || (c >= 9 && c <= 13);
}

/* The first position at or after `from` where n occurs in h, or -1. */
static int64_t al_find_bytes(const uint8_t *h, uint32_t hn, const uint8_t *n, uint32_t nn, uint32_t from) {
    uint32_t i, j;
    for (i = from; i <= hn && nn <= hn - i; i++) {
        for (j = 0; j < nn && h[i + j] == n[j]; j++) {
        }
        if (j == nn) return (int64_t)i;
    }
    return -1;
}

static bool al_starts_with(const uint8_t *s, uint32_t n, const uint8_t *p, uint32_t pn) {
    return pn <= n && al_eq_bytes(s, pn, p, pn);
}

static bool al_ends_with(const uint8_t *s, uint32_t n, const uint8_t *p, uint32_t pn) {
    return pn <= n && al_eq_bytes(s + (n - pn), pn, p, pn);
}

static uint32_t al_copy_bytes(uint8_t *dst, const uint8_t *src, uint32_t n) {
    uint32_t i;
    for (i = 0; i < n; i++) dst[i] = src[i];
    return n;
}

static uint32_t al_trim_into(uint8_t *dst, const uint8_t *s, uint32_t n) {
    uint32_t start = 0;
    while (start < n && al_is_space(s[start])) start++;
    while (n > start && al_is_space(s[n - 1])) n--;
    return al_copy_bytes(dst, s + start, n - start);
}

static uint32_t al_case_into(uint8_t *dst, const uint8_t *s, uint32_t n, int upper) {
    uint32_t i;
    for (i = 0; i < n; i++) {
        uint8_t c = s[i];
        if (upper && c >= 97 && c <= 122) c = (uint8_t)(c - 32);
        else if (!upper && c >= 65 && c <= 90) c = (uint8_t)(c + 32);
        dst[i] = c;
    }
    return n;
}

/*
 * The next piece of split (words = 0) or words (words = 1) of s, from *pos on: sets [*from, *to) and
 * returns 1, or returns 0 when there are no more. After the last piece of split, *pos is n + 1.
 */
static int al_next_piece(const uint8_t *s, uint32_t n, uint32_t *pos, const uint8_t *sep, uint32_t sn, int words,
                         uint32_t *from, uint32_t *to) {
    uint32_t i = *pos;
    if (words) {
        while (i < n && al_is_space(s[i])) i++;
        *pos = i;
        if (i == n) return 0;
        *from = i;
        while (i < n && !al_is_space(s[i])) i++;
        *to = i;
        *pos = i;
        return 1;
    }
    if (i > n) return 0;
    {
        int64_t hit = al_find_bytes(s, n, sep, sn, i);
        *from = i;
        *to = hit < 0 ? n : (uint32_t)hit;
        *pos = hit < 0 ? n + 1 : (uint32_t)hit + sn;
    }
    return 1;
}

/* count() in a str: the non-overlapping occurrences of p, left to right. */
static int64_t al_count_bytes(const uint8_t *s, uint32_t n, const uint8_t *p, uint32_t pn, int line, int col) {
    int64_t hit, k = 0;
    if (pn == 0) AL_TRAP("R0013", "count pattern is empty", line, col);
    for (hit = al_find_bytes(s, n, p, pn, 0); hit >= 0; hit = al_find_bytes(s, n, p, pn, (uint32_t)hit + pn)) k++;
    return k;
}

/* str[N](s) or list[T; N](xs) given a longer value. */
static void al_resize_trap(int str, uint32_t n, const char *type, int line, int col) {
    al_out e = al_trap_begin("R0004");
    if (str) AL_PUTS(&e, "a str of ");
    else AL_PUTS(&e, "a list of ");
    al_fmt_u64(&e, n);
    if (str) AL_PUTS(&e, " bytes does not fit in ");
    else AL_PUTS(&e, " elements does not fit in ");
    al_puts_c(&e, type);
    al_trap_end(&e, line, col);
}

/* The part of a piece check that does not depend on the list's type: a piece too long for its str. */
static void al_piece_trap(int words, uint32_t n, uint32_t k, int line, int col) {
    al_out e = al_trap_begin("R0004");
    if (words) AL_PUTS(&e, "words");
    else AL_PUTS(&e, "split");
    AL_PUTS(&e, " piece of ");
    al_fmt_u64(&e, n);
    AL_PUTS(&e, " bytes exceeds str capacity ");
    al_fmt_u64(&e, k);
    al_trap_end(&e, line, col);
}

static void al_need_trap(const char *what, uint64_t need, uint32_t cap, int line, int col) {
    al_out e = al_trap_begin("R0004");
    al_puts_c(&e, what);
    AL_PUTS(&e, " needs ");
    al_fmt_u64(&e, need);
    AL_PUTS(&e, " bytes, more than str capacity ");
    al_fmt_u64(&e, cap);
    al_trap_end(&e, line, col);
}

/* replace() into dst, which holds cap bytes; returns the length. */
static uint32_t al_replace_into(uint8_t *dst, uint32_t cap, const uint8_t *s, uint32_t n, const uint8_t *f, uint32_t fn,
                                const uint8_t *t, uint32_t tn, int line, int col) {
    int64_t hit;
    uint64_t need = n;
    uint32_t pos = 0, len = 0;
    if (fn == 0) AL_TRAP("R0013", "replace pattern is empty", line, col);
    for (hit = al_find_bytes(s, n, f, fn, 0); hit >= 0; hit = al_find_bytes(s, n, f, fn, (uint32_t)hit + fn)) {
        need = need - fn + tn;
    }
    if (need > cap) al_need_trap("replace", need, cap, line, col);
    for (hit = al_find_bytes(s, n, f, fn, 0); hit >= 0; hit = al_find_bytes(s, n, f, fn, pos)) {
        len += al_copy_bytes(dst + len, s + pos, (uint32_t)hit - pos);
        len += al_copy_bytes(dst + len, t, tn);
        pos = (uint32_t)hit + fn;
    }
    len += al_copy_bytes(dst + len, s + pos, n - pos);
    return len;
}

/*
 * Decimal text: an optional + or -, then at least one digit, nothing else. Returns 0 for any other text;
 * otherwise sets the sign and the magnitude, and *big when the magnitude is past UINT64_MAX.
 */
static int al_parse_dec(const uint8_t *b, uint32_t n, int *neg, uint64_t *mag, int *big) {
    uint32_t i = 0;
    *neg = 0;
    *mag = 0;
    *big = 0;
    if (n > 0 && (b[0] == '+' || b[0] == '-')) {
        *neg = b[0] == '-';
        i = 1;
    }
    if (i == n) return 0;
    for (; i < n; i++) {
        uint64_t d;
        if (b[i] < '0' || b[i] > '9') return 0;
        d = (uint64_t)(b[i] - '0');
        if (*mag > (UINT64_MAX - d) / 10) *big = 1;
        else *mag = *mag * 10 + d;
    }
    return 1;
}

static bool al_is_int(const uint8_t *b, uint32_t n) {
    int neg, big;
    uint64_t mag;
    if (!al_parse_dec(b, n, &neg, &mag, &big) || big) return false;
    return mag <= (neg ? (uint64_t)INT64_MAX + 1 : (uint64_t)INT64_MAX);
}

static void al_parse_trap(const uint8_t *b, uint32_t n, const char *type, int range, int line, int col) {
    al_out e = al_trap_begin("R0009");
    AL_PUTS(&e, "cannot convert ");
    al_fmt_str(&e, b, n, 1);
    AL_PUTS(&e, " to ");
    al_puts_c(&e, type);
    if (range) AL_PUTS(&e, ": out of range");
    else AL_PUTS(&e, ": not a decimal integer");
    al_trap_end(&e, line, col);
}

/* Text to a signed type whose largest value is max (its smallest is -max - 1). */
static int64_t al_str_to_s(const uint8_t *b, uint32_t n, uint64_t max, const char *type, int line, int col) {
    int neg, big;
    uint64_t mag;
    if (!al_parse_dec(b, n, &neg, &mag, &big)) al_parse_trap(b, n, type, 0, line, col);
    if (big || mag > (neg ? max + 1 : max)) al_parse_trap(b, n, type, 1, line, col);
    if (!neg || mag == 0) return (int64_t)mag;
    return -(int64_t)(mag - 1) - 1;
}

static uint64_t al_str_to_u(const uint8_t *b, uint32_t n, uint64_t max, const char *type, int line, int col) {
    int neg, big;
    uint64_t mag;
    if (!al_parse_dec(b, n, &neg, &mag, &big)) al_parse_trap(b, n, type, 0, line, col);
    if (big || (neg && mag != 0) || mag > max) al_parse_trap(b, n, type, 1, line, col);
    return mag;
}

/* ---- collections */

/* The order of sort: -0.0 before 0.0, every NaN after everything else and equal to the others. */
static int al_cmp_f64(double a, double b) {
    if (a < b) return -1;
    if (a > b) return 1;
    if (a == b) return (signbit(b) != 0) - (signbit(a) != 0);
    return (a != a) - (b != b);
}

static int al_cmp_bytes(const uint8_t *a, uint32_t an, const uint8_t *b, uint32_t bn) {
    uint32_t i;
    for (i = 0; i < an && i < bn; i++) {
        if (a[i] != b[i]) return a[i] < b[i] ? -1 : 1;
    }
    return (an > bn) - (an < bn);
}

static void al_swap_bytes(uint8_t *a, uint8_t *b, size_t n) {
    while (n-- > 0) {
        uint8_t t = *a;
        *a++ = *b;
        *b++ = t;
    }
}

static void al_reverse(void *base, uint32_t n, size_t size) {
    uint8_t *v = (uint8_t *)base;
    uint32_t i = 0, j = n;
    while (j > i + 1) {
        j--;
        al_swap_bytes(v + (size_t)i * size, v + (size_t)j * size, size);
        i++;
    }
}

/*
 * Heapsort: in place, with no element copied to the stack, so the checker's memory bound holds. It is
 * not stable, but values that cmp calls equal cannot be told apart, so the result is the interpreter's.
 */
static void al_heapsort(void *base, uint32_t n, size_t size, int (*cmp)(const void *, const void *)) {
    uint8_t *v = (uint8_t *)base;
    uint32_t start = n / 2, end = n, root, child;
    while (end > 1) {
        if (start > 0) {
            start--;
        } else {
            end--;
            al_swap_bytes(v, v + (size_t)end * size, size);
        }
        root = start;
        while ((child = 2 * root + 1) < end) {
            if (child + 1 < end && cmp(v + (size_t)child * size, v + (size_t)(child + 1) * size) < 0) child++;
            if (cmp(v + (size_t)root * size, v + (size_t)child * size) >= 0) break;
            al_swap_bytes(v + (size_t)root * size, v + (size_t)child * size, size);
            root = child;
        }
    }
}
