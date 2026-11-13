/*
 * ALang — an agent-first, heap-free programming language.
 *
 * This grammar is the single source of truth for ALang syntax. It contains no
 * target-specific actions, so any ANTLR 4 target (Java, Python, Go, JS, C#, ...)
 * can generate a parser from it.
 *
 * Design rules that are visible in the grammar:
 *   - Every statement starts with a keyword, or is an assignment / call.
 *   - Blocks always use braces. Conditions never need parentheses.
 *   - Types are always written out: `let x: i32 = 0;`.
 *   - There is no `new`, no pointer type, no reference type and no `null`.
 *     Every type has a size known at compile time; nothing is heap-allocated.
 *   - `while` loops must state an iteration limit: `while c limit 100 { }`.
 *     `for` loops iterate over finite ranges or fixed-capacity collections.
 *     Together with the ban on recursion (enforced by the checker), every
 *     ALang program terminates.
 *   - Operator precedence is shallow and explicit:
 *       * `* / %` bind tighter than `+ -` (ordinary arithmetic).
 *       * Comparisons take arithmetic operands and never chain.
 *       * `and` / `or` never mix without parentheses.
 *       * Bitwise operators (`& | ^ << >>`) never mix with anything without
 *         parentheses: write `(flags & MASK) == 0`.
 *   - Struct values are built with named arguments: `Point(x: 1, y: 2)`.
 *   - Enum variants are qualified: `Color.Red`, `Shape.Circle(2.0)`.
 *   - Arguments passed by reference are marked at the call site: `push(mut xs, 1)`.
 *   - Imports come first and name a file: `import "helpers.al";`.
 *
 * Semantic rules (types, mutability, exhaustiveness, ...) are described in
 * docs/SPEC.md and enforced by the checker.
 */
grammar ALang;

// =====================================================================
// Program structure
// =====================================================================

program
    : importDecl* item* EOF
    ;

// import "helpers.al";  (a path relative to this file; its declarations join the program)
// import "lib/text.al" as text;  keeps that file's declarations apart: text.trim_all(s), text.Point(x: 1)
importDecl
    : IMPORT STRING_LIT (AS identifier)? SEMI
    ;

item
    : constDecl
    | structDecl
    | enumDecl
    | functionDecl
    | testDecl
    ;

// const MAX_USERS: i32 = 64;
constDecl
    : CONST identifier COLON type ASSIGN expression SEMI
    ;

// struct Point { x: i32, y: i32 }
// struct Pair<A, B> { first: A, second: B }: a type parameter stands for any type; Pair<i64, bool> fixes them.
structDecl
    : STRUCT identifier typeParams? LBRACE fieldDecl (COMMA fieldDecl)* COMMA? RBRACE
    ;

fieldDecl
    : identifier COLON type
    ;

// enum Shape { Circle(f64), Rect(f64, f64), Empty }
// enum Maybe<T> { None, Some(T) }
enumDecl
    : ENUM identifier typeParams? LBRACE variantDecl (COMMA variantDecl)* COMMA? RBRACE
    ;

variantDecl
    : identifier (LPAREN type (COMMA type)* COMMA? RPAREN)?
    ;

// tool fn area(w: f64, h: f64) -> f64 requires w >= 0.0 ensures result >= 0.0 { ... }
// fn first<T>(xs: list[T; _]) -> T: a type parameter stands for any type; each call fixes it from the arguments.
functionDecl
    : TOOL? FN identifier typeParams? LPAREN paramList? RPAREN (ARROW type)? contract* block
    ;

typeParams
    : LT identifier (COMMA identifier)* GT
    ;

paramList
    : param (COMMA param)* COMMA?
    ;

// `mut` parameters are passed by reference; all others are read-only values.
// `limit: i64 = 10` has a default: a constant that a call leaving the argument out passes instead.
param
    : identifier COLON MUT? type (ASSIGN expression)?
    ;

contract
    : kind=(REQUIRES | ENSURES) expression
    ;

