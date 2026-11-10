package dev.ytstream.render;

import dev.ytstream.model.ServerModel.PlayerEntry;

import java.awt.*;

/** 描画ヘルパ。 */
final class Draw {
    private Draw() {}

    static void panel(Graphics2D g, Rectangle r, Theme t, int arc) {
        g.setColor(t.panel());
        g.fillRoundRect(r.x, r.y, r.width, r.height, arc, arc);
        g.setColor(t.border());
        g.setStroke(new BasicStroke(Math.max(1f, arc / 12f)));
        g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, arc, arc);
        g.setStroke(new BasicStroke(1f));
    }

    static void text(Graphics2D g, String s, int x, int baseline, Color color) {
        g.setColor(new Color(0, 0, 0, 150));
        g.drawString(s, x + 1, baseline + 1);
        g.setColor(color);
        g.drawString(s, x, baseline);
    }

    static Color nameColor(String name) {
        float hue = (name.hashCode() & 0xFFFF) / 65535f;
        return Color.getHSBColor(hue, 0.42f, 1f);
    }

    static void head(Graphics2D g, PlayerEntry e, int x, int y, int size, Font fallbackFont) {
        var head = e.head;
        if (head != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(head, x, y, size, size, null);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            g.setColor(nameColor(e.name).darker());
            g.fillRect(x, y, size, size);
            g.setFont(fallbackFont);
            FontMetrics fm = g.getFontMetrics();
            String initial = e.name.isEmpty() ? "?" : e.name.substring(e.name.startsWith(".") && e.name.length() > 1 ? 1 : 0).substring(0, 1).toUpperCase();
            g.setColor(Color.WHITE);
            g.drawString(initial, x + (size - fm.stringWidth(initial)) / 2, y + (size + fm.getAscent() - fm.getDescent()) / 2);
        }
    }
}
