package dev.ytstream;

import dev.ytstream.command.YtStreamCommand;
import dev.ytstream.config.Settings;
import dev.ytstream.config.SettingsLoader;
import dev.ytstream.listener.ModelListener;
import dev.ytstream.model.ServerModel;
import dev.ytstream.render.SkinService;
import dev.ytstream.stream.StreamManager;
import dev.ytstream.util.Redactor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

public final class YtStreamPlugin extends JavaPlugin {
    private ServerModel model;
    private SkinService skins;
    private StreamManager manager;
    private ModelListener listener;
    private volatile Settings settings = Settings.defaults();

    @Override
    public void onLoad() {
        // Java2Dをヘッドレスで使う(ディスプレイ無しサーバー向け)
        if (System.getProperty("java.awt.headless") == null) System.setProperty("java.awt.headless", "true");
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        model = new ServerModel();
        skins = new SkinService();
        Redactor redactor = new Redactor();
        manager = new StreamManager(getDataFolder().toPath(), model, getLogger(), redactor, this::notifyAdmins);
        listener = new ModelListener(model, skins, settings, () -> manager.state() == StreamManager.State.LIVE);
        manager.setStateListener(this::onStreamState);

        applyConfig();
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand cmd = getCommand("ytstream");
        if (cmd != null) {
            YtStreamCommand handler = new YtStreamCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }

        listener.sync();

        // サーバー内プレイヤーへの定期告知(配信中のみ)
        int mins = settings.promo().intervalMinutes();
        if (mins > 0) {
            long ticks = mins * 60L * 20L;
            Bukkit.getScheduler().runTaskTimer(this, () -> {
                if (manager.state() == StreamManager.State.LIVE) broadcastPromo();
            }, ticks, ticks);
        }

        // サーバー起動と同時に配信を自動開始(稼働中は常時配信したい場合)
        if (settings.autoStart() && settings.fatalProblems().isEmpty()) {
            Bukkit.getScheduler().runTaskLater(this, () -> manager.start(new StreamManager.Reporter() {
                @Override
                public void info(String m) {
                    getLogger().info(m);
                }

                @Override
                public void error(String m) {
                    getLogger().warning(m);
                }
            }), settings.autoStartDelaySec() * 20L);
        }
        // 取りこぼし・バニッシュ切替の補正(5秒ごと、処理は極めて軽量)
        Bukkit.getScheduler().runTaskTimer(this, listener::sync, 100L, 100L);
        getLogger().info("YtStream を有効化しました。/ytstream start で配信を開始できます。");
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        if (manager != null) manager.shutdown(); // FFmpegを必ず終了させる
        if (skins != null) skins.shutdown();
    }

    /** config.yml を再読み込みして反映。実行中の配信には次回開始/再接続から反映される。 */
    public void applyConfig() {
        reloadConfig();
        SettingsLoader.Result r = SettingsLoader.load(getConfig(), getDataFolder().toPath());
        r.warnings().forEach(getLogger()::warning);
        r.settings().fatalProblems().forEach(p -> getLogger().severe("設定エラー: " + p));
        settings = r.settings();
        manager.configure(r.settings(), r.key());
        listener.setSettings(r.settings());
    }

    /** 配信状態の変化(制御スレッドから呼ばれる)。LIVEになったらサーバー内へ告知する。 */
    private void onStreamState(StreamManager.State s) {
        if (s == StreamManager.State.LIVE && settings.promo().broadcastOnLive() && isEnabled()) {
            try {
                Bukkit.getScheduler().runTask(this, this::broadcastPromo);
            } catch (RuntimeException ignored) {
                // 無効化処理中
            }
        }
    }

    private void broadcastPromo() {
        Settings.Promo p = settings.promo();
        if (p.message().isBlank()) return;
        if (p.liveUrl().isEmpty() && p.message().contains("{url}")) return; // URL未設定なら告知しない
        Component c = Component.text(p.message().replace("{url}", p.liveUrl()), NamedTextColor.AQUA);
        if (!p.liveUrl().isEmpty()) c = c.clickEvent(ClickEvent.openUrl(p.liveUrl()));
        Bukkit.broadcast(c);
    }

    public Settings settings() {
        return settings;
    }

    public ServerModel model() {
        return model;
    }

    public StreamManager manager() {
        return manager;
    }

    /** コンソールと管理者(ytstream.admin)へ通知。どのスレッドから呼んでもよい。 */
    private void notifyAdmins(String message) {
        getLogger().info(message);
        if (!isEnabled()) return;
        try {
            Bukkit.getScheduler().runTask(this, () ->
                    Bukkit.broadcast(Component.text("[YtStream] " + message, NamedTextColor.AQUA), "ytstream.admin"));
        } catch (RuntimeException ignored) {
            // 無効化処理中
        }
    }
}
