package com.github.tvbox.osc.ktv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.receiver.DetailReceiver;
import com.github.tvbox.osc.server.RequestProcess;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.activity.PlayActivity;
import com.github.tvbox.osc.util.AppManager;
import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * KTV 手机点歌 API（挂在 RemoteServer 上）
 *
 * GET /ktv/search?wd=xxx   搜索（固定使用「KTV┃音乐」MusicAiIKtv 单源）
 * GET /ktv/queue           已点列表（含当前播放标记）
 * GET /ktv/add             点歌（id/sourceKey/name/artist/pic）；队列为空时自动在电视上开唱
 * GET /ktv/addNext         优先点歌：新歌插到当前歌之后（下一首就唱它）；已在队列则移过去
 * GET /ktv/remove?index=n  从已点列表删除
 * GET /ktv/top?index=n     已点列表置顶：把第 n 首移到当前歌之后
 * GET /ktv/clear           清空已点列表
 * GET /ktv/play?index=n    指定某首立刻播放（电视端自动开唱）
 * GET /ktv/skip            切歌：跳过正在唱的（不计入已播），自动连播下一首；队列空则停止
 * GET /ktv/track?t=1|2     切音轨：1=原唱（音轨1），2=伴唱（音轨2）
 * GET /ktv/clearPlayed     清空已播列表
 */
public class KtvApiProcess implements RequestProcess {

    private static final int MAX_SOURCE = 12;          // 最多并发搜索的源站数
    private static final long SEARCH_TIMEOUT_MS = 30;  // 单路搜索超时（秒）；歌手搜索(all=1)大结果可到 15s+，12s 会砍掉

    private final Context mContext;
    private final OkHttpClient mClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    /** 后台配置加载去重标记 */
    private static volatile boolean sLoadingConfig = false;

    public KtvApiProcess(Context context) {
        this.mContext = context;
    }

    /**
     * 手机端搜索时若「KTV┃音乐」源尚未加载（app 进程刚重启/电视端没开过 KTV），
     * 在后台线程自动补齐 loadConfig + loadJar，几秒后手机重试即可搜到。
     */
    private void ensureConfigAsync() {
        if (KtvSource.available() || sLoadingConfig) return;
        sLoadingConfig = true;
        new Thread(() -> {
            try {
                android.util.Log.d("KTVLOG", "phone search: config not ready, loading config...");
                ApiConfig.get().loadConfig(true, new ApiConfig.LoadConfigCallback() {
                    @Override
                    public void success() {
                        String spider = ApiConfig.get().getSpider();
                        if (spider == null || spider.isEmpty()) {
                            sLoadingConfig = false;
                            android.util.Log.d("KTVLOG", "config loaded (no spider), sources=" + ApiConfig.get().getSourceBeanList().size());
                            return;
                        }
                        ApiConfig.get().loadJar(true, spider, new ApiConfig.LoadConfigCallback() {
                            @Override
                            public void success() {
                                sLoadingConfig = false;
                                android.util.Log.d("KTVLOG", "jar loaded, KtvSource available=" + KtvSource.available());
                            }

                            @Override
                            public void retry() {
                            }

                            @Override
                            public void error(String msg) {
                                sLoadingConfig = false;
                                android.util.Log.d("KTVLOG", "loadJar error: " + msg);
                            }
                        });
                    }

                    @Override
                    public void retry() {
                    }

                    @Override
                    public void error(String msg) {
                        sLoadingConfig = false;
                        android.util.Log.d("KTVLOG", "loadConfig error: " + msg);
                    }
                }, null);
            } catch (Throwable th) {
                sLoadingConfig = false;
                android.util.Log.d("KTVLOG", "ensureConfigAsync exception: " + th);
            }
        }, "ktv-config-loader").start();
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String fileName) {
        return session.getMethod() == NanoHTTPD.Method.GET
                && fileName.startsWith("/ktv/")
                && !fileName.equalsIgnoreCase("/ktv/index.html");
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String fileName, Map<String, String> params, Map<String, String> files) {
        try {
            if (fileName.equalsIgnoreCase("/ktv/search")) {
                return json(search(params.get("wd") == null ? "" : params.get("wd").trim()));
            } else if (fileName.equalsIgnoreCase("/ktv/queue")) {
                return json(queueBody());
            } else if (fileName.equalsIgnoreCase("/ktv/add")) {
                return add(params);
            } else if (fileName.equalsIgnoreCase("/ktv/addNext")) {
                return addNext(params);
            } else if (fileName.equalsIgnoreCase("/ktv/remove")) {
                int idx = parseInt(params.get("index"), -1);
                if (idx >= 0 && !SongQueue.get().isEmpty()) SongQueue.get().remove(idx);
                return json(queueBody());
            } else if (fileName.equalsIgnoreCase("/ktv/top")) {
                int idx = parseInt(params.get("index"), -1);
                SongQueue.get().moveNext(idx);
                return json(queueBody());
            } else if (fileName.equalsIgnoreCase("/ktv/clear")) {
                SongQueue.get().clear();
                return json(queueBody());
            } else if (fileName.equalsIgnoreCase("/ktv/play")) {
                int idx = parseInt(params.get("index"), -1);
                Song song = SongQueue.get().playAt(idx);
                if (song != null) launchOnTv(song, true);
                return json(queueBody());
            } else if (fileName.equalsIgnoreCase("/ktv/skip")) {
                return skipSong();
            } else if (fileName.equalsIgnoreCase("/ktv/track")) {
                return switchTrack(params.get("t"));
            } else if (fileName.equalsIgnoreCase("/ktv/clearPlayed")) {
                SongQueue.get().clearPlayed();
                return json(queueBody());
            }
            return text(NanoHTTPD.Response.Status.NOT_FOUND, "not found");
        } catch (Throwable th) {
            th.printStackTrace();
            return text(NanoHTTPD.Response.Status.INTERNAL_ERROR, "error: " + th.getMessage());
        }
    }

