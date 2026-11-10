package dev.ytstream.stream;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.render.FrameRenderer;
import dev.ytstream.util.Redactor;
import dev.ytstream.util.Secret;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 配信のライフサイクル管理。状態遷移はすべて単一の制御スレッド上で直列実行するため、
 * ロック不要かつレースが起きない。Bukkitのメインスレッドは待たせない。
 */
public final class StreamManager {
    public enum State { STOPPED, STARTING, LIVE, RECONNECTING, ERROR }

    public interface Reporter {
        void info(String message);

        void error(String message);
    }

    public record Status(State state, long liveSinceMs, int attempt, int reconnects,
                         String speed, String bitrate, String ffmpegVersion, String lastError,
                         long framesSent, long framesReused, long framesDropped,
                         int width, int height, int fps, List<String> problems) {}

    private static final long STARTUP_TIMEOUT_SEC = 25;

    private final Path dataDir;
    private final ServerModel model;
    private final Logger log;
    private final Redactor redactor;
    private final Consumer<String> notifier;
    private final long jvmStartMs = ManagementFactory.getRuntimeMXBean().getStartTime();
    private final ScheduledExecutorService control = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ytstream-control");
        t.setDaemon(true);
        return t;
    });

    private volatile Settings settings = Settings.defaults();
    private volatile Secret key = Secret.EMPTY;
    private volatile State state = State.STOPPED;
    private volatile String lastError;
    private volatile long liveSince;
    private volatile FramePump pump;
    private volatile FfmpegSession session;
    private volatile String ffmpegVersion = "-";
    private volatile int attempt;
    private volatile int reconnects;
    private volatile BgmFeeder feeder;
    private volatile Consumer<State> stateListener;
    private final BgmControl bgm = new BgmControl();

    // 制御スレッドのみが触る
    private ScheduledFuture<?> pending;
    private boolean stopRequested;
    private FfmpegLocator.Info ffInfo;

    public StreamManager(Path dataDir, ServerModel model, Logger log, Redactor redactor, Consumer<String> notifier) {
        this.dataDir = dataDir;
        this.model = model;
        this.log = log;
        this.redactor = redactor;
        this.notifier = notifier;
        // 死活監視: 送信停止・スレッド停止を検知して自動復旧(サーバー稼働中は配信を維持する)
        control.scheduleWithFixedDelay(this::watchdog, 10, 5, TimeUnit.SECONDS);
    }

    public BgmControl bgm() {
        return bgm;
    }

    public void setStateListener(Consumer<State> l) {
        this.stateListener = l;
    }

    public void configure(Settings s, Secret k) {
        this.settings = s;
        this.key = k;
        redactor.setSecret(k.reveal());
        bgm.setVolume(s.bgm().volume());
    }

    public State state() {
        return state;
    }

    // ---------------- 公開操作(すべて非同期) ----------------

    public void start(Reporter r) {
        control.execute(() -> startInternal(r));
    }

    public void stop(Reporter r) {
        control.execute(() -> {
            if (state == State.STOPPED) {
                r.info("配信はすでに停止しています。");
                return;
            }
            haltInternal();
            r.info("配信を停止しました。");
            notifier.accept("配信を停止しました。");
        });
    }

    public void restart(Reporter r) {
        control.execute(() -> {
            if (state != State.STOPPED) haltInternal();
            r.info("配信を再起動します…");
            startInternal(r);
        });
    }

    /** プラグイン無効化/サーバー停止時: 完全に後始末して制御スレッドも終了する。 */
    public void shutdown() {
        try {
            control.submit(this::haltInternal).get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warning("配信停止処理が時間内に完了しませんでした: " + e);
        }
        control.shutdownNow();
    }

    public Status status() {
        FramePump p = pump;
        FfmpegSession s = session;
        FfmpegSession.Progress pr = s == null ? null : s.progress();
        Settings st = settings;
        return new Status(state, liveSince, attempt, reconnects,
                pr == null ? "-" : pr.speed(), pr == null ? "-" : pr.bitrate(), ffmpegVersion, lastError,
                p == null ? 0 : p.sent.get(), p == null ? 0 : p.reused.get(), p == null ? 0 : p.dropped.get(),
                st.width(), st.height(), st.fps(), st.fatalProblems());
    }

    // ---------------- 内部(制御スレッド) ----------------

    private void startInternal(Reporter r) {
        if (state != State.STOPPED && state != State.ERROR) {
            r.error("すでに配信中です(状態: " + state + ")。");
            return;
        }
        stopRequested = false;
        attempt = 0;
        reconnects = 0;
        lastError = null;
        setState(State.STARTING);
        r.info("配信を開始しています…");
        try {
            launch();
            r.info("FFmpegを起動しました。YouTubeへの接続を待っています(通常10〜30秒)。");
        } catch (StreamException e) {
            lastError = redactor.redact(e.getMessage());
            setState(State.ERROR);
            log.warning("配信を開始できません: " + lastError);
            r.error("配信を開始できません: " + lastError);
        }
    }

    private void launch() throws StreamException {
        Settings s = settings;
        if (!s.fatalProblems().isEmpty()) {
            throw new StreamException("設定エラー: " + String.join(" / ", s.fatalProblems()), true);
        }
        if (!key.isSet()) {
            throw new StreamException("ストリームキーが未設定です。環境変数 YTSTREAM_KEY、plugins/YtStream/secrets.yml、"
                    + "または config.yml の youtube.stream-key に設定してください。", true);
        }
        if (ffInfo == null || attempt == 0) ffInfo = verifyFfmpeg(s);
        ffmpegVersion = ffInfo.version();

        FrameRenderer renderer;
        try {
            renderer = new FrameRenderer(s, model, dataDir, jvmStartMs);
        } catch (Exception | LinkageError e) {
            throw new StreamException("映像生成の初期化に失敗しました: " + e
                    + "(Linuxでは fontconfig と日本語フォント(例: fonts-noto-cjk)が必要な場合があります)");
        }
        renderer.notes().forEach(n -> log.info(n));

        BgmFeeder f = null;
        int audioPort = 0;
        if (s.audioEnabled() && s.bgm().enabled()) {
            try {
                f = BgmFeeder.start(ffInfo.path(), dataDir.resolve(s.bgm().dir()).normalize(),
                        s.bgm().shuffle(), s.bgm().showNowPlaying(), bgm, model, log);
                audioPort = f.port();
            } catch (java.io.IOException e) {
                log.warning("BGMの準備に失敗したため無音で配信します: " + e.getMessage());
            }
        }

        FfmpegSession sess;
        try {
            sess = FfmpegSession.start(FfmpegCommand.build(ffInfo.path(), s, key.reveal(), audioPort), redactor, log);
        } catch (java.io.IOException e) {
            renderer.close();
            if (f != null) f.close();
            throw new StreamException("FFmpegを起動できません: " + redactor.redact(e.getMessage()));
        }
        this.feeder = f;
        FramePump p = new FramePump(renderer, sess.stdin(), s.fps(), log);
        this.session = sess;
        this.pump = p;
        p.start();

        sess.exit().thenAccept(code -> execute(() -> onExit(sess, code)));
        sess.firstProgress().orTimeout(STARTUP_TIMEOUT_SEC, TimeUnit.SECONDS)
                .whenComplete((v, ex) -> execute(() -> onFirstProgress(sess, ex)));
    }

    private FfmpegLocator.Info verifyFfmpeg(Settings s) throws StreamException {
        Path exe = FfmpegLocator.find(s.ffmpegPath(), dataDir);
        if (exe == null) {
            throw new StreamException("FFmpegが見つかりません。" + FfmpegLocator.installHint(), true);
        }
        try {
            FfmpegLocator.Info info = FfmpegLocator.verify(exe, s.codec());
            if (!info.hasCodec()) {
                throw new StreamException("このFFmpegはエンコーダ「" + s.codec() + "」に対応していません。"
                        + "config.yml の video.codec を libx264 にするか、対応ビルドを導入してください。");
            }
            return info;
        } catch (java.io.IOException e) {
            throw new StreamException("FFmpegを実行できません: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StreamException("FFmpegの確認が中断されました。");
        }
    }

    private void onFirstProgress(FfmpegSession sess, Throwable timeout) {
        if (sess != session || stopRequested) return;
        if (timeout == null) {
            liveSince = System.currentTimeMillis();
            setState(State.LIVE);
            notifier.accept("YouTubeへの送信を開始しました(配信中)。");
        } else {
            String reason = "YouTubeへの接続が" + STARTUP_TIMEOUT_SEC + "秒以内に完了しませんでした。"
                    + Diagnose.of(sess.tail());
            teardown();
            handleFailure(reason);
        }
    }

    private void onExit(FfmpegSession sess, int code) {
        if (sess != session) return; // 意図的に停止済みの古いセッション
        String reason = "FFmpegが終了しました(終了コード " + code + ")。" + Diagnose.of(sess.tail());
        teardown();
        if (stopRequested) {
            setState(State.STOPPED);
            return;
        }
        handleFailure(reason);
    }

    private void handleFailure(String reason) {
        lastError = redactor.redact(reason);
        log.warning(lastError);
        long liveFor = liveSince > 0 ? System.currentTimeMillis() - liveSince : 0;
        if (liveFor > 60_000) attempt = 0; // しばらく安定して配信できていたら再接続回数をリセット
        liveSince = 0;
        Settings s = settings;
        if (s.reconnectEnabled() && !stopRequested && (s.maxAttempts() == 0 || attempt < s.maxAttempts())) {
            attempt++;
            reconnects++;
            long delay = Math.min(s.maxDelaySec(), (long) s.initialDelaySec() << Math.min(attempt - 1, 10));
            setState(State.RECONNECTING);
            if (attempt <= 3 || attempt % 10 == 0) { // 失敗が続く間、通知が洪水にならないよう間引く
                notifier.accept("配信が切断されました。" + delay + "秒後に再接続します(" + attempt
                        + "/" + (s.maxAttempts() == 0 ? "∞" : s.maxAttempts()) + ")。原因: " + lastError);
            }
            pending = control.schedule(() -> execute(this::reconnect), delay, TimeUnit.SECONDS);
        } else {
            setState(State.ERROR);
            notifier.accept("配信を継続できません。原因: " + lastError);
        }
    }

    private void reconnect() {
        pending = null;
        if (stopRequested || state != State.RECONNECTING) return;
        try {
            launch();
        } catch (StreamException e) {
            if (e.fatal()) { // 再試行しても直らない問題は即エラー確定
                lastError = redactor.redact(e.getMessage());
                setState(State.ERROR);
                notifier.accept("配信を継続できません。原因: " + lastError);
            } else {
                handleFailure(e.getMessage());
            }
        }
    }

    /** 送信停止(ネットワーク断でFFmpegが詰まる等)・内部スレッド停止を検知して復旧する。 */
    private void watchdog() {
        try {
            if (state != State.LIVE || stopRequested) return;
            FfmpegSession sess = session;
            FramePump p = pump;
            BgmFeeder f = feeder;
            if (sess == null) return;
            Settings s = settings;
            String reason = null;
            if (System.currentTimeMillis() - sess.lastProgressMs() > s.stallTimeoutSec() * 1000L) {
                reason = "送信が" + s.stallTimeoutSec() + "秒以上進んでいません(ネットワーク断の可能性)。";
            } else if (p != null && !p.isAlive()) {
                reason = "映像生成スレッドが停止しました。";
            } else if (f != null && !f.isAlive()) {
                reason = "BGM送出スレッドが停止しました。";
            }
            if (reason != null) {
                String detail = reason;
                teardown();
                handleFailure(detail);
            }
        } catch (RuntimeException e) {
            log.warning("死活監視で例外が発生しました: " + e);
        }
    }

    private void haltInternal() {
        stopRequested = true;
        if (pending != null) {
            pending.cancel(false);
            pending = null;
        }
        teardown();
        liveSince = 0;
        setState(State.STOPPED);
    }

    /** 映像スレッド停止 → FFmpegへEOF通知 → 必要なら強制終了。 */
    private void teardown() {
        FramePump p = pump;
        FfmpegSession s = session;
        BgmFeeder f = feeder;
        pump = null;
        session = null;
        feeder = null;
        if (p != null) {
            p.stop();
            if (!p.join(1500)) { // stdin書き込みで詰まっている場合
                if (s != null) s.kill();
                p.join(1000);
            }
        }
        if (f != null) f.close();
        if (s != null) s.close();
    }

    private void setState(State n) {
        State old = state;
        state = n;
        if (old != n) {
            log.info("配信状態: " + old + " → " + n);
            Consumer<State> l = stateListener;
            if (l != null) {
                try {
                    l.accept(n);
                } catch (RuntimeException e) {
                    log.warning("状態通知で例外: " + e);
                }
            }
        }
    }

    private void execute(Runnable r) {
        try {
            control.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // シャットダウン済み
        }
    }
}
