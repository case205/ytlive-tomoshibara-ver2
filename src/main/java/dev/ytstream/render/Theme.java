package dev.ytstream.render;

import java.awt.Color;

/** 配信画面の配色。新しいテーマは byName に追加するだけ。 */
public record Theme(Color panel, Color border, Color text, Color dim, Color accent, Color live, Color join, Color leave) {

    public static Theme byName(String name, String accentHex, int panelOpacityPercent) {
        int alpha = Math.max(0, Math.min(255, panelOpacityPercent * 255 / 100));
        String n = name == null ? "default" : name.toLowerCase();
        Color panelRgb;
        Color accent;
        switch (n) {
            case "nether" -> {
                panelRgb = new Color(38, 10, 12);
                accent = new Color(255, 120, 70);
            }
            case "end" -> {
                panelRgb = new Color(24, 14, 40);
                accent = new Color(190, 130, 255);
            }
            default -> {
                panelRgb = new Color(14, 18, 26);
                accent = new Color(96, 214, 104);
            }
        }
        if (accentHex != null && accentHex.matches("#[0-9a-fA-F]{6}")) {
            accent = Color.decode(accentHex);
        }
        return new Theme(
                new Color(panelRgb.getRed(), panelRgb.getGreen(), panelRgb.getBlue(), alpha),
                new Color(255, 255, 255, 46),
                new Color(245, 247, 250),
                new Color(190, 198, 210),
                accent,
                new Color(226, 48, 48),
                new Color(96, 214, 104),
                new Color(240, 98, 98));
    }
}
