package com.github.tvbox.osc.ktv;

/**
 * KTV 模块内部事件（EventBus 使用）
 */
public class KtvEvent {

    /** 已点队列发生变化（点歌/删歌/清空/置顶） */
    public static final int TYPE_QUEUE_CHANGED = 8001;
    /** 当前播放歌曲发生变化（切歌/指定播放） */
    public static final int TYPE_CURRENT_CHANGED = 8002;
    /** 点歌搜索结果（增量返回，每次一个源站的结果） */
    public static final int TYPE_SEARCH_RESULT = 8003;
    /** 点歌搜索全部结束 */
    public static final int TYPE_SEARCH_DONE = 8004;
    /** 歌词加载完成 */
    public static final int TYPE_LYRICS_READY = 8005;

    public int type;
    public Object obj;
    public String tag;

    public KtvEvent(int type) {
        this.type = type;
    }

    public KtvEvent(int type, Object obj) {
        this.type = type;
        this.obj = obj;
    }

    public KtvEvent(int type, Object obj, String tag) {
        this.type = type;
        this.obj = obj;
        this.tag = tag;
    }
}