// test "sum of empty list is zero" { assert sum([]) == 0; }
// test "zero is refused" traps R0006 { let _q: i64 = div(1, 0); }  passes only if the body stops with that trap
testDecl
    : TEST STRING_LIT (TRAPS identifier)? block
    ;

// =====================================================================
// Types
// =====================================================================

type
    : primitiveType                              # primitiveTypeRef
    | STR LBRACK capacity RBRACK                 # strTypeRef      // str[64]: text, up to 64 bytes
    | LIST LBRACK type SEMI capacity RBRACK      # listTypeRef     // list[i32; 16]: up to 16 items
    | LBRACK type SEMI capacity RBRACK           # arrayTypeRef    // [i32; 16]: exactly 16 items
    | LPAREN type (COMMA type)+ COMMA? RPAREN    # tupleTypeRef    // (i64, str[8]): a pair
    | MAP LBRACK type COMMA type SEMI capacity RBRACK  # mapTypeRef  // map[str[16], i64; 64]: up to 64 entries, in key order
    | SET LBRACK type SEMI capacity RBRACK       # setTypeRef      // set[i64; 32]: up to 32 distinct values, in order
    | FN LPAREN (type (COMMA type)*)? RPAREN (ARROW type)?  # fnTypeRef  // fn(i64) -> bool: a function, as a parameter
    | (qual=identifier DOT)? identifier (LT type (COMMA type)* GT)?   # namedTypeRef  // struct or enum, or ns.Name; Pair<i64, bool>
    // Option<Option<i64>>: the lexer reads `>>` as one token, which closes the last type argument's list and this one.
    | (qual=identifier DOT)? identifier LT (type COMMA)* innerType SHR   # nestedTypeRef
    ;

// The last type argument of a nestedTypeRef, whose `>` is the first half of the `>>`.
innerType
    : (qual=identifier DOT)? identifier LT type (COMMA type)*
    ;

// A constant capacity or length. In the type of a function parameter, `_` accepts any:
// fn total(xs: list[i64; _]) takes a list of i64 of every capacity.
capacity
    : expression
    | UNDERSCORE
    ;

primitiveType
    : BOOL
    | I8 | I16 | I32 | I64
    | U8 | U16 | U32 | U64
    | F32 | F64
    ;

// =====================================================================
// Statements
// =====================================================================

block
    : LBRACE statement* RBRACE
    ;

statement
    : letStmt
    | assignStmt
    | ifStmt
    | whileStmt
    | forStmt
    | labeledStmt
    | matchStmt
    | breakStmt
    | continueStmt
    | returnStmt
    | assertStmt
    | exprStmt
    ;

// `let` is immutable, `var` is mutable. Both must be initialized.
// let (q, r): (i64, i64) = divmod(a, b);  takes a value apart with a pattern that always matches (`_` skips a part):
// let Point(x: px, y: _): Point = p;
letStmt
    : kind=(LET | VAR) (identifier | pattern) COLON type ASSIGN expression SEMI
    ;

assignStmt
    : place op=(ASSIGN | PLUS_ASSIGN | MINUS_ASSIGN | STAR_ASSIGN | SLASH_ASSIGN | PERCENT_ASSIGN) expression SEMI
    ;

// A storage location: a variable, optionally followed by fields and indexes.
place
    : identifier placeSuffix*
    ;

placeSuffix
    : DOT identifier
    | DOT INT_LIT                        // t.0: an element of a tuple
    | DOT FLOAT_LIT                      // t.0.1, which the lexer reads as t . 0.1
    | LBRACK expression RBRACK
    ;

// if let Maybe.Some(x) = m { ... } else { ... }: the block runs when the pattern matches, with its names bound.
ifStmt
    : IF condition block (ELSE IF condition block)* (ELSE block)?
    ;

condition
    : LET pattern ASSIGN expression
    | expression
    ;

