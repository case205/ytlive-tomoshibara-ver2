package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配信フレームの生成。1インスタンス=1スレッド専用。
 *
 * <p>性能設計:
 * <ul>
 *   <li>出力はTYPE_3BYTE_BGRのバイト配列そのもの(FFmpegのbgr24入力にそのまま書ける。変換ゼロ)</li>
 *   <li>背景は起動時に1回だけ生成し、毎フレームは System.arraycopy で複製</li>
 *   <li>状態が変わらず、アニメーションも無いフレームは再描画せず前フレームを再利用(CPUほぼゼロ)</li>
 * </ul>
 */
public final class FrameRenderer implements AutoCloseable {
    private final Settings s;
    private final ServerModel model;
    private final long startMs;
    private final BufferedImage image;
    private final byte[] data;
    private final byte[] bgData;
    private final Graphics2D g;
    private final Fonts fonts;
    private final Theme theme;
    private final List<Widget> widgets = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private long lastVersion = -1;
    private int lastUptime = -1;
    private boolean lastAnimating = true;

    public FrameRenderer(Settings s, ServerModel model, Path dataDir, long startMs) {
        this.s = s;
        this.model = model;
        this.startMs = startMs;
        this.image = new BufferedImage(s.width(), s.height(), BufferedImage.TYPE_3BYTE_BGR);
        this.data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

        Path bgPath = s.backgroundPath() == null || s.backgroundPath().isBlank()
                ? null : dataDir.resolve(s.backgroundPath()).normalize();
        BackgroundProvider.Result bg = BackgroundProvider.load(bgPath, s.width(), s.height());
        if (bg.note() != null) notes.add(bg.note());
        this.bgData = ((DataBufferByte) bg.image().getRaster().getDataBuffer()).getData();

        this.fonts = Fonts.load(s, dataDir, notes);
        this.theme = Theme.byName(s.themeName(), s.accentColor(), s.panelOpacity());

        this.g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // fontconfig未導入環境をここで早期に検出(失敗時は例外→配信開始時に分かりやすく表示)
        g.getFontMetrics(fonts.get(Font.PLAIN, 12f)).getHeight();

        widgets.add(new HeaderWidget());
        widgets.add(new FooterWidget(s));
        if (s.showPlayerList()) widgets.add(new PlayersWidget());
        if (s.showJoinLeave()) widgets.add(new ToastWidget(s));
        if (s.showChat()) widgets.add(new ChatWidget(s));
    }

    public List<String> notes() {
        return notes;
    }

    /** FFmpegへ書き込むフレームのバイト配列(同一配列を使い回す)。 */
    public byte[] data() {
        return data;
    }

    public BufferedImage image() {
        return image;
    }

    /** @return 再描画したら true。false の場合 data() は前フレームのまま有効。 */
    public boolean render(long now, boolean force) {
        int upSec = (int) Math.max(0, (now - startMs) / 1000);
        long version = model.version();
        boolean animating = false;
        for (Widget w : widgets) {
            if (w.isAnimating(now, model)) {
                animating = true;
                break;
            }
        }
        if (!force && !animating && !lastAnimating && version == lastVersion && upSec == lastUptime) {
            return false;
        }
        System.arraycopy(bgData, 0, data, 0, data.length);
        RenderContext ctx = new RenderContext(g, s, theme, model, fonts, now, upSec);
        for (Widget w : widgets) {
            w.render(ctx);
            g.setComposite(AlphaComposite.SrcOver);
            g.setClip(null);
        }
        lastVersion = version;
        lastUptime = upSec;
        lastAnimating = animating;
        return true;
    }

    @Override
    public void close() {
        g.dispose();
    }
}
