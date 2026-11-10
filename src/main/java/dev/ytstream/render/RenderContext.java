package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;

import java.awt.*;

final class RenderContext {
    final Graphics2D g;
    final Settings s;
    final Theme theme;
    final ServerModel model;
    final Fonts fonts;
    final long now;
    final int uptimeSec;
    final double sc; // 720pを基準にした拡大率

    RenderContext(Graphics2D g, Settings s, Theme theme, ServerModel model, Fonts fonts, long now, int uptimeSec) {
        this.g = g;
        this.s = s;
        this.theme = theme;
        this.model = model;
        this.fonts = fonts;
        this.now = now;
        this.uptimeSec = uptimeSec;
        this.sc = s.height() / 720.0;
    }

    Rectangle rect(String key) {
        return s.rect(key).toPixels(s.width(), s.height());
    }

    /** 720p基準のpx指定をスケールしたフォント。 */
    Font font(int style, double designPx) {
        return fonts.get(style, (float) (designPx * sc));
    }

    /** 実ピクセル指定のフォント。 */
    Font fontPx(int style, double px) {
        return fonts.get(style, (float) Math.max(6, px));
    }

    int px(double design) {
        return (int) Math.round(design * sc);
    }

    static double easeOutCubic(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 1 - Math.pow(1 - c, 3);
    }
}
