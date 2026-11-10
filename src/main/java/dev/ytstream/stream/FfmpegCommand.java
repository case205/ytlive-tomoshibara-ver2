package dev.ytstream.stream;

import dev.ytstream.config.Settings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** FFmpeg起動引数の組み立て。シェルを介さず引数配列で渡すためコマンドインジェクションは起きない。 */
final class FfmpegCommand {
    private FfmpegCommand() {}

    static List<String> build(Path exe, Settings s, String streamKey, int audioPort) {
        List<String> c = new ArrayList<>();
        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (s.lowPriority() && linux && Files.isExecutable(Path.of("/usr/bin/nice"))) {
            c.add("/usr/bin/nice");
            c.add("-n");
            c.add("10");
        }
        c.add(exe.toString());
        c.addAll(List.of("-hide_banner", "-loglevel", "warning", "-nostats", "-progress", "pipe:1"));

        // 入力0: プラグインが生成する生フレーム(BGR24)をstdinから受け取る
        c.addAll(List.of("-f", "rawvideo", "-pix_fmt", "bgr24",
                "-video_size", s.width() + "x" + s.height(),
                "-framerate", String.valueOf(s.fps()), "-i", "pipe:0"));
        // 入力1: BGM(プラグインがループバックTCPで送るPCM)。BGM無効/準備失敗時は無音
        // (YouTubeは音声トラックが無いと警告を出すため、常に音声トラックを付ける)
        if (s.audioEnabled()) {
            if (audioPort > 0) {
                c.addAll(List.of("-f", "s16le", "-ar", "44100", "-ac", "2", "-thread_queue_size", "1024",
                        "-i", "tcp://127.0.0.1:" + audioPort));
            } else {
                c.addAll(List.of("-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"));
            }
        }
        c.addAll(List.of("-map", "0:v:0"));
        if (s.audioEnabled()) c.addAll(List.of("-map", "1:a:0"));

        int gop = s.fps() * 2; // YouTube推奨: キーフレーム間隔2秒
        c.addAll(List.of("-c:v", s.codec()));
        if (s.codec().equals("libx264")) {
            c.addAll(List.of("-preset", s.preset()));
            if (!s.tune().isEmpty()) c.addAll(List.of("-tune", s.tune()));
            if (s.threads() > 0) c.addAll(List.of("-threads", String.valueOf(s.threads())));
            c.addAll(List.of("-sc_threshold", "0", "-keyint_min", String.valueOf(gop)));
        }
        c.addAll(List.of("-pix_fmt", "yuv420p", "-r", String.valueOf(s.fps()), "-g", String.valueOf(gop),
                "-b:v", s.videoKbps() + "k", "-maxrate", s.videoKbps() + "k", "-bufsize", (s.videoKbps() * 2) + "k"));
        if (s.audioEnabled()) {
            if (audioPort > 0) c.addAll(List.of("-af", "aresample=async=1000")); // 映像とのわずかなズレを吸収
            c.addAll(List.of("-c:a", "aac", "-b:a", s.audioKbps() + "k", "-ar", "44100", "-ac", "2"));
        }
        String base = s.rtmpUrl().endsWith("/") ? s.rtmpUrl().substring(0, s.rtmpUrl().length() - 1) : s.rtmpUrl();
        c.addAll(List.of("-f", "flv", base + "/" + streamKey));
        return c;
    }
}
