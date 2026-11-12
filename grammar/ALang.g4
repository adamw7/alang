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
importDecl
    : IMPORT STRING_LIT SEMI
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
structDecl
    : STRUCT identifier LBRACE fieldDecl (COMMA fieldDecl)* COMMA? RBRACE
    ;

fieldDecl
    : identifier COLON type
    ;

// enum Shape { Circle(f64), Rect(f64, f64), Empty }
enumDecl
    : ENUM identifier LBRACE variantDecl (COMMA variantDecl)* COMMA? RBRACE
    ;

variantDecl
    : identifier (LPAREN type (COMMA type)* COMMA? RPAREN)?
    ;

// tool fn area(w: f64, h: f64) -> f64 requires w >= 0.0 ensures result >= 0.0 { ... }
functionDecl
    : TOOL? FN identifier LPAREN paramList? RPAREN (ARROW type)? contract* block
    ;

paramList
    : param (COMMA param)* COMMA?
    ;

// `mut` parameters are passed by reference; all others are read-only values.
param
    : identifier COLON MUT? type
    ;

contract
    : kind=(REQUIRES | ENSURES) expression
    ;

// test "sum of empty list is zero" { assert sum([]) == 0; }
testDecl
    : TEST STRING_LIT block
    ;

// =====================================================================
// Types
// =====================================================================

type
    : primitiveType                              # primitiveTypeRef
    | STR LBRACK expression RBRACK               # strTypeRef      // str[64]: text, up to 64 bytes
    | LIST LBRACK type SEMI expression RBRACK    # listTypeRef     // list[i32; 16]: up to 16 items
    | LBRACK type SEMI expression RBRACK         # arrayTypeRef    // [i32; 16]: exactly 16 items
    | identifier                                 # namedTypeRef    // struct or enum name
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
    | matchStmt
    | breakStmt
    | continueStmt
    | returnStmt
    | assertStmt
    | exprStmt
    ;

// `let` is immutable, `var` is mutable. Both must be initialized.
letStmt
    : kind=(LET | VAR) identifier COLON type ASSIGN expression SEMI
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
    | LBRACK expression RBRACK
    ;

ifStmt
    : IF expression block (ELSE IF expression block)* (ELSE block)?
    ;

// The limit is evaluated once; exceeding it is a runtime trap.
whileStmt
    : WHILE expression LIMIT expression block
    ;

// for i in 0..n { }      (half-open integer range)
// for item in items { }  (array, list or str; iterates over copies)
forStmt
    : FOR identifier IN expression (DOTDOT expression)? block
    ;

// Arms are checked for exhaustiveness. `_` matches anything and must be last.
matchStmt
    : MATCH expression LBRACE matchArm+ RBRACE
    ;

matchArm
    : pattern FAT_ARROW block
    ;

pattern
    : identifier DOT identifier (LPAREN binding (COMMA binding)* COMMA? RPAREN)?   # variantPattern
    | MINUS? INT_LIT                                                              # intPattern
    | CHAR_LIT                                                                    # charPattern
    | STRING_LIT                                                                  # stringPattern
    | (TRUE | FALSE)                                                              # boolPattern
    | UNDERSCORE                                                                  # wildcardPattern
    ;

binding
    : identifier
    | UNDERSCORE
    ;

breakStmt
    : BREAK SEMI
    ;

continueStmt
    : CONTINUE SEMI
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

expression
    : unary (AMP unary)+                 # bitAndExpr
    | unary (PIPE unary)+                # bitOrExpr
    | unary (CARET unary)+               # bitXorExpr
    | unary op=(SHL | SHR) unary         # shiftExpr
    | logic                              # logicExpr
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
    | LBRACK expression RBRACK           # indexAccess
    ;

primary
    : literal                                                           # literalExpr
    | primitiveType LPAREN expression RPAREN                            # conversionExpr
    // str[16](s), list[i64; 8](xs): the same text or elements with another capacity; traps if too long.
    | STR LBRACK expression RBRACK LPAREN expression RPAREN               # strResizeExpr
    | LIST LBRACK type SEMI expression RBRACK LPAREN expression RPAREN    # listResizeExpr
    | identifier LPAREN fieldInit (COMMA fieldInit)* COMMA? RPAREN      # structExpr
    | identifier DOT identifier LPAREN argList? RPAREN                  # variantExpr
    | identifier LPAREN argList? RPAREN                                 # callExpr
    | identifier                                                        # nameExpr
    | LPAREN expression RPAREN                                          # parenExpr
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

// `limit`, `test`, `tool` and `import` are contextual keywords and remain usable as names.
identifier
    : IDENT
    | LIMIT
    | TEST
    | TOOL
    | IMPORT
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

LINE_COMMENT
    : '//' ~[\r\n]* -> skip
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