// The limit is evaluated once; exceeding it is a runtime trap.
whileStmt
    : WHILE expression LIMIT expression block
    ;

// for i in 0..n { }            (half-open integer range)
// for i in 0..=n step 2 { }    (inclusive, counting by 2)
// for i in rev 0..n { }        (the same values, last first)
// for item in items { }        (array, list or str; iterates over copies)
// for i, item in items { }     (with each element's index)
forStmt
    : FOR identifier (COMMA identifier)? IN REV? expression (range=(DOTDOT | DOTDOTEQ) expression (STEP expression)?)? block
    ;

// outer: for i in 0..n { ... break outer; ... }
labeledStmt
    : identifier COLON (whileStmt | forStmt)
    ;

// Arms are checked for exhaustiveness: together they cover every value, and none is covered by those above it.
matchStmt
    : MATCH expression LBRACE matchArm+ RBRACE
    ;

// A guard `if cond` makes the arm match only when cond holds too; guarded arms do not count toward exhaustiveness.
matchArm
    : pattern (IF expression)? FAT_ARROW block
    ;

// `1 | 2`: any of the alternatives, which bind the same names.
pattern
    : singlePattern (PIPE singlePattern)*
    ;

singlePattern
    : (qual=identifier DOT)? identifier DOT identifier (LPAREN pattern (COMMA pattern)* COMMA? RPAREN)?   # variantPattern
    | (qual=identifier DOT)? identifier LPAREN fieldPattern (COMMA fieldPattern)* COMMA? RPAREN          # structPattern
    | LPAREN pattern (COMMA pattern)+ COMMA? RPAREN                                                     # tuplePattern
    | LPAREN pattern RPAREN                                                                             # parenPattern
    | rangeBound op=(DOTDOT | DOTDOTEQ) rangeBound                                                      # rangePattern
    | MINUS? INT_LIT                                                              # intPattern
    | CHAR_LIT                                                                    # charPattern
    | STRING_LIT                                                                  # stringPattern
    | (TRUE | FALSE)                                                              # boolPattern
    | UNDERSCORE                                                                  # wildcardPattern
    | identifier                                                                  # bindingPattern
    ;

// Point(x: 0, y: py): the fields it names match their patterns; the others match anything.
fieldPattern
    : identifier COLON pattern
    ;

// 1..=9, 'a'..='z', -5..0: the integers from the first to the second (`..` leaves it out).
rangeBound
    : MINUS? INT_LIT
    | CHAR_LIT
    ;

breakStmt
    : BREAK identifier? SEMI
    ;

continueStmt
    : CONTINUE identifier? SEMI
    ;

returnStmt
    : RETURN expression? SEMI
    ;

assertStmt
    : ASSERT expression (COMMA STRING_LIT)? SEMI
    ;

// Only calls are allowed as expression statements (enforced by the checker).
exprStmt
    : expression SEMI
    ;

// =====================================================================
// Expressions
// =====================================================================

// `if c then a else b` is a whole expression, never an operand: (if c then a else b) + 1.
// So is `match e { P => a, Q => b }`, the value of the first arm that matches.
expression
    : IF expression THEN expression ELSE expression   # ifExpr
    | MATCH expression LBRACE matchExprArm (COMMA matchExprArm)* COMMA? RBRACE   # matchExpr
    | unary (AMP unary)+                 # bitAndExpr
    | unary (PIPE unary)+                # bitOrExpr
    | unary (CARET unary)+               # bitXorExpr
    | unary op=(SHL | SHR) unary         # shiftExpr
    | logic                              # logicExpr
    ;

matchExprArm
    : pattern (IF expression)? FAT_ARROW expression
    ;

logic
    : notExpr (AND notExpr)+             # andChain
    | notExpr (OR notExpr)+              # orChain
    | notExpr                            # singleLogic
    ;

notExpr
    : NOT notExpr
    | comparison
    ;

comparison
    : additive (op=(EQ | NEQ | LT | LE | GT | GE) additive)?
    ;

