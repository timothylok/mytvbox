import com.setupbox.karaoke.Lrc;

/**
 * Plain-Java checks for the LRC parser (no Android needed). Run from the karaoke folder:
 *   javac -encoding UTF-8 -d build/qtest app/src/main/java/com/setupbox/karaoke/Lrc.java tests/LrcTest.java
 *   java -Dfile.encoding=UTF-8 -cp build/qtest LrcTest
 */
public class LrcTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        // plain lines
        Lrc p = Lrc.parse("[00:04.05]\n[00:10.00]我唱得不够\n[00:20.00]约定至死\n");
        check("plain: 3 lines in order", p.lines.size() == 3 && p.lines.get(1).text.equals("我唱得不够"));
        check("plain: no word times", p.lines.get(1).charMs == null && p.lines.get(1).endMs == -1);
        check("plain: line index at time", p.indexAt(9999) == 0 && p.indexAt(10000) == 1 && p.indexAt(25000) == 2 && p.indexAt(1000) == -1);
        check("plain: estimate starts at char 0", p.currentChar(1, 10000) == 0);
        check("plain: estimate moves on", p.currentChar(1, 10000 + 900) == 2);   // 5 chars over 2.25 s
        check("plain: whole line done after its time", p.currentChar(1, 10000 + 2300) == 5);
        check("plain: empty line has no current char", p.currentChar(0, 4100) == -1);

        // enhanced: one tag per character, trailing end tag
        Lrc e = Lrc.parse("[00:10.00]<00:10.00>我<00:10.50>唱<00:11.00>得<00:11.50>");
        Lrc.Line l = e.lines.get(0);
        check("enhanced: text without tags", l.text.equals("我唱得"));
        check("enhanced: per-character times", l.charMs != null && l.charMs.length == 3 && l.charMs[0] == 10000 && l.charMs[1] == 10500 && l.charMs[2] == 11000);
        check("enhanced: trailing tag is the line end", l.endMs == 11500);
        check("enhanced: first char at its time", e.currentChar(0, 10000) == 0);
        check("enhanced: second char", e.currentChar(0, 10600) == 1);
        check("enhanced: third char", e.currentChar(0, 11200) == 2);
        check("enhanced: line done at its end", e.currentChar(0, 11500) == 3);

        // a word of several characters is spread evenly
        Lrc w = Lrc.parse("[00:10.00]<00:10.00>我唱<00:11.00>得<00:11.40>");
        check("word spread", w.lines.get(0).charMs[0] == 10000 && w.lines.get(0).charMs[1] == 10500 && w.lines.get(0).charMs[2] == 11000);

        // spaces stay in the text but are not counted as characters
        Lrc s = Lrc.parse("[00:10.00]<00:10.00>Hi <00:10.40>you<00:11.00>");
        check("spaces kept in text", s.lines.get(0).text.equals("Hi you"));
        check("space skipped when counting", s.currentChar(0, 10450) == 2);       // H, i, y -> 'y' is index 2

        // no trailing tag: last character gets a default length, the line ends before the next line starts
        Lrc n = Lrc.parse("[00:10.00]<00:10.00>a<00:10.50>b\n[00:30.00]next");
        check("no end tag: still on last char shortly after it starts", n.currentChar(0, 10700) == 1);
        check("no end tag: done 0.8 s after the last char", n.currentChar(0, 10500 + 800) == 2);

        // text before the first tag belongs to the line start
        Lrc lead = Lrc.parse("[00:10.00]A<00:10.50>b<00:11.00>");
        check("leading text starts with the line", lead.lines.get(0).text.equals("Ab") && lead.lines.get(0).charMs[0] == 10000 && lead.lines.get(0).charMs[1] == 10500);

        // repeated line (two line tags) shifts the word times too
        Lrc r = Lrc.parse("[00:10.00][01:10.00]<00:10.00>a<00:10.50>b<00:11.00>");
        check("repeat: both lines present", r.lines.size() == 2);
        check("repeat: word times shifted by 60 s", r.lines.get(1).ms == 70000 && r.lines.get(1).charMs[1] == 70500 && r.lines.get(1).endMs == 71000);

        // [offset:] applies to line, word and end times
        Lrc o = Lrc.parse("[offset:+500]\n[00:10.00]<00:10.00>a<00:10.50>b<00:11.00>");
        check("offset applies everywhere (positive = earlier, per the LRC spec)", o.lines.get(0).ms == 9500 && o.lines.get(0).charMs[1] == 10000 && o.lines.get(0).endMs == 10500);

        // "(合)" / "（男）" only say who sings: they are dropped, with their word times, and never shown
        Lrc t1 = Lrc.parse("[00:10.00](合)\t飛 我伴你飛\n[00:20.00]（男）你好(女)我好\n[00:30.00](合)\n[00:40.00]未閉合(男 你好");
        check("turn markers: plain line", t1.lines.get(0).text.equals("飛 我伴你飛") && t1.lines.get(1).text.equals("你好我好"));
        check("turn markers: still shown, flagged so they are never highlighted",
                t1.lines.get(0).shown.startsWith("(合)") && t1.lines.get(0).turn != null && t1.lines.get(0).turn[0] && t1.lines.get(0).turn[2]
                && !t1.lines.get(0).turn[t1.lines.get(0).shown.length() - 1] && t1.lines.get(1).shown.equals("（男）你好(女)我好")
                && Lrc.parse("[00:01.00]普通").lines.get(0).turn == null);
        check("turn markers: marker-only line is empty", t1.lines.get(2).text.isEmpty());
        check("turn markers: unclosed bracket kept", t1.lines.get(3).text.equals("未閉合(男 你好"));
        Lrc t2 = Lrc.parse("[00:10.00]<00:10.00>(<00:10.10>合<00:10.20>)<00:10.30>飛<00:10.80>我<00:11.30>");
        check("turn markers: timed line keeps the right times", t2.lines.get(0).text.equals("飛我") && t2.lines.get(0).charMs.length == 2
                && t2.lines.get(0).shown.equals("(合)飛我") && t2.lines.get(0).turn[1]
                && t2.lines.get(0).charMs[0] == 10300 && t2.lines.get(0).charMs[1] == 10800 && t2.lines.get(0).endMs == 11300);

        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
