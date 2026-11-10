package dev.ytstream.command;

import dev.ytstream.YtStreamPlugin;
import dev.ytstream.config.Settings;
import dev.ytstream.render.FrameRenderer;
import dev.ytstream.stream.BgmLibrary;
import dev.ytstream.stream.FfmpegLocator;
import dev.ytstream.stream.StreamManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class YtStreamCommand implements CommandExecutor, TabCompleter {
    private static final String PERMISSION = "ytstream.admin";
    private static final List<String> SUBS = List.of("start", "stop", "restart", "status", "reload", "preview", "ffmpeg", "bgm");
    private static final List<String> BGM_SUBS = List.of("skip", "volume", "mute", "unmute", "list");

    private final YtStreamPlugin plugin;

    public YtStreamCommand(YtStreamPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("このコマンドを使う権限がありません。", NamedTextColor.RED));
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        StreamManager.Reporter rep = reporter(sender);
        switch (sub) {
            case "start" -> plugin.manager().start(rep);
            case "stop" -> plugin.manager().stop(rep);
            case "restart" -> plugin.manager().restart(rep);
            case "status" -> status(sender);
            case "reload" -> {
                plugin.applyConfig();
                sender.sendMessage(Component.text("設定を再読み込みしました。", NamedTextColor.GREEN));
                if (plugin.manager().state() != StreamManager.State.STOPPED) {
                    sender.sendMessage(Component.text("実行中の配信には /ytstream restart 後に反映されます。", NamedTextColor.YELLOW));
                }
                var problems = plugin.settings().fatalProblems();
                for (String p : problems) sender.sendMessage(Component.text("設定エラー: " + p, NamedTextColor.RED));
            }
            case "preview" -> preview(sender);
            case "ffmpeg" -> ffmpeg(sender);
            case "bgm" -> bgm(sender, args);
            default -> sender.sendMessage(Component.text(
                    "使い方: /" + label + " <start|stop|restart|status|reload|preview|ffmpeg|bgm>", NamedTextColor.YELLOW));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("bgm")) {
            String p = args[1].toLowerCase(Locale.ROOT);
            return BGM_SUBS.stream().filter(s -> s.startsWith(p)).toList();
        }
        if (args.length != 1) return List.of();
        String p = args[0].toLowerCase(Locale.ROOT);
        return SUBS.stream().filter(s -> s.startsWith(p)).toList();
    }

    private StreamManager.Reporter reporter(CommandSender s) {
        return new StreamManager.Reporter() {
            @Override
            public void info(String m) {
                send(s, m, NamedTextColor.GREEN);
            }

            @Override
            public void error(String m) {
                send(s, m, NamedTextColor.RED);
            }
        };
    }

    /** 制御/非同期スレッドから呼ばれるため、メインスレッドへ戻して送信する。 */
    private void send(CommandSender s, String m, NamedTextColor color) {
        if (!plugin.isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> s.sendMessage(Component.text("[YtStream] " + m, color)));
        } catch (RuntimeException ignored) {
            // 無効化処理中
        }
    }

    private void status(CommandSender sender) {
        StreamManager.Status st = plugin.manager().status();
        NamedTextColor c = switch (st.state()) {
            case LIVE -> NamedTextColor.GREEN;
            case STARTING, RECONNECTING -> NamedTextColor.YELLOW;
            case ERROR -> NamedTextColor.RED;
            default -> NamedTextColor.GRAY;
        };
        sender.sendMessage(Component.text("===== YtStream 状態 =====", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("状態: " + st.state(), c));
        if (st.state() == StreamManager.State.LIVE && st.liveSinceMs() > 0) {
            long sec = (System.currentTimeMillis() - st.liveSinceMs()) / 1000;
            sender.sendMessage(Component.text(String.format("配信時間: %d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60), NamedTextColor.WHITE));
        }
        sender.sendMessage(Component.text(st.width() + "x" + st.height() + " @" + st.fps() + "fps"
                + " / 速度 " + st.speed() + " / ビットレート " + st.bitrate(), NamedTextColor.WHITE));
        sender.sendMessage(Component.text("フレーム: 送信 " + st.framesSent() + " / 再利用 " + st.framesReused()
                + " / 破棄 " + st.framesDropped() + "  再接続回数: " + st.reconnects(), NamedTextColor.GRAY));
        sender.sendMessage(Component.text("FFmpeg: " + st.ffmpegVersion(), NamedTextColor.GRAY));
        String np = plugin.model().nowPlaying();
        sender.sendMessage(Component.text("BGM: " + (np.isEmpty() ? "-" : np) + " / 音量 " + plugin.manager().bgm().volume() + "%"
                + (plugin.manager().bgm().muted() ? "(ミュート中)" : ""), NamedTextColor.GRAY));
        if (st.lastError() != null) {
            sender.sendMessage(Component.text("直近のエラー: " + st.lastError(), NamedTextColor.RED));
        }
        for (String p : st.problems()) sender.sendMessage(Component.text("設定エラー: " + p, NamedTextColor.RED));
        if (st.speed() != null && st.speed().matches("0\\.[0-8].*x.*")) {
            sender.sendMessage(Component.text("エンコード速度が1.0xを下回っています。解像度/FPS/ビットレートを下げるか、preset を上げてください。", NamedTextColor.YELLOW));
        }
    }

    /** 配信せずに現在の配信画面をPNG保存(レイアウト調整用)。 */
    private void preview(CommandSender sender) {
        StreamManager.Reporter rep = reporter(sender);
        Settings s = plugin.settings();
        Path dir = plugin.getDataFolder().toPath();
        long start = ManagementFactory.getRuntimeMXBean().getStartTime();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (FrameRenderer r = new FrameRenderer(s, plugin.model(), dir, start)) {
                r.render(System.currentTimeMillis(), true);
                Path out = dir.resolve("preview.png");
                ImageIO.write(r.image(), "png", out.toFile());
                rep.info("プレビューを保存しました: " + out.toAbsolutePath());
            } catch (Exception | LinkageError e) {
                rep.error("プレビュー生成に失敗しました: " + e
                        + "(Linuxでは fontconfig と日本語フォントが必要な場合があります)");
            }
        });
    }

    private void bgm(CommandSender sender, String[] args) {
        var ctl = plugin.manager().bgm();
        String sub = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "skip" -> {
                ctl.requestSkip();
                sender.sendMessage(Component.text("[YtStream] 次の曲へスキップします。", NamedTextColor.GREEN));
            }
            case "mute", "unmute" -> {
                ctl.setMuted(sub.equals("mute"));
                sender.sendMessage(Component.text("[YtStream] BGMを" + (sub.equals("mute") ? "ミュート" : "ミュート解除") + "しました。", NamedTextColor.GREEN));
            }
            case "volume" -> {
                if (args.length < 3) {
                    sender.sendMessage(Component.text("[YtStream] 現在の音量: " + ctl.volume() + "% (使い方: /ytstream bgm volume <0-100>)", NamedTextColor.YELLOW));
                    return;
                }
                try {
                    ctl.setVolume(Integer.parseInt(args[2]));
                    sender.sendMessage(Component.text("[YtStream] BGM音量を " + ctl.volume() + "% にしました(再読込で設定値へ戻ります)。", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("[YtStream] 0〜100の数値で指定してください。", NamedTextColor.RED));
                }
            }
            case "list" -> {
                Settings s = plugin.settings();
                var tracks = BgmLibrary.scan(plugin.getDataFolder().toPath().resolve(s.bgm().dir()).normalize());
                sender.sendMessage(Component.text("[YtStream] BGM " + tracks.size() + "曲 (" + s.bgm().dir() + "/)"
                        + (tracks.isEmpty() ? " — mp3/ogg/wav/flac/m4a/opus を置くと自動で再生されます" : ""), NamedTextColor.AQUA));
                for (int i = 0; i < Math.min(15, tracks.size()); i++) {
                    sender.sendMessage(Component.text(" - " + BgmLibrary.title(tracks.get(i)), NamedTextColor.GRAY));
                }
            }
            default -> sender.sendMessage(Component.text("使い方: /ytstream bgm <skip|volume|mute|unmute|list>", NamedTextColor.YELLOW));
        }
    }

    private void ffmpeg(CommandSender sender) {
        StreamManager.Reporter rep = reporter(sender);
        Settings s = plugin.settings();
        Path dir = plugin.getDataFolder().toPath();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Path exe = FfmpegLocator.find(s.ffmpegPath(), dir);
            if (exe == null) {
                rep.error("FFmpegが見つかりません。" + FfmpegLocator.installHint());
                return;
            }
            try {
                FfmpegLocator.Info info = FfmpegLocator.verify(exe, s.codec());
                rep.info("FFmpeg OK: " + info.version() + " (" + exe + ")");
                if (!info.hasCodec()) rep.error("エンコーダ「" + s.codec() + "」に未対応です。video.codec を libx264 にしてください。");
            } catch (Exception e) {
                rep.error("FFmpegを実行できません: " + e.getMessage());
            }
        });
    }
}
