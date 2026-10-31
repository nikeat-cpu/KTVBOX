package com.github.tvbox.osc.ktv;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * KTV 已点歌曲队列管理器（单例）
 *
 * 支持：点歌、下一首插队（置顶）、删除、清空、切歌、指定播放。
 * 队列通过 Hawk 持久化，退出应用后保留。
 * 另维护「已播列表」：唱完的歌从队列移入已播历史（也持久化）。
 */
public class SongQueue {

    private static final String HAWK_KEY_QUEUE = "ktv_song_queue";
    private static final String HAWK_KEY_INDEX = "ktv_song_queue_index";
    private static final String HAWK_KEY_PLAYED = "ktv_song_played";
    private static final int MAX_PLAYED = 200;   // 已播历史上限，超出丢弃最旧的

    private static volatile SongQueue instance;

    private final List<Song> songs = new ArrayList<>();
    private final List<Song> played = new ArrayList<>();
    private final Gson gson = new Gson();
    private int currentIndex = -1;

    private SongQueue() {
        load();
    }

    public static SongQueue get() {
        if (instance == null) {
            synchronized (SongQueue.class) {
                if (instance == null) {
                    instance = new SongQueue();
                }
            }
        }
        return instance;
    }

    public List<Song> getSongs() {
        return songs;
    }

    public int size() {
        return songs.size();
    }

    public boolean isEmpty() {
        return songs.isEmpty();
    }

