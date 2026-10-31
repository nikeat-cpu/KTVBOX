package com.github.tvbox.osc.ktv;

import android.graphics.Color;
import android.text.TextUtils;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;

import java.util.ArrayList;

/**
 * 已点歌曲队列适配器（列表行）
 */
public class KtvQueueAdapter extends BaseQuickAdapter<Song, BaseViewHolder> {

    private int currentIndex = -1;

    public KtvQueueAdapter() {
        super(R.layout.item_ktv_queue, new ArrayList<>());
    }

    public void setCurrentIndex(int index) {
        this.currentIndex = index;
        notifyDataSetChanged();
    }

    @Override
    protected void convert(BaseViewHolder helper, Song item) {
        int position = helper.getLayoutPosition();

        // 序号：正在唱的显示 ▶
        if (position == currentIndex) {
            helper.setText(R.id.tvIndex, "▶");
            helper.setTextColor(R.id.tvIndex, Color.parseColor("#FF3CE0FF"));
        } else {
            helper.setText(R.id.tvIndex, String.valueOf(position + 1));
            helper.setTextColor(R.id.tvIndex, Color.parseColor("#B3FFFFFF"));
        }

        helper.setText(R.id.tvName, TextUtils.isEmpty(item.name) ? "未知歌曲" : item.name);
        helper.setText(R.id.tvArtist, TextUtils.isEmpty(item.artist) ? "" : item.artist);
    }
}
