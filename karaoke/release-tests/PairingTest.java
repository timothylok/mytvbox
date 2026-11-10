import com.setupbox.karaoke.Pairing;

import java.util.HashSet;
import java.util.Set;

/**
 * Release test (no Android needed): the phone-page pairing token logic.
 *   javac -encoding UTF-8 -d build/rtest app/src/main/java/com/setupbox/karaoke/Pairing.java release-tests/PairingTest.java
 *   java -cp build/rtest PairingTest        (run_tests.py does this for you)
 */
public class PairingTest {
    static int failures = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) failures++;
    }

    public static void main(String[] args) {
        String t = Pairing.newToken();
        check("token has 12 characters", t.length() == 12);
        check("token uses only the safe alphabet", t.matches("[abcdefghjkmnpqrstuvwxyz23456789]{12}"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) seen.add(Pairing.newToken());
        check("200 tokens are all different", seen.size() == 200);

        check("same(): right token accepted", Pairing.same(t, t));
        check("same(): wrong token rejected", !Pairing.same(t, "aaaaaaaaaaaa"));
        check("same(): one character off rejected", !Pairing.same("abcdefghjkmn", "abcdefghjkmm"));
        check("same(): shorter / longer rejected", !Pairing.same(t, t.substring(1)) && !Pairing.same(t, t + "x"));
        check("same(): null / empty rejected", !Pairing.same(t, null) && !Pairing.same(t, "") && !Pairing.same(null, t) && !Pairing.same("", ""));

        String c = Pairing.COOKIE;
        check("cookieOk: only our cookie", Pairing.cookieOk(t, c + "=" + t));
        check("cookieOk: among other cookies", Pairing.cookieOk(t, "a=1; " + c + "=" + t + "; b=2"));
        check("cookieOk: wrong value rejected", !Pairing.cookieOk(t, c + "=wrongwrongwr"));
        check("cookieOk: other cookie name rejected", !Pairing.cookieOk(t, "other=" + t));
        check("cookieOk: no header rejected", !Pairing.cookieOk(t, null) && !Pairing.cookieOk(t, ""));
        check("cookieOk: a prefix of the token is rejected", !Pairing.cookieOk(t, c + "=" + t.substring(0, 6)));

        String sc = Pairing.setCookie(t);
        check("Set-Cookie is HttpOnly, SameSite=Lax, long-lived, site-wide",
                sc.startsWith(c + "=" + t) && sc.contains("HttpOnly") && sc.contains("SameSite=Lax") && sc.contains("Max-Age=31536000") && sc.contains("Path=/"));
        check("QR address carries the token", Pairing.pairUrl("192.168.3.79", 8080, t).equals("http://192.168.3.79:8080/?k=" + t));

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures != 0) System.exit(1);
    }
}