additive
    : multiplicative (ops+=(PLUS | MINUS) multiplicative)*
    ;

multiplicative
    : unary (ops+=(STAR | SLASH | PERCENT) unary)*
    ;

unary
    : op=(MINUS | TILDE) unary
    | postfix
    ;

postfix
    : primary postfixOp*
    ;

postfixOp
    : DOT identifier                     # fieldAccess
    | DOT (INT_LIT | FLOAT_LIT)          # tupleAccess   // t.0, and t.0.1 (lexed as t . 0.1)
    | LBRACK expression RBRACK           # indexAccess
    | LBRACK (from=expression)? DOTDOT (to=expression)? RBRACK   # sliceAccess  // s[1..], xs[a..b]: the elements from..to
    ;

primary
    : literal                                                           # literalExpr
    | primitiveType LPAREN expression RPAREN                            # conversionExpr
    // str[16](s), list[i64; 8](xs): the same text or elements with another capacity; traps if too long.
    | STR LBRACK expression RBRACK LPAREN expression RPAREN               # strResizeExpr
    | LIST LBRACK type SEMI expression RBRACK LPAREN expression RPAREN    # listResizeExpr
    | identifier LPAREN fieldInit (COMMA fieldInit)* COMMA? RPAREN      # structExpr
    | identifier DOT identifier LPAREN fieldInit (COMMA fieldInit)* COMMA? RPAREN   # qualifiedStructExpr
    | identifier DOT identifier DOT identifier LPAREN argList? RPAREN    # qualifiedVariantExpr
    | identifier DOT identifier LPAREN argList? RPAREN                  # variantExpr
    | identifier LPAREN argList? RPAREN                                 # callExpr
    | identifier                                                        # nameExpr
    | LPAREN expression RPAREN                                          # parenExpr
    | LPAREN expression (COMMA expression)+ COMMA? RPAREN               # tupleExpr
    | LBRACK expression SEMI expression RBRACK                          # repeatExpr
    | LBRACK (expression (COMMA expression)* COMMA?)? RBRACK            # arrayExpr
    ;

fieldInit
    : identifier COLON expression
    ;

argList
    : arg (COMMA arg)* COMMA?
    ;

// `mut place` passes the place by reference; the callee may modify it.
arg
    : MUT place                          # mutArg
    | expression                         # valueArg
    ;

literal
    : INT_LIT
    | FLOAT_LIT
    | STRING_LIT
    | CHAR_LIT
    | TRUE
    | FALSE
    ;

// `limit`, `test`, `tool`, `import`, `step`, `rev`, `then`, `map`, `set`, `as` and `traps` are contextual keywords and remain usable as names.
identifier
    : IDENT
    | LIMIT
    | TEST
    | TOOL
    | IMPORT
    | STEP
    | REV
    | THEN
    | MAP
    | SET
    | AS
    | TRAPS
    ;

// =====================================================================
// Lexer
// =====================================================================

// ---- keywords ----
IMPORT    : 'import' ;
FN        : 'fn' ;
TOOL      : 'tool' ;
LET       : 'let' ;
VAR       : 'var' ;
CONST     : 'const' ;
STRUCT    : 'struct' ;
ENUM      : 'enum' ;
IF        : 'if' ;
ELSE      : 'else' ;
WHILE     : 'while' ;
LIMIT     : 'limit' ;
STEP      : 'step' ;
AS        : 'as' ;
MAP       : 'map' ;
SET       : 'set' ;
THEN      : 'then' ;
REV       : 'rev' ;
FOR       : 'for' ;
IN        : 'in' ;
MATCH     : 'match' ;
BREAK     : 'break' ;
CONTINUE  : 'continue' ;
RETURN    : 'return' ;
ASSERT    : 'assert' ;
REQUIRES  : 'requires' ;
ENSURES   : 'ensures' ;
TEST      : 'test' ;
TRAPS     : 'traps' ;
MUT       : 'mut' ;
AND       : 'and' ;
OR        : 'or' ;
NOT       : 'not' ;
TRUE      : 'true' ;
FALSE     : 'false' ;

