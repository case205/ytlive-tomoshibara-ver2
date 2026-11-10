package dev.ytstream.render;

import dev.ytstream.model.ServerModel;
import dev.ytstream.model.ServerModel.PlayerEntry;
import dev.ytstream.util.TextUtil;

import java.awt.*;
import java.util.List;

final class PlayersWidget implements Widget {
    @Override
    public boolean isAnimating(long now, ServerModel m) {
        return false;
    }

    @Override
    public void render(RenderContext c) {
        Graphics2D g = c.g;
        Theme t = c.theme;
        Rectangle r = c.rect("players");
        Draw.panel(g, r, t, c.px(16));
        List<PlayerEntry> list = c.model.players();

        int pad = c.px(14);
        Font fTitle = c.font(Font.BOLD, 21);
        FontMetrics fmT = g.getFontMetrics(fTitle);
        g.setFont(fTitle);
        Draw.text(g, TextUtil.ellipsize(fmT, c.s.label("players") + " (" + list.size() + ")", r.width - 2 * pad),
                r.x + pad, r.y + pad + fmT.getAscent(), t.text());

        int rowH = c.px(36);
        int head = c.px(28);
        int top = r.y + pad + fmT.getHeight() + c.px(8);
        int rows = Math.max(0, (r.y + r.height - pad - top) / rowH);
        int show = list.size() <= rows ? list.size() : Math.max(0, rows - 1);

        Font fName = c.font(Font.PLAIN, 20);
        FontMetrics fmN = g.getFontMetrics(fName);
        Font fInit = c.font(Font.BOLD, 16);
        int nameX = r.x + pad + head + c.px(10);
        int nameMax = r.x + r.width - pad - nameX;
        for (int i = 0; i < show; i++) {
            PlayerEntry p = list.get(i);
            int y = top + i * rowH;
            Draw.head(g, p, r.x + pad, y + (rowH - head) / 2, head, fInit);
            g.setFont(fName);
            Draw.text(g, TextUtil.ellipsize(fmN, p.name, nameMax), nameX,
                    y + (rowH + fmN.getAscent() - fmN.getDescent()) / 2, t.text());
        }
        if (list.size() > show) {
            int y = top + show * rowH;
            g.setFont(fName);
            String more = c.s.label("more").replace("{n}", String.valueOf(list.size() - show));
            Draw.text(g, TextUtil.ellipsize(fmN, more, r.width - 2 * pad), r.x + pad,
                    y + (rowH + fmN.getAscent() - fmN.getDescent()) / 2, t.dim());
        }
    }
}