    public List<Song> getPlayed() {
        return played;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public Song getCurrent() {
        if (currentIndex < 0 || currentIndex >= songs.size()) return null;
        return songs.get(currentIndex);
    }

    /**
     * 点歌（加入队尾）。已存在则不重复添加，返回 false。
     */
    public boolean add(Song song) {
        if (song == null || TextUtils.isEmpty(song.id)) return false;
        if (songs.contains(song)) return false;
        songs.add(song);
        save();
        notifyChanged();
        return true;
    }

    /**
     * 置顶：插到「当前歌之后」，即下一首就唱它。
     */
    public boolean addNext(Song song) {
        if (song == null || TextUtils.isEmpty(song.id)) return false;
        if (songs.contains(song)) return false;
        int insertAt = currentIndex + 1;
        if (insertAt < 0 || insertAt > songs.size()) insertAt = songs.size();
        songs.add(insertAt, song);
        save();
        notifyChanged();
        return true;
    }

    /**
     * 把已点列表中第 index 首移到「当前歌之后」（下一首就唱它）。
     * 正在唱的那首本身不能置顶（返回 false）。
     */
    public boolean moveNext(int index) {
        if (index < 0 || index >= songs.size()) return false;
        if (index == currentIndex) return false;
        Song song = songs.remove(index);
        if (index < currentIndex) currentIndex--;
        int insertAt = currentIndex + 1;
        if (insertAt < 0) insertAt = 0;
        if (insertAt > songs.size()) insertAt = songs.size();
        songs.add(insertAt, song);
        save();
        notifyChanged();
        return true;
    }

    public Song remove(int index) {
        if (index < 0 || index >= songs.size()) return null;
        Song removed = songs.remove(index);
        if (index < currentIndex) {
            currentIndex--;
        } else if (index == currentIndex) {
            // 删掉了正在唱的，指向下一首（索引不变，因为后面元素前移）
            if (currentIndex >= songs.size()) currentIndex = songs.size() - 1;
        }
        save();
        notifyChanged();
        return removed;
    }

    public void clear() {
        songs.clear();
        currentIndex = -1;
        save();
        notifyChanged();
    }

    /**
     * 指定第 index 首为当前歌曲
     */
    public Song playAt(int index) {
        if (index < 0 || index >= songs.size()) return null;
        currentIndex = index;
        save();
        EventBus.getDefault().post(new KtvEvent(KtvEvent.TYPE_CURRENT_CHANGED, getCurrent()));
        notifyChanged();
        return getCurrent();
    }

    /**
     * 切歌：播放下一首，到队尾则停止（返回 null）
     */
    public Song next() {
        if (songs.isEmpty()) return null;
        int next = currentIndex + 1;
        if (next >= songs.size()) return null;
        return playAt(next);
    }

    /**
     * 上一首
     */
    public Song prev() {
        if (songs.isEmpty()) return null;
        int prev = currentIndex - 1;
        if (prev < 0) return null;
        return playAt(prev);
    }

    /**
     * 手动切歌：把正在唱（或指定）的歌直接从队列移除——不计入已播列表（与自然完播不同）。
     * 移除后 currentIndex 指向下一首；队列空则指向 -1。
     *
     * @return 移除后的「下一首」；找不到要移除的歌或队列已空时返回 null
     */
    public Song skipCurrent(String sourceKey, String vodId) {
        int idx;
        if (!TextUtils.isEmpty(vodId)) {
            idx = songs.indexOf(new Song(vodId, null, null, null, sourceKey));
        } else {
            idx = currentIndex;
        }
        if (idx < 0 || idx >= songs.size()) return null;
        songs.remove(idx);
        if (idx < currentIndex) {
            currentIndex--;
        } else if (idx == currentIndex) {
            // 指向下一首（索引不变，后面元素前移）；队列空则回到 -1
            if (currentIndex >= songs.size()) currentIndex = songs.size() - 1;
        }
        save();
        notifyChanged();
        return getCurrent();
    }

    public int indexOf(Song song) {
        return songs.indexOf(song);
    }

    public boolean contains(Song song) {
        return songs.contains(song);
    }

    /**
     * 判断某个正在播放的内容（源站 + id）是否在已点列表中。
     * 用于播放完成时判断是否走 KTV 自动切歌。
     */
    public boolean containsVod(String sourceKey, String id) {
        if (TextUtils.isEmpty(id)) return false;
        return contains(new Song(id, null, null, null, sourceKey));
    }

    /**
     * 完播：把正在唱（或指定）的歌从队列移入已播列表。
     * 只在播放自然结束时调用（手动切歌不算完播）。
     * <p>移除后 currentIndex 指向「下一首之前」的位置，随后调用 {@link #next()}
     * 即可无缝连播队列中的下一首；队列空了则 next() 返回 null。
     *
     * @return 被移入已播列表的歌；该歌不在队列中时返回 null
     */
    public Song finishPlaying(String sourceKey, String vodId) {
        if (TextUtils.isEmpty(vodId)) return null;
        int idx = songs.indexOf(new Song(vodId, null, null, null, sourceKey));
        if (idx < 0) return null;
        Song cur = songs.get(idx);
        // 已播去重：同一首只保留最新一条（追加到末尾，时间正序）
        played.remove(cur);
        played.add(cur);
        while (played.size() > MAX_PLAYED) played.remove(0);
        songs.remove(idx);
        if (idx == currentIndex) {
            // 指向下一首之前：next() 会 +1 指向原下一首；队列空则停在 -1
            currentIndex = idx - 1;
        } else if (idx < currentIndex) {
            currentIndex--;
        }
        save();
        notifyChanged();
        return cur;
    }

    public void clearPlayed() {
        if (played.isEmpty()) return;
        played.clear();
        save();
        notifyChanged();
    }

    // ---------------------------------------------------------------- 持久化

    private void save() {
        try {
            Hawk.put(HAWK_KEY_QUEUE, gson.toJson(songs));
            Hawk.put(HAWK_KEY_INDEX, currentIndex);
            Hawk.put(HAWK_KEY_PLAYED, gson.toJson(played));
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private void load() {
        try {
            String json = Hawk.get(HAWK_KEY_QUEUE, "");
            if (!TextUtils.isEmpty(json)) {
                List<Song> list = gson.fromJson(json, new TypeToken<List<Song>>() {
                }.getType());
                if (list != null) songs.addAll(list);
            }
            currentIndex = Hawk.get(HAWK_KEY_INDEX, -1);
            if (currentIndex >= songs.size()) currentIndex = songs.size() - 1;
            String playedJson = Hawk.get(HAWK_KEY_PLAYED, "");
            if (!TextUtils.isEmpty(playedJson)) {
                List<Song> playedList = gson.fromJson(playedJson, new TypeToken<List<Song>>() {
                }.getType());
                if (playedList != null) played.addAll(playedList);
            }
        } catch (Throwable th) {
            th.printStackTrace();
            songs.clear();
            played.clear();
            currentIndex = -1;
        }
    }

    private void notifyChanged() {
        EventBus.getDefault().post(new KtvEvent(KtvEvent.TYPE_QUEUE_CHANGED, null));
    }
}
