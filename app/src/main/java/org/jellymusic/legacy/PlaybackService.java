package org.jellymusic.legacy;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import java.util.ArrayList;
import java.io.File;
import android.os.AsyncTask;
import android.os.Handler;
import android.widget.RemoteViews;

public class PlaybackService extends Service implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {
    public static final String ACTION_PLAY = "org.jellymusic.legacy.PLAY";
    public static final String ACTION_TOGGLE = "org.jellymusic.legacy.TOGGLE";
    public static final String ACTION_STOP = "org.jellymusic.legacy.STOP";
    public static final String ACTION_NEXT = "org.jellymusic.legacy.NEXT";
    public static final String ACTION_PREVIOUS = "org.jellymusic.legacy.PREVIOUS";
    public static final String ACTION_SEEK = "org.jellymusic.legacy.SEEK";
    public static final String ACTION_REPEAT = "org.jellymusic.legacy.REPEAT";
    public static final String ACTION_SHUFFLE = "org.jellymusic.legacy.SHUFFLE";
    public static final String ACTION_STATE = "org.jellymusic.legacy.STATE";
    private MediaPlayer player;
    private String title = "", art = "", itemId = "";
    // repeatMode: 0=off, 1=repeat current song, 2=repeat whole queue
    private int repeatMode=0;
    private boolean shuffleOn=false, localPlayback=false;
    private ArrayList<String> queueUrls=new ArrayList<String>(),queueTitles=new ArrayList<String>(),queueArt=new ArrayList<String>(),queueIds=new ArrayList<String>(); private int queueIndex=0;
    private Handler progressHandler=new Handler();
    private int progressReportTick=0;
    private Runnable progressTick=new Runnable(){public void run(){if(player!=null&&prepared){if(wakeLock.isHeld())wakeLock.acquire(60000);boolean playing=player.isPlaying();broadcast(playing,false,null);if(++progressReportTick%10==0)reportProgressAsync(!playing);progressHandler.postDelayed(this,1000);}}};
    private boolean prepared=false; private int playToken=0;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override public void onCreate() {
        super.onCreate(); player = new MediaPlayer(); player.setAudioStreamType(AudioManager.STREAM_MUSIC); player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK); player.setOnPreparedListener(this); player.setOnCompletionListener(this); player.setOnErrorListener(this);
        // MediaPlayer.setWakeMode() above only covers the native player's own decode/output state -- it
        // does NOT keep the CPU awake for downloadThenPlay()'s AsyncTask, which does a real HTTP download
        // on every single track transition (the ID3v2-tag workaround forces download-then-play instead of
        // true streaming). With the screen off the CPU can suspend right as that download starts, silently
        // stalling the "went to next song" transition forever. This explicit lock covers that gap.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JellyMusicLegacy:playback");
        wakeLock.setReferenceCounted(false);
        // Separate from the CPU wake lock above: the WiFi radio itself can power down on its own sleep
        // policy once the screen is off, independent of whether the CPU is awake. If that happens mid
        // track-transition, downloadThenPlay()'s HTTP request just hangs/fails with no CPU-side symptom at
        // all. FULL_HIGH_PERF keeps the radio fully active rather than letting it cycle into a power-save
        // state while something here actually needs it.
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "JellyMusicLegacy:playback");
        wifiLock.setReferenceCounted(false);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) play(intent);
        else if (ACTION_TOGGLE.equals(action)) toggle();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREVIOUS.equals(action)) previous();
        else if (ACTION_SEEK.equals(action) && prepared) { try{player.seekTo(intent.getIntExtra("position",0));broadcast(player.isPlaying(),false,null);}catch(Exception ignored){} }
        else if (ACTION_REPEAT.equals(action)) { repeatMode=(repeatMode+1)%3; broadcast(prepared&&player.isPlaying(), false, null); }
        else if (ACTION_SHUFFLE.equals(action)) { shuffleOn=!shuffleOn; if(shuffleOn) shuffleRemaining(); broadcast(prepared&&player.isPlaying(), false, null); }
        else if (ACTION_STOP.equals(action)) { reportCurrentStoppedAsync(); prepared=false; progressHandler.removeCallbacks(progressTick); try{player.stop();}catch(Exception ignored){} title=""; art=""; itemId=""; stopForeground(true); stopSelf(); broadcast(false, false, null); if(wakeLock.isHeld())wakeLock.release(); if(wifiLock.isHeld())wifiLock.release(); }
        return START_STICKY;
    }
    private void play(Intent intent) {
        try {
            ArrayList<String> urls=intent.getStringArrayListExtra("urls");ArrayList<String> titles=intent.getStringArrayListExtra("titles");ArrayList<String> arts=intent.getStringArrayListExtra("arts");ArrayList<String> ids=intent.getStringArrayListExtra("ids");
            queueUrls=urls!=null?urls:new ArrayList<String>();queueTitles=titles!=null?titles:new ArrayList<String>();queueArt=arts!=null?arts:new ArrayList<String>();queueIds=ids!=null?ids:new ArrayList<String>();
            if(queueUrls.size()==0){queueUrls.add(intent.getStringExtra("url"));queueTitles.add(intent.getStringExtra("title"));queueArt.add(intent.getStringExtra("art"));queueIds.add(intent.getStringExtra("id"));}
            queueIndex=intent.getIntExtra("startIndex",0);if(queueIndex<0||queueIndex>=queueUrls.size())queueIndex=0;
            playCurrent();
        } catch (Exception e) { broadcast(false, false, "Could not start playback: "+e.getMessage()); stopSelf(); }
    }
    private void playCurrent() throws Exception {
            wakeLock.acquire(60000); // refreshed every progressTick while playing; self-expires if something breaks
            if(!wifiLock.isHeld())wifiLock.acquire();
            android.util.Log.e("JMPlayback","playCurrent queueIndex="+queueIndex+"/"+queueUrls.size()+" repeatMode="+repeatMode);
            final int token=++playToken;
            title=queueTitles.get(queueIndex);String url=queueUrls.get(queueIndex);art=queueIndex<queueArt.size()?queueArt.get(queueIndex):"";itemId=queueIndex<queueIds.size()?queueIds.get(queueIndex):"";
            localPlayback=url.startsWith("file://");
            prepared=false; progressHandler.removeCallbacks(progressTick);
            player.reset(); player.setAudioStreamType(AudioManager.STREAM_MUSIC);
            notifyNow("Loading…"); broadcast(false, true, null);
            if (localPlayback) { setDataSourceSkippingId3(url.substring(7)); player.prepareAsync(); }
            else downloadThenPlay(url, token);
    }
    // The LG Optimus L5's circa-2012 Stagefright MP3 extractor can choke on ID3v2 tags (confirmed with
    // ID3v2.4 tags from Jellyfin's transcoder: MediaPlayer error 1/-2147483648 even on a byte-perfect,
    // valid MPEG file). Skip straight to the first audio frame so the device never has to parse the tag.
    private void setDataSourceSkippingId3(String path) throws Exception {
        File file = new File(path);
        long offset = 0;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
        try {
            if (raf.length() >= 10) {
                byte[] h = new byte[10]; raf.readFully(h);
                if (h[0]=='I' && h[1]=='D' && h[2]=='3') {
                    boolean footer = (h[5] & 0x10) != 0;
                    long size = ((h[6]&0x7f)<<21) | ((h[7]&0x7f)<<14) | ((h[8]&0x7f)<<7) | (h[9]&0x7f);
                    long total = 10 + size + (footer ? 10 : 0);
                    if (total > 0 && total < raf.length()) offset = total;
                }
            }
        } finally { raf.close(); }
        android.util.Log.e("JMPlayback","setDataSource "+path+" size="+file.length()+" id3Offset="+offset);
        if (offset <= 0) { player.setDataSource(path); return; }
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try { player.setDataSource(fis.getFD(), offset, file.length()-offset); } finally { fis.close(); }
    }
    private void downloadThenPlay(final String url, final int token) {
        android.util.Log.e("JMPlayback","downloadThenPlay starting, token="+token+" url="+url);
        new AsyncTask<Void,Void,Exception>(){
            File cacheFile;
            protected Exception doInBackground(Void...unused){try{cacheFile=new File(getCacheDir(),"current-playback-"+token+".mp3");new JellyfinApi(PlaybackService.this).downloadUrl(url,cacheFile);android.util.Log.e("JMPlayback","download finished, token="+token+" size="+(cacheFile!=null?cacheFile.length():-1));return null;}catch(Exception e){android.util.Log.e("JMPlayback","download threw, token="+token,e);return e;}}
            protected void onPostExecute(Exception error){
                if(token!=playToken){android.util.Log.e("JMPlayback","onPostExecute DISCARDED stale token="+token+" currentPlayToken="+playToken);if(cacheFile!=null)cacheFile.delete();return;}
                if(error!=null){android.util.Log.e("JMPlayback","download failed",error);broadcast(false,false,"Could not load song: "+error.getMessage());return;}
                try{
                    setDataSourceSkippingId3(cacheFile.getAbsolutePath());
                    // Safe to drop the file now: setDataSource(FileDescriptor,...) already dup'd its own fd
                    // (same guarantee setDataSourceSkippingId3 relies on to close its FileInputStream above),
                    // so the native player keeps reading fine after unlink. Without this, every track played
                    // in a session left its downloaded copy behind in the cache dir forever.
                    cacheFile.delete();
                    player.prepareAsync();
                    android.util.Log.e("JMPlayback","prepareAsync() called, token="+token);
                }catch(Exception e){android.util.Log.e("JMPlayback","setDataSource/prepareAsync threw",e);broadcast(false,false,"Could not play downloaded song: "+e.getMessage());}
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void toggle() { if (!prepared) return; if (player.isPlaying()) { player.pause(); notifyNow("Paused"); broadcast(false, false, null); reportProgressAsync(true); } else { player.start(); notifyNow("Playing"); broadcast(true, false, null); reportProgressAsync(false); } }
    private void next(){if(queueIndex+1<queueUrls.size()){reportCurrentStoppedAsync();queueIndex++;try{playCurrent();}catch(Exception e){broadcast(false,false,"Could not play next song");}}}
    private void previous(){if(queueIndex>0){reportCurrentStoppedAsync();queueIndex--;try{playCurrent();}catch(Exception e){broadcast(false,false,"Could not play previous song");}}else if(prepared){try{player.seekTo(0);}catch(Exception ignored){}}}
    // Shuffles only the not-yet-played tail of the queue (queueIndex+1..end), leaving history and the
    // currently playing track untouched -- matches how most players treat "shuffle" as reordering what's
    // coming up next, not jumping to a random track immediately.
    private void shuffleRemaining(){
        int lo=queueIndex+1, hi=queueUrls.size()-1; java.util.Random rnd=new java.util.Random();
        for(int i=hi;i>lo;i--){int j=lo+rnd.nextInt(i-lo+1);swapQueue(i,j);}
    }
    private void swapQueue(int i,int j){
        String u=queueUrls.get(i);queueUrls.set(i,queueUrls.get(j));queueUrls.set(j,u);
        String t=queueTitles.get(i);queueTitles.set(i,queueTitles.get(j));queueTitles.set(j,t);
        String a=queueArt.get(i);queueArt.set(i,queueArt.get(j));queueArt.set(j,a);
        String id=queueIds.get(i);queueIds.set(i,queueIds.get(j));queueIds.set(j,id);
    }
    @Override public void onPrepared(MediaPlayer mp) { android.util.Log.e("JMPlayback","onPrepared fired"); prepared=true; mp.start(); notifyNow("Playing"); progressHandler.removeCallbacks(progressTick);progressReportTick=0;progressHandler.post(progressTick);broadcast(true, false, null); reportPlayingAsync(); }
    @Override public void onCompletion(MediaPlayer mp) {
        android.util.Log.e("JMPlayback","onCompletion fired, queueIndex="+queueIndex+"/"+queueUrls.size()+" repeatMode="+repeatMode+" screenOn="+((PowerManager)getSystemService(POWER_SERVICE)).isScreenOn());
        reportCurrentStoppedAsync();
        prepared=false; progressHandler.removeCallbacks(progressTick);
        if(repeatMode==1){try{playCurrent();}catch(Exception e){broadcast(false,false,"Could not repeat song");}}
        else if(queueIndex+1<queueUrls.size()){queueIndex++;try{playCurrent();}catch(Exception e){broadcast(false,false,"Could not play next song");}}
        else if(repeatMode==2 && queueUrls.size()>0){queueIndex=0;try{playCurrent();}catch(Exception e){broadcast(false,false,"Could not restart queue");}}
        else{
            // Clear the now-stale track info: without this the mini-player bar kept showing the last
            // song's name with a "Play" button that silently did nothing (toggle() no-ops when !prepared),
            // a dead end once the queue ran out with repeat off.
            title=""; art=""; itemId="";
            stopForeground(true);broadcast(false,false,null);
            if(wakeLock.isHeld())wakeLock.release();
            if(wifiLock.isHeld())wifiLock.release();
        }
    }
    // Fire-and-forget Jellyfin session reporting: lets the server's own dashboard show a live "Now
    // Playing" card for this device (title, progress bar) the same way a browser session does, instead
    // of the stale "last active" timestamp the device list showed before this existed. A fresh
    // JellyfinApi per call (not a cached field) matches downloadThenPlay's existing pattern below and
    // avoids reporting with stale credentials if the user logs out/in again while this service is alive.
    private void reportPlayingAsync(){
        final String id=itemId; if(id.length()==0)return;
        final String method=localPlayback?"DirectPlay":"Transcode";
        final JellyfinApi reportApi=new JellyfinApi(this);
        new Thread(){public void run(){try{reportApi.reportPlaying(id,method);}catch(Exception e){android.util.Log.e("JMPlayback","reportPlaying failed",e);}}}.start();
    }
    private void reportProgressAsync(final boolean paused){
        final String id=itemId; if(id.length()==0||!prepared)return;
        final long ticks; try{ticks=((long)player.getCurrentPosition())*10000L;}catch(Exception e){return;}
        final String method=localPlayback?"DirectPlay":"Transcode";
        final JellyfinApi reportApi=new JellyfinApi(this);
        new Thread(){public void run(){try{reportApi.reportProgress(id,ticks,paused,method);}catch(Exception e){android.util.Log.e("JMPlayback","reportProgress failed",e);}}}.start();
    }
    private void reportCurrentStoppedAsync(){
        final String id=itemId; if(id.length()==0)return;
        long posMs=0; try{if(prepared)posMs=player.getCurrentPosition();}catch(Exception ignored){}
        final long ticks=posMs*10000L;
        final JellyfinApi reportApi=new JellyfinApi(this);
        new Thread(){public void run(){try{reportApi.reportStopped(id,ticks);}catch(Exception e){android.util.Log.e("JMPlayback","reportStopped failed",e);}}}.start();
    }
    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
        // A MediaPlayer in the Error state only accepts reset()/release(); calling anything else (even
        // getCurrentPosition()/getDuration() from broadcast()) re-triggers this same callback and can spin
        // forever. Reset the player and mark it unprepared *before* broadcasting, so broadcast()'s guarded
        // position/duration lookup can't touch it while it's still errored.
        android.util.Log.e("JMPlayback","onError what="+what+" extra="+extra);
        // If this track was already prepared (reported "Playing" to the server) and then errors out
        // mid-stream rather than during the initial load, the dashboard's session card would otherwise
        // stay stuck showing it as playing forever.
        if(prepared)reportCurrentStoppedAsync();
        prepared=false; progressHandler.removeCallbacks(progressTick);
        try{player.reset();player.setAudioStreamType(AudioManager.STREAM_MUSIC);}catch(Exception ignored){}
        broadcast(false, false, "Playback error "+what+"/"+extra);
        if(wakeLock.isHeld())wakeLock.release();
        if(wifiLock.isHeld())wifiLock.release();
        return true;
    }
    // Custom RemoteViews instead of Notification.MediaStyle: MediaStyle (and Builder.addAction) only
    // exist from API 16/21 onward and would throw NoSuchMethodError on this API 15 device at runtime
    // even though they'd compile fine against the newer SDK this project builds against. RemoteViews
    // with setOnClickPendingIntent has worked since API 3, so it's the actually-compatible way to put
    // basic prev/play-pause/next controls on the notification here.
    private void notifyNow(String status) {
        Intent open = new Intent(this, MainActivity.class); PendingIntent openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT);
        boolean playing = prepared && player.isPlaying();
        // Foreground except while genuinely user-paused -- NOT the same condition as `playing`. The old
        // version tied foreground status to `playing`, which is also false during "Loading...", the brief
        // window between one track ending and the next one being downloaded+prepared. That's exactly when
        // a real background network fetch (downloadThenPlay's AsyncTask) is in flight, often with the
        // screen off -- dropping out of foreground right there left the service looking like an idle
        // background process doing network I/O, which the OS (and LG's own aggressive background-process
        // management on this era of device) is most likely to stall or kill, silently breaking auto-advance.
        boolean keepForeground = !"Paused".equals(status);
        android.util.Log.e("JMPlayback","notifyNow status="+status+" keepForeground="+keepForeground);
        RemoteViews views = new RemoteViews(getPackageName(), R.layout.notification_controls);
        views.setTextViewText(R.id.notif_title, title);
        views.setImageViewResource(R.id.notif_playpause, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        views.setOnClickPendingIntent(R.id.notif_prev, servicePendingIntent(ACTION_PREVIOUS, 1));
        views.setOnClickPendingIntent(R.id.notif_playpause, servicePendingIntent(ACTION_TOGGLE, 2));
        views.setOnClickPendingIntent(R.id.notif_next, servicePendingIntent(ACTION_NEXT, 3));
        Notification n = new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(openPi).setOngoing(keepForeground).setContent(views).getNotification();
        if (keepForeground) startForeground(7, n);
        else { stopForeground(false); ((android.app.NotificationManager)getSystemService(android.content.Context.NOTIFICATION_SERVICE)).notify(7, n); }
    }
    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent i = new Intent(this, PlaybackService.class); i.setAction(action);
        return PendingIntent.getService(this, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void broadcast(boolean playing, boolean loading, String error) { Intent i = new Intent(ACTION_STATE); i.putExtra("playing", playing); i.putExtra("loading", loading); i.putExtra("title", title); i.putExtra("art", art); i.putExtra("itemId", itemId); i.putExtra("repeatMode", repeatMode); i.putExtra("shuffle", shuffleOn); i.putExtra("error",error); if(prepared){try{i.putExtra("position",player.getCurrentPosition());i.putExtra("duration",player.getDuration());}catch(Exception ignored){}} sendBroadcast(i); }
    @Override public void onDestroy() { reportCurrentStoppedAsync(); progressHandler.removeCallbacks(progressTick);player.release(); if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release(); if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
