package org.jellymusic.legacy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.widget.ImageView;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ImageLoader {
    private final JellyfinApi api;
    private final Map<String, Bitmap> cache = Collections.synchronizedMap(new LinkedHashMap<String, Bitmap>(30, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) { return size() > 30; }
    });
    public ImageLoader(JellyfinApi api) { this.api = api; }
    public void load(final MediaItem item, final ImageView view) { load(api.imageUrl(item, 180), view); }
    public void load(final String url, final ImageView view) {
        view.setTag(url); view.setImageResource(android.R.drawable.ic_menu_gallery);
        Bitmap hit = cache.get(url); if (hit != null) { view.setImageBitmap(hit); return; }
        // THREAD_POOL_EXECUTOR, not the default execute(): on this API level plain execute() runs every
        // AsyncTask in the whole app on one shared serial thread, so scrolling a list of thumbnails would
        // queue behind (and block on) any in-flight library fetch or playback download elsewhere in the app.
        AsyncTask<Void,Void,Bitmap> task = new AsyncTask<Void,Void,Bitmap>() {
            protected Bitmap doInBackground(Void... unused) {
                try {
                    HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection(); for(Map.Entry<String,String> h:api.playbackHeaders().entrySet())c.setRequestProperty(h.getKey(),h.getValue()); c.setConnectTimeout(10000); c.setReadTimeout(15000);
                    InputStream in=c.getInputStream();
                    // RGB_565 instead of the default ARGB_8888: cover art has no alpha channel, and this
                    // halves the memory of every cached thumbnail on a device with a very small heap.
                    BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inPreferredConfig=Bitmap.Config.RGB_565;
                    Bitmap b=BitmapFactory.decodeStream(in,null,opts); in.close(); c.disconnect(); if(b!=null)cache.put(url,b); return b;
                } catch(Exception e){ return null; }
            }
            protected void onPostExecute(Bitmap b) { if(b!=null && url.equals(view.getTag())) view.setImageBitmap(b); }
        };
        try {
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // THREAD_POOL_EXECUTOR has a bounded queue (128 on the stock implementation); a fast fling
            // through a long list can fire many of these in a short burst. Worst case here is a thumbnail
            // that stays a placeholder instead of crashing the app.
        }
    }
}
