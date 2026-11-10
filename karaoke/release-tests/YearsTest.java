import com.setupbox.karaoke.Years;

import java.util.Map;

/**
 * Release test (no Android needed): years.txt parsing and lookup.
 *   javac -encoding UTF-8 -d build/rtest app/src/main/java/com/setupbox/karaoke/Years.java release-tests/YearsTest.java
 *   java -cp build/rtest YearsTest        (run_tests.py does this for you)
 */
public class YearsTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        String text = "﻿# years\n"
                + "K歌之王 = 1998\n"
                + "Eason Chan/浮誇 = 2009\n"
                + "Cantopop/Eason Chan/富士山下 = 2006\n"
                + "bad line without equals\n"
                + "Eason Chan/NoYear = abc\n"
                + "Eason Chan/TooOld = 1500\n"
                + "Eason Chan/Future = 2999\n"
                + " = 2000\n"
                + "Eason Chan/Spaces =   2012  \r\n"
                + "A = B = 2001\n";
        Map<String, Integer> m = Years.parse(text.substring(1));      // the app strips the BOM when reading
        check("plain key", Years.lookup(m, "Cantopop", "Eason Chan", "K歌之王") == 1998);
        check("Artist/name key", Years.lookup(m, "Cantopop", "Eason Chan", "浮誇") == 2009);
        check("Language/Artist/name key", Years.lookup(m, "Cantopop", "Eason Chan", "富士山下") == 2006);
        check("spaces around the year are ignored", Years.lookup(m, "Cantopop", "Eason Chan", "Spaces") == 2012);
        check("last '=' separates the year", Years.lookup(m, "x", "y", "A = B") == 2001);
        check("non-numeric year ignored", Years.lookup(m, "Cantopop", "Eason Chan", "NoYear") == 0);
        check("year before 1900 ignored", Years.lookup(m, "Cantopop", "Eason Chan", "TooOld") == 0);
        check("year after 2100 ignored", Years.lookup(m, "Cantopop", "Eason Chan", "Future") == 0);
        check("unknown song has no year (0)", Years.lookup(m, "Cantopop", "Eason Chan", "Unknown") == 0);
        check("comment and junk lines ignored", m.size() == 5);
        Map<String, Integer> two = Years.parse("Eason Chan/X = 2000\nCantopop/Eason Chan/X = 2010\nX = 1990\n");
        check("most specific key wins", Years.lookup(two, "Cantopop", "Eason Chan", "X") == 2010);
        check("then Artist/name over plain", Years.lookup(two, "English", "Eason Chan", "X") == 2000);
        check("then plain name", Years.lookup(two, "English", "Other", "X") == 1990);
        check("null / empty text gives an empty map", Years.parse(null).isEmpty() && Years.parse("").isEmpty());
        check("lookup in a null map is 0", Years.lookup(null, "a", "b", "c") == 0);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
