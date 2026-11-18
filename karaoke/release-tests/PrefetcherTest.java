import com.setupbox.karaoke.FileStorage;
import com.setupbox.karaoke.Prefetcher;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;

/**
 * Plain-Java checks for the next-song prefetcher (warm-up reads, lyric pre-parse, fail-soft, cancel).
 *   javac -encoding UTF-8 -d build/rtest app/src/main/java/com/setupbox/karaoke/{Storage,FileStorage,Lrc,Prefetcher}.java release-tests/PrefetcherTest.java
 *   java -Dfile.encoding=UTF-8 -cp build/rtest PrefetcherTest
 */
public class PrefetcherTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static File make(File dir, String name, long size) throws Exception {
        File f = new File(dir, name);
        byte[] b = new byte[1 << 20];
        try (FileOutputStream o = new FileOutputStream(f)) {
            for (long left = size; left > 0; left -= b.length) o.write(b, 0, (int) Math.min(b.length, left));
        }
        return f;
    }

    public static void main(String[] a) throws Exception {
        File dir = Files.createTempDirectory("prefetch").toFile();
        FileStorage st = new FileStorage(dir);
        File mp3 = make(dir, "a.mp3", 3L << 20);
        File mp4 = make(dir, "a.mp4", 40L << 20);
        File small = make(dir, "s.mp4", 5L << 20);
        File lrc = new File(dir, "a.lrc");
        Files.write(lrc.toPath(), "[00:01.00]hello world\n[00:05.00]second line\n".getBytes("UTF-8"));
        File junk = new File(dir, "junk.lrc");
        Files.write(junk.toPath(), new byte[]{0, 1, 2, (byte) 0xff, (byte) 0xfe, '[', '9', '9'});

        Prefetcher p = new Prefetcher();
        check("nothing wanted at the start", p.wantedId().isEmpty() && p.take("x") == null);

        p.request(st, "song1", mp3.getPath(), mp4.getPath(), lrc.getPath());
        check("the request is remembered", p.wantedId().equals("song1"));
        check("the warm-up finishes", p.await(10000));
        Prefetcher.Result r = p.take("song1");
        check("a result is ready for that song", r != null && r.songId.equals("song1"));
        check("the lyrics were parsed ahead (2 lines)", r != null && r.lrc != null && r.lrc.lines.size() == 2);
        long expect = (3L << 20) + Prefetcher.VIDEO_HEAD + Prefetcher.VIDEO_TAIL;
        check("bytes read = whole mp3 + video head + video tail (" + (r == null ? -1 : r.bytes) + " of " + expect + ")", r != null && r.bytes == expect);
        check("the result is handed out only once", p.take("song1") == null);
        check("a result for another song is not handed out", p.take("other") == null);

        p.request(st, "song1", mp3.getPath(), mp4.getPath(), lrc.getPath());
        check("asking again for the same song does not start another warm-up (no new result)", p.await(5000) && p.take("song1") == null);

        p.request(st, "small", mp3.getPath(), small.getPath(), lrc.getPath());
        p.await(10000);
        r = p.take("small");
        check("a video smaller than head+tail is read once, not twice (" + (r == null ? -1 : r.bytes) + ")", r != null && r.bytes == (3L << 20) + (5L << 20));

        p.request(st, "missing", new File(dir, "nope.mp3").getPath(), new File(dir, "nope.mp4").getPath(), new File(dir, "nope.lrc").getPath());
        p.await(5000);
        r = p.take("missing");
        check("missing files: fail soft, result with no lyrics and 0 bytes", r != null && r.lrc == null && r.bytes == 0);

        p.request(st, "junk", "", "", junk.getPath());
        p.await(5000);
        r = p.take("junk");
        check("a broken lyric file does not break the warm-up", r != null);

        p.request(st, "none", "", "", "");
        p.await(5000);
        r = p.take("none");
        check("a song with no files at all gives an empty result", r != null && r.lrc == null && r.bytes == 0);

        p.request(st, "big", mp3.getPath(), mp4.getPath(), lrc.getPath());
        p.cancel();
        p.await(10000);
        check("cancel: no result is kept and nothing is wanted", p.take("big") == null && p.wantedId().isEmpty());

        p.request(st, "first", mp3.getPath(), mp4.getPath(), lrc.getPath());
        p.request(st, "second", mp3.getPath(), small.getPath(), lrc.getPath());
        p.await(10000);
        check("a newer request replaces an older one (only 'second' is delivered)", p.take("first") == null && p.take("second") != null);

        p.request(null, "x", "", "", "");
        p.request(st, null, "", "", "");
        p.request(st, "", "", "", "");
        check("null storage / empty ids are ignored", !p.wantedId().equals("x"));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
