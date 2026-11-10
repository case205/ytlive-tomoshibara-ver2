package dev.ytstream;

import dev.ytstream.config.Settings;
import dev.ytstream.model.ServerModel;
import dev.ytstream.render.FrameRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RendererSmokeTest {
    @Test
    void rendersFrameAndSkipsWhenStatic(@TempDir Path dir) {
        System.setProperty("java.awt.headless", "true");
        Settings s = Settings.defaults();
        ServerModel m = new ServerModel();
        m.setMaxPlayers(50);
        m.playerJoined(new ServerModel.PlayerEntry(UUID.randomUUID(), "Steve", false));
        long now = 1_000_000_000L;
        m.addChat("Steve", "hello", now - 60_000);
        try (FrameRenderer r = new FrameRenderer(s, m, dir, now - 5000)) {
            assertEquals(s.width() * s.height() * 3, r.data().length);
            assertTrue(r.render(now, false), "最初のフレームは描画される");
            byte[] first = r.data().clone();
            assertFalse(r.render(now + 20, false), "状態不変・アニメ無しなら再描画しない");
            assertArrayEquals(first, r.data());
            m.addChat("Alex", "new message", now + 30);
            assertTrue(r.render(now + 40, false), "状態変化で再描画される");
            assertFalse(java.util.Arrays.equals(first, r.data()));
        }
    }
}
