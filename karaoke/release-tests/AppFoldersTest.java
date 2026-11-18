import com.setupbox.karaoke.AppFolders;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Plain-Java checks for the in-app song-location chooser rules (path containment, listing, parent, names).
 *   javac -encoding UTF-8 -d build/rtest app/src/main/java/com/setupbox/karaoke/AppFolders.java release-tests/AppFoldersTest.java
 *   java -Dfile.encoding=UTF-8 -cp build/rtest AppFoldersTest
 */
public class AppFoldersTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        File tmp = Files.createTempDirectory("appfolders").toFile();
        File root = new File(tmp, "vol1"), other = new File(tmp, "vol2");
        File k = new File(root, "Karaoke"), lang = new File(k, "Cantopop"), hidden = new File(root, ".trash");
        File sibling = new File(tmp, "vol1-evil");
        for (File d : new File[]{lang, other, hidden, sibling, new File(root, "alpha"), new File(root, "Zulu")}) d.mkdirs();
        new File(root, "file.txt").createNewFile();

        check("root is under itself", AppFolders.isUnder(root, root));
        check("a child is under the root", AppFolders.isUnder(root, lang));
        check("a sibling with the same prefix is NOT under the root (vol1-evil)", !AppFolders.isUnder(root, sibling));
        check("a parent is not under the child", !AppFolders.isUnder(lang, root));
        check("'..' cannot climb out", !AppFolders.isUnder(root, new File(root, "../vol2")));
        check("null is never under", !AppFolders.isUnder(root, null) && !AppFolders.isUnder(null, root));

        List<File> roots = new ArrayList<>(Arrays.asList(root, other));
        check("allowed: below the first root", AppFolders.isAllowed(roots, lang));
        check("allowed: below the second root", AppFolders.isAllowed(roots, new File(other, "x")));
        check("not allowed: outside every root", !AppFolders.isAllowed(roots, tmp));
        check("not allowed: empty/null roots", !AppFolders.isAllowed(null, lang) && !AppFolders.isAllowed(new ArrayList<File>(), lang));
        check("rootOf finds the containing root", root.equals(AppFolders.rootOf(roots, lang)) && other.equals(AppFolders.rootOf(roots, other)));
        check("rootOf is null outside", AppFolders.rootOf(roots, tmp) == null);

        List<File> subs = AppFolders.subDirs(root);
        List<String> names = new ArrayList<>();
        for (File f : subs) names.add(f.getName());
        check("subDirs: folders only, hidden skipped, sorted ignoring case: " + names, names.equals(Arrays.asList("alpha", "Karaoke", "Zulu")));
        check("subDirs of a missing folder is empty", AppFolders.subDirs(new File(tmp, "nope")).isEmpty());
        check("subDirs(null) is empty", AppFolders.subDirs(null).isEmpty());

        check("parentWithin: a child goes up one level", k.equals(AppFolders.parentWithin(root, lang)));
        check("parentWithin: the root has no parent (never leaves the volume)", AppFolders.parentWithin(root, root) == null);
        check("parentWithin: outside the root is null", AppFolders.parentWithin(root, tmp) == null);

        check("plain name accepted", AppFolders.isPlainName("Karaoke") && AppFolders.isPlainName("我的歌 2"));
        check("name rejected: empty, hidden, slashes, backslash, NUL, too long, null",
                !AppFolders.isPlainName("") && !AppFolders.isPlainName("  ") && !AppFolders.isPlainName(".x") && !AppFolders.isPlainName("a/b")
                        && !AppFolders.isPlainName("a\\b") && !AppFolders.isPlainName("a\0b") && !AppFolders.isPlainName(new String(new char[81]).replace('\0', 'x'))
                        && !AppFolders.isPlainName(null));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
