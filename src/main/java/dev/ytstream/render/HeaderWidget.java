package dev.ytstream.render;

import dev.ytstream.model.ServerModel;

import java.awt.*;

/** LIVEバッジ、サーバー名、オンライン人数(滑らかに変化)、稼働時間。 */
final class HeaderWidget implements Widget {
    private double shown = -1;
    private long lastNow = 0;

    @Override
    public boolean isAnimating(long now, ServerModel m) {
        return shown >= 0 && Math.abs(shown - m.players().size()) > 0.02;
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        ServerModel m = c.model;
        Theme t = c.theme;
        double target = m.players().size();
        if (shown < 0) {
            shown = target;
        } else {
            double dt = Math.min(200, Math.max(0, c.now - lastNow));
            shown += (target - shown) * (1 - Math.exp(-dt / 140.0));
            if (Math.abs(target - shown) < 0.02) shown = target;
        }
        lastNow = c.now;

        Rectangle r = c.rect("header");
        Draw.panel(g, r, t, c.px(18));
        int pad = (int) (r.height * 0.16);

        // LIVE バッジ
        Font fLive = c.fontPx(Font.BOLD, r.height * 0.30);
        FontMetrics fmLive = g.getFontMetrics(fLive);
        int tw = fmLive.stringWidth("LIVE");
        int bh = (int) (r.height * 0.58);
        int dot = (int) (bh * 0.28);
        int bw = tw + dot + (int) (bh * 1.0);
        int bx = r.x + pad;
        int by = r.y + (r.height - bh) / 2;
        g.setColor(t.live());
        g.fillRoundRect(bx, by, bw, bh, bh, bh);
        g.setColor(new Color(255, 255, 255, c.uptimeSec % 2 == 0 ? 255 : 110));
        g.fillOval(bx + (int) (bh * 0.4), by + (bh - dot) / 2, dot, dot);
        g.setFont(fLive);
        g.setColor(Color.WHITE);
        g.drawString("LIVE", bx + (int) (bh * 0.4) + dot + (int) (bh * 0.2),
                by + (bh + fmLive.getAscent() - fmLive.getDescent()) / 2);

        // 右側の列
        int right = r.x + r.width - pad;
        boolean showUp = c.s.showUptime();
        int uw = showUp ? (int) (r.width * 0.16) : 0;
        int ow = (int) (r.width * 0.19);
        int ux = right - uw;
        int ox = ux - (showUp ? pad : 0) - ow;

        // サーバー名
        Font fName = c.fontPx(Font.BOLD, r.height * 0.40);
        FontMetrics fmName = g.getFontMetrics(fName);
        int nx = bx + bw + pad;
        int nameMax = ox - pad - nx;
        g.setFont(fName);
        String name = dev.ytstream.util.TextUtil.ellipsize(fmName, c.s.serverName(), nameMax);
        Draw.text(g, name, nx, r.y + (r.height + fmName.getAscent() - fmName.getDescent()) / 2, t.text());

        Font fLabel = c.fontPx(Font.PLAIN, r.height * 0.20);
        Font fValue = c.fontPx(Font.BOLD, r.height * 0.32);
        FontMetrics fmL = g.getFontMetrics(fLabel);
        FontMetrics fmV = g.getFontMetrics(fValue);
        int top = r.y + (int) (r.height * 0.12);
        int labelBase = top + fmL.getAscent();
        int valueBase = labelBase + fmL.getDescent() + fmV.getAscent();

        // オンライン人数
        int max = Math.max(1, m.maxPlayers());
        g.setFont(fLabel);
        Draw.text(g, c.s.label("online"), ox, labelBase, t.dim());
        g.setFont(fValue);
        Draw.text(g, Math.round(shown) + " / " + max, ox, valueBase, t.text());
        int gh = Math.max(4, (int) (r.height * 0.07));
        int gy = r.y + r.height - (int) (r.height * 0.12) - gh;
        g.setColor(new Color(255, 255, 255, 40));
        g.fillRoundRect(ox, gy, ow, gh, gh, gh);
        g.setColor(t.accent());
        int fill = (int) Math.round(ow * Math.min(1.0, shown / max));
        if (fill > 0) g.fillRoundRect(ox, gy, Math.max(fill, gh), gh, gh, gh);

        // 稼働時間
        if (showUp) {
            g.setFont(fLabel);
            Draw.text(g, c.s.label("uptime"), ux, labelBase, t.dim());
            g.setFont(fValue);
            Draw.text(g, formatUptime(c.uptimeSec), ux, valueBase, t.text());
        }
    }

    static String formatUptime(int sec) {
        int d = sec / 86400;
        int h = (sec % 86400) / 3600;
        int mi = (sec % 3600) / 60;
        int s = sec % 60;
        return d > 0 ? String.format("%dd %02d:%02d:%02d", d, h, mi, s) : String.format("%02d:%02d:%02d", h, mi, s);
    }
}
