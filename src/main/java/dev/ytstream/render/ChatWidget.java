package dev.ytstream.render;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.model.ServerModel.ChatEntry;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** チャット欄。新着が下からスライドインし、既存の行が滑らかに押し上げられる。 */
final class ChatWidget implements Widget {
    private static final long IN_MS = 350;
    private static final long FADE_MS = 600;
    private final long lifetimeMs;

    ChatWidget(Settings s) {
        this.lifetimeMs = s.chatLifetimeSec() * 1000L;
    }

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        if (now - m.lastChatMs() < IN_MS + 60) return true;
        if (lifetimeMs > 0) {
            for (ChatEntry e : m.chat()) {
                long age = now - e.timeMs();
                if (age > lifetimeMs - FADE_MS - 60 && age < lifetimeMs + 60) return true;
            }
        }
        return false;
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        Theme t = c.theme;
        Rectangle r = c.rect("chat");
        Draw.panel(g, r, t, c.px(16));

        int pad = c.px(14);
        Font fTitle = c.font(Font.BOLD, 21);
        FontMetrics fmT = g.getFontMetrics(fTitle);
        g.setFont(fTitle);
        Draw.text(g, c.s.label("chat"), r.x + pad, r.y + pad + fmT.getAscent(), t.text());

        int areaTop = r.y + pad + fmT.getHeight() + c.px(6);
        int areaBottom = r.y + r.height - pad;
        int areaLeft = r.x + pad;
        int areaW = r.width - 2 * pad;
        if (areaBottom <= areaTop) return;

        List<ChatEntry> visible = new ArrayList<>();
        for (ChatEntry e : c.model.chat()) {
            if (lifetimeMs == 0 || c.now - e.timeMs() < lifetimeMs) visible.add(e);
        }
        int from = Math.max(0, visible.size() - c.s.chatMaxLines());
        visible = visible.subList(from, visible.size());
        if (visible.isEmpty()) return;

        Font f = c.font(Font.PLAIN, 19);
        FontMetrics fm = g.getFontMetrics(f);
        g.setFont(f);
        int lh = fm.getHeight();
        int gap = c.px(6);

        List<List<String>> wrapped = new ArrayList<>(visible.size());
        for (ChatEntry e : visible) {
            wrapped.add(TextUtil.wrap(fm, e.name() + ": " + e.text(), areaW, 2));
        }

        int last = visible.size() - 1;
        ChatEntry newest = visible.get(last);
        double ease = RenderContext.easeOutCubic((c.now - newest.timeMs()) / (double) IN_MS);
        int newestH = wrapped.get(last).size() * lh + gap;
        double y = areaBottom + (1 - ease) * newestH;

        Shape oldClip = g.getClip();
        g.clipRect(areaLeft - 2, areaTop, areaW + 4, areaBottom - areaTop);
        for (int i = last; i >= 0; i--) {
            List<String> lines = wrapped.get(i);
            int h = lines.size() * lh + gap;
            y -= h;
            if (y + h < areaTop) break;
            ChatEntry e = visible.get(i);
            double alpha = i == last ? ease : 1.0;
            if (lifetimeMs > 0) {
                double remaining = lifetimeMs - (c.now - e.timeMs());
                if (remaining < FADE_MS) alpha *= Math.max(0, remaining / FADE_MS);
            }
            if (alpha <= 0.01) continue;
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1.0, alpha)));
            String prefix = e.name() + ": ";
            for (int j = 0; j < lines.size(); j++) {
                int base = (int) Math.round(y) + j * lh + fm.getAscent();
                String line = lines.get(j);
                if (j == 0 && line.startsWith(prefix)) {
                    Draw.text(g, prefix, areaLeft, base, Draw.nameColor(e.name()));
                    Draw.text(g, line.substring(prefix.length()), areaLeft + fm.stringWidth(prefix), base, t.text());
                } else {
                    Draw.text(g, line, areaLeft, base, t.text());
                }
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
        g.setClip(oldClip);
    }
}
