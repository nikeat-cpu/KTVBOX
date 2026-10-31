package com.github.tvbox.osc.ktv;

import android.text.TextUtils;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.util.ImgUtil;

import java.util.ArrayList;

/**
 * 点歌搜索结果适配器（网格卡片）
 */
public class KtvSongAdapter extends BaseQuickAdapter<Song, BaseViewHolder> {

    public KtvSongAdapter() {
        super(R.layout.item_ktv_song, new ArrayList<>());
    }

    @Override
    protected void convert(BaseViewHolder helper, Song item) {
        helper.setText(R.id.tvName, TextUtils.isEmpty(item.name) ? "未知歌曲" : item.name);

        if (TextUtils.isEmpty(item.artist)) {
            helper.setVisible(R.id.tvArtist, false);
        } else {
            helper.setVisible(R.id.tvArtist, true);
            helper.setText(R.id.tvArtist, item.artist);
        }

        String siteName = item.sourceKey;
        try {
            if (ApiConfig.get().getSource(item.sourceKey) != null) {
                siteName = ApiConfig.get().getSource(item.sourceKey).getName();
            }
        } catch (Throwable ignored) {
        }
        helper.setText(R.id.tvSite, siteName);

        // 已在队列中则显示「已点」标记
        if (SongQueue.get().contains(item)) {
            helper.setVisible(R.id.tvQueued, true);
        } else {
            helper.setVisible(R.id.tvQueued, false);
        }

        ImageView ivThumb = helper.getView(R.id.ivThumb);
        if (!TextUtils.isEmpty(item.pic)) {
            ImgUtil.load(item.pic, ivThumb, 14);
        } else {
            ivThumb.setImageResource(R.drawable.img_loading_placeholder);
        }
    }
}
