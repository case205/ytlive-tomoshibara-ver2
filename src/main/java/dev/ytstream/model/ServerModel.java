package dev.ytstream.model;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bukkitに依存しないスレッドセーフな表示用モデル。
 * メインスレッド/非同期チャットスレッドが書き込み、映像スレッドが読み取る。
 * 保持量は固定上限で、チャットが大量に流れてもメモリは増えない。
 */
public final class ServerModel {
    public static final class PlayerEntry {
        public final UUID uuid;
        public final String name;
        public final boolean bedrock;
        public volatile BufferedImage head;

        public PlayerEntry(UUID uuid, String name, boolean bedrock) {
            this.uuid = uuid;
            this.name = name;
            this.bedrock = bedrock;
        }
    }

    public record ChatEntry(String name, String text, long timeMs) {}

    public enum ToastKind { JOIN, LEAVE, ADVANCEMENT, DEATH }

    /** text は ADVANCEMENT/DEATH のときの表示文。JOIN/LEAVE は name を文言テンプレートへ埋め込む。 */
    public record Toast(ToastKind kind, String name, String text, long timeMs) {}

    private static final int CHAT_CAP = 64;
    private static final int TOAST_CAP = 8;

    private final AtomicLong version = new AtomicLong();
    private final Object lock = new Object();
    private final ArrayDeque<ChatEntry> chat = new ArrayDeque<>();
    private final ArrayDeque<Toast> toasts = new ArrayDeque<>();
    private volatile List<PlayerEntry> players = List.of();
    private volatile int maxPlayers = 20;
    private volatile long lastChatMs = 0;
    private volatile String nowPlaying = "";

    public String nowPlaying() {
        return nowPlaying;
    }

    public void setNowPlaying(String title) {
        String t = title == null ? "" : title;
        if (!t.equals(nowPlaying)) {
            nowPlaying = t;
            bump();
        }
    }

    public long version() {
        return version.get();
    }

    public void bump() {
        version.incrementAndGet();
    }

    public List<PlayerEntry> players() {
        return players;
    }

    public boolean contains(UUID id) {
        for (PlayerEntry e : players) if (e.uuid.equals(id)) return true;
        return false;
    }

    public int maxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int max) {
        int m = Math.max(1, max);
        if (m != maxPlayers) {
            maxPlayers = m;
            bump();
        }
    }

    public boolean playerJoined(PlayerEntry e) {
        synchronized (lock) {
            for (PlayerEntry p : players) if (p.uuid.equals(e.uuid)) return false;
            List<PlayerEntry> n = new ArrayList<>(players);
            n.add(e);
            players = List.copyOf(n);
        }
        bump();
        return true;
    }

    public PlayerEntry playerLeft(UUID id) {
        PlayerEntry removed = null;
        synchronized (lock) {
            List<PlayerEntry> n = new ArrayList<>(players);
            for (int i = 0; i < n.size(); i++) {
                if (n.get(i).uuid.equals(id)) {
                    removed = n.remove(i);
                    break;
                }
            }
            if (removed != null) players = List.copyOf(n);
        }
        if (removed != null) bump();
        return removed;
    }

    public void addChat(String name, String text, long nowMs) {
        synchronized (lock) {
            chat.addLast(new ChatEntry(name, text, nowMs));
            while (chat.size() > CHAT_CAP) chat.removeFirst();
            lastChatMs = nowMs;
        }
        bump();
    }

    public void addToast(ToastKind kind, String name, String text, long nowMs) {
        synchronized (lock) {
            toasts.addLast(new Toast(kind, name, text, nowMs));
            while (toasts.size() > TOAST_CAP) toasts.removeFirst();
        }
        bump();
    }

    public long lastChatMs() {
        return lastChatMs;
    }

    public List<ChatEntry> chat() {
        synchronized (lock) {
            return new ArrayList<>(chat);
        }
    }

    public List<Toast> toasts() {
        synchronized (lock) {
            return new ArrayList<>(toasts);
        }
    }
}
