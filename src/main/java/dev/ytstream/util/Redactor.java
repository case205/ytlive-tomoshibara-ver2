package dev.ytstream.util;

import java.util.regex.Pattern;

/** ログ・エラーメッセージからストリームキーを確実に除去する。 */
public final class Redactor {
    private static final Pattern RTMP_URL =
            Pattern.compile("(rtmps?://[^/\\s'\"]+/[^/\\s'\"]+/)[^\\s'\"]+", Pattern.CASE_INSENSITIVE);

    private volatile String secret = "";

    public void setSecret(String secret) {
        this.secret = secret == null ? "" : secret;
    }

    public String redact(String input) {
        if (input == null) return null;
        String out = input;
        String s = secret;
        if (!s.isEmpty()) out = out.replace(s, "****");
        return RTMP_URL.matcher(out).replaceAll("$1****");
    }
}
