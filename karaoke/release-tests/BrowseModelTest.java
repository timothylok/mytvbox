import com.setupbox.karaoke.BrowseModel;
import com.setupbox.karaoke.BrowseModel.Entry;
import com.setupbox.karaoke.BrowseModel.Row;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java checks for the on-TV song browser's grouping and ordering.
 *   javac -encoding UTF-8 -d build/rtest app/src/main/java/com/setupbox/karaoke/BrowseModel.java release-tests/BrowseModelTest.java
 *   java -Dfile.encoding=UTF-8 -cp build/rtest BrowseModelTest
 */
public class BrowseModelTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static Entry e(String id, String lang, String artist, String title, int year) { return new Entry(id, lang, artist, title, year); }

    static String names(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (Row r : rows) sb.append(r.value).append(r.count > 0 ? "" : "?").append(",");
        return sb.toString();
    }

    public static void main(String[] a) {
        List<Entry> all = new ArrayList<>();
        all.add(e("1", "Mandarin", "JJ Lin", "小酒窩", 2008));
        all.add(e("2", "Cantopop", "Eason Chan", "十面埋伏", 2006));
        all.add(e("3", "Cantopop", "Andy Hui", "男人最痛", 0));
        all.add(e("4", "KPop", "IU", "Dear my crazy soulmate", 2019));
        all.add(e("5", "Cantopop", "Eason Chan", "K歌之王", 1998));
        all.add(e("6", "Cantopop", "eason chan", "lowercase twin", 2001));
        all.add(e("7", "Other", "Timlok", "My song", 0));
        all.add(e("8", "Cantopop", "Eason Chan", "明年今日", 2006));
        all.add(e("9", "JPop", "YAO", "ありふれた世界の果てに", 2024));
        all.add(e("10", "English", "The Weeknd", "Blinding Lights", 2019));
        all.add(e("11", "Zulu", "Z Band", "Zed", 2000));

        List<Row> langs = BrowseModel.languages(all);
        check("languages in the fixed order, others after by name: " + names(langs),
                names(langs).equals("Cantopop,Mandarin,English,JPop,KPop,Other,Zulu,"));
        check("language counts are right (Cantopop has 5)", langs.get(0).count == 5 && langs.get(1).count == 1);
        check("a language row reads \"Cantopop  (5)\"", BrowseModel.label(langs.get(0)).equals("Cantopop  (5)"));

        List<Row> arts = BrowseModel.artists(all, "Cantopop");
        check("artists sorted by name ignoring case: " + names(arts), names(arts).equals("Andy Hui,Eason Chan,eason chan,"));
        check("artist counts are right", arts.get(1).count == 3 && arts.get(0).count == 1);
        check("an unknown language has no artists", BrowseModel.artists(all, "Nope").isEmpty());

        List<Entry> songs = BrowseModel.songs(all, "Cantopop", "Eason Chan");
        StringBuilder ids = new StringBuilder();
        for (Entry s : songs) ids.append(s.id).append(",");
        check("songs newest first, same year by title: " + ids, ids.toString().equals("2,8,5,"));
        List<Entry> mixed = new ArrayList<>();
        mixed.add(e("a", "L", "X", "Beta", 0));
        mixed.add(e("b", "L", "X", "Alpha", 0));
        mixed.add(e("c", "L", "X", "Gamma", 1990));
        List<Entry> ms = BrowseModel.songs(mixed, "L", "X");
        check("songs with an unknown year come after the dated ones, by title", ms.get(0).id.equals("c") && ms.get(1).id.equals("b") && ms.get(2).id.equals("a"));
        check("no songs for an unknown artist", BrowseModel.songs(all, "Cantopop", "Nobody").isEmpty());

        check("song label with year", BrowseModel.songLabel(all.get(0), false).equals("小酒窩   2008"));
        check("song label without year, queued marker", BrowseModel.songLabel(all.get(2), true).equals("[queued]  男人最痛"));

        List<Entry> weird = new ArrayList<>();
        weird.add(e("w1", null, null, null, 0));
        weird.add(e("w2", "", "", "T", 5));
        check("null language/artist/title never throw", BrowseModel.languages(weird).size() == 1 && BrowseModel.artists(weird, "").size() == 1
                && BrowseModel.songs(weird, "", "").size() == 2);
        check("a row with an empty value reads (unknown)", BrowseModel.label(BrowseModel.languages(weird).get(0)).startsWith("(unknown)"));
        check("an empty library gives empty lists", BrowseModel.languages(new ArrayList<Entry>()).isEmpty());

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
