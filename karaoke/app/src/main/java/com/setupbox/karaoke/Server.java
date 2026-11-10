package com.setupbox.karaoke;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Phone-facing web server (port 8080). Everything is a plain GET/POST with query-string parameters:
 *   GET  /                      the phone page (assets/index.html)
 *   GET  /api/state             queue, now playing, player state
 *   GET  /api/browse            languages -> artists
 *   GET  /api/library           ?q=&amp;lang=&amp;artist=&amp;limit=   songs
 *   GET  /api/history           ?days=30   songs played, newest first (kept 30 days, older ones are dropped automatically)
 *   POST /api/queue/add         ?song=ID&amp;singer=NAME&amp;position=end|next
 *   POST /api/queue/remove      ?id=QID
 *   POST /api/queue/move        ?id=QID&amp;dir=up|down|top|bottom
 *   POST /api/queue/shuffle  /  /api/queue/clear
 *   POST /api/player/pause|resume|skip|restart|vocal|flip
 *   POST /api/rescan            re-read the Karaoke folder
 * Every POST answers with the fresh state, so the phone can redraw straight away.
 */
public final class Server extends NanoHTTPD {
    /** What the server needs from the app. */
    public interface Host {
        JSONObject state();
        Library library();
        QueueManager queue();
        /** Play history of the last 30 days (null in single-file debug mode). */
        History history();
        /** Runs on the app's main thread: pause, resume, skip, restart, vocal, flip, kick, rescan. */
        void command(String cmd);
    }

    private final Context ctx;
    private final Host host;
    private final String token;      // pairing token (see Pairing): without it nothing is served
    private byte[] page;

    public Server(Context ctx, Host host, int port, String token) {
        super(port);
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.token = token;
    }

    @Override public Response serve(IHTTPSession s) {
        try {
            String uri = s.getUri();
            Method method = s.getMethod();
            if (method == Method.POST || method == Method.PUT) {
                try { s.parseBody(new HashMap<String, String>()); } catch (Exception ignored) { }
            }
            Map<String, String> p = s.getParms();

            // Pairing: the QR code opens /?k=TOKEN -> cookie + redirect to /. Everything else needs that cookie.
            if (Pairing.same(token, p.get(Pairing.PARAM)) && (uri.equals("/") || uri.equals("/index.html"))) {
                Response r = newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain; charset=utf-8", "Paired");
                r.addHeader("Location", "/");
                r.addHeader("Set-Cookie", Pairing.setCookie(token));
                return noCache(r);
            }
            if (!Pairing.cookieOk(token, s.getHeaders().get("cookie"))) return unpaired(uri);

            if (uri.equals("/") || uri.equals("/index.html")) return html();
            if (!uri.startsWith("/api/")) return text(Response.Status.NOT_FOUND, "Not found");

            boolean post = method == Method.POST;
            switch (uri) {
                case "/api/state": return json(host.state());
                case "/api/browse": return json(browse());
                case "/api/library": return json(library(p));
                case "/api/history": {
                    int days = 30;
                    try { days = Integer.parseInt(str(p, "days")); } catch (NumberFormatException ignored) { }
                    History h = host.history();
                    return json(h == null ? new JSONObject().put("days", days).put("total", 0).put("plays", new JSONArray()) : h.toJson(System.currentTimeMillis(), days));
                }
                default: break;
            }
            if (!post) return text(Response.Status.METHOD_NOT_ALLOWED, "Use POST");
            host.command("touch");     // any phone action counts as activity (wakes the sleeping screen)

            if (uri.startsWith("/api/queue/")) {
                if (!queueAction(uri.substring("/api/queue/".length()), p)) return text(Response.Status.BAD_REQUEST, "Bad request");
            } else if (uri.startsWith("/api/player/")) {
                String cmd = uri.substring("/api/player/".length());
                if (!java.util.Arrays.asList("pause", "resume", "skip", "restart", "vocal", "flip").contains(cmd)) return text(Response.Status.BAD_REQUEST, "Unknown command");
                host.command(cmd);
            } else if (uri.equals("/api/rescan")) {
                host.command("rescan");
            } else {
                return text(Response.Status.NOT_FOUND, "Not found");
            }
            return json(host.state());
        } catch (Exception e) {
            return text(Response.Status.INTERNAL_ERROR, "Error: " + e);
        }
    }

    // ---------- queue ----------

    private boolean queueAction(String action, Map<String, String> p) {
        QueueManager q = host.queue();
        switch (action) {
            case "add": {
                Library.Song song = host.library().byId(str(p, "song"));
                if (song == null) return false;
                q.add(song.id, str(p, "singer"), "next".equals(str(p, "position")));
                host.command("kick");     // starts playback if nothing is playing
                return true;
            }
            case "remove": return q.remove(str(p, "id"));
            case "move": {
                QueueManager.Move m;
                switch (str(p, "dir")) {
                    case "up": m = QueueManager.Move.UP; break;
                    case "down": m = QueueManager.Move.DOWN; break;
                    case "top": m = QueueManager.Move.TOP; break;
                    case "bottom": m = QueueManager.Move.BOTTOM; break;
                    default: return false;
                }
                q.move(str(p, "id"), m);
                return true;
            }
            case "shuffle": q.shuffle(); return true;
            case "clear": q.clear(); return true;
            default: return false;
        }
    }

    // ---------- library ----------

    private JSONObject browse() throws Exception {
        Library lib = host.library();
        JSONArray langs = new JSONArray();
        for (String l : lib.languages()) {
            JSONArray artists = new JSONArray();
            for (String a : lib.artists(l)) {
                artists.put(new JSONObject().put("name", a).put("count", lib.search("", l, a).size()));
            }
            langs.put(new JSONObject().put("name", l).put("artists", artists));
        }
        return new JSONObject().put("languages", langs).put("total", lib.songs.size());
    }

    private JSONObject library(Map<String, String> p) throws Exception {
        int limit = 100;
        try { limit = Math.max(1, Math.min(300, Integer.parseInt(str(p, "limit")))); } catch (NumberFormatException ignored) { }
        List<Library.Song> hits = host.library().search(str(p, "q"), str(p, "lang"), str(p, "artist"));
        JSONArray arr = new JSONArray();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) arr.put(hits.get(i).toJson());
        return new JSONObject().put("total", hits.size()).put("songs", arr);
    }

    // ---------- responses ----------

    private static String str(Map<String, String> p, String k) { String v = p.get(k); return v == null ? "" : v; }

    private Response json(JSONObject o) { return noCache(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())); }

    /** Not paired: a friendly page for a browser, a plain 401 for the API. */
    private Response unpaired(String uri) {
        if (uri.startsWith("/api/")) return noCache(text(Response.Status.UNAUTHORIZED, "Scan the QR code on the TV"));
        String body = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<body style='font-family:sans-serif;background:#101418;color:#fff;text-align:center;padding:48px 24px'>"
                + "<h1>&#127908; Karaoke</h1><p style='font-size:20px'>Scan the QR code on the TV with your phone's camera to connect.</p>";
        return noCache(newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/html; charset=utf-8", body));
    }

    private Response text(Response.IStatus st, String t) { return newFixedLengthResponse(st, "text/plain; charset=utf-8", t); }

    private Response html() throws Exception {
        if (page == null) {
            InputStream in = ctx.getAssets().open("index.html");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            in.close();
            page = out.toByteArray();
        }
        return noCache(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", new java.io.ByteArrayInputStream(page), page.length));
    }

    private static Response noCache(Response r) { r.addHeader("Cache-Control", "no-store"); return r; }
}