    // ------------------------------------------------------------- 切歌（跳过当前）

    /** 跳过正在唱的歌：不计入已播，自动连播下一首；队列空则停止播放 */
    private NanoHTTPD.Response skipSong() throws Exception {
        JSONObject body;
        PlayActivity act = (PlayActivity) AppManager.getInstance().getActivity(PlayActivity.class);
        if (act != null) {
            String msg = act.ktvSkipCurrent();
            body = queueBody();
            if (msg == null) {
                body.put("code", 1);
                body.put("msg", "电视当前没有在播放");
            } else {
                body.put("msg", msg);
            }
        } else {
            // 播放页不在前台：队列层面切歌，队列还有歌就自动开唱
            Song cur = SongQueue.get().getCurrent();
            Song next = null;
            if (cur != null) {
                SongQueue.get().skipCurrent(cur.sourceKey, cur.id);
                next = SongQueue.get().getCurrent();
            }
            if (next != null) launchOnTv(next, true);
            body = queueBody();
            body.put("msg", next != null ? "已切歌，下一首：" + next.name : "队列已空");
        }
        return json(body);
    }

    // ------------------------------------------------------------- 音轨切换（原唱/伴唱）

    /** t=1 或缺省 → 音轨1（原唱）；t=2 → 音轨2（伴唱） */
    private NanoHTTPD.Response switchTrack(String t) throws Exception {
        JSONObject body = new JSONObject();
        PlayActivity act = (PlayActivity) AppManager.getInstance().getActivity(PlayActivity.class);
        if (act == null) {
            body.put("code", 1);
            body.put("msg", "电视当前没有在播放");
            return json(body);
        }
        boolean vocal = !"2".equals(t);
        String msg = act.ktvSwitchAudioTrack(vocal ? 0 : 1);
        if (msg == null) {
            body.put("code", 1);
            body.put("msg", "电视当前没有在播放");
        } else {
            body.put("code", 0);
            body.put("msg", msg);
        }
        return json(body);
    }

    // ------------------------------------------------------------- 已点列表

