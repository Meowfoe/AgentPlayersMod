package dev.maddie.agents.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ring buffer of chat and system messages one agent has received. */
public final class ChatLog {
    public record Entry(long id, String text, boolean own) {
    }

    private static final int MAX = 200;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private long nextId = 1;

    public synchronized void add(String text, boolean own) {
        entries.addLast(new Entry(nextId++, text, own));
        while (entries.size() > MAX) entries.removeFirst();
    }

    /** The newest {@code limit} entries with id greater than {@code afterId}, oldest first. */
    public synchronized List<Entry> since(long afterId, int limit) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.id() > afterId) out.add(e);
        return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
    }

    public synchronized long lastId() {
        return nextId - 1;
    }
}
