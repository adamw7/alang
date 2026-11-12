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
 * Prints d with `decimals` digits after the point (none, and no point, for 0; at most 20): its exact
 * binary value rounded half to even, as the interpreter's Format.formatFixed does. With trim, trailing
 * zeros are dropped but one decimal is kept, print's form: 1.5, 2.0, 0.333333, -0.0. nan, inf, -inf.
 */
static void al_fmt_dec(al_out *o, double d, int decimals, int trim) {
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
    for (i = 0; i < decimals; i++) al_big_mul(&b, 10u);
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
    /* at least one digit before the point and `decimals` after it */
    if (len < decimals + 1) {
        int pad = decimals + 1 - len;
        for (i = len - 1; i >= 0; i--) digits[i + pad] = digits[i];
        for (i = 0; i < pad; i++) digits[i] = '0';
        len = decimals + 1;
    }
    al_put(o, digits, (uint32_t)(len - decimals));
    if (decimals == 0) return;
    AL_PUTS(o, ".");
    end = len;
    if (trim) {
        while (end > len - decimals + 1 && digits[end - 1] == '0') end--;
    }
    al_put(o, digits + len - decimals, (uint32_t)(end - (len - decimals)));
}

static void al_fmt_f64(al_out *o, double d) {
    al_fmt_dec(o, d, 6, 1);
}

/* fixed(d, decimals) after its range check: exactly that many decimals. */
static void al_fmt_fixed(al_out *o, double d, int64_t decimals) {
    al_fmt_dec(o, d, (int)decimals, 0);
}

