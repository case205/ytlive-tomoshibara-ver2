package dev.ytstream.config;

import dev.ytstream.config.Settings.Rect;
import dev.ytstream.util.Secret;
import dev.ytstream.util.TextUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** config.yml を検証し、不正値は安全な値に補正して警告する。致命的な問題は fatalProblems に積み、配信開始を拒否する。 */
public final class SettingsLoader {
    private SettingsLoader() {}

    public record Result(Settings settings, Secret key, List<String> warnings) {}

    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.\\-]{3,127}$");
    private static final Set<String> CODECS = Set.of("libx264", "h264_nvenc", "h264_qsv", "h264_videotoolbox", "h264_amf");
    private static final Set<String> THEMES = Set.of("default", "nether", "end");
    private static final Pattern PRESET = Pattern.compile("^(ultrafast|superfast|veryfast|faster|fast|medium|slow)$");
    private static final Pattern TUNE = Pattern.compile("^(|zerolatency|stillimage|fastdecode|film|animation)$");

    public static Result load(FileConfiguration c, Path dataDir) {
        List<String> w = new ArrayList<>();
        List<String> fatal = new ArrayList<>();

        String serverName = TextUtil.sanitize(c.getString("server-name", "My Minecraft Server"), 40);
        if (serverName.isBlank()) serverName = "My Minecraft Server";

        // --- ストリームキー: 環境変数 > secrets.yml > config.yml ---
        String key = "";
        String env = System.getenv("YTSTREAM_KEY");
        if (env != null && !env.isBlank()) key = env.trim();
        if (key.isEmpty()) {
            java.io.File f = dataDir.resolve("secrets.yml").toFile();
            if (f.isFile()) key = YamlConfiguration.loadConfiguration(f).getString("stream-key", "").trim();
        }
        if (key.isEmpty()) {
            key = c.getString("youtube.stream-key", "").trim();
            if (!key.isEmpty() && !key.equals("YOUR_STREAM_KEY")) {
                w.add("ストリームキーが config.yml に平文で保存されています。環境変数 YTSTREAM_KEY か secrets.yml(権限600)の利用を推奨します。");
            }
        }
        if (key.equals("YOUR_STREAM_KEY")) key = "";
        if (!key.isEmpty() && !KEY_PATTERN.matcher(key).matches()) {
            fatal.add("ストリームキーの形式が不正です(英数字と - _ . のみ、4〜128文字)");
            key = "";
        }

        // --- RTMP送信先(通信先の制限) ---
        String rtmp = c.getString("youtube.rtmp-url", "rtmp://a.rtmp.youtube.com/live2").trim();
        boolean allowCustom = c.getBoolean("youtube.allow-custom-host", false);
        try {
            URI u = URI.create(rtmp);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase();
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
            if (!(scheme.equals("rtmp") || scheme.equals("rtmps")) || host.isEmpty()) {
                fatal.add("youtube.rtmp-url は rtmp:// または rtmps:// で始まる必要があります");
            } else if (!allowCustom && !(host.equals("youtube.com") || host.endsWith(".youtube.com"))) {
                fatal.add("youtube.rtmp-url のホスト(" + host + ")は youtube.com ではありません。"
                        + "他サービスへ送信する場合のみ youtube.allow-custom-host: true にしてください");
            }
            if (u.getUserInfo() != null || u.getQuery() != null) {
                fatal.add("youtube.rtmp-url にユーザー情報やクエリは指定できません(ストリームキーは別項目です)");
            }
        } catch (IllegalArgumentException e) {
            fatal.add("youtube.rtmp-url が不正なURLです");
        }

        // --- 映像・音声 ---
        int width = num(c, "video.width", 1280, 320, 3840, w) & ~1;
        int height = num(c, "video.height", 720, 180, 2160, w) & ~1;
        int fps = num(c, "video.fps", 30, 5, 60, w);
        int vk = num(c, "video.bitrate-kbps", 2500, 300, 20000, w);
        int ak = num(c, "audio.bitrate-kbps", 128, 32, 320, w);
        boolean audio = c.getBoolean("audio.enabled", true);

        String codec = c.getString("video.codec", "libx264").trim();
        if (!CODECS.contains(codec)) {
            w.add("video.codec「" + codec + "」は未対応のため libx264 を使用します");
            codec = "libx264";
        }
        String preset = c.getString("video.preset", "veryfast").trim();
        if (!PRESET.matcher(preset).matches()) {
            w.add("video.preset が不正なため veryfast を使用します");
            preset = "veryfast";
        }
        String tune = c.getString("video.tune", "zerolatency").trim();
        if (!TUNE.matcher(tune).matches()) {
            w.add("video.tune が不正なため zerolatency を使用します");
            tune = "zerolatency";
        }
        int threads = num(c, "video.threads", 2, 0, 16, w);
        boolean lowPrio = c.getBoolean("video.low-priority", true);

        String ffmpeg = c.getString("ffmpeg.path", "ffmpeg").trim();
        if (ffmpeg.isEmpty()) ffmpeg = "ffmpeg";

        boolean rcEnabled = c.getBoolean("reconnect.enabled", true);
        int rcMax = num(c, "reconnect.max-attempts", 0, 0, 1000, w);
        int rcInit = num(c, "reconnect.initial-delay-seconds", 5, 1, 600, w);
        int rcMaxDelay = Math.max(rcInit, num(c, "reconnect.max-delay-seconds", 60, 1, 3600, w));

        // --- 見た目 ---
        String bg = insideDataDir(c.getString("background.path", "background.png"), dataDir, "background.path", w);
        String fontFile = insideDataDir(c.getString("theme.font-file", ""), dataDir, "theme.font-file", w);
        String fontName = c.getString("theme.font", "SansSerif").trim();
        if (fontName.isEmpty()) fontName = "SansSerif";
        String theme = c.getString("theme.name", "default").trim().toLowerCase();
        if (!THEMES.contains(theme)) {
            w.add("theme.name「" + theme + "」は未対応です(default / nether / end)。default を使用します");
            theme = "default";
        }
        String accent = c.getString("theme.accent", "").trim();
        if (!accent.isEmpty() && !accent.matches("#[0-9a-fA-F]{6}")) {
            w.add("theme.accent は #RRGGBB 形式で指定してください。無視します");
            accent = "";
        }
        int opacity = num(c, "theme.panel-opacity", 62, 0, 100, w);

        boolean showChat = c.getBoolean("display.chat", true);
        boolean showJoinLeave = c.getBoolean("display.join-leave", true);
        boolean showPlayers = c.getBoolean("display.player-list", true);
        boolean showUptime = c.getBoolean("display.uptime", true);

        int chatLines = num(c, "chat.max-lines", 10, 1, 30, w);
        int chatLen = num(c, "chat.max-length", 100, 10, 200, w);
        int chatLife = num(c, "chat.lifetime-seconds", 0, 0, 3600, w);
        List<Pattern> blocked = new ArrayList<>();
        for (String p : c.getStringList("chat.blocked-patterns")) {
            if (blocked.size() >= 50 || p == null || p.isBlank() || p.length() > 200) continue;
            try {
                blocked.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                w.add("chat.blocked-patterns の正規表現が不正なため無視します: " + p);
            }
        }
        int toastLife = num(c, "notifications.lifetime-seconds", 6, 2, 60, w);

        String bgmDir = insideDataDir(c.getString("bgm.folder", "bgm"), dataDir, "bgm.folder", w);
        if (bgmDir.isEmpty()) bgmDir = "bgm";
        Settings.Bgm bgm = new Settings.Bgm(
                c.getBoolean("bgm.enabled", true),
                bgmDir,
                c.getBoolean("bgm.shuffle", true),
                num(c, "bgm.volume", 50, 0, 100, w),
                c.getBoolean("bgm.show-now-playing", true));

        List<String> infoMsgs = new ArrayList<>();
        for (String m : c.getStringList("info.messages")) {
            String t = TextUtil.sanitize(m, 90);
            if (!t.isBlank() && infoMsgs.size() < 10) infoMsgs.add(t);
        }
        Settings.Info info = new Settings.Info(c.getBoolean("info.enabled", true), List.copyOf(infoMsgs),
                num(c, "info.interval-seconds", 8, 3, 120, w));

        String liveUrl = c.getString("promo.live-url", "").trim();
        if (!liveUrl.isEmpty() && !liveUrl.matches("^https://[A-Za-z0-9.\\-]+(/[^\\s]*)?$")) {
            w.add("promo.live-url は https:// で始まるURLを指定してください。無視します");
            liveUrl = "";
        }
        Settings.Promo promo = new Settings.Promo(liveUrl,
                c.getBoolean("promo.broadcast-on-live", true),
                num(c, "promo.broadcast-interval-minutes", 30, 0, 1440, w),
                TextUtil.sanitize(c.getString("promo.broadcast-message", "サーバーをYouTubeでライブ配信中です！ {url}"), 200),
                c.getBoolean("promo.join-notice", true),
                TextUtil.sanitize(c.getString("promo.join-notice-message",
                        "このサーバーはYouTubeでライブ配信中です。チャットとプレイヤー名が配信に映ります。"), 200));
        boolean showEvents = c.getBoolean("display.events", true);
        boolean autoStart = c.getBoolean("auto-start.enabled", false);
        int autoDelay = num(c, "auto-start.delay-seconds", 15, 0, 600, w);
        int stall = num(c, "reconnect.stall-timeout-seconds", 20, 10, 300, w);

        boolean hideVanished = c.getBoolean("players.hide-vanished", true);
        String bedrockPrefix = c.getString("players.bedrock-prefix", ".");
        if (bedrockPrefix == null) bedrockPrefix = ".";
        bedrockPrefix = bedrockPrefix.trim();

        // --- レイアウト(相対座標) ---
        Map<String, Rect> layout = new LinkedHashMap<>();
        for (Map.Entry<String, Rect> e : Settings.DEFAULT_LAYOUT.entrySet()) {
            String p = "layout." + e.getKey();
            Rect d = e.getValue();
            if (!c.isConfigurationSection(p)) {
                layout.put(e.getKey(), d);
                continue;
            }
            double x = c.getDouble(p + ".x", d.x());
            double y = c.getDouble(p + ".y", d.y());
            double rw = c.getDouble(p + ".w", d.w());
            double rh = c.getDouble(p + ".h", d.h());
            boolean ok = x >= 0 && y >= 0 && rw > 0.02 && rh > 0.02 && x + rw <= 1.0001 && y + rh <= 1.0001;
            if (ok) {
                layout.put(e.getKey(), new Rect(x, y, rw, rh));
            } else {
                w.add(p + " の座標が不正です(0〜1、はみ出し不可)。既定値を使用します");
                layout.put(e.getKey(), d);
            }
        }

        Map<String, String> labels = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : Settings.DEFAULT_LABELS.entrySet()) {
            String v = TextUtil.sanitize(c.getString("labels." + e.getKey(), e.getValue()), 60);
            labels.put(e.getKey(), v.isEmpty() ? e.getValue() : v);
        }

        Settings s = new Settings(serverName, rtmp, width, height, fps, vk, ak, audio,
                codec, preset, tune, threads, lowPrio, ffmpeg,
                rcEnabled, rcMax, rcInit, rcMaxDelay,
                bg, fontName, fontFile, theme, accent, opacity,
                showChat, showJoinLeave, showPlayers, showUptime,
                chatLines, chatLen, chatLife, List.copyOf(blocked), toastLife,
                bgm, info, promo, showEvents, autoStart, autoDelay, stall,
                hideVanished, bedrockPrefix, layout, labels, List.copyOf(fatal));
        return new Result(s, new Secret(key), w);
    }

    private static int num(FileConfiguration c, String path, int def, int min, int max, List<String> w) {
        int v = c.getInt(path, def);
        int clamped = Math.max(min, Math.min(max, v));
        if (clamped != v) w.add(path + " は " + min + "〜" + max + " の範囲で指定してください(" + v + " → " + clamped + " に補正)");
        return clamped;
    }

    /** データフォルダ外(../ や絶対パス)を指すパスは拒否する。 */
    private static String insideDataDir(String value, Path dataDir, String name, List<String> w) {
        if (value == null || value.isBlank()) return "";
        try {
            Path base = dataDir.toAbsolutePath().normalize();
            Path p = base.resolve(value.trim()).normalize();
            if (!p.startsWith(base)) {
                w.add(name + " はプラグインフォルダ内のパスのみ指定できます。無視します");
                return "";
            }
            return value.trim();
        } catch (RuntimeException e) {
            w.add(name + " が不正なパスです。無視します");
            return "";
        }
    }
}
