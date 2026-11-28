package alang.diag;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Lines, spans and caret markers of a source file, counted in code points and with CRLF line breaks read as LF. */
class SourceTest {

    @Test
    void lines_are_split_on_lf_with_cr_stripped() {
        Source s = new Source("a.al", "one\r\ntwo\nthree");
        assertEquals(3, s.lineCount());
        assertEquals("one", s.line(1));
        assertEquals("two", s.line(2));
        assertEquals("three", s.line(3));
        assertEquals("", s.line(0));
        assertEquals("", s.line(4));
        assertEquals("", s.line(-1));
    }

    @Test
    void text_ending_with_a_line_break_has_an_empty_last_line() {
        assertEquals(2, new Source("a.al", "x\n").lineCount());
        assertEquals(1, new Source("a.al", "").lineCount());
        assertEquals(3, new Source("a.al", "\n\n").lineCount());
        // Only a CR right before the LF (or at the very end) is dropped, not one inside a line.
        Source s = new Source("a.al", "a\rb\r");
        assertEquals("a\rb", s.line(1));
    }

    @Test
    void the_text_of_a_span_counts_code_points() {
        Source s = new Source("a.al", "let 😀x: i64 = 1;\nfn f() {}");
        assertEquals("😀x", s.text(new Span(1, 5, 2, s)));
        assertEquals("i64", s.text(new Span(1, 9, 3, s)));
        assertEquals("fn", s.text(new Span(2, 1, 2, s)));
        // A span past the end of its line is cut short; one on no line, or with no length, is empty.
        assertEquals("{}", s.text(new Span(2, 8, 50, s)));
        assertEquals("", s.text(new Span(2, 40, 2, s)));
        assertEquals("", s.text(new Span(9, 1, 2, s)));
        assertEquals("", s.text(new Span(1, 1, 0, s)));
        assertEquals("le", s.text(new Span(1, 0, 2, s)));
    }

    @Test
    void the_caret_lines_up_under_the_span_keeping_tabs() {
        Source s = new Source("a.al", "\tlet é = x;");
        assertEquals("\t    ^", s.caret(new Span(1, 6, 1, s)));
        assertEquals("\t^^^", s.caret(new Span(1, 2, 3, s)));
        // At least one caret, and no more than the line has from the column on.
        assertEquals("^", s.caret(new Span(1, 1, 0, s)));
        assertEquals("\t        ^^", s.caret(new Span(1, 10, 99, s)));
        // A column past the end gets a single caret after the line's text.
        assertEquals("\t          ^", s.caret(new Span(1, 30, 3, s)));
        assertEquals("^", s.caret(new Span(5, 3, 2, s)));
    }

    @Test
    void columns_count_code_points_not_utf16_chars() {
        assertEquals(0, Source.columns(""));
        assertEquals(3, Source.columns("abc"));
        assertEquals(1, Source.columns("😀"));
        assertEquals(2, Source.columns("é😀"));
    }

    @Test
    void a_span_names_its_file_only_when_it_is_another() {
        Source a = new Source("a.al", "x");
        Source b = new Source("lib/b.al", "y");
        Span inB = new Span(3, 7, 1, b);
        assertEquals("3:7", inB.toString());
        assertEquals("3:7", inB.where(b));
        assertEquals("lib/b.al:3:7", inB.where(a));
        assertEquals("1:2", new Span(1, 2, 1, null).where(a));
    }
}