    private NanoHTTPD.Response add(Map<String, String> params) throws Exception {
        String id = trim(params.get("id"));
        String sourceKey = trim(params.get("sourceKey"));
        String name = trim(params.get("name"));
        String artist = trim(params.get("artist"));
        String pic = trim(params.get("pic"));
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(sourceKey)) {
            return error("缺少歌曲参数");
        }
        boolean wasEmpty = SongQueue.get().isEmpty();
        Song newSong = new Song(id, name, artist, pic, sourceKey);
        boolean added = SongQueue.get().add(newSong);
        // 仅当电视当前没有在播放（PlayActivity 不在前台）时自动开唱，否则只入队排队
        boolean tvPlaying = AppManager.getInstance().getActivity(PlayActivity.class) != null;
        if (added && wasEmpty && !tvPlaying) {
            SongQueue.get().playAt(0);
            launchOnTv(SongQueue.get().getCurrent(), true);
        } else if (!added && !tvPlaying) {
            // 已在队列中但电视没在播（例如上次点过但没唱到/播放被中断）：直接开播这首
            int idx = SongQueue.get().indexOf(newSong);
            Song s = SongQueue.get().playAt(idx);
            if (s != null) launchOnTv(s, true);
        }
        JSONObject body = queueBody();
        body.put("added", added);
        return json(body);
    }

    /**
     * 优先点歌：把这首歌唱到下一个。已在队列中则移动位置，否则新歌插到当前歌之后。
     * 队列为空且电视没在播时自动开唱（与 add 一致）。
     */
    private NanoHTTPD.Response addNext(Map<String, String> params) throws Exception {
        String id = trim(params.get("id"));
        String sourceKey = trim(params.get("sourceKey"));
        String name = trim(params.get("name"));
        String artist = trim(params.get("artist"));
        String pic = trim(params.get("pic"));
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(sourceKey)) {
            return error("缺少歌曲参数");
        }
        boolean wasEmpty = SongQueue.get().isEmpty();
        Song newSong = new Song(id, name, artist, pic, sourceKey);
        boolean added;
        int existing = SongQueue.get().indexOf(newSong);
        if (existing >= 0) {
            added = SongQueue.get().moveNext(existing);
        } else {
            added = SongQueue.get().addNext(newSong);
        }
        boolean tvPlaying = AppManager.getInstance().getActivity(PlayActivity.class) != null;
        if (added && wasEmpty && !tvPlaying) {
            SongQueue.get().playAt(0);
            launchOnTv(SongQueue.get().getCurrent(), true);
        }
        JSONObject body = queueBody();
        body.put("added", added);
        return json(body);
    }

    private JSONObject queueBody() throws Exception {
        JSONArray arr = new JSONArray();
        List<Song> songs = SongQueue.get().getSongs();
        int cur = SongQueue.get().getCurrentIndex();
        for (int i = 0; i < songs.size(); i++) {
            Song s = songs.get(i);
            JSONObject o = new JSONObject();
            o.put("index", i);
            o.put("id", nullToEmpty(s.id));
            o.put("name", nullToEmpty(s.name));
            o.put("artist", nullToEmpty(s.artist));
            o.put("pic", nullToEmpty(s.pic));
            o.put("sourceKey", nullToEmpty(s.sourceKey));
            o.put("current", i == cur);
            arr.put(o);
        }
        // 已播列表（唱完的歌，时间正序，最新的在末尾）
        JSONArray parr = new JSONArray();
        List<Song> played = SongQueue.get().getPlayed();
        for (int i = 0; i < played.size(); i++) {
            Song s = played.get(i);
            JSONObject o = new JSONObject();
            o.put("index", i);
            o.put("id", nullToEmpty(s.id));
            o.put("name", nullToEmpty(s.name));
            o.put("artist", nullToEmpty(s.artist));
            o.put("pic", nullToEmpty(s.pic));
            o.put("sourceKey", nullToEmpty(s.sourceKey));
            parr.put(o);
        }
        JSONObject body = new JSONObject();
        body.put("code", 0);
        body.put("currentIndex", cur);
        body.put("list", arr);
        body.put("played", parr);
        return body;
    }

    private void launchOnTv(Song song, boolean autoPlay) {
        if (song == null || mContext == null) return;
        // 关键：先把旧的播放页/详情页全部关掉（否则旧播放器一直在后台跑，切几首就声音重叠+卡顿）
        // finish 是异步的，延迟几百毫秒再发广播，让旧页面有时间释放播放器
        AppManager.getInstance().finishActivities(PlayActivity.class);
        AppManager.getInstance().finishActivities(DetailActivity.class);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent();
            intent.putExtra("id", song.id);
            intent.putExtra("sourceKey", song.sourceKey);
            intent.putExtra("autoPlay", autoPlay);
            intent.setAction(DetailReceiver.action);
            intent.setPackage(mContext.getPackageName());
            intent.setComponent(new ComponentName(mContext, DetailReceiver.class));
            mContext.sendBroadcast(intent);
        }, 500);
    }

    // ------------------------------------------------------------- 聚合搜索（两段式）
    // 背景：投影仪 WiFi 会掐断「请求后间隔数秒才回包」的长连接（实测 >5s 的响应 body 必丢），
    // 所以搜索不能阻塞到爬虫返回——立即返回 code:2 让手机轮询，爬虫在后台搜完进缓存。

    private static class SearchJob {
        final String keyword;
        volatile boolean done = false;
        volatile List<Song> result = new ArrayList<>();
        SearchJob(String kw) { keyword = kw; }
    }

    private final Object searchJobLock = new Object();
    private final java.util.LinkedHashMap<String, SearchJob> searchJobs = new java.util.LinkedHashMap<>();

    private SearchJob getOrCreateJob(String wd) {
        synchronized (searchJobLock) {
            SearchJob job = searchJobs.get(wd);
            if (job != null) return job;
            job = new SearchJob(wd);
            searchJobs.put(wd, job);
            // 只保留最近 8 个关键词的缓存
            while (searchJobs.size() > 8) {
                String eldest = searchJobs.keySet().iterator().next();
                searchJobs.remove(eldest);
            }
            final SearchJob j = job;
            new Thread(() -> runSearchJob(j), "ktv-search-" + wd).start();
            return job;
        }
    }

    private void runSearchJob(SearchJob job) {
        job.result = aggregatedSearch(job.keyword);
        job.done = true;
    }

    /**
     * 聚合搜索核心（同步，最多约 30s）：等配置就绪 → 并发歌名+歌手搜索 → 去重合并 → 歌手匹配置顶。
     * 手机端（SearchJob 后台线程）与电视端（KtvActivity）共用。
     */
    private List<Song> aggregatedSearch(String keyword) {
        List<SourceBean> searchable = null;
        // 最多等 15s 让配置/爬虫就绪
        for (int i = 0; i < 15 && (searchable == null || searchable.isEmpty()); i++) {
            searchable = KtvSource.list();
            if (searchable == null || searchable.isEmpty()) {
                ensureConfigAsync();
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    return new ArrayList<>();
                }
            }
        }
        if (searchable == null || searchable.isEmpty()) return new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(searchable.size() * 2, 8));
        List<Future<List<Song>>> futures = new ArrayList<>();
        for (final SourceBean bean : searchable) {
            futures.add(pool.submit(() -> searchSource(bean, keyword)));
            // KTV 源追加歌手搜索通道：蜘蛛 categoryContent 的 tid 以 $$$singer 结尾时，
            // 会把 $$$ 前的内容当歌手关键词调 songer.php 搜索该歌手的全部歌曲
            if (KtvSource.KEY.equals(bean.getKey())) {
                futures.add(pool.submit(() -> searchSinger(bean, keyword)));
            }
        }
        Set<String> seen = new HashSet<>();
        List<Song> result = new ArrayList<>();
        for (Future<List<Song>> f : futures) {
            try {
                for (Song s : f.get(SEARCH_TIMEOUT_MS, TimeUnit.SECONDS)) {
                    String k = s.name + "|" + s.artist;
                    if (seen.contains(k)) continue;
                    seen.add(k);
                    result.add(s);
                }
            } catch (Throwable ignored) {
            }
        }
        pool.shutdownNow();
        // 搜歌手时，把「歌手=关键词」的原唱结果排到最前（稳定排序，歌名搜索不受影响）
        try {
            final String kw = keyword == null ? "" : keyword;
            result.sort((a, b) -> Boolean.compare(
                    !(a.artist != null && a.artist.contains(kw)),
                    !(b.artist != null && b.artist.contains(kw))));
        } catch (Throwable ignored) {
        }
        return result;
    }

    /** 电视端（KtvActivity）复用的同步聚合搜索：含歌手通道，最多约 30s */
    public List<Song> searchSync(String keyword) {
        if (TextUtils.isEmpty(keyword)) return new ArrayList<>();
        return aggregatedSearch(keyword);
    }

    private JSONObject search(String wd) throws Exception {
        JSONObject body = new JSONObject();
        if (TextUtils.isEmpty(wd)) {
            body.put("code", 0);
            body.put("list", new JSONArray());
            return body;
        }
        SearchJob job = getOrCreateJob(wd);
        if (!job.done) {
            // 立即返回，手机端轮询；HTTP 响应保持毫秒级，规避投影仪 WiFi 断流
            body.put("code", 2);
            body.put("msg", "搜索中，请稍候…");
            body.put("list", new JSONArray());
            return body;
        }
        JSONArray arr = new JSONArray();
        for (Song s : job.result) {
            JSONObject o = new JSONObject();
            o.put("id", nullToEmpty(s.id));
            o.put("name", nullToEmpty(s.name));
            o.put("artist", nullToEmpty(s.artist));
            o.put("pic", nullToEmpty(s.pic));
            o.put("sourceKey", nullToEmpty(s.sourceKey));
            arr.put(o);
        }
        body.put("code", 0);
        body.put("list", arr);
        return body;
    }

    private List<Song> searchSource(SourceBean bean, String wd) {
        List<Song> songs = new ArrayList<>();
        try {
            int type = bean.getType();
            if (type == 3) {
                Spider sp = ApiConfig.get().getCSP(bean);
                String s = sp.searchContent(wd, false);
                android.util.Log.d("KTVLOG", "search[" + bean.getKey() + "] wd=" + wd
                        + " resultLen=" + (s == null ? -1 : s.length())
                        + " head=" + (s == null ? "null" : s.substring(0, Math.min(300, s.length())).replace('\n', ' ')));
                if (!TextUtils.isEmpty(s)) songs.addAll(parseJson(s, bean.getKey()));
            } else if (type == 0) {
                String s = httpGet(bean.getApi() + "?wd=" + enc(wd));
                if (!TextUtils.isEmpty(s)) songs.addAll(parseXml(s, bean.getKey()));
            } else if (type == 1 || type == 4) {
                String url = bean.getApi() + "?wd=" + enc(wd) + "&ac=detail" + (type == 4 ? "&quick=false" : "");
                String s = httpGet(url);
                if (!TextUtils.isEmpty(s)) songs.addAll(parseJson(s, bean.getKey()));
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return songs;
    }

    /**
     * 歌手搜索：利用蜘蛛 categoryContent 的隐藏通道——tid 形如「周杰伦$$$singer」时，
     * 蜘蛛内部调 <base>/songer.php?page=1&key=周杰伦&all=1 返回该歌手的全部歌曲。
     * 返回的 JSON 与 searchContent 同构（vod_id=number$$$歌名$$$歌手），共用 parseJson 解析。
     */
    private List<Song> searchSinger(SourceBean bean, String wd) {
        List<Song> songs = new ArrayList<>();
        try {
            if (bean.getType() != 3) return songs;
            Spider sp = ApiConfig.get().getCSP(bean);
            if (sp == null) return songs;
            String s = sp.categoryContent(wd + "$$$singer", "1", false, null);
            android.util.Log.d("KTVLOG", "singerSearch[" + bean.getKey() + "] wd=" + wd
                    + " resultLen=" + (s == null ? -1 : s.length()));
            if (!TextUtils.isEmpty(s)) songs.addAll(parseJson(s, bean.getKey()));
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return songs;
    }

    private List<Song> parseJson(String s, String sourceKey) {
        List<Song> songs = new ArrayList<>();
        try {
            // 兼容两种返回格式：{"list":[{vod_id,vod_name,...}]} 或直接 [...]
            Object root = new org.json.JSONTokener(s).nextValue();
            JSONArray list = null;
            if (root instanceof JSONObject) {
                JSONObject obj = (JSONObject) root;
                list = obj.optJSONArray("list");
                if (list == null) list = obj.optJSONArray("data");
            } else if (root instanceof JSONArray) {
                list = (JSONArray) root;
            }
            if (list != null) {
                for (int i = 0; i < list.length(); i++) {
                    JSONObject v = list.optJSONObject(i);
                    if (v == null) continue;
                    String id = String.valueOf(v.opt("vod_id"));
                    String name = v.optString("vod_name", "");
                    if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name)) continue;
                    String artist = firstArtist(v.optString("vod_actor", ""));
                    if (TextUtils.isEmpty(artist)) {
                        // KTV 源 id 为 number$$$歌名$$$歌手 三段式，歌手名在第三段
                        int p1 = id.indexOf("$$$");
                        if (p1 >= 0) {
                            int p2 = id.indexOf("$$$", p1 + 3);
                            if (p2 > p1) artist = id.substring(p2 + 3).trim();
                        }
                    }
                    songs.add(new Song(id, name, artist, v.optString("vod_pic", ""), sourceKey));
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return songs;
    }

    private List<Song> parseXml(String s, String sourceKey) {
        List<Song> songs = new ArrayList<>();
        try {
            if (s.contains("<year></year>")) s = s.replace("<year></year>", "<year>0</year>");
            if (s.contains("<state></state>")) s = s.replace("<state></state>", "<state>0</state>");
            XStream xstream = new XStream(new DomDriver());
            xstream.autodetectAnnotations(true);
            xstream.processAnnotations(AbsXml.class);
            xstream.ignoreUnknownElements();
            xstream.allowTypes(new Class[]{AbsXml.class});
            AbsXml data = (AbsXml) xstream.fromXML(s);
            if (data != null && data.movie != null && data.movie.videoList != null) {
                for (Movie.Video v : data.movie.videoList) {
                    if (v == null || TextUtils.isEmpty(v.id)) continue;
                    v.sourceKey = sourceKey;
                    songs.add(new Song(v.id, v.name, firstArtist(v.actor), v.pic, sourceKey));
                }
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
        return songs;
    }

    // ------------------------------------------------------------- 工具

    private String httpGet(String url) throws Exception {
        Request request = new Request.Builder().url(url).tag("ktv-search").build();
        Response response = mClient.newCall(request).execute();
        if (response.body() == null) return null;
        String body = response.body().string();
        response.close();
        return body;
    }

    private String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private String firstArtist(String actor) {
        if (TextUtils.isEmpty(actor)) return "";
        String a = actor.trim();
        int idx = a.indexOf(',');
        if (idx <= 0) idx = a.indexOf('/');
        if (idx > 0) a = a.substring(0, idx);
        a = a.trim();
        return a.length() > 16 ? a.substring(0, 16) : a;
    }

    private String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Throwable th) {
            return def;
        }
    }

    private NanoHTTPD.Response json(JSONObject body) {
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json", body.toString());
    }

    private NanoHTTPD.Response error(String msg) throws Exception {
        JSONObject body = new JSONObject();
        body.put("code", 1);
        body.put("msg", msg);
        return json(body);
    }

    private NanoHTTPD.Response text(NanoHTTPD.Response.IStatus status, String msg) {
        return NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, msg);
    }
}
