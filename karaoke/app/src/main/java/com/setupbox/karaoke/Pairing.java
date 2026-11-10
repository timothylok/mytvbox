package com.setupbox.karaoke;

import java.security.SecureRandom;

/**
 * Pairing for the phone page. The QR code on the TV carries a secret token (http://ip:8080/?k=TOKEN). The first visit with the
 * right token sets a cookie, so the phone never asks again; anyone on the Wi-Fi who has not scanned the code (or has no cookie) gets
 * a "scan the code" page and cannot read or control anything. No Android classes here, so it is unit-tested.
 */
public final class Pairing {
    public static final String COOKIE = "karaoke_k";
    public static final String PARAM = "k";
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";    // no look-alike characters

    private Pairing() { }

    /** A new random token (12 characters, about 60 bits). */
    public static String newToken() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    /** Constant-time comparison; false for null/empty. */
    public static boolean same(String token, String given) {
        if (token == null || token.isEmpty() || given == null || given.length() != token.length()) return false;
        int diff = 0;
        for (int i = 0; i < token.length(); i++) diff |= token.charAt(i) ^ given.charAt(i);
        return diff == 0;
    }

    /** True when the Cookie header carries the right token. */
    public static boolean cookieOk(String token, String cookieHeader) {
        if (cookieHeader == null) return false;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            if (p.startsWith(COOKIE + "=") && same(token, p.substring(COOKIE.length() + 1))) return true;
        }
        return false;
    }

    /** The Set-Cookie value that pairs a phone for a year. HttpOnly + SameSite=Lax: scripts and other sites cannot use it. */
    public static String setCookie(String token) {
        return COOKIE + "=" + token + "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax";
    }

    /** The address the QR code holds. */
    public static String pairUrl(String ip, int port, String token) {
        return "http://" + ip + ":" + port + "/?" + PARAM + "=" + token;
    }
}
