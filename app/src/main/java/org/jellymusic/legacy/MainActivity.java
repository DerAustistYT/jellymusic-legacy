package org.jellymusic.legacy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.SeekBar;
import android.widget.ImageView;
import java.util.ArrayList;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONObject;

public class MainActivity extends Activity {
    // Stock Ice Cream Sandwich/Holo colours, sized around the LG Optimus L5's 320x480 mdpi panel.
    // Every AsyncTask below runs via executeOnExecutor(THREAD_POOL_EXECUTOR) rather than plain execute():
    // on this API level, execute() funnels every AsyncTask in the whole app through one shared serial
    // thread, so without this a background playback download would stall library browsing/search/thumbnail
    // loading (and vice versa) until it finished, instead of them running independently.
    private static final int BG=Color.rgb(0,0,0), PANEL=Color.rgb(24,24,24), ACCENT=Color.rgb(51,181,229), MUTED=Color.rgb(170,170,170);
    private JellyfinApi api; private Handler handler=new Handler(); private LinearLayout root,content; private TextView title,nowPlaying,playerTitle,playerTime; private Button playButton,fullPlayButton,repeatButton,shuffleButton; private SeekBar nowSeek; private ImageView nowPlayingArt; private Runnable quickPoll; private ImageLoader images;private String currentTrack="Nothing playing",currentArt="",currentItemId="";private boolean currentlyPlaying=false,seekTouching=false,shuffleOn=false,favoriteOn=false;private int repeatMode=0;private Button favoriteButton;
    private Runnable currentReload; private View prevHint,prevListContainer; private ListView prevList; private MediaAdapter prevAdapter;
    private interface PageFetcher { List<MediaItem> fetch(int startIndex,int limit) throws Exception; }
    private interface LetterFetcher { PageFetcher forLetter(String letter); }
    private static final int PAGE_SIZE=300;
    private PageFetcher pageFetcher; private LetterFetcher pageLetterFetcher; private boolean pageClearFirst; private int pageNext; private TextView pageFooter; private int pageToken;
    private final BroadcastReceiver playbackReceiver=new BroadcastReceiver(){ public void onReceive(Context c,Intent i){
        currentTrack=i.getStringExtra("title");if(currentTrack==null||currentTrack.length()==0)currentTrack="Nothing playing";currentlyPlaying=i.getBooleanExtra("playing",false);
        String art=i.getStringExtra("art");if(art==null)art="";
        if(!art.equals(currentArt)){currentArt=art;if(nowPlayingArt!=null){if(currentArt.length()>0)images.load(currentArt,nowPlayingArt);else nowPlayingArt.setImageResource(android.R.drawable.ic_media_play);}}
        String itemId=i.getStringExtra("itemId");if(itemId==null)itemId="";
        if(!itemId.equals(currentItemId)){currentItemId=itemId;favoriteOn=false;if(favoriteButton!=null)updateFavoriteButton();}
        repeatMode=i.getIntExtra("repeatMode",repeatMode);if(repeatButton!=null){repeatButton.setText(repeatMode==1?"Repeat 1":repeatMode==2?"Repeat All":"Repeat");repeatButton.setTextColor(repeatMode!=0?ACCENT:MUTED);}
        shuffleOn=i.getBooleanExtra("shuffle",shuffleOn);if(shuffleButton!=null)shuffleButton.setTextColor(shuffleOn?ACCENT:MUTED);
        if(nowPlaying!=null)nowPlaying.setText(currentTrack);if(playButton!=null)playButton.setText(currentlyPlaying?"Pause":"Play");if(playerTitle!=null)playerTitle.setText(currentTrack);if(fullPlayButton!=null)fullPlayButton.setText(currentlyPlaying?"Pause":"Play");
        int duration=i.getIntExtra("duration",0),position=i.getIntExtra("position",0);if(nowSeek!=null&&!seekTouching){nowSeek.setMax(Math.max(duration,1));nowSeek.setProgress(position);}if(playerTime!=null)playerTime.setText(formatTime(position)+" / "+formatTime(duration));
        String e=i.getStringExtra("error");if(e!=null)Toast.makeText(MainActivity.this,e,Toast.LENGTH_LONG).show();
    } };
    private int dp(float n){return(int)(n*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle b){
        super.onCreate(b); api=new JellyfinApi(this); images=new ImageLoader(api); showShell();
        if(!api.hasSession())showLogin();
        else if(isOnline())showLibrary();
        else{Toast.makeText(this,"No internet connection — showing offline songs",Toast.LENGTH_LONG).show();showOffline();}
    }
    @Override protected void onResume(){super.onResume();registerReceiver(playbackReceiver,new IntentFilter(PlaybackService.ACTION_STATE));}
    @Override protected void onPause(){super.onPause();try{unregisterReceiver(playbackReceiver);}catch(Exception ignored){}}
    @Override protected void onDestroy(){if(quickPoll!=null)handler.removeCallbacks(quickPoll);super.onDestroy();}

    private void showShell(){
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(14),dp(8),dp(10),dp(8)); bar.setBackgroundColor(PANEL);
        title=text("JellyMusic",21,Color.WHITE,true); bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        Button overflow=button("⋮"); overflow.setOnClickListener(new View.OnClickListener(){public void onClick(View v){showMenu();}}); bar.addView(overflow,new LinearLayout.LayoutParams(dp(48),dp(44))); root.addView(bar);
        content=new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout player=new LinearLayout(this); player.setGravity(Gravity.CENTER_VERTICAL); player.setPadding(dp(10),dp(3),dp(5),dp(3)); player.setBackgroundColor(Color.rgb(30,30,30)); nowPlaying=text("Nothing playing",13,Color.WHITE,true);nowPlaying.setOnClickListener(new View.OnClickListener(){public void onClick(View v){showNowPlaying();}}); player.addView(nowPlaying,new LinearLayout.LayoutParams(0,dp(42),1)); playButton=button("Play"); playButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendPlayerAction(PlaybackService.ACTION_TOGGLE);}}); player.addView(playButton,new LinearLayout.LayoutParams(dp(68),dp(40))); root.addView(player); setContentView(root);
    }
    private TextView text(String s,float size,int color,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setGravity(Gravity.CENTER_VERTICAL);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextColor(Color.WHITE);b.setTextSize(14);return b;}
    private EditText input(String hint){EditText e=new EditText(this);e.setHint(hint);e.setHintTextColor(MUTED);e.setTextColor(Color.WHITE);e.setSingleLine(true);e.setPadding(dp(10),0,dp(10),0);return e;}
    private void clear(String heading){content.removeAllViews();prevHint=null;prevList=null;title.setText(heading);}
    private void sendPlayerAction(String action){Intent i=new Intent(this,PlaybackService.class);i.setAction(action);startService(i);}
    private String formatTime(int ms){int total=Math.max(ms,0)/1000;return String.format("%d:%02d",total/60,total%60);}
    private void showNowPlaying(){
        clear("Now Playing");LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(12),dp(8),dp(12),dp(4));
        nowPlayingArt=new ImageView(this);nowPlayingArt.setBackgroundColor(PANEL);nowPlayingArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if(currentArt.length()>0)images.load(currentArt,nowPlayingArt);
        box.addView(nowPlayingArt,new LinearLayout.LayoutParams(dp(150),dp(150)));
        LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);
        playerTitle=text(currentTrack,17,Color.WHITE,true);playerTitle.setGravity(Gravity.CENTER);titleRow.addView(playerTitle,new LinearLayout.LayoutParams(0,dp(48),1));
        favoriteButton=button("♡");favoriteButton.setTextSize(20);favoriteButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){toggleFavorite();}});
        updateFavoriteButton();titleRow.addView(favoriteButton,new LinearLayout.LayoutParams(dp(48),dp(48)));
        box.addView(titleRow,new LinearLayout.LayoutParams(-1,dp(48)));
        nowSeek=new SeekBar(this);nowSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){seekTouching=true;}public void onProgressChanged(SeekBar s,int p,boolean from){if(from&&playerTime!=null)playerTime.setText(formatTime(p));}public void onStopTrackingTouch(SeekBar s){seekTouching=false;Intent i=new Intent(MainActivity.this,PlaybackService.class);i.setAction(PlaybackService.ACTION_SEEK);i.putExtra("position",s.getProgress());startService(i);}});box.addView(nowSeek,new LinearLayout.LayoutParams(-1,dp(38)));
        playerTime=text("0:00 / 0:00",12,MUTED,false);playerTime.setGravity(Gravity.CENTER);box.addView(playerTime,new LinearLayout.LayoutParams(-1,dp(24)));
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);
        Button prev=button("Prev");prev.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendPlayerAction(PlaybackService.ACTION_PREVIOUS);}});
        fullPlayButton=button(currentlyPlaying?"Pause":"Play");fullPlayButton.setTextColor(ACCENT);fullPlayButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendPlayerAction(PlaybackService.ACTION_TOGGLE);}});
        Button next=button("Next");next.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendPlayerAction(PlaybackService.ACTION_NEXT);}});
        shuffleButton=button("Shuffle");shuffleButton.setTextColor(shuffleOn?ACCENT:MUTED);shuffleButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendPlayerAction(PlaybackService.ACTION_SHUFFLE);}});
        repeatButton=button(repeatMode==1?"Repeat 1":repeatMode==2?"Repeat All":"Repeat");repeatButton.setTextColor(repeatMode!=0?ACCENT:MUTED);repeatButton.setOnClickListener(new View.OnClickListener(){public void onClick(View v){sendPlayerAction(PlaybackService.ACTION_REPEAT);}});
        controls.addView(prev,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(fullPlayButton,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(shuffleButton,new LinearLayout.LayoutParams(0,dp(48),1));controls.addView(repeatButton,new LinearLayout.LayoutParams(0,dp(48),1));
        box.addView(controls,new LinearLayout.LayoutParams(-1,dp(50)));content.addView(box,new LinearLayout.LayoutParams(-1,-1));
    }
    private void updateFavoriteButton(){favoriteButton.setText(favoriteOn?"♥":"♡");favoriteButton.setTextColor(favoriteOn?Color.rgb(224,64,64):MUTED);}
    // Only flips the local heart + fires the request: the queue doesn't carry each track's existing
    // IsFavorite state (would mean threading a 5th parallel list through PlaybackService's queue/broadcast
    // alongside url/title/art/id), so the heart always starts outline for a freshly opened track even if
    // it's already a favourite server-side — tapping it is still correct, it just won't show as pre-filled.
    private void toggleFavorite(){
        if(currentItemId.length()==0)return;
        favoriteOn=!favoriteOn;updateFavoriteButton();
        final boolean value=favoriteOn;final String id=currentItemId;
        new AsyncTask<Void,Void,Exception>(){
            protected Exception doInBackground(Void...x){try{api.setFavorite(id,value);return null;}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}
            protected void onPostExecute(Exception e){if(e!=null)error("Could not update favourite: "+e.getMessage());}
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void showLogin(){
        clear("Connect to Jellyfin"); LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(26),dp(18),dp(18));
        TextView lead=text("Your music, on your server.",22,Color.WHITE,true);box.addView(lead,new LinearLayout.LayoutParams(-1,dp(56)));
        TextView help=text("Enter the full HTTPS address of your Jellyfin server.",14,MUTED,false);box.addView(help,new LinearLayout.LayoutParams(-1,dp(52)));
        final EditText server=input("https://music.example.com");server.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);server.setText(api.getBaseUrl());box.addView(server,new LinearLayout.LayoutParams(-1,dp(54)));
        Button connect=button("Connect with Quick Connect");connect.setOnClickListener(new View.OnClickListener(){public void onClick(View v){api.setServer(server.getText().toString());beginQuickConnect();}});box.addView(connect,new LinearLayout.LayoutParams(-1,dp(58)));content.addView(box);
    }
    private void beginQuickConnect(){
        showLoading("Contacting server…");new AsyncTask<Void,Void,Object>(){protected Object doInBackground(Void...x){try{api.serverInfo();return api.beginQuickConnect();}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}protected void onPostExecute(Object o){if(o instanceof Exception){error(((Exception)o).getMessage());showLogin();return;}JSONObject q=(JSONObject)o;showQuickCode(q.optString("Code"),q.optString("Secret"));}}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void showQuickCode(final String code,final String secret){
        clear("Quick Connect");LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER_HORIZONTAL);box.setPadding(dp(18),dp(25),dp(18),dp(10));
        box.addView(text("Enter this code in Jellyfin",16,MUTED,false),new LinearLayout.LayoutParams(-1,dp(48)));TextView c=text(code,38,ACCENT,true);c.setGravity(Gravity.CENTER);box.addView(c,new LinearLayout.LayoutParams(-1,dp(80)));TextView step=text("On another device: Settings → Quick Connect",14,Color.WHITE,false);step.setGravity(Gravity.CENTER);box.addView(step,new LinearLayout.LayoutParams(-1,dp(58)));ProgressBar p=new ProgressBar(this);box.addView(p,new LinearLayout.LayoutParams(-1,dp(54)));content.addView(box);
        quickPoll=new Runnable(){public void run(){new AsyncTask<Void,Void,Object>(){protected Object doInBackground(Void...x){try{return api.quickConnectState(secret);}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}protected void onPostExecute(Object o){if(o instanceof JSONObject&&((JSONObject)o).optBoolean("Authenticated",false)){finishQuick(secret);}else if(content.getChildCount()>0)handler.postDelayed(quickPoll,5000);}}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);}};handler.postDelayed(quickPoll,2500);
    }
    private void finishQuick(final String secret){showLoading("Signing in…");new AsyncTask<Void,Void,Exception>(){protected Exception doInBackground(Void...x){try{api.finishQuickConnect(secret);return null;}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}protected void onPostExecute(Exception e){if(e!=null){error(e.getMessage());showLogin();}else showLibrary();}}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);}
    private void showLoading(String message){clear("JellyMusic");LinearLayout b=new LinearLayout(this);b.setGravity(Gravity.CENTER);b.setOrientation(LinearLayout.VERTICAL);ProgressBar p=new ProgressBar(this);b.addView(p,new LinearLayout.LayoutParams(dp(64),dp(64)));b.addView(text(message,15,MUTED,false),new LinearLayout.LayoutParams(-1,dp(54)));content.addView(b,new LinearLayout.LayoutParams(-1,-1));}
    private void showLibrary(){
        clear("Albums");final EditText search=input("Search songs");search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setOnEditorActionListener(new TextView.OnEditorActionListener(){public boolean onEditorAction(TextView v,int id,android.view.KeyEvent e){if(id==EditorInfo.IME_ACTION_SEARCH){runSearch(search.getText().toString());return true;}return false;}});content.addView(search,new LinearLayout.LayoutParams(-1,dp(48)));loadAlbums();
    }
    private void loadAlbums(){
        startPaged("Albums",
            new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.albums(s,l);}},
            new LetterFetcher(){public PageFetcher forLetter(final String letter){return new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.albumsFrom(letter,s,l);}};}},
            false, true);
    }
    private void runSearch(final String query){if(query.trim().length()==0){showLibrary();return;}currentReload=new Runnable(){public void run(){runSearch(query);}};showLoading("Searching…");new AsyncTask<Void,Void,Object>(){protected Object doInBackground(Void...x){try{return api.search(query);}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}protected void onPostExecute(Object o){if(o instanceof Exception){if(!handleIfOffline((Exception)o)){error(((Exception)o).getMessage());showLibrary();}}else{clear("Search results");showItems((List<MediaItem>)o,false);}}}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);}
    private void showItems(final List<MediaItem> items,final boolean albums){ showItems(items,albums,null,null); }
    private void showItems(final List<MediaItem> items,final boolean albums,final View footer){ showItems(items,albums,footer,null); }
    private void showItems(final List<MediaItem> items,final boolean albums,final View footer,final View alphabetStrip){
        if(prevListContainer!=null){content.removeView(prevHint);content.removeView(prevListContainer);prevHint=null;prevListContainer=null;prevList=null;prevAdapter=null;}
        if(items.size()==0){content.addView(text(albums?"No music albums found.":"No songs found.",16,MUTED,false),new LinearLayout.LayoutParams(-1,dp(80)));return;}
        final ListView list=new ListView(this);list.setDivider(new ColorDrawable(Color.rgb(45,47,57)));list.setDividerHeight(1);list.setCacheColorHint(BG);if(footer!=null)list.addFooterView(footer,null,false);final MediaAdapter adapter=new MediaAdapter(this,items,images);list.setAdapter(adapter);prevAdapter=adapter;prevList=list;list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?>p,View v,int pos,long id){MediaItem item=items.get(pos);if(item.kind!=null)openGroup(item);else if(item.album)openAlbum(item);else play(items,pos);}});
        final TextView hint=text("Pull down to refresh",12,MUTED,false);hint.setGravity(Gravity.CENTER);hint.setBackgroundColor(Color.rgb(20,20,20));hint.setLayoutParams(new LinearLayout.LayoutParams(-1,0));
        content.addView(hint);
        if(alphabetStrip!=null){
            LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
            row.addView(list,new LinearLayout.LayoutParams(0,-1,1));row.addView(alphabetStrip,new LinearLayout.LayoutParams(dp(22),-1));
            content.addView(row,new LinearLayout.LayoutParams(-1,0,1));prevListContainer=row;
        } else { content.addView(list,new LinearLayout.LayoutParams(-1,0,1)); prevListContainer=list; }
        prevHint=hint;attachPullToRefresh(list,hint,currentReload);
    }
    // Lets you drag/tap down the strip to jump straight to a letter instead of paging through "Load
    // more" dozens of times on an 11k+ song / 2.7k+ album library. heading/letterFetcher/clearFirst are
    // baked in per screen (see loadAlbums/loadSection) so the same strip code works for Songs, Albums
    // and Artists even though each needs a different underlying query and a different refresh shell
    // (Albums keeps its search box above the list; the others fully replace the screen).
    private View buildAlphabetStrip(final String heading,final LetterFetcher letterFetcher,final boolean clearFirst){
        final String letters="#ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        LinearLayout strip=new LinearLayout(this);strip.setOrientation(LinearLayout.VERTICAL);strip.setBackgroundColor(PANEL);
        for(int i=0;i<letters.length();i++){TextView t=text(String.valueOf(letters.charAt(i)),9,MUTED,false);t.setGravity(Gravity.CENTER);strip.addView(t,new LinearLayout.LayoutParams(-1,0,1));}
        // Press-hold-drag-release, not jump-per-letter-crossed: touching down shows a big magnified
        // letter so the tiny 9sp strip labels don't matter while scrubbing, and the server is only
        // queried once on release — not once per letter the finger passes over mid-drag.
        strip.setOnTouchListener(new View.OnTouchListener(){
            char current=0;
            public boolean onTouch(View v,android.view.MotionEvent e){
                int action=e.getActionMasked();
                if(action==android.view.MotionEvent.ACTION_DOWN||action==android.view.MotionEvent.ACTION_MOVE){
                    float ratio=e.getY()/Math.max(1,v.getHeight());ratio=Math.max(0f,Math.min(0.999f,ratio));
                    current=letters.charAt((int)(ratio*letters.length()));
                    showLetterPopup(current);
                    return true;
                }
                if(action==android.view.MotionEvent.ACTION_UP){
                    hideLetterPopup();
                    if(current!=0){String letter=current=='#'?null:String.valueOf(current);startPaged(heading,letterFetcher.forLetter(letter),letterFetcher,clearFirst);}
                    current=0;
                    return true;
                }
                if(action==android.view.MotionEvent.ACTION_CANCEL){hideLetterPopup();current=0;return true;}
                return false;
            }
        });
        return strip;
    }
    private TextView letterPopup;
    private void showLetterPopup(char c){
        if(letterPopup==null){
            letterPopup=new TextView(this);letterPopup.setTextColor(Color.WHITE);letterPopup.setTextSize(42);letterPopup.setTypeface(Typeface.DEFAULT,Typeface.BOLD);letterPopup.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(Color.argb(230,20,20,20));bg.setCornerRadius(dp(14));
            // setBackgroundDrawable, not the non-deprecated setBackground: setBackground is API16+ and
            // would throw NoSuchMethodError at runtime on this API15 device despite compiling fine.
            letterPopup.setBackgroundDrawable(bg);
            android.widget.FrameLayout.LayoutParams lp=new android.widget.FrameLayout.LayoutParams(dp(110),dp(110));lp.gravity=Gravity.CENTER;
            getWindow().addContentView(letterPopup,lp);
        }
        letterPopup.setText(String.valueOf(c));letterPopup.setVisibility(View.VISIBLE);
    }
    private void hideLetterPopup(){ if(letterPopup!=null)letterPopup.setVisibility(View.GONE); }
    private void attachPullToRefresh(final ListView list,final TextView hint,final Runnable reload){
        final int slop=dp(16),max=dp(44),threshold=dp(70);
        list.setOnTouchListener(new View.OnTouchListener(){
            float startY;boolean dragging;float lastH;
            public boolean onTouch(View v,android.view.MotionEvent e){
                switch(e.getActionMasked()){
                    case android.view.MotionEvent.ACTION_DOWN: startY=e.getRawY();dragging=false;return false;
                    case android.view.MotionEvent.ACTION_MOVE:{
                        float dy=e.getRawY()-startY;
                        boolean atTop=list.getChildCount()==0||(list.getFirstVisiblePosition()==0&&list.getChildAt(0).getTop()>=0);
                        if(!dragging){if(reload==null||dy<slop||!atTop)return false;dragging=true;}
                        lastH=Math.max(0,Math.min(dy-slop,max*2));
                        android.view.ViewGroup.LayoutParams lp=hint.getLayoutParams();lp.height=(int)Math.min(lastH,max);hint.setLayoutParams(lp);
                        hint.setText(lastH>=threshold?"Release to refresh":"Pull down to refresh");
                        return true;
                    }
                    case android.view.MotionEvent.ACTION_UP: case android.view.MotionEvent.ACTION_CANCEL:
                        if(dragging){boolean fire=lastH>=threshold;dragging=false;
                            if(fire){hint.setText("Refreshing…");reload.run();}
                            else{android.view.ViewGroup.LayoutParams lp=hint.getLayoutParams();lp.height=0;hint.setLayoutParams(lp);}
                            return true;
                        }
                        return false;
                    default: return false;
                }
            }
        });
    }
    // Fetches PAGE_SIZE items at a time instead of the whole section in one shot: a big library's
    // full JSON response is large enough to OutOfMemoryError on this device's small heap before it
    // even finishes parsing. A tap on the footer row pulls the next page into the same ListView.
    // letterFetcher!=null adds an alphabet-index strip alongside the list (null for e.g. an artist's own
    // song list, which is usually short enough not to need one). clearFirst=false is for Albums only:
    // that screen keeps its search box above the list, so loading must not wipe the whole content area
    // the way every other paged screen's showLoading()/clear() does.
    private void startPaged(final String heading,final PageFetcher fetcher){ startPaged(heading,fetcher,null,true,false); }
    private void startPaged(final String heading,final PageFetcher fetcher,final LetterFetcher letterFetcher,final boolean clearFirst){ startPaged(heading,fetcher,letterFetcher,clearFirst,false); }
    private void startPaged(final String heading,final PageFetcher fetcher,final LetterFetcher letterFetcher,final boolean clearFirst,final boolean albumsStyle){
        pageFetcher=fetcher;pageLetterFetcher=letterFetcher;pageClearFirst=clearFirst;pageNext=0;final int token=++pageToken;
        currentReload=new Runnable(){public void run(){startPaged(heading,fetcher,letterFetcher,clearFirst,albumsStyle);}};
        if(clearFirst)showLoading("Loading "+heading.toLowerCase()+"…");
        fetchPage(heading,token,albumsStyle);
    }
    // token pins each fetch to the paged "session" that started it (startPaged bumps pageToken; every
    // fetchPage call for that session, including later "Load more" taps, shares it). Without this, firing
    // several page-fetches close together — e.g. dragging the alphabet strip across a couple of letters
    // before settling — could let an older request's response land after a newer one, since they now run
    // truly in parallel (THREAD_POOL_EXECUTOR) instead of the old serial executor masking the race by
    // forcing in-order completion.
    private void fetchPage(final String heading,final int token,final boolean albumsStyle){
        final int start=pageNext;final boolean first=start==0;final boolean clearFirst=pageClearFirst;final LetterFetcher letterFetcher=pageLetterFetcher;
        new AsyncTask<Void,Void,Object>(){
            protected Object doInBackground(Void...x){try{return pageFetcher.fetch(start,PAGE_SIZE);}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}
            protected void onPostExecute(Object o){
                if(token!=pageToken)return;
                if(o instanceof Exception){if(!handleIfOffline((Exception)o)){error(((Exception)o).getMessage());if(first&&clearFirst)showLibrary();}return;}
                List<MediaItem> page=(List<MediaItem>)o;pageNext=start+page.size();boolean more=page.size()>=PAGE_SIZE;
                if(first){
                    pageFooter=text("Load more…",14,ACCENT,false);pageFooter.setGravity(Gravity.CENTER);pageFooter.setPadding(0,dp(16),0,dp(16));
                    pageFooter.setOnClickListener(new View.OnClickListener(){public void onClick(View v){pageFooter.setText("Loading…");pageFooter.setEnabled(false);fetchPage(heading,token,albumsStyle);}});
                    pageFooter.setVisibility(more?View.VISIBLE:View.GONE);
                    View strip=letterFetcher!=null?buildAlphabetStrip(heading,letterFetcher,clearFirst):null;
                    if(clearFirst)clear(heading);
                    showItems(page,albumsStyle,pageFooter,strip);
                } else {
                    if(prevAdapter!=null)prevAdapter.addAll(page);
                    if(pageFooter!=null){pageFooter.setText("Load more…");pageFooter.setEnabled(true);pageFooter.setVisibility(more?View.VISIBLE:View.GONE);}
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void openAlbum(final MediaItem album){currentReload=new Runnable(){public void run(){openAlbum(album);}};showLoading("Loading " + album.name + "…");new AsyncTask<Void,Void,Object>(){protected Object doInBackground(Void...x){try{return api.tracks(album.id);}catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}}protected void onPostExecute(Object o){if(o instanceof Exception){if(!handleIfOffline((Exception)o)){error(((Exception)o).getMessage());showLibrary();}}else{clear(album.name);showItems((List<MediaItem>)o,false);}}}.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);}
    private void openGroup(final MediaItem group){
        startPaged(group.name,
            new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.songsForGroup(group,s,l);}},
            new LetterFetcher(){public PageFetcher forLetter(final String letter){return new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.songsForGroupFrom(group,letter,s,l);}};}},
            true);
    }
    // Queues the WHOLE visible list starting at the tapped song, not just that one song: onCompletion()
    // in PlaybackService only advances if there's a next entry in the queue, and a single-song queue
    // meant playback always just stopped after each track instead of moving on to the next one.
    private void play(List<MediaItem> items, int index){
        ArrayList<String> urls=new ArrayList<String>(),titles=new ArrayList<String>(),arts=new ArrayList<String>(),ids=new ArrayList<String>();
        for(MediaItem it:items){urls.add(it.localPath!=null?"file://"+it.localPath:api.streamUrl(it.id));titles.add(it.name);arts.add(api.imageUrl(it,300));ids.add(it.id);}
        Intent i=new Intent(this,PlaybackService.class);i.setAction(PlaybackService.ACTION_PLAY);
        i.putStringArrayListExtra("urls",urls);i.putStringArrayListExtra("titles",titles);i.putStringArrayListExtra("arts",arts);i.putStringArrayListExtra("ids",ids);i.putExtra("startIndex",index);
        startService(i);
        MediaItem item=items.get(index);nowPlaying.setText(item.name);playButton.setText("…");
    }
    @Override public void onBackPressed(){if(api.hasSession()&&!"Albums".equals(title.getText().toString()))showLibrary();else super.onBackPressed();}
    @Override public boolean onKeyDown(int keyCode,KeyEvent event){if(keyCode==KeyEvent.KEYCODE_MENU){showMenu();return true;}return super.onKeyDown(keyCode,event);}
    private void showMenu(){final String[] options=api.hasSession()?new String[]{"Albums","Songs","Favorite songs","Artists","Genres","Shuffle all songs","Download favorites","Offline songs","Storage location","Log out","About"}:new String[]{"Offline songs","Storage location","About"};new AlertDialog.Builder(this).setItems(options,new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){String s=options[which];if("Albums".equals(s))showLibrary();else if("Songs".equals(s))loadSection("Songs",0);else if("Favorite songs".equals(s))loadFavorites();else if("Artists".equals(s))loadSection("Artists",1);else if("Genres".equals(s))loadSection("Genres",2);else if("Shuffle all songs".equals(s))shuffleAll();else if("Download favorites".equals(s))downloadFavorites();else if("Offline songs".equals(s))showOffline();else if("Storage location".equals(s))chooseStorage();else if("Log out".equals(s)){api.logout();showLogin();}else new AlertDialog.Builder(MainActivity.this).setTitle("JellyMusic Legacy").setMessage("A small Jellyfin music player for Android 4.0.3+\n\nModern HTTPS • Quick Connect • Offline favorites • Background playback").setPositiveButton("OK",null).show();}}).show();}
    private void loadFavorites(){
        startPaged("Favorite songs",
            new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.favoriteSongs(s,l);}},
            new LetterFetcher(){public PageFetcher forLetter(final String letter){return new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.favoriteSongsFrom(letter,s,l);}};}},
            true);
    }
    private void loadSection(final String heading,final int type){
        if(type==0){
            startPaged(heading,
                new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.allSongs(s,l);}},
                new LetterFetcher(){public PageFetcher forLetter(final String letter){return new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.allSongsFrom(letter,s,l);}};}},
                true);
            return;
        }
        if(type==1){
            startPaged(heading,
                new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.artists(s,l);}},
                new LetterFetcher(){public PageFetcher forLetter(final String letter){return new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.artistsFrom(letter,s,l);}};}},
                true, true);
            return;
        }
        startPaged(heading,
            new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.genres(s,l);}},
            new LetterFetcher(){public PageFetcher forLetter(final String letter){return new PageFetcher(){public List<MediaItem> fetch(int s,int l)throws Exception{return api.genresFrom(letter,s,l);}};}},
            true, true);
    }
    // Walks the library a page at a time (same OOM reason as startPaged) and keeps only the cheap
    // url+title pair per song, not the full MediaItem/JSON, so a ~12k-song library stays well under
    // the heap limit that broke the old single-shot api.allSongs() call here.
    private void shuffleAll(){showLoading("Shuffling your library…");new AsyncTask<Void,Void,Object>(){
        protected Object doInBackground(Void...x){
            try{
                ArrayList<String> urls=new ArrayList<String>(),titles=new ArrayList<String>(),arts=new ArrayList<String>(),ids=new ArrayList<String>();
                int start=0;
                while(true){
                    List<MediaItem> page=api.allSongs(start,500);
                    for(MediaItem song:page){urls.add(api.streamUrl(song.id));titles.add(song.name);arts.add(api.imageUrl(song,300));ids.add(song.id);}
                    if(page.size()<500)break;
                    start+=page.size();
                }
                java.util.Random rnd=new java.util.Random();
                for(int i=urls.size()-1;i>0;i--){int j=rnd.nextInt(i+1);String tu=urls.get(i);urls.set(i,urls.get(j));urls.set(j,tu);String tt=titles.get(i);titles.set(i,titles.get(j));titles.set(j,tt);String ta=arts.get(i);arts.set(i,arts.get(j));arts.set(j,ta);String tid=ids.get(i);ids.set(i,ids.get(j));ids.set(j,tid);}
                return new Object[]{urls,titles,arts,ids};
            }catch(Throwable e){return (e instanceof Exception)?(Exception)e:new Exception(e);}
        }
        protected void onPostExecute(Object o){
            if(o instanceof Exception){if(!handleIfOffline((Exception)o)){error(((Exception)o).getMessage());showLibrary();}return;}
            Object[] r=(Object[])o;ArrayList<String> urls=(ArrayList<String>)r[0],titles=(ArrayList<String>)r[1],arts=(ArrayList<String>)r[2],ids=(ArrayList<String>)r[3];
            if(urls.size()==0){error("No songs found");showLibrary();return;}
            Intent i=new Intent(MainActivity.this,PlaybackService.class);i.setAction(PlaybackService.ACTION_PLAY);i.putStringArrayListExtra("urls",urls);i.putStringArrayListExtra("titles",titles);i.putStringArrayListExtra("arts",arts);i.putStringArrayListExtra("ids",ids);startService(i);showLibrary();
        }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);}
    private void chooseStorage(){
        final String internal="Internal storage";final String sd=OfflineStore.hasRemovableStorage()?"SD card":"SD card (not detected)";int selected=OfflineStore.usesRemovable(this)?1:0;
        new AlertDialog.Builder(this).setTitle("Download storage\n"+OfflineStore.storageLabel(this)).setSingleChoiceItems(new String[]{internal,sd},selected,new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int which){if(which==1&&!OfflineStore.hasRemovableStorage()){Toast.makeText(MainActivity.this,"No removable SD card detected",Toast.LENGTH_LONG).show();return;}OfflineStore.setUseRemovable(MainActivity.this,which==1);Toast.makeText(MainActivity.this,"Future downloads: "+OfflineStore.storageLabel(MainActivity.this),Toast.LENGTH_LONG).show();dialog.dismiss();}}).setNegativeButton("Cancel",null).show();
    }
    private void showOffline(){currentReload=new Runnable(){public void run(){showOffline();}};clear("Offline songs");List<MediaItem> items=OfflineStore.load(this);showItems(items,false);}
    private void downloadFavorites(){
        showLoading("Finding favorite songs…");
        new AsyncTask<Void,String,String>(){
            protected String doInBackground(Void...x){
                int downloaded=0,skipped=0,failed=0;
                try{
                    List<MediaItem> favorites=api.favoriteSongs();
                    List<MediaItem> existing=OfflineStore.load(MainActivity.this);LinkedHashMap<String,MediaItem> all=new LinkedHashMap<String,MediaItem>();for(MediaItem m:existing)all.put(m.id,m);
                    // Saving writes the WHOLE offline index (re-serialized to JSON) to disk, so doing it
                    // after every single song turns a big favorites batch into O(n^2) work plus one disk
                    // write per song. Flushing every 10 songs, plus once more at the end for the remainder,
                    // keeps most of the crash-resilience of saving often without the quadratic blowup.
                    int index=0;for(MediaItem song:favorites){index++;File finalFile=OfflineStore.file(MainActivity.this,song.id);if(finalFile.exists()&&finalFile.length()>0){skipped++;continue;}publishProgress("Downloading "+index+" of "+favorites.size()+"\n"+song.name);File temp=new File(OfflineStore.directory(MainActivity.this),song.id+".part");try{api.download(song,temp);if(!temp.renameTo(finalFile))throw new Exception("Could not finalize file");song.localPath=finalFile.getAbsolutePath();all.put(song.id,song);downloaded++;if(downloaded%10==0)OfflineStore.save(MainActivity.this,new ArrayList<MediaItem>(all.values()));}catch(Exception e){failed++;}}
                    OfflineStore.save(MainActivity.this,new ArrayList<MediaItem>(all.values()));
                    return downloaded+" downloaded, "+skipped+" already offline"+(failed>0?", "+failed+" failed":"");
                }catch(Exception e){return "Download failed: "+e.getMessage();}
            }
            protected void onProgressUpdate(String...message){showLoading(message[0]);}
            protected void onPostExecute(String result){Toast.makeText(MainActivity.this,result,Toast.LENGTH_LONG).show();showOffline();}
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
    private void error(String m){Toast.makeText(this,m==null?"Something went wrong":m,Toast.LENGTH_LONG).show();}
    private boolean isOnline(){
        try{
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni=cm.getActiveNetworkInfo();
            return ni!=null&&ni.isConnected();
        }catch(Exception e){return true;}
    }
    private boolean isConnectivityException(Throwable e){
        while(e!=null){
            if(e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException||e instanceof java.net.SocketTimeoutException||e instanceof java.net.NoRouteToHostException)return true;
            e=e.getCause();
        }
        return false;
    }
    // Distinguishes "the network is actually down" from "connected but this request failed for some
    // other reason" (bad auth, server error) — only the former silently drops into Offline songs
    // instead of the usual error toast, so real server/app errors stay visible rather than being
    // masked behind a generic "offline" state.
    private boolean handleIfOffline(Exception e){
        if(!isOnline()||isConnectivityException(e)){
            Toast.makeText(this,"No internet connection — showing offline songs",Toast.LENGTH_LONG).show();
            showOffline();
            return true;
        }
        return false;
    }
}