// ---- type keywords ----
BOOL      : 'bool' ;
I8        : 'i8' ;
I16       : 'i16' ;
I32       : 'i32' ;
I64       : 'i64' ;
U8        : 'u8' ;
U16       : 'u16' ;
U32       : 'u32' ;
U64       : 'u64' ;
F32       : 'f32' ;
F64       : 'f64' ;
STR       : 'str' ;
LIST      : 'list' ;

// ---- multi-character operators ----
ARROW          : '->' ;
FAT_ARROW      : '=>' ;
DOTDOTEQ       : '..=' ;
DOTDOT         : '..' ;
EQ             : '==' ;
NEQ            : '!=' ;
LE             : '<=' ;
GE             : '>=' ;
SHL            : '<<' ;
SHR            : '>>' ;
PLUS_ASSIGN    : '+=' ;
MINUS_ASSIGN   : '-=' ;
STAR_ASSIGN    : '*=' ;
SLASH_ASSIGN   : '/=' ;
PERCENT_ASSIGN : '%=' ;

// ---- single-character operators and punctuation ----
LT         : '<' ;
GT         : '>' ;
ASSIGN     : '=' ;
PLUS       : '+' ;
MINUS      : '-' ;
STAR       : '*' ;
SLASH      : '/' ;
PERCENT    : '%' ;
AMP        : '&' ;
PIPE       : '|' ;
CARET      : '^' ;
TILDE      : '~' ;
LPAREN     : '(' ;
RPAREN     : ')' ;
LBRACE     : '{' ;
RBRACE     : '}' ;
LBRACK     : '[' ;
RBRACK     : ']' ;
COMMA      : ',' ;
SEMI       : ';' ;
COLON      : ':' ;
DOT        : '.' ;
UNDERSCORE : '_' ;

// ---- tokens that are never valid ALang; they exist so tools can explain the fix ----
AMPAMP     : '&&' ;   // use `and`
PIPEPIPE   : '||' ;   // use `or`
BANG       : '!' ;    // use `not`
COLONCOLON : '::' ;   // use `.`  (Color.Red)
PLUSPLUS   : '++' ;   // use `x += 1;`
QUESTION   : '?' ;    // no ternary / try operator

// ---- literals ----
FLOAT_LIT
    : DIGITS '.' DIGITS EXPONENT?
    | DIGITS EXPONENT
    ;

INT_LIT
    : DIGITS
    | '0x' HEX_DIGIT ('_'? HEX_DIGIT)*
    | '0b' [01] ('_'? [01])*
    ;

STRING_LIT
    : '"' (ESCAPE | ~["\\\r\n])* '"'
    ;

// A single byte: 'a', '\n', '\x41'. Its value is an integer constant.
CHAR_LIT
    : '\'' (ESCAPE | ~['\\\r\n]) '\''
    ;

IDENT
    : [a-zA-Z_] [a-zA-Z0-9_]*
    ;

// ---- trivia ----
// `///` doc comments document the next declaration (used for tool descriptions).
DOC_COMMENT
    : '///' ~[\r\n]* -> channel(HIDDEN)
    ;

// Kept off the parser's channel, like doc comments, so that `alang fmt` can keep them.
LINE_COMMENT
    : '//' ~[\r\n]* -> channel(HIDDEN)
    ;

WS
    : [ \t\r\n]+ -> skip
    ;

// Anything else becomes a single error token so the parser can report it.
ERROR_CHAR
    : .
    ;

fragment DIGITS    : [0-9] ('_'? [0-9])* ;
fragment EXPONENT  : [eE] [+-]? DIGITS ;
fragment HEX_DIGIT : [0-9a-fA-F] ;
fragment ESCAPE    : '\\' ( [ntr0\\"'] | 'x' HEX_DIGIT HEX_DIGIT ) ;
