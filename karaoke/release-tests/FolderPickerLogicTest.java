import com.setupbox.karaoke.FileStorage;
import com.setupbox.karaoke.Storage;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Release test (no Android needed): the pure logic behind the folder picker.
 *   - the "is this the box's own storage?" rule that rejects USB drives / SD cards
 *   - Storage listing and text reading (FileStorage is the same contract as SafStorage), incl. Chinese names and a BOM
 *
 * Run from the karaoke folder (run_tests.py does this for you):
 *   javac -encoding UTF-8 -d build/rtest app/src/main/java/com/setupbox/karaoke/Storage.java app/src/main/java/com/setupbox/karaoke/FileStorage.java release-tests/FolderPickerLogicTest.java
 *   java -cp build/rtest FolderPickerLogicTest
 */
public class FolderPickerLogicTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        // --- internal vs USB
        check("primary:Karaoke is internal", Storage.isInternalTreeId("primary:Karaoke"));
        check("primary (storage root) is internal", Storage.isInternalTreeId("primary"));
        check("primary:Music/Karaoke is internal", Storage.isInternalTreeId("primary:Music/Karaoke"));
        check("USB drive 1234-ABCD:Karaoke is rejected", !Storage.isInternalTreeId("1234-ABCD:Karaoke"));
        check("SD card A6D0-F59E: is rejected", !Storage.isInternalTreeId("A6D0-F59E:"));
        check("a folder merely named primary on a USB drive is rejected", !Storage.isInternalTreeId("1234-ABCD:primary:x"));
        check("null is rejected", !Storage.isInternalTreeId(null));
        check("empty is rejected", !Storage.isInternalTreeId(""));
        check("'primaryX:' is rejected", !Storage.isInternalTreeId("primaryX:Karaoke"));

        // --- Storage contract over a temp folder shaped like the Karaoke folder
        File root = Files.createTempDirectory("karaoke_rtest").toFile();
        File artist = new File(root, "Cantopop/Eason Chan");
        artist.mkdirs();
        File mp3 = new File(artist, "十面埋伏.mp3");
        File lrc = new File(artist, "十面埋伏.lrc");
        try (FileOutputStream o = new FileOutputStream(mp3)) { o.write(new byte[]{1, 2, 3}); }
        try (FileOutputStream o = new FileOutputStream(lrc)) { o.write("﻿[00:01.00]你好".getBytes("UTF-8")); }
        Storage st = new FileStorage(root);

        List<String> top = names(st.list(st.root()));
        check("root lists the language folder", top.size() == 1 && top.contains("Cantopop"));
        Storage.Entry lang = Storage.child(st, st.root(), "Cantopop");
        check("child() finds a folder and says it is a dir", lang != null && lang.dir);
        Storage.Entry art = lang == null ? null : Storage.child(st, lang.handle, "Eason Chan");
        check("artist folder found", art != null && art.dir);
        List<String> files = art == null ? new ArrayList<String>() : names(st.list(art.handle));
        check("Chinese file names are listed", files.contains("十面埋伏.mp3") && files.contains("十面埋伏.lrc"));
        Storage.Entry l = art == null ? null : Storage.child(st, art.handle, "十面埋伏.lrc");
        check("readText strips the BOM and keeps the Chinese text", l != null && "[00:01.00]你好".equals(Storage.readText(st, l.handle)));
        check("readText of a missing file is null", Storage.readText(st, new File(root, "nope.txt").getPath()) == null);
        check("readText(null) is null", Storage.readText(st, null) == null);
        check("list of a missing folder is empty, never null", st.list(new File(root, "nope").getPath()).isEmpty());
        check("child() of a missing name is null", Storage.child(st, st.root(), "Nope") == null);
        Storage.Entry m = art == null ? null : Storage.child(st, art.handle, "十面埋伏.mp3");
        check("uri() gives a file:/// URI for the player", m != null && st.uri(m.handle).startsWith("file:///"));

        deleteAll(root);
        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }

    static List<String> names(List<Storage.Entry> es) {
        List<String> out = new ArrayList<>();
        for (Storage.Entry e : es) out.add(e.name);
        return out;
    }

    static void deleteAll(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteAll(k);
        f.delete();
    }
}
