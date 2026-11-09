package com.setupbox.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Minimal ADB-protocol client used to run shell commands on this same box (127.0.0.1:5555).
 * Android keeps shutdown/reboot behind system-only permissions, but this firmware's adbd accepts
 * unauthenticated connections (ro.adb.secure=0), so a "shell:" service gives us that ability.
 * If network ADB is switched off, or adbd starts demanding authorisation, exec() throws.
 */
final class AdbShell {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;

    private AdbShell() { }

    private static final class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] data) throws IOException {
        byte[] d = data == null ? new byte[0] : data;
        int sum = 0;
        for (byte b : d) sum += (b & 0xff);
        ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(sum).putInt(cmd ^ 0xffffffff);
        o.write(bb.array());
        if (d.length > 0) o.write(d);
        o.flush();
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) throw new IOException("adb connection closed");
            off += r;
        }
        return b;
    }

    private static Msg recv(InputStream in) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(readFully(in, 24)).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = bb.getInt();
        m.a0 = bb.getInt();
        m.a1 = bb.getInt();
        int len = bb.getInt();
        bb.getInt(); // checksum, not verified
        int magic = bb.getInt();
        if ((m.cmd ^ 0xffffffff) != magic) throw new IOException("bad adb message");
        if (len < 0 || len > (1 << 20)) throw new IOException("bad adb length");
        m.data = len > 0 ? readFully(in, len) : new byte[0];
        return m;
    }

    /**
     * Runs {@code command} through the adb "shell:" service and returns its output.
     * A connection that drops after the command was accepted (as happens on reboot/shutdown)
     * is treated as success.
     */
    static String exec(String host, String command, int timeoutMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, 5555), timeoutMs);
            s.setSoTimeout(timeoutMs);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            send(out, CNXN, 0x01000000, 256 * 1024, "host::\0".getBytes("UTF-8"));
            Msg m = recv(in);
            if (m.cmd == AUTH) throw new IOException("adb wants authorisation (network ADB is locked)");
            if (m.cmd != CNXN) throw new IOException("unexpected adb reply");
            send(out, OPEN, 1, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
            Msg ok = recv(in);
            if (ok.cmd != OKAY) throw new IOException("adb shell refused");
            int remote = ok.a0;
            ByteArrayOutputStream sb = new ByteArrayOutputStream();
            try {
                while (true) {
                    Msg r = recv(in);
                    if (r.cmd == WRTE) {
                        sb.write(r.data);
                        send(out, OKAY, 1, remote, null);
                    } else if (r.cmd == CLSE) {
                        break;
                    }
                }
            } catch (IOException ignored) {
                // closed or timed out after the command was accepted: expected on reboot/shutdown
            }
            return sb.toString("UTF-8");
        }
    }

    static String exec(String command, int timeoutMs) throws IOException {
        return exec("127.0.0.1", command, timeoutMs);
    }
}
