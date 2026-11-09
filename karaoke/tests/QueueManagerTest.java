import com.setupbox.karaoke.QueueManager;
import com.setupbox.karaoke.QueueManager.Item;
import com.setupbox.karaoke.QueueManager.Move;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain-Java checks for QueueManager (no Android needed). Run from the karaoke folder:
 *   javac -d build/qtest app/src/main/java/com/setupbox/karaoke/QueueManager.java tests/QueueManagerTest.java
 *   java -cp build/qtest QueueManagerTest
 */
public class QueueManagerTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    static String order(QueueManager q) {
        StringBuilder sb = new StringBuilder();
        for (Item it : q.upcoming()) sb.append(it.songId);
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        QueueManager q = new QueueManager();
        Item a = q.add("A", "Amy", false), b = q.add("B", "Bob", false), c = q.add("C", "", false);
        check("add to end keeps order", order(q).equals("ABC"));

        Item d = q.add("D", "Dee", true);
        check("add as next goes to front", order(q).equals("DABC"));

        Item a2 = q.add("A", "Zed", false);
        check("same song twice gets a different id", !a.id.equals(a2.id) && order(q).equals("DABCA"));

        check("move up", q.move(c.id, Move.UP) && order(q).equals("DACBA"));
        check("move down", q.move(d.id, Move.DOWN) && order(q).equals("ADCBA"));
        check("move to top", q.move(b.id, Move.TOP) && order(q).equals("BADCA"));
        check("move to bottom", q.move(b.id, Move.BOTTOM) && order(q).equals("ADCAB"));
        check("move up at top is a no-op", !q.move(a.id, Move.UP) && order(q).equals("ADCAB"));
        check("move down at bottom is a no-op", !q.move(b.id, Move.DOWN));
        check("move unknown id fails", !q.move("nope", Move.UP));

        check("remove", q.remove(d.id) && order(q).equals("ACAB"));
        check("remove twice fails", !q.remove(d.id));

        Item now = q.advance();
        check("advance plays the first item", now != null && now.id.equals(a.id) && q.current() == now && order(q).equals("CAB"));
        q.shuffle();
        check("shuffle keeps current and all items", q.current() == now && order(q).length() == 3
                && order(q).contains("A") && order(q).contains("B") && order(q).contains("C"));

        // shuffle really reorders (try a few times - one result can match by chance)
        boolean changed = false;
        QueueManager big = new QueueManager();
        for (int i = 0; i < 20; i++) big.add("s" + i, "", false);
        String before = order(big);
        big.shuffle();
        changed = !order(big).equals(before);
        check("shuffle reorders a long queue", changed);

        long v = q.version();
        q.move("nope", Move.UP);
        q.remove("nope");
        check("failed changes don't bump the version", q.version() == v);
        q.add("X", "x", false);
        check("changes bump the version", q.version() > v);

        // ids stay unique after save + restore, tabs/newlines in singer names are cleaned, CJK survives
        QueueManager w = new QueueManager();
        w.add("K1", "阿Ming\tLee\n", false);
        w.add("K2", "Amy", false);
        w.advance();
        w.add("K3", "Bob", false);
        String saved = w.serialize();
        QueueManager r = new QueueManager();
        r.restore(saved);
        List<String> ids = new ArrayList<>();
        for (Item it : r.upcoming()) ids.add(it.songId);
        check("restore order: playing song first, then the rest", ids.toString().equals("[K1, K2, K3]"));
        check("singer name cleaned and CJK kept", r.upcoming().get(0).singer.equals("阿Ming Lee"));
        Item fresh = r.add("K4", "", false);
        boolean unique = true;
        for (Item it : r.upcoming()) if (it != fresh && it.id.equals(fresh.id)) unique = false;
        check("ids stay unique after restore", unique);

        // file persistence
        File f = File.createTempFile("queue", ".txt");
        f.delete();
        QueueManager p1 = QueueManager.withFile(f);
        p1.add("P1", "Ann", false);
        p1.add("P2", "冰", false);
        p1.advance();
        QueueManager p2 = QueueManager.withFile(f);
        check("queue survives a restart", order(p2).equals("P1P2") && p2.upcoming().get(1).singer.equals("冰"));
        f.delete();
        new File(f.getPath() + ".tmp").delete();

        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
