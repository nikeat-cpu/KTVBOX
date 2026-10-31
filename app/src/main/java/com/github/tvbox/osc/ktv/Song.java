package com.github.tvbox.osc.ktv;

import android.text.TextUtils;

import java.io.Serializable;

/**
 * KTV 歌曲数据模型
 * 由点歌搜索结果（Movie.Video）转换而来，只保留 KTV 场景需要的字段。
 */
public class Song implements Serializable {

    public String id;         // 内容 id（来自源站）
    public String name;       // 歌名
    public String artist;     // 歌手 / 演员
    public String pic;        // 封面图
    public String sourceKey;  // 来源站点 key
    public long addTime;      // 点歌时间戳

    public Song() {
    }

    public Song(String id, String name, String artist, String pic, String sourceKey) {
        this.id = id;
        this.name = name;
        this.artist = artist;
        this.pic = pic;
        this.sourceKey = sourceKey;
        this.addTime = System.currentTimeMillis();
    }

    /**
     * 唯一标识：同一首歌可能出现在多个源站，用 源站+id 区分
     */
    public String key() {
        return (sourceKey == null ? "" : sourceKey) + "@" + (id == null ? "" : id);
    }

    /**
     * 展示用标题：「歌名 - 歌手」
     */
    public String display() {
        if (TextUtils.isEmpty(artist)) return TextUtils.isEmpty(name) ? "" : name;
        return (TextUtils.isEmpty(name) ? "" : name) + " - " + artist;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Song)) return false;
        Song other = (Song) o;
        if (id == null || other.id == null) return false;
        String a = key();
        String b = other.key();
        return a.equals(b);
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
