package com.github.tvbox.osc.ktv;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.BounceInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.bean.AbsXml;
import com.github.tvbox.osc.bean.Movie;
import com.github.tvbox.osc.bean.SourceBean;
import com.github.tvbox.osc.event.RefreshEvent;
import com.github.tvbox.osc.ui.activity.DetailActivity;
import com.github.tvbox.osc.ui.dialog.ConfirmClearDialog;
import com.github.tvbox.osc.ui.tv.widget.CustomEditText;
import com.github.tvbox.osc.viewmodel.SourceViewModel;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * KTV 点歌台
 *
 * 功能：
 * - 关键词搜索点歌（复用 TVBox 的爬虫聚合搜索，多源并发，结果实时汇总）
 * - 已点歌曲队列（点歌 / 置顶 / 删除 / 清空 / 切歌），队列持久化
 * - 点歌后直接进入播放（复用现有播放链路）
 *
 * 遥控器操作：
 * - 确定：点歌 / 播放
 * - 长按确定：从队列删除
 * - 菜单键：切下一首
 */
public class KtvActivity extends BaseActivity {

    private static final int MAX_SEARCH_SOURCE = 10;   // 最多并发搜索的源站数量
    private static final int MAX_RESULT = 200;         // 结果上限，防止列表过长

    private CustomEditText edtSearch;
    private TextView btnSearch;
    private TextView tvSearchTip;
    private TextView tvQueueTitle;
    private TextView tvQueueCount;
    private TextView tvQueueEmpty;
    private TextView tvClearQueue;
    private TextView tvClearPlayed;
    private TextView tvPlayedTitle;
    private TextView tvPlayedEmpty;
    private View layoutPlayed;
    private TvRecyclerView rvResult;
    private TvRecyclerView rvQueue;
    private TvRecyclerView rvPlayed;

    private KtvSongAdapter songAdapter;
    private KtvApiProcess ktvApi;
    private TextView tvPageInfo;
    private View layoutPager, btnPrevPage, btnNextPage;
    private KtvQueueAdapter queueAdapter;
    private KtvPlayedAdapter playedAdapter;

    private SourceViewModel sourceViewModel;

    /** 主界面右下角的「手机扫码点歌」二维码浮层 */
    private KtvQrOverlay ktvQrOverlay;