/* The fill bytes that pad text of n bytes to width (pad_left and pad_right); none if it is that long already. */
static void al_pad(al_out *o, uint8_t fill, int64_t width, uint32_t n) {
    int64_t i;
    for (i = (int64_t)n; i < width; i++) al_put(o, &fill, 1);
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

/*
 * wrapping_add, wrapping_sub and wrapping_mul (op 0, 1, 2) in 64 unsigned bits, which wrap without undefined
 * behavior; the caller keeps the low bits of the type.
 */
static uint64_t al_wrapping(uint64_t a, uint64_t b, int op) {
    return op == 0 ? a + b : op == 1 ? a - b : a * b;
}

/*
 * saturating_add, saturating_sub and saturating_mul (op 0, 1, 2) of two values of a signed type with limits
 * lo..hi: each test decides overflow without computing a result that could overflow int64_t.
 */
static int64_t al_sat_s(int64_t a, int64_t b, int op, int64_t lo, int64_t hi) {
    if (op == 0) {
        if (b > 0 && a > hi - b) return hi;
        if (b < 0 && a < lo - b) return lo;
        return a + b;
    }
    if (op == 1) {
        if (b < 0 && a > hi + b) return hi;
        if (b > 0 && a < lo + b) return lo;
        return a - b;
    }
    if (a == 0 || b == 0) return 0;
    if (a > 0) {
        if (b > 0) return a > hi / b ? hi : a * b;
        return b < lo / a ? lo : a * b;
    }
    if (b > 0) return a < lo / b ? lo : a * b;
    return b < hi / a ? hi : a * b;
}

static uint64_t al_sat_u(uint64_t a, uint64_t b, int op, uint64_t hi) {
    if (op == 0) return a > hi - b ? hi : a + b;
    if (op == 1) return a < b ? 0 : a - b;
    return a != 0 && b > hi / a ? hi : a * b;
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

/* ---- elementary functions
 *
 * exp, log, log2, log10, sin, cos, tan, asin, acos, atan, atan2 and a float pow, computed exactly as
 * alang.interp.FloatMath computes them: the same correctly rounded operations in the same order, and integer
 * operations on the bits of a double, so that the results are the same everywhere. exp, log, log10, the sin
 * and cos kernels, asin, acos, atan and atan2 follow fdlibm 5.3:
 *
 * Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved.
 *
 * Developed at SunSoft, a Sun Microsystems, Inc. business.
 * Permission to use, copy, modify, and distribute this
 * software is freely granted, provided that this notice
 * is preserved.
 */

typedef union {
    double d;
    uint64_t u;
} al_dbits;

static uint64_t al_dbl_bits(double x) {
    al_dbits b;
    b.d = x;
    return b.u;
}

static double al_dbl_from(uint64_t u) {
    al_dbits b;
    b.u = u;
    return b.d;
}

/* A 32-bit pattern as the int32_t it is in two's complement, without an implementation-defined conversion. */
static int32_t al_bits_i32(uint32_t u) {
    return u <= 0x7fffffffu ? (int32_t)u : -(int32_t)(~u) - 1;
}

static int32_t al_hiw(double x) {
    return al_bits_i32((uint32_t)(al_dbl_bits(x) >> 32));
}

static int32_t al_low(double x) {
    return al_bits_i32((uint32_t)al_dbl_bits(x));
}

static double al_with_hi(double x, int32_t h) {
    return al_dbl_from(((uint64_t)(uint32_t)h << 32) | (al_dbl_bits(x) & 0xffffffffu));
}

static double al_words(int32_t h, int32_t l) {
    return al_dbl_from(((uint64_t)(uint32_t)h << 32) | (uint64_t)(uint32_t)l);
}

/* 2^e for e from -1022 to 1023. */
static double al_pow2(int e) {
    return al_dbl_from((uint64_t)(e + 1023) << 52);
}

/* v >> 20 rounded down, for any int32_t v (a right shift of a negative value is implementation-defined). */
static int32_t al_asr20(int32_t v) {
    return v >= 0 ? v >> 20 : -(int32_t)(((uint32_t)(-v) + 0xfffffu) >> 20);
}

#define AL_TWO54 1.80143985094819840000e+16
#define AL_SPLITTER 134217729.0

/* The rounding error of p = a * b: a * b - p exactly (Dekker). */
static double al_prod_err(double a, double b, double p) {
    double c = AL_SPLITTER * a;
    double ah = c - (c - a);
    double al = a - ah;
    double bh, bl;
    c = AL_SPLITTER * b;
    bh = c - (c - b);
    bl = b - bh;
    return ((ah * bh - p) + ah * bl + al * bh) + al * bl;
}

/* The rounding error of s = a + b (Knuth). */
static double al_sum_err(double a, double b, double s) {
    double bb = s - a;
    return (a - (s - bb)) + (b - bb);
}

static const double al_m_half[2] = {0.5, -0.5};
static const double al_m_ln2hi[2] = {6.93147180369123816490e-01, -6.93147180369123816490e-01};
static const double al_m_ln2lo[2] = {1.90821492927058770002e-10, -1.90821492927058770002e-10};
#define AL_TWOM1000 9.33263618503218878990e-302
#define AL_INVLN2 1.44269504088896338700e+00
#define AL_EP1 1.66666666666666019037e-01
#define AL_EP2 -2.77777777770155933842e-03
#define AL_EP3 6.61375632143793436117e-05
#define AL_EP4 -1.65339022054652515390e-06
#define AL_EP5 4.13813679705723846039e-08

static double al_m_exp(double x) {
    int32_t hx = al_hiw(x);
    int xsb = (int)((uint32_t)hx >> 31);
    double hi = 0.0, lo = 0.0, t, c, y;
    int k = 0;
    hx &= 0x7fffffff;
    if (hx >= 0x40862E42) {
        if (hx >= 0x7ff00000) {
            if (((hx & 0xfffff) | al_low(x)) != 0) return x + x;
            return xsb == 0 ? x : 0.0;
        }
        if (x > 7.09782712893383973096e+02) return INFINITY;
        if (x < -7.45133219101941108420e+02) return 0.0;
    }
    if (hx > 0x3fd62e42) {
        if (hx < 0x3FF0A2B2) {
            hi = x - al_m_ln2hi[xsb];
            lo = al_m_ln2lo[xsb];
            k = 1 - xsb - xsb;
        } else {
            k = (int)(AL_INVLN2 * x + al_m_half[xsb]);
            t = k;
            hi = x - t * al_m_ln2hi[0];
            lo = t * al_m_ln2lo[0];
        }
        x = hi - lo;
    } else if (hx < 0x3e300000) {
        return 1.0 + x;
    }
    t = x * x;
    c = x - t * (AL_EP1 + t * (AL_EP2 + t * (AL_EP3 + t * (AL_EP4 + t * AL_EP5))));
    if (k == 0) return 1.0 - ((x * c) / (c - 2.0) - x);
    y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
    if (k >= -1021) return al_with_hi(y, al_hiw(y) + k * 0x100000);
    return al_with_hi(y, al_hiw(y) + (k + 1000) * 0x100000) * AL_TWOM1000;
}

#define AL_LN2_HI 6.93147180369123816490e-01
#define AL_LN2_LO 1.90821492927058770002e-10
#define AL_LG1 6.666666666666735130e-01
#define AL_LG2 3.999999999940941908e-01
#define AL_LG3 2.857142874366239149e-01
#define AL_LG4 2.222219843214978396e-01
#define AL_LG5 1.818357216161805012e-01
#define AL_LG6 1.531383769920937332e-01
#define AL_LG7 1.479819860511658591e-01

static double al_m_log(double x) {
    int32_t hx = al_hiw(x), lx = al_low(x), i, i2, j;
    int k = 0;
    double f, s, dk, z, w, t1, t2, r, hfsq;
    if (hx < 0x00100000) {
        if (((hx & 0x7fffffff) | lx) == 0) return -INFINITY;
        if (hx < 0) return NAN;
        k -= 54;
        x *= AL_TWO54;
        hx = al_hiw(x);
    }
    if (hx >= 0x7ff00000) return x + x;
    k += (int)((uint32_t)hx >> 20) - 1023;
    hx &= 0x000fffff;
    i = (hx + 0x95f64) & 0x100000;
    x = al_with_hi(x, hx | (i ^ 0x3ff00000));
    k += (int)((uint32_t)i >> 20);
    f = x - 1.0;
    if ((0x000fffff & (2 + hx)) < 3) {
        if (f == 0.0) {
            if (k == 0) return 0.0;
            dk = k;
            return dk * AL_LN2_HI + dk * AL_LN2_LO;
        }
        r = f * f * (0.5 - 0.33333333333333333 * f);
        if (k == 0) return f - r;
        dk = k;
        return dk * AL_LN2_HI - ((r - dk * AL_LN2_LO) - f);
    }
    s = f / (2.0 + f);
    dk = k;
    z = s * s;
    i2 = hx - 0x6147a;
    w = z * z;
    j = 0x6b851 - hx;
    t1 = w * (AL_LG2 + w * (AL_LG4 + w * AL_LG6));
    t2 = z * (AL_LG1 + w * (AL_LG3 + w * (AL_LG5 + w * AL_LG7)));
    i2 |= j;
    r = t2 + t1;
    if (i2 > 0) {
        hfsq = 0.5 * f * f;
        if (k == 0) return f - (hfsq - s * (hfsq + r));
        return dk * AL_LN2_HI - ((hfsq - (s * (hfsq + r) + dk * AL_LN2_LO)) - f);
    }
    if (k == 0) return f - s * (f - r);
    return dk * AL_LN2_HI - ((s * (f - r) - dk * AL_LN2_LO) - f);
}

static double al_m_log10(double x) {
    int32_t hx = al_hiw(x), lx = al_low(x), i;
    int k = 0;
    double y, z;
    if (hx < 0x00100000) {
        if (((hx & 0x7fffffff) | lx) == 0) return -INFINITY;
        if (hx < 0) return NAN;
        k -= 54;
        x *= AL_TWO54;
        hx = al_hiw(x);
    }
    if (hx >= 0x7ff00000) return x + x;
    k += (int)((uint32_t)hx >> 20) - 1023;
    i = (int32_t)((uint32_t)k >> 31);
    hx = (hx & 0x000fffff) | ((0x3ff - i) << 20);
    y = (double)(k + i);
    x = al_with_hi(x, hx);
    z = y * 3.69423907715893078616e-13 + 4.34294481903251816668e-01 * al_m_log(x);
    return z + y * 3.01029995663611771306e-01;
}

/* ---- sin, cos, tan */

static double al_k_sin(double x, double y, int tail) {
    int32_t ix = al_hiw(x) & 0x7fffffff;
    double z, v, r;
    if (ix < 0x3e400000) return x;
    z = x * x;
    v = z * x;
    r = 8.33333333332248946124e-03 + z * (-1.98412698298579493134e-04 + z * (2.75573137070700676789e-06
        + z * (-2.50507602534068634195e-08 + z * 1.58969099521155010221e-10)));
    if (!tail) return x + v * (-1.66666666666666324348e-01 + z * r);
    return x - ((z * (0.5 * y - v * r) - y) - v * -1.66666666666666324348e-01);
}

static double al_k_cos(double x, double y) {
    int32_t ix = al_hiw(x) & 0x7fffffff;
    double z, r, qx, hz, a;
    if (ix < 0x3e400000) return 1.0;
    z = x * x;
    r = z * (4.16666666666666019037e-02 + z * (-1.38888888888741095749e-03 + z * (2.48015872894767294178e-05
        + z * (-2.75573143513906633035e-07 + z * (2.08757232129817482790e-09 + z * -1.13596475577881948265e-11)))));
    if (ix < 0x3FD33333) return 1.0 - (0.5 * z - (z * r - x * y));
    qx = ix > 0x3fe90000 ? 0.28125 : al_words(ix - 0x00200000, 0);
    hz = 0.5 * z - qx;
    a = 1.0 - qx;
    return a - (hz - (z * r - x * y));
}

#define AL_PIO2_1 1.57079632673412561417e+00
#define AL_PIO2_1T 6.07710050650619224932e-11
#define AL_PIO2_2 6.07710050630396597660e-11
#define AL_PIO2_2T 2.02226624879595063154e-21
#define AL_PIO2_3 2.02226624871116645580e-21
#define AL_PIO2_3T 8.47842766036889956997e-32
#define AL_PIO2_HI 1.57079632679489655800e+00
#define AL_PIO2_LO 6.12323399573676603587e-17

static const uint32_t al_two_over_pi[40] = {
    0xa2f9836eu, 0x4e441529u, 0xfc2757d1u, 0xf534ddc0u, 0xdb629599u, 0x3c439041u, 0xfe5163abu, 0xdebbc561u,
    0xb7246e3au, 0x424dd2e0u, 0x06492eeau, 0x09d1921cu, 0xfe1deb1cu, 0xb129a73eu, 0xe88235f5u, 0x2ebb4484u,
    0xe99c7026u, 0xb45f7e41u, 0x3991d639u, 0x835339f4u, 0x9c845f8bu, 0xbdf9283bu, 0x1ff897ffu, 0xde05980fu,
    0xef2f118bu, 0x5a0a6d1fu, 0x6d367ecfu, 0x27cb09b7u, 0x4f463f66u, 0x9e5fea2du, 0x7527bac7u, 0xebe5f17bu,
    0x3d0739f7u, 0x8a5292eau, 0x6bfb5fb1u, 0x1f8d5d08u, 0x56033046u, 0xfc7b6babu, 0xf0cfbc20u, 0x9af4361du,
};

static uint64_t al_limb(const uint64_t *r, int i) {
    return i < 10 ? r[i] : 0u;
}

/* Bits q to q + 63 of the number in r (32-bit limbs, least significant first). */
static uint64_t al_bits_at(const uint64_t *r, int q) {
    int i = q >> 5, off = q & 31;
    if (off == 0) return al_limb(r, i) | (al_limb(r, i + 1) << 32);
    return (al_limb(r, i) >> off) | (al_limb(r, i + 1) << (32 - off)) | (al_limb(r, i + 2) << (64 - off));
}

/* x mod pi/2 for a large positive x (Payne and Hanek), as FloatMath.remPio2Large. */
static int al_rem_pio2_large(double x, double *y) {
    uint64_t b = al_dbl_bits(x), m, r[10] = {0}, mw[2], fhi, flo;
    int e = (int)((b >> 52) & 0x7ffu) - 1075, p0, w, j, k, sh, n, neg;
    double a1, a2, a3, a4, s, err, s2, s3, fh, fl, p, q, y0, y1;
    m = (b & 0xfffffffffffffu) | 0x10000000000000u;
    p0 = e - 1 > 1 ? e - 1 : 1;
    w = (p0 - 1) >> 5;
    mw[0] = m & 0xffffffffu;
    mw[1] = m >> 32;
    for (j = 0; j < 2; j++) {
        uint64_t carry = 0;
        for (k = 0; k < 8; k++) {
            uint64_t t = (uint64_t)al_two_over_pi[w + 7 - k] * mw[j] + r[k + j] + carry;
            r[k + j] = t & 0xffffffffu;
            carry = t >> 32;
        }
        r[8 + j] += carry;
    }
    sh = 32 * (w + 8) - e;
    n = (int)(al_bits_at(r, sh) & 7u);
    fhi = al_bits_at(r, sh - 64);
    flo = al_bits_at(r, sh - 128);
    neg = (int)(fhi >> 63);
    if (neg) {
        n++;
        flo = (uint64_t)0 - flo;
        fhi = ~fhi + (flo == 0 ? 1u : 0u);
    }
    a1 = (double)(fhi >> 32) * 0x1p-32;
    a2 = (double)(fhi & 0xffffffffu) * 0x1p-64;
    a3 = (double)(flo >> 32) * 0x1p-96;
    a4 = (double)(flo & 0xffffffffu) * 0x1p-128;
    s = a1 + a2;
    err = al_sum_err(a1, a2, s);
    s2 = s + a3;
    err += al_sum_err(s, a3, s2);
    s3 = s2 + a4;
    err += al_sum_err(s2, a4, s3);
    fh = s3 + err;
    fl = err - (fh - s3);
    p = fh * AL_PIO2_HI;
    q = al_prod_err(fh, AL_PIO2_HI, p) + (fh * AL_PIO2_LO + fl * AL_PIO2_HI);
    y0 = p + q;
    y1 = q - (y0 - p);
    y[0] = neg ? -y0 : y0;
    y[1] = neg ? -y1 : y1;
    return n & 7;
}

static int al_rem_pio2(double x, double *y) {
    int32_t hx = al_hiw(x), ix = hx & 0x7fffffff, j, i;
    double z, t, fn, r, w;
    int n;
    if (ix <= 0x3fe921fb) {
        y[0] = x;
        y[1] = 0.0;
        return 0;
    }
    if (ix < 0x4002d97c) {
        if (hx > 0) {
            z = x - AL_PIO2_1;
            if (ix != 0x3ff921fb) {
                y[0] = z - AL_PIO2_1T;
                y[1] = (z - y[0]) - AL_PIO2_1T;
            } else {
                z -= AL_PIO2_2;
                y[0] = z - AL_PIO2_2T;
                y[1] = (z - y[0]) - AL_PIO2_2T;
            }
            return 1;
        }
        z = x + AL_PIO2_1;
        if (ix != 0x3ff921fb) {
            y[0] = z + AL_PIO2_1T;
            y[1] = (z - y[0]) + AL_PIO2_1T;
        } else {
            z += AL_PIO2_2;
            y[0] = z + AL_PIO2_2T;
            y[1] = (z - y[0]) + AL_PIO2_2T;
        }
        return -1;
    }
    if (ix <= 0x413921fb) {
        t = fabs(x);
        n = (int)(t * 6.36619772367581382433e-01 + 0.5);
        fn = n;
        r = t - fn * AL_PIO2_1;
        w = fn * AL_PIO2_1T;
        j = (int32_t)((uint32_t)ix >> 20);
        y[0] = r - w;
        i = j - (int32_t)(((uint32_t)al_hiw(y[0]) >> 20) & 0x7ffu);
        if (i > 16) {
            t = r;
            w = fn * AL_PIO2_2;
            r = t - w;
            w = fn * AL_PIO2_2T - ((t - r) - w);
            y[0] = r - w;
            i = j - (int32_t)(((uint32_t)al_hiw(y[0]) >> 20) & 0x7ffu);
            if (i > 49) {
                t = r;
                w = fn * AL_PIO2_3;
                r = t - w;
                w = fn * AL_PIO2_3T - ((t - r) - w);
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
    n = al_rem_pio2_large(fabs(x), y);
    if (hx < 0) {
        y[0] = -y[0];
        y[1] = -y[1];
        return -n;
    }
    return n;
}

static double al_m_sin(double x) {
    int32_t ix = al_hiw(x) & 0x7fffffff;
    double y[2];
    int n;
    if (ix <= 0x3fe921fb) return al_k_sin(x, 0.0, 0);
    if (ix >= 0x7ff00000) return NAN;
    n = al_rem_pio2(x, y);
    switch ((unsigned)n & 3u) {
        case 0: return al_k_sin(y[0], y[1], 1);
        case 1: return al_k_cos(y[0], y[1]);
        case 2: return -al_k_sin(y[0], y[1], 1);
        default: return -al_k_cos(y[0], y[1]);
    }
}

static double al_m_cos(double x) {
    int32_t ix = al_hiw(x) & 0x7fffffff;
    double y[2];
    int n;
    if (ix <= 0x3fe921fb) return al_k_cos(x, 0.0);
    if (ix >= 0x7ff00000) return NAN;
    n = al_rem_pio2(x, y);
    switch ((unsigned)n & 3u) {
        case 0: return al_k_cos(y[0], y[1]);
        case 1: return -al_k_sin(y[0], y[1], 1);
        case 2: return -al_k_cos(y[0], y[1]);
        default: return al_k_sin(y[0], y[1], 1);
    }
}

static double al_m_tan(double x) {
    int32_t ix = al_hiw(x) & 0x7fffffff;
    double y[2], s, c;
    int n;
    if (ix < 0x3e400000) return x;
    if (ix >= 0x7ff00000) return NAN;
    n = al_rem_pio2(x, y);
    s = al_k_sin(y[0], y[1], 1);
    c = al_k_cos(y[0], y[1]);
    return ((unsigned)n & 1u) == 0 ? s / c : -c / s;
}

/* ---- atan, atan2, asin, acos */

static const double al_atanhi[4] = {
    4.63647609000806093515e-01, 7.85398163397448278999e-01, 9.82793723247329054082e-01, 1.57079632679489655800e+00,
};
static const double al_atanlo[4] = {
    2.26987774529616870924e-17, 3.06161699786838301793e-17, 1.39033110312309984516e-17, 6.12323399573676603587e-17,
};
static const double al_at[11] = {
    3.33333333333329318027e-01, -1.99999999998764832476e-01, 1.42857142725034663711e-01, -1.11111104054623557880e-01,
    9.09088713343650656196e-02, -7.69187620504482999495e-02, 6.66107313738753120669e-02, -5.83357013379057348645e-02,
    4.97687799461593236017e-02, -3.65315727442169155270e-02, 1.62858201153657823623e-02,
};

static double al_m_atan(double x) {
    int32_t hx = al_hiw(x), ix = hx & 0x7fffffff;
    int id;
    double z, w, s1, s2;
    if (ix >= 0x44100000) {
        if (ix > 0x7ff00000 || (ix == 0x7ff00000 && al_low(x) != 0)) return x + x;
        return hx > 0 ? al_atanhi[3] + al_atanlo[3] : -al_atanhi[3] - al_atanlo[3];
    }
    if (ix < 0x3fdc0000) {
        if (ix < 0x3e200000) return x;
        id = -1;
    } else {
        x = fabs(x);
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
    z = x * x;
    w = z * z;
    s1 = z * (al_at[0] + w * (al_at[2] + w * (al_at[4] + w * (al_at[6] + w * (al_at[8] + w * al_at[10])))));
    s2 = w * (al_at[1] + w * (al_at[3] + w * (al_at[5] + w * (al_at[7] + w * al_at[9]))));
    if (id < 0) return x - x * (s1 + s2);
    z = al_atanhi[id] - ((x * (s1 + s2) - al_atanlo[id]) - x);
    return hx < 0 ? -z : z;
}

#define AL_PI_O_4 7.8539816339744827900e-01
#define AL_PI_O_2 1.5707963267948965580e+00
#define AL_PI 3.1415926535897931160e+00
#define AL_PI_LO 1.2246467991473531772e-16

static double al_m_atan2(double y, double x) {
    int32_t hx, ix, lx, hy, iy, ly, k;
    int m;
    double z;
    if (isnan(x) || isnan(y)) return x + y;
    hx = al_hiw(x);
    ix = hx & 0x7fffffff;
    lx = al_low(x);
    hy = al_hiw(y);
    iy = hy & 0x7fffffff;
    ly = al_low(y);
    if (x == 1.0) return al_m_atan(y);
    m = (int)(((uint32_t)hy >> 31) | (((uint32_t)hx >> 30) & 2u));
    if ((iy | ly) == 0) {
        switch (m) {
            case 0: case 1: return y;
            case 2: return AL_PI;
            default: return -AL_PI;
        }
    }
    if ((ix | lx) == 0) return hy < 0 ? -AL_PI_O_2 : AL_PI_O_2;
    if (ix == 0x7ff00000) {
        if (iy == 0x7ff00000) {
            switch (m) {
                case 0: return AL_PI_O_4;
                case 1: return -AL_PI_O_4;
                case 2: return 3.0 * AL_PI_O_4;
                default: return -3.0 * AL_PI_O_4;
            }
        }
        switch (m) {
            case 0: return 0.0;
            case 1: return -0.0;
            case 2: return AL_PI;
            default: return -AL_PI;
        }
    }
    if (iy == 0x7ff00000) return hy < 0 ? -AL_PI_O_2 : AL_PI_O_2;
    k = al_asr20(iy - ix);
    if (k > 60) z = AL_PI_O_2 + 0.5 * AL_PI_LO;
    else if (hx < 0 && k < -60) z = 0.0;
    else z = al_m_atan(fabs(y / x));
    switch (m) {
        case 0: return z;
        case 1: return -z;
        case 2: return AL_PI - (z - AL_PI_LO);
        default: return (z - AL_PI_LO) - AL_PI;
    }
}

static double al_asin_ratio(double t) {
    double p = t * (1.66666666666666657415e-01 + t * (-3.25565818622400915405e-01 + t * (2.01212532134862925881e-01
        + t * (-4.00555345006794114027e-02 + t * (7.91534994289814532176e-04 + t * 3.47933107596021167570e-05)))));
    double q = 1.0 + t * (-2.40339491173441421878e+00 + t * (2.02094576023350569471e+00 + t * (-6.88283971605453293030e-01
        + t * 7.70381505559019352791e-02)));
    return p / q;
}

#define AL_PIO4_HI 7.85398163397448278999e-01

static double al_m_asin(double x) {
    int32_t hx = al_hiw(x), ix = hx & 0x7fffffff;
    double w, t, s, r, ws, c, p, q;
    if (ix >= 0x3ff00000) {
        if (((ix - 0x3ff00000) | al_low(x)) == 0) return x * AL_PIO2_HI + x * AL_PIO2_LO;
        return NAN;
    }
    if (ix < 0x3fe00000) {
        if (ix < 0x3e400000) return x;
        return x + x * al_asin_ratio(x * x);
    }
    w = 1.0 - fabs(x);
    t = w * 0.5;
    s = sqrt(t);
    if (ix >= 0x3FEF3333) {
        t = AL_PIO2_HI - (2.0 * (s + s * al_asin_ratio(t)) - AL_PIO2_LO);
    } else {
        r = al_asin_ratio(t);
        ws = al_words(al_hiw(s), 0);
        c = (t - ws * ws) / (s + ws);
        p = 2.0 * s * r - (AL_PIO2_LO - 2.0 * c);
        q = AL_PIO4_HI - 2.0 * ws;
        t = AL_PIO4_HI - (p - q);
    }
    return hx > 0 ? t : -t;
}

static double al_m_acos(double x) {
    int32_t hx = al_hiw(x), ix = hx & 0x7fffffff;
    double z, s, w, df, c;
    if (ix >= 0x3ff00000) {
        if (((ix - 0x3ff00000) | al_low(x)) == 0) return hx > 0 ? 0.0 : AL_PI + 2.0 * AL_PIO2_LO;
        return NAN;
    }
    if (ix < 0x3fe00000) {
        if (ix <= 0x3c600000) return AL_PIO2_HI + AL_PIO2_LO;
        return AL_PIO2_HI - (x - (AL_PIO2_LO - x * al_asin_ratio(x * x)));
    }
    if (hx < 0) {
        z = (1.0 + x) * 0.5;
        s = sqrt(z);
        w = al_asin_ratio(z) * s - AL_PIO2_LO;
        return AL_PI - 2.0 * (s + w);
    }
    z = (1.0 - x) * 0.5;
    s = sqrt(z);
    df = al_words(al_hiw(s), 0);
    c = (z - df * df) / (s + df);
    w = al_asin_ratio(z) * s + c;
    return 2.0 * (df + w);
}

/* ---- log2 and pow */

/* The exponent of a nonzero finite x: x = m * 2^e with 1 <= |m| < 2. */
static int al_exponent_of(double x) {
    int e = (int)((al_dbl_bits(x) >> 52) & 0x7ffu);
    if (e != 0) return e - 1023;
    return (int)((al_dbl_bits(x * AL_TWO54) >> 52) & 0x7ffu) - 1023 - 54;
}

/* x * 2^k, exact where the result is normal. */
static double al_mul_pow2(double x, int k) {
    int half = k / 2;
    return x * al_pow2(half) * al_pow2(k - half);
}

/* x * 2^e for a nonzero finite x, rounded once. */
static double al_scale(double x, int64_t e) {
    int k = al_exponent_of(x);
    x = al_mul_pow2(x, -k);
    e += k;
    if (e > 2100) return x * INFINITY;
    while (e > 1023) {
        x *= al_pow2(1023);
        e -= 1023;
    }
    if (e >= -1022) return x * al_pow2((int)e);
    if (e < -2044) return x * 0.0;
    return x * al_pow2((int)(e + 1022)) * al_pow2(-1022);
}

static double al_pow_int(double ax, double y) {
    int e0 = al_exponent_of(ax), k;
    double m = al_mul_pow2(ax, -e0), bh = m, bl = 0.0, rh = 1.0, rl = 0.0, p, q;
    int64_t be = e0, re = 0;
    uint64_t n;
    if (y < 0) {
        bh = 1.0 / m;
        p = bh * m;
        bl = ((1.0 - p) - al_prod_err(bh, m, p)) / m;
        k = al_exponent_of(bh);
        bh *= al_pow2(-k);
        bl *= al_pow2(-k);
        be = k - e0;
    }
    n = (uint64_t)fabs(y);
    for (;;) {
        if ((n & 1u) != 0) {
            p = rh * bh;
            q = al_prod_err(rh, bh, p) + (rh * bl + rl * bh);
            rh = p + q;
            rl = q - (rh - p);
            k = al_exponent_of(rh);
            rh *= al_pow2(-k);
            rl *= al_pow2(-k);
            re += be + k;
        }
        n >>= 1;
        if (n == 0) break;
        p = bh * bh;
        q = al_prod_err(bh, bh, p) + 2.0 * (bh * bl);
        bh = p + q;
        bl = q - (bh - p);
        k = al_exponent_of(bh);
        bh *= al_pow2(-k);
        bl *= al_pow2(-k);
        be = 2 * be + k;
        if (be > 4000) return INFINITY;
        if (be < -4000) return 0.0;
    }
    return al_scale(rh + rl, re);
}

/* log(x) for a positive finite x as out[0] + out[1], as FloatMath.logDD. */
static void al_log_dd(double x, double *out) {
    int k = 0, hx, i;
    double f, dh, dl, sh, p, sl, zh, zl, z2, ch, cl, c2, th, tl, z, rest, a, h, l, dk, kh, hh, ll;
    if (x < 0x1p-1022) {
        x *= AL_TWO54;
        k = -54;
    }
    hx = al_hiw(x);
    k += (int)((uint32_t)hx >> 20) - 1023;
    hx &= 0x000fffff;
    i = (hx + 0x95f64) & 0x100000;
    x = al_with_hi(x, hx | (i ^ 0x3ff00000));
    k += (int)((uint32_t)i >> 20);
    f = x - 1.0;
    dh = 2.0 + f;
    dl = f - (dh - 2.0);
    sh = f / dh;
    p = sh * dh;
    sl = (((f - p) - al_prod_err(sh, dh, p)) - sh * dl) / dh;
    zh = sh * sh;
    zl = al_prod_err(sh, sh, zh) + 2.0 * (sh * sl);
    z2 = zh + zl;
    zl = zl - (z2 - zh);
    zh = z2;
    ch = zh * sh;
    cl = al_prod_err(zh, sh, ch) + (zh * sl + zl * sh);
    c2 = ch + cl;
    cl = cl - (c2 - ch);
    ch = c2;
    th = ch * 6.66666666666666629659e-01;
    tl = al_prod_err(ch, 6.66666666666666629659e-01, th) + (ch * 0x1.5555555555555p-55 + cl * 6.66666666666666629659e-01);
    z = zh;
    rest = ch * z * (0.4 + z * (2.0 / 7 + z * (2.0 / 9 + z * (2.0 / 11 + z * (2.0 / 13 + z * (2.0 / 15
        + z * (2.0 / 17 + z * (2.0 / 19 + z * (2.0 / 21 + z * (2.0 / 23 + z * (2.0 / 25 + z * (2.0 / 27))))))))))));
    a = 2.0 * sh;
    h = a + th;
    l = (th - (h - a)) + (2.0 * sl + tl + rest);
    dk = k;
    kh = dk * AL_LN2_HI;
    hh = kh + h;
    ll = al_sum_err(kh, h, hh) + (l + dk * AL_LN2_LO);
    out[0] = hh + ll;
    out[1] = ll - (out[0] - hh);
}

static double al_m_log2(double x) {
    int k;
    double l[2], p;
    if (isnan(x) || x < 0.0) return NAN;
    if (x == 0.0) return -INFINITY;
    if (isinf(x)) return x;
    k = al_exponent_of(x);
    if (al_mul_pow2(x, -k) == 1.0) return k;
    al_log_dd(x, l);
    p = l[0] * 0x1.71547652b82fep0;
    return p + (al_prod_err(l[0], 0x1.71547652b82fep0, p) + (l[0] * 0x1.777d0ffda0d24p-56 + l[1] * 0x1.71547652b82fep0));
}

static double al_exp_dd(double xh, double xl) {
    int xsb = (int)((uint32_t)al_hiw(xh) >> 31);
    int k = (int)(AL_INVLN2 * xh + al_m_half[xsb]);
    double t = k, hi, lo, x, z, c, y;
    hi = xh - t * al_m_ln2hi[0];
    lo = t * al_m_ln2lo[0] - xl;
    x = hi - lo;
    z = x * x;
    c = x - z * (AL_EP1 + z * (AL_EP2 + z * (AL_EP3 + z * (AL_EP4 + z * AL_EP5))));
    y = 1.0 - ((lo - (x * c) / (2.0 - c)) - hi);
    return al_scale(y, k);
}

static double al_pow_general(double ax, double y) {
    double l[2], th, tl, t;
    if (fabs(y) > 0x1p900) return (ax > 1.0) == (y > 0) ? INFINITY : 0.0;
    al_log_dd(ax, l);
    th = y * l[0];
    if (th > 710.0) return INFINITY;
    if (th < -746.0) return 0.0;
    tl = al_prod_err(y, l[0], th) + y * l[1];
    t = th + tl;
    return al_exp_dd(t, tl - (t - th));
}

static int al_is_odd(double y) {
    return fabs(y) < 9007199254740992.0 && floor(y * 0.5) * 2.0 != y;
}

static double al_m_pow(double x, double y) {
    double ax, r;
    int odd, negx, integer;
    if (y == 0.0 || x == 1.0) return 1.0;
    if (isnan(x) || isnan(y)) return NAN;
    ax = fabs(x);
    if (isinf(y)) {
        if (ax == 1.0) return 1.0;
        return (ax < 1.0) == (y < 0) ? INFINITY : 0.0;
    }
    integer = floor(y) == y;
    odd = integer && al_is_odd(y);
    negx = al_hiw(x) < 0;
    if (x == 0.0 || isinf(x)) {
        int inf = (y < 0) != (x != 0.0);
        r = inf ? INFINITY : 0.0;
        return odd && negx ? -r : r;
    }
    if (negx && !integer) return NAN;
    if (y == 0.5) return sqrt(x);
    r = integer && fabs(y) <= 9007199254740992.0 ? al_pow_int(ax, y) : al_pow_general(ax, y);
    return odd && negx ? -r : r;
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

/* ---- UTF-8 */

/*
 * The code point that starts at byte i of s[0..n), as Stdlib.utf8Next decodes it: *len gets its length in
 * bytes and *ok whether it is well-formed. A byte that does not start a well-formed sequence stands for
 * U+FFFD on its own.
 */
static uint32_t al_utf8_next(const uint8_t *s, uint32_t n, uint32_t i, uint32_t *len, int *ok) {
    uint32_t b0 = s[i], cp, lo = 0x80u, hi = 0xBFu, k, c, l;
    *len = 1;
    *ok = 0;
    if (b0 < 0x80u) {
        *ok = 1;
        return b0;
    }
    if (b0 >= 0xC2u && b0 <= 0xDFu) {
        l = 2;
        cp = b0 & 0x1Fu;
    } else if (b0 >= 0xE0u && b0 <= 0xEFu) {
        l = 3;
        cp = b0 & 0x0Fu;
        if (b0 == 0xE0u) lo = 0xA0u;
        if (b0 == 0xEDu) hi = 0x9Fu;
    } else if (b0 >= 0xF0u && b0 <= 0xF4u) {
        l = 4;
        cp = b0 & 0x07u;
        if (b0 == 0xF0u) lo = 0x90u;
        if (b0 == 0xF4u) hi = 0x8Fu;
    } else {
        return 0xFFFDu;
    }
    if (n - i < l) return 0xFFFDu;
    for (k = 1; k < l; k++) {
        c = s[i + k];
        if (c < (k == 1 ? lo : 0x80u) || c > (k == 1 ? hi : 0xBFu)) return 0xFFFDu;
        cp = (cp << 6) | (c & 0x3Fu);
    }
    *len = l;
    *ok = 1;
    return cp;
}

static bool al_is_utf8(const uint8_t *s, uint32_t n) {
    uint32_t i = 0, l;
    int ok;
    while (i < n) {
        (void)al_utf8_next(s, n, i, &l, &ok);
        if (!ok) return false;
        i += l;
    }
    return true;
}

static int64_t al_char_count(const uint8_t *s, uint32_t n) {
    uint32_t i = 0, l;
    int ok;
    int64_t count = 0;
    while (i < n) {
        (void)al_utf8_next(s, n, i, &l, &ok);
        i += l;
        count++;
    }
    return count;
}

/* Appends the UTF-8 encoding of the code point c to b, whose length is *len and capacity cap. */
static void al_push_char(uint8_t *b, uint32_t *len, uint32_t cap, al_pos c, int line, int col) {
    uint64_t v = c.u ? c.v : (uint64_t)c.s;
    uint32_t n, k, lead;
    if ((!c.u && c.s < 0) || v > 0x10FFFFu || (v >= 0xD800u && v <= 0xDFFFu)) {
        al_out e = al_trap_begin("R0013");
        AL_PUTS(&e, "push_char needs a Unicode code point from 0 to 1114111 that is not a surrogate (55296 to 57343), found ");
        al_fmt_pos(&e, c);
        al_trap_end(&e, line, col);
    }
    n = v < 0x80u ? 1 : v < 0x800u ? 2 : v < 0x10000u ? 3 : 4;
    if (*len + n > cap) {
        al_out e = al_trap_begin("R0004");
        AL_PUTS(&e, "push_char exceeds str capacity ");
        al_fmt_u64(&e, cap);
        AL_PUTS(&e, " (length ");
        al_fmt_u64(&e, *len);
        AL_PUTS(&e, ", appending ");
        al_fmt_u64(&e, n);
        AL_PUTS(&e, " bytes)");
        al_trap_end(&e, line, col);
    }
    if (n == 1) {
        b[(*len)++] = (uint8_t)v;
        return;
    }
    lead = n == 2 ? 0xC0u : n == 3 ? 0xE0u : 0xF0u;
    b[(*len)++] = (uint8_t)(lead | (uint32_t)(v >> (6 * (n - 1))));
    for (k = n - 1; k-- > 0;) b[(*len)++] = (uint8_t)(0x80u | (uint32_t)((v >> (6 * k)) & 0x3Fu));
}

/* The code points f..t of s[0..n) into dst; returns the length in bytes. */
static uint32_t al_slice_chars(uint8_t *dst, const uint8_t *s, uint32_t n, al_pos f, al_pos t, int line, int col) {
    uint32_t count = (uint32_t)al_char_count(s, n), i = 0, start = 0, k, l, from, to;
    int ok;
    if (!al_pos_le(f, count) || !al_pos_le(t, count) || al_pos_get(f) > al_pos_get(t)) {
        al_out e = al_trap_begin("R0003");
        AL_PUTS(&e, "slice_chars ");
        al_fmt_pos(&e, f);
        AL_PUTS(&e, "..");
        al_fmt_pos(&e, t);
        AL_PUTS(&e, " is out of range for ");
        al_fmt_u64(&e, count);
        AL_PUTS(&e, " code points");
        al_trap_end(&e, line, col);
    }
    from = al_pos_get(f);
    to = al_pos_get(t);
    for (k = 0; k < to; k++) {
        if (k == from) start = i;
        (void)al_utf8_next(s, n, i, &l, &ok);
        i += l;
    }
    if (from == to) start = i;
    return al_copy_bytes(dst, s + start, i - start);
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

/*
 * Decimal text as a double, the algorithm of the interpreter's Stdlib.decimalFloat: an optional + or -,
 * then digits with at most one '.' among them. With M the digits between the first and last that are not 0
 * and the value M * 10^E, it converts when M <= 2^53 and 10^E (or M * 10^(E - 22) and 10^22) is a double,
 * so that one multiplication or division rounds the exact value to the nearest double.
 * Returns 0 for text of another form, 1 for a number too precise to convert exactly, 2 with *out set.
 */
static const double al_pow10[23] = {
    1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18,
    1e19, 1e20, 1e21, 1e22};

static int al_parse_f64(const uint8_t *b, uint32_t n, double *out) {
    uint32_t i = 0, j;
    int negative = n > 0 && b[0] == '-';
    int64_t digits = 0, before = -1, first = -1, last = -1, k = 0, e;
    uint64_t m = 0;
    double v;
    if (n > 0 && (b[0] == '+' || b[0] == '-')) i = 1;
    for (j = i; j < n; j++) {
        if (b[j] == '.') {
            if (before >= 0) return 0;
            before = digits;
        } else if (b[j] >= '0' && b[j] <= '9') {
            if (b[j] != '0') {
                if (first < 0) first = digits;
                last = digits;
            }
            digits++;
        } else {
            return 0;
        }
    }
    if (digits == 0) return 0;
    if (before < 0) before = digits;
    if (first < 0) {
        *out = negative ? -0.0 : 0.0;
        return 2;
    }
    if (last - first >= 16) return 1;
    for (j = i; j < n; j++) {
        if (b[j] == '.') continue;
        if (k >= first && k <= last) m = m * 10u + (uint64_t)(b[j] - '0');
        k++;
    }
    if (m > ((uint64_t)1 << 53)) return 1;
    e = before - 1 - last;
    if (e < 0) {
        if (e < -22) return 1;
        v = (double)m / al_pow10[-e];
    } else if (e <= 22) {
        v = (double)m * al_pow10[e];
    } else {
        if (e > 22 + 16) return 1;
        for (k = e - 22; k > 0; k--) {
            m *= 10u;
            if (m > ((uint64_t)1 << 53)) return 1;
        }
        v = (double)m * al_pow10[22];
    }
    *out = negative ? -v : v;
    return 2;
}

static bool al_is_float(const uint8_t *b, uint32_t n) {
    double v;
    return al_parse_f64(b, n, &v) == 2;
}

static double al_str_to_f64(const uint8_t *b, uint32_t n, int line, int col) {
    double v = 0.0;
    int kind = al_parse_f64(b, n, &v);
    if (kind != 2) {
        al_out e = al_trap_begin("R0009");
        AL_PUTS(&e, "cannot convert ");
        al_fmt_str(&e, b, n, 1);
        if (kind == 0) AL_PUTS(&e, " to f64: not a decimal number");
        else AL_PUTS(&e, " to f64: more digits than an f64 converts exactly");
        al_trap_end(&e, line, col);
    }
    return v;
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
