package org.jellymusic.legacy;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;

public class MediaAdapter extends ArrayAdapter<MediaItem> {
    private final ImageLoader images;
    public MediaAdapter(Context c, List<MediaItem> items, ImageLoader images) { super(c, 0, items); this.images=images; }
    private int dp(float n){ return (int)(n*getContext().getResources().getDisplayMetrics().density+.5f); }
    @Override public View getView(int pos, View convert, ViewGroup parent) {
        Holder h;
        if(convert==null){
            LinearLayout row=new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10),dp(7),dp(10),dp(7)); row.setMinimumHeight(dp(68));
            ImageView art=new ImageView(getContext()); art.setScaleType(ImageView.ScaleType.CENTER_CROP); row.addView(art,new LinearLayout.LayoutParams(dp(54),dp(54)));
            LinearLayout text=new LinearLayout(getContext()); text.setOrientation(LinearLayout.VERTICAL); text.setPadding(dp(12),0,0,0);
            TextView title=new TextView(getContext()); title.setTextColor(Color.WHITE); title.setTextSize(17); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); title.setSingleLine(true);
            TextView sub=new TextView(getContext()); sub.setTextColor(Color.rgb(170,174,184)); sub.setTextSize(13); sub.setSingleLine(true); text.addView(title); text.addView(sub); row.addView(text,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
            h=new Holder(); h.art=art; h.title=title; h.sub=sub; row.setTag(h); convert=row;
        } else h=(Holder)convert.getTag();
        MediaItem item=getItem(pos); h.title.setText(item.name); h.sub.setText(item.subtitle); images.load(item,h.art); return convert;
    }
    static class Holder { ImageView art; TextView title,sub; }
}
