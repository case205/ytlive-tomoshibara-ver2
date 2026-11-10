package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.util.List;

/**
 * 画面下部のバー。左に再生中のBGM(♪ 曲名)、右にお知らせを順番にフェード切替。
 * 参加アドレス・Discord・ルールなど、視聴者をプレイヤーに変えるための枠。
 */
final class FooterWidget implements Widget {
    private static final long FADE_MS = 450;
    private final List<String> messages;
    private final long periodMs;

    FooterWidget(Settings s) {
        this.messages = s.info().enabled() ? s.info().messages() : List.of();
        this.periodMs = s.info().intervalSec() * 1000L;
    }

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        if (messages.size() < 2) return false;
        long phase = now % periodMs;
        return phase < FADE_MS + 50 || phase > periodMs - FADE_MS - 50;
    }

    @Override
    public void render(RenderContext c) {
        String np = c.model.nowPlaying();
        boolean showNp = c.s.bgm().enabled() && c.s.bgm().showNowPlaying() && !np.isEmpty();
        if (!showNp && messages.isEmpty()) return;

        Graphics2D g = c.g;
        Theme t = c.theme;
        Rectangle r = c.rect("footer");
        Draw.panel(g, r, t, c.px(14));
        int pad = (int) (r.height * 0.28);
        Font f = c.fontPx(Font.BOLD, r.height * 0.42);
        FontMetrics fm = g.getFontMetrics(f);
        g.setFont(f);
        int base = r.y + (r.height + fm.getAscent() - fm.getDescent()) / 2;
        int x = r.x + pad;
        int right = r.x + r.width - pad;

        if (showNp) {
            int w = (int) (r.width * 0.36);
            Draw.text(g, TextUtil.ellipsize(fm, c.s.label("nowplaying") + " " + np, w), x, base, t.accent());
            x += w + pad;
            if (!messages.isEmpty()) {
                g.setColor(t.border());
                g.fillRect(x - pad / 2, r.y + r.height / 4, 2, r.height / 2);
            }
        }
        if (messages.isEmpty()) return;

        long now = c.now;
        int idx = (int) ((now / periodMs) % messages.size());
        long phase = now % periodMs;
        double alpha = 1.0;
        if (messages.size() > 1) {
            if (phase < FADE_MS) alpha = phase / (double) FADE_MS;
            else if (phase > periodMs - FADE_MS) alpha = (periodMs - phase) / (double) FADE_MS;
        }
        String msg = messages.get(idx)
                .replace("{online}", String.valueOf(c.model.players().size()))
                .replace("{max}", String.valueOf(c.model.maxPlayers()));
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.max(0, Math.min(1, alpha))));
        Draw.text(g, TextUtil.ellipsize(fm, msg, right - x), x, base, t.text());
        g.setComposite(AlphaComposite.SrcOver);
    }
}