    /** 聚合搜索全量结果（分页前）与当前页码 */
    private List<Song> allResults = new ArrayList<>();
    private int resultPage = 0;
    private static final int PAGE_SIZE = 20;           // 搜索结果每页条数
    private boolean configLoading = false;
    private String pendingKeyword = null;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_ktv;
    }

    @Override
    protected void init() {
        // 确保 Web 服务已启动（手机点歌页依赖它；从 KTV 图标直接进入时 HomeActivity 可能未运行）
        try {
            com.github.tvbox.osc.server.ControlManager.get().startServer();
        } catch (Throwable ignored) {
        }
        sourceViewModel = new ViewModelProvider(this).get(SourceViewModel.class);
        sourceViewModel.searchResult.observe(this, absXml -> {
            // 结果通过 EventBus 汇总，这里仅占位避免 LiveData 空实现
        });
        initView();
        refreshQueue();
        // 点歌台主界面也挂「手机扫码点歌」二维码（固定右下角，播放页的是左上/右上轮换）
        ktvQrOverlay = KtvQrOverlay.attach(this, false);
        // 一进 KTV 就预加载「KTV┃音乐」源配置（含爬虫 jar），这样手机端随时可以点歌
        if (!KtvSource.available()) {
            ensureConfigLoaded(null);
        }
    }

    private void initView() {
        EventBus.getDefault().register(this);

        edtSearch = findViewById(R.id.edtSearch);
        btnSearch = findViewById(R.id.btnSearch);
        tvSearchTip = findViewById(R.id.tvSearchTip);
        layoutPager = findViewById(R.id.layoutPager);
        btnPrevPage = findViewById(R.id.btnPrevPage);
        btnNextPage = findViewById(R.id.btnNextPage);
        tvPageInfo = findViewById(R.id.tvPageInfo);
        btnPrevPage.setOnClickListener(v -> {
            if (resultPage > 0) {
                resultPage--;
                renderSearchPage();
            }
        });
        btnNextPage.setOnClickListener(v -> {
            int pages = (allResults.size() + PAGE_SIZE - 1) / PAGE_SIZE;
            if (resultPage < pages - 1) {
                resultPage++;
                renderSearchPage();
            }
        });
        tvQueueTitle = findViewById(R.id.tvQueueTitle);
        tvQueueCount = findViewById(R.id.tvQueueCount);
        tvQueueEmpty = findViewById(R.id.tvQueueEmpty);
        tvClearQueue = findViewById(R.id.tvClearQueue);
        tvClearPlayed = findViewById(R.id.tvClearPlayed);
        tvPlayedTitle = findViewById(R.id.tvPlayedTitle);
        tvPlayedEmpty = findViewById(R.id.tvPlayedEmpty);
        layoutPlayed = findViewById(R.id.layoutPlayed);
        rvResult = findViewById(R.id.rvResult);
        rvQueue = findViewById(R.id.rvQueue);
        rvPlayed = findViewById(R.id.rvPlayed);

        // 搜索结果网格
        rvResult.setHasFixedSize(true);
        rvResult.setLayoutManager(new V7GridLayoutManager(mContext, isBaseOnWidth() ? 4 : 5));
        songAdapter = new KtvSongAdapter();
        rvResult.setAdapter(songAdapter);
        rvResult.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.15f).scaleY(1.15f).setDuration(300).setInterpolator(new BounceInterpolator()).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        songAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Song song = songAdapter.getData().get(position);
                onSongPicked(song);
            }
        });
        // 长按：置顶（下一首唱）
        songAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                Song song = songAdapter.getData().get(position);
                if (song == null) return true;
                if (SongQueue.get().addNext(song)) {
                    toast(getString(R.string.ktv_top) + "：" + song.display());
                } else {
                    toast("已在已点列表中");
                }
                return true;
            }
        });

        // 已点队列
        rvQueue.setHasFixedSize(true);
        rvQueue.setLayoutManager(new V7LinearLayoutManager(mContext, 1, false));
        queueAdapter = new KtvQueueAdapter();
        rvQueue.setAdapter(queueAdapter);
        rvQueue.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        queueAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Song song = queueAdapter.getData().get(position);
                if (song == null) return;
                SongQueue.get().playAt(position);
                playSong(song);
            }
        });
        queueAdapter.setOnItemLongClickListener(new BaseQuickAdapter.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(BaseQuickAdapter adapter, View view, int position) {
                SongQueue.get().remove(position);
                toast(getString(R.string.ktv_removed));
                return true;
            }
        });

        // 已播列表（点按重唱：重新入队并立即开唱）
        rvPlayed.setHasFixedSize(true);
        rvPlayed.setLayoutManager(new V7LinearLayoutManager(mContext, 1, false));
        playedAdapter = new KtvPlayedAdapter();
        rvPlayed.setAdapter(playedAdapter);
        rvPlayed.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                itemView.animate().scaleX(1.05f).scaleY(1.05f).setDuration(200).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
            }
        });
        playedAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                Song song = playedAdapter.getData().get(position);
                if (song == null) return;
                SongQueue.get().add(song);          // 已在队列会自动去重
                int idx = SongQueue.get().indexOf(song);
                if (idx >= 0) SongQueue.get().playAt(idx);
                playSong(song);
            }
        });

        // 搜索按钮 / 软键盘「完成」
        btnSearch.setOnClickListener(v -> doSearch(edtSearch.getText().toString().trim()));
        edtSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN)) {
                doSearch(edtSearch.getText().toString().trim());
                return true;
            }
            return false;
        });

        // 热门快捷点歌（明星直达：歌手搜索）
        findViewById(R.id.tvHot1).setOnClickListener(v -> quickSearch(getString(R.string.ktv_hot_1)));
        findViewById(R.id.tvHot2).setOnClickListener(v -> quickSearch(getString(R.string.ktv_hot_2)));
        findViewById(R.id.tvHot3).setOnClickListener(v -> quickSearch(getString(R.string.ktv_hot_3)));
        findViewById(R.id.tvHot4).setOnClickListener(v -> quickSearch(getString(R.string.ktv_hot_4)));

        // 清空队列
        tvClearQueue.setOnClickListener(v -> {
            if (SongQueue.get().isEmpty()) return;
            new ConfirmClearDialog(mContext, "KTV", new Runnable() {
                @Override
                public void run() {
                    SongQueue.get().clear();
                    toast(getString(R.string.ktv_cleared));
                }
            }).show();
        });

        // 清空已播
        tvClearPlayed.setOnClickListener(v -> {
            if (SongQueue.get().getPlayed().isEmpty()) return;
            new ConfirmClearDialog(mContext, "KTV", new Runnable() {
                @Override
                public void run() {
                    SongQueue.get().clearPlayed();
                    toast(getString(R.string.ktv_cleared_played));
                }
            }).show();
        });

        tvSearchTip.setText(getString(R.string.ktv_tip_default));
    }

    // ------------------------------------------------------------------ 点歌

    private void quickSearch(String keyword) {
        edtSearch.setText(keyword);
        edtSearch.setSelection(edtSearch.getText().length());
        doSearch(keyword);
    }

    /**
     * 多源并发搜索点歌
     */
    private void doSearch(String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            toast(getString(R.string.ktv_search_hint));
            return;
        }
        List<SourceBean> allSources;
        try {
            allSources = ApiConfig.get().getSourceBeanList();
        } catch (Throwable th) {
            th.printStackTrace();
            return;
        }
        if (allSources == null || allSources.isEmpty()) {
            tvSearchTip.setText("尚未配置内容源，请先在首页配置接口");
            return;
        }

        // KTV 点歌固定只用「KTV┃音乐」源，其他源一律不参与
        List<SourceBean> searchable = KtvSource.list();
        if (searchable.isEmpty()) {
            // 配置未加载（KTV 图标直进时配置引擎未初始化）：自动加载后重试搜索
            ensureConfigLoaded(keyword);
            return;
        }

        songAdapter.setNewData(new ArrayList<>());
        tvSearchTip.setText(getString(R.string.ktv_searching) + " " + keyword);
        layoutPager.setVisibility(View.GONE);

        // 与手机端同一套聚合搜索（歌名+歌手双通道，30s 超时），后台线程执行
        final String kw = keyword;
        new Thread(() -> {
            List<Song> r;
            try {
                r = getKtvApi().searchSync(kw);
            } catch (Throwable th) {
                th.printStackTrace();
                r = new ArrayList<>();
            }
            final List<Song> result = r;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                allResults = result == null ? new ArrayList<>() : result;
                if (allResults.isEmpty()) {
                    tvSearchTip.setText(getString(R.string.ktv_no_result));
                    layoutPager.setVisibility(View.GONE);
                } else {
                    resultPage = 0;
                    renderSearchPage();
                }
            });
        }, "ktv-tv-search").start();
    }

    private KtvApiProcess getKtvApi() {
        if (ktvApi == null) ktvApi = new KtvApiProcess(mContext.getApplicationContext());
        return ktvApi;
    }

    /** 渲染当前搜索结果页（20 首/页） */
    private void renderSearchPage() {
        int total = allResults.size();
        int pages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        if (resultPage >= pages) resultPage = pages - 1;
        if (resultPage < 0) resultPage = 0;
        int from = resultPage * PAGE_SIZE;
        int to = Math.min(total, from + PAGE_SIZE);
        songAdapter.setNewData(new ArrayList<>(allResults.subList(from, to)));
        rvResult.scrollToPosition(0);
        tvPageInfo.setText("第 " + (resultPage + 1) + "/" + pages + " 页");
        layoutPager.setVisibility(pages > 1 ? View.VISIBLE : View.GONE);
        btnPrevPage.setVisibility(resultPage > 0 ? View.VISIBLE : View.INVISIBLE);
        btnNextPage.setVisibility(resultPage < pages - 1 ? View.VISIBLE : View.INVISIBLE);
        tvSearchTip.setText("共 " + total + " 首｜第 " + (resultPage + 1) + "/" + pages
                + " 页｜确定=点歌，长按=置顶");
    }

    /**
     * KTV 图标直进时配置引擎未初始化：自动加载配置（含爬虫 jar），成功后自动重试搜索。
     * 配置地址优先取用户已保存的接口，否则用内置默认源（王二小放牛娃）。
     */
    private void ensureConfigLoaded(String keyword) {
        pendingKeyword = keyword;
        if (configLoading) return;
        configLoading = true;
        tvSearchTip.setText("正在加载「KTV┃音乐」源配置…");
        // 必须在后台线程执行：loadConfig 缓存命中路径会同步解析配置(96个源)，
        // loadJar 会同步加载爬虫 dex，在低性能电视上主线程做这些必然 ANR
        new Thread(() -> {
            ApiConfig.get().loadConfig(true, new ApiConfig.LoadConfigCallback() {
            @Override
            public void success() {
                String spider = ApiConfig.get().getSpider();
                if (spider == null || spider.isEmpty()) {
                    onConfigReady();
                } else {
                    ApiConfig.get().loadJar(true, spider, new ApiConfig.LoadConfigCallback() {
                        @Override
                        public void success() {
                            onConfigReady();
                        }

                        @Override
                        public void retry() {
                        }

                        @Override
                        public void error(String msg) {
                            onConfigFail("爬虫加载失败：" + msg);
                        }
                    });
                }
            }

            @Override
            public void retry() {
            }

            @Override
            public void error(String msg) {
                onConfigFail("源配置加载失败：" + msg);
            }
        }, this);
        }, "ktv-config-loader").start();
    }

    private void onConfigReady() {
        configLoading = false;
        runOnUiThread(() -> {
            String kw = pendingKeyword;
            pendingKeyword = null;
            if (KtvSource.available()) {
                if (!TextUtils.isEmpty(kw)) doSearch(kw);
                else tvSearchTip.setText(getString(R.string.ktv_tip_default));
            } else {
                tvSearchTip.setText("当前配置里没有「KTV┃音乐」源，请检查接口地址");
            }
        });
    }

    private void onConfigFail(String msg) {
        configLoading = false;
        runOnUiThread(() -> tvSearchTip.setText(msg));
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

    /**
     * 点歌：加入队列；若队列原本为空则直接开唱
     */
    private void onSongPicked(Song song) {
        if (song == null) return;
        boolean wasEmpty = SongQueue.get().isEmpty();
        boolean added = SongQueue.get().add(song);
        if (added) {
            toast(getString(R.string.ktv_added) + "：" + song.display());
            songAdapter.notifyDataSetChanged();
            if (wasEmpty) {
                SongQueue.get().playAt(0);
                playSong(song);
            }
        } else {
            toast("已在已点列表中");
        }
    }

    /**
     * 播放：复用现有详情/播放链路
     */
    private void playSong(Song song) {
        if (song == null || TextUtils.isEmpty(song.id)) return;
        // DetailActivity 是 singleTask 且未处理 onNewIntent，先结束旧实例避免复用旧页面
        try {
            com.github.tvbox.osc.util.AppManager.getInstance()
                    .finishActivity(com.github.tvbox.osc.ui.activity.DetailActivity.class);
        } catch (Throwable ignored) {
        }
        Bundle bundle = new Bundle();
        bundle.putString("id", song.id);
        bundle.putString("sourceKey", song.sourceKey);
        bundle.putBoolean("autoPlay", true);
        jumpActivity(DetailActivity.class, bundle);
    }

    // ------------------------------------------------------------------ 队列刷新

    private void refreshQueue() {
        List<Song> songs = SongQueue.get().getSongs();
        queueAdapter.setNewData(new ArrayList<>(songs));
        queueAdapter.setCurrentIndex(SongQueue.get().getCurrentIndex());
        tvQueueCount.setText("(" + songs.size() + ")");
        tvQueueEmpty.setVisibility(songs.isEmpty() ? View.VISIBLE : View.GONE);
        rvQueue.setVisibility(songs.isEmpty() ? View.GONE : View.VISIBLE);
        // 已播列表
        List<Song> played = SongQueue.get().getPlayed();
        playedAdapter.setNewData(new ArrayList<>(played));
        tvPlayedTitle.setText(getString(R.string.ktv_played_title) + " (" + played.size() + ")");
        tvPlayedEmpty.setVisibility(played.isEmpty() ? View.VISIBLE : View.GONE);
        rvPlayed.setVisibility(played.isEmpty() ? View.GONE : View.VISIBLE);
        layoutPlayed.setVisibility(View.VISIBLE);
        if (songAdapter != null) songAdapter.notifyDataSetChanged();
    }

    // ------------------------------------------------------------------ 事件

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onKtvEvent(KtvEvent event) {
        switch (event.type) {
            case KtvEvent.TYPE_QUEUE_CHANGED:
                refreshQueue();
                break;
            default:
                break;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            Song next = SongQueue.get().next();
            if (next != null) {
                toast(getString(R.string.ktv_playing) + "：" + next.display());
                playSong(next);
            } else {
                toast("已经是最后一首");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        if (ktvQrOverlay != null) {
            ktvQrOverlay.detach();
            ktvQrOverlay = null;
        }
        if (sourceViewModel != null) {
            try {
                sourceViewModel.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
    }
}
