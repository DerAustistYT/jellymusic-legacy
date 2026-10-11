package org.jellymusic.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class JellyfinApi {
    private static final String APP = "JellyMusic Legacy";
    private static final String VERSION = "1.0.0";
    private final Context context;
    private final SharedPreferences prefs;
    private String baseUrl;
    private String token;
    private String userId;
    private final String deviceId;

    public JellyfinApi(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("session", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("server", "");
        token = prefs.getString("token", "");
        userId = prefs.getString("user", "");
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceId = TextUtils.isEmpty(id) ? "legacy-android-device" : id;
    }

    public boolean hasSession() { return !TextUtils.isEmpty(baseUrl) && !TextUtils.isEmpty(token) && !TextUtils.isEmpty(userId); }
    public String getBaseUrl() { return baseUrl; }
    public String getToken() { return token; }
    public String getUserId() { return userId; }

    public void setServer(String value) {
        String url = value == null ? "" : value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!(url.startsWith("https://") || url.startsWith("http://"))) url = "https://" + url;
        baseUrl = url;
        prefs.edit().putString("server", baseUrl).apply();
    }

    public void logout() {
        token = ""; userId = "";
        prefs.edit().remove("token").remove("user").apply();
    }

    private String authorization(boolean includeToken) {
        String device = Build.MANUFACTURER + " " + Build.MODEL;
        String value = "MediaBrowser Client=\"" + APP + "\", Device=\"" + clean(device) + "\", DeviceId=\"" + clean(deviceId) + "\", Version=\"" + VERSION + "\"";
        if (includeToken && !TextUtils.isEmpty(token)) value += ", Token=\"" + clean(token) + "\"";
        return value;
    }

    private String clean(String value) { return value == null ? "" : value.replace("\\", "").replace("\"", ""); }

    private JSONObject json(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(20000); c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", authorization(authenticated));
        c.setRequestProperty("X-Emby-Authorization", authorization(authenticated));
        if (authenticated && !TextUtils.isEmpty(token)) c.setRequestProperty("X-Emby-Token", token);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream out = c.getOutputStream(); out.write(body.toString().getBytes("UTF-8")); out.close();
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = readText(stream, c.getContentLength());
        c.disconnect();
        if (code < 200 || code >= 300) throw new Exception("Server returned " + code + (text.length() > 0 ? ": " + text : ""));
        return text.length() == 0 ? new JSONObject() : new JSONObject(text);
    }

    private String readText(InputStream stream) throws Exception { return readText(stream, -1); }
    private String readText(InputStream stream, int sizeHint) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        // A size hint (Content-Length, when the server sends one uncompressed) avoids StringBuilder's
        // repeated reallocate-and-copy growth on the larger responses here, like a 300-song page of Items.
        StringBuilder result = new StringBuilder(sizeHint > 0 ? sizeHint : 16); String line;
        while ((line = reader.readLine()) != null) result.append(line);
        reader.close(); return result.toString();
    }

    public JSONObject serverInfo() throws Exception { return json("GET", "/System/Info/Public", null, false); }
    public JSONObject beginQuickConnect() throws Exception { return json("POST", "/QuickConnect/Initiate", new JSONObject(), false); }
    public JSONObject quickConnectState(String secret) throws Exception { return json("GET", "/QuickConnect/Connect?secret=" + enc(secret), null, false); }
    public void finishQuickConnect(String secret) throws Exception {
        JSONObject body = new JSONObject(); body.put("Secret", secret);
        JSONObject result = json("POST", "/Users/AuthenticateWithQuickConnect", body, false);
        token = result.optString("AccessToken", "");
        JSONObject user = result.optJSONObject("User");
        userId = user == null ? "" : user.optString("Id", "");
        if (TextUtils.isEmpty(token) || TextUtils.isEmpty(userId)) throw new Exception("Server approved the code but returned no session token.");
        prefs.edit().putString("token", token).putString("user", userId).apply();
    }

    // Was a one-shot Limit=500 fetch, silently truncating a 2,705-album library to its first 500 (by
    // sort order) with no way to reach the rest from the Albums tab. Paged like allSongs/allSongsFrom.
    public List<MediaItem> albums(int startIndex, int limit) throws Exception { return albumsFrom(null, startIndex, limit); }
    public List<MediaItem> albumsFrom(String letter, int startIndex, int limit) throws Exception {
        String filter = (letter == null || letter.length() == 0) ? "" : "&NameStartsWithOrGreater=" + enc(letter);
        String path = "/Users/" + enc(userId) + "/Items?Recursive=true&IncludeItemTypes=MusicAlbum&SortBy=SortName&SortOrder=Ascending&Fields=PrimaryImageAspectRatio&StartIndex=" + startIndex + "&Limit=" + limit + filter;
        return parseItems(json("GET", path, null, true).optJSONArray("Items"), true);
    }

    public List<MediaItem> tracks(String albumId) throws Exception {
        String path = "/Users/" + enc(userId) + "/Items?ParentId=" + enc(albumId) + "&Recursive=true&IncludeItemTypes=Audio&SortBy=ParentIndexNumber,IndexNumber,SortName&SortOrder=Ascending&Fields=Album,Artists,RunTimeTicks&Limit=1000";
        return parseItems(json("GET", path, null, true).optJSONArray("Items"), false);
    }

    public List<MediaItem> search(String query) throws Exception {
        String path = "/Users/" + enc(userId) + "/Items?SearchTerm=" + enc(query) + "&Recursive=true&IncludeItemTypes=Audio&SortBy=SortName&Fields=Album,Artists,RunTimeTicks&Limit=100";
        return parseItems(json("GET", path, null, true).optJSONArray("Items"), false);
    }

    // Paged: a full library can be tens of thousands of tracks, and buffering that whole JSON
    // response as one String (readText) was enough to OutOfMemoryError on the Optimus L5's ~24MB
    // heap even before parsing it. Callers fetch one bounded page at a time instead.
    public List<MediaItem> allSongs(int startIndex, int limit) throws Exception { return allSongsFrom(null, startIndex, limit); }
    // NameStartsWithOrGreater jumps the sorted result set straight to a letter server-side, so the
    // alphabet index doesn't need the whole (possibly tens-of-thousands-long) library loaded first.
    public List<MediaItem> allSongsFrom(String letter, int startIndex, int limit) throws Exception {
        String filter = (letter == null || letter.length() == 0) ? "" : "&NameStartsWithOrGreater=" + enc(letter);
        String path = "/Users/" + enc(userId) + "/Items?Recursive=true&IncludeItemTypes=Audio&SortBy=SortName&SortOrder=Ascending&Fields=Album,Artists,RunTimeTicks&StartIndex=" + startIndex + "&Limit=" + limit + filter;
        return parseItems(json("GET", path, null, true).optJSONArray("Items"), false);
    }

    public List<MediaItem> artists(int startIndex, int limit) throws Exception { return artistsFrom(null, startIndex, limit); }
    public List<MediaItem> artistsFrom(String letter, int startIndex, int limit) throws Exception {
        String filter = (letter == null || letter.length() == 0) ? "" : "&NameStartsWithOrGreater=" + enc(letter);
        String path = "/Artists?UserId="+enc(userId)+"&Recursive=true&SortBy=SortName&SortOrder=Ascending&StartIndex="+startIndex+"&Limit="+limit+filter;
        return parseGroups(json("GET", path, null, true).optJSONArray("Items"), "artist");
    }

    // /Genres is a generic cross-media endpoint (movies, audiobooks, everything), and filtering it with
    // IncludeItemTypes=Audio returned zero results rather than just music genres — confirmed live that
    // Jellyfin keeps music genres in a wholly separate taxonomy behind the dedicated /MusicGenres endpoint
    // (Type:"MusicGenre" in the response, vs Type:"Genre" from /Genres).
    public List<MediaItem> genres(int startIndex, int limit) throws Exception { return genresFrom(null, startIndex, limit); }
    public List<MediaItem> genresFrom(String letter, int startIndex, int limit) throws Exception {
        String filter = (letter == null || letter.length() == 0) ? "" : "&NameStartsWithOrGreater=" + enc(letter);
        String path = "/MusicGenres?UserId=" + enc(userId) + "&Recursive=true&SortBy=SortName&SortOrder=Ascending&StartIndex=" + startIndex + "&Limit=" + limit + filter;
        return parseGroups(json("GET", path, null, true).optJSONArray("Items"), "genre");
    }

    public List<MediaItem> songsForGroup(MediaItem group, int startIndex, int limit) throws Exception { return songsForGroupFrom(group, null, startIndex, limit); }
    public List<MediaItem> songsForGroupFrom(MediaItem group, String letter, int startIndex, int limit) throws Exception {
        String idFilter="artist".equals(group.kind)?"ArtistIds":"GenreIds";
        String letterFilter = (letter == null || letter.length() == 0) ? "" : "&NameStartsWithOrGreater=" + enc(letter);
        String path="/Users/"+enc(userId)+"/Items?Recursive=true&IncludeItemTypes=Audio&"+idFilter+"="+enc(group.id)+"&SortBy=SortName&Fields=Album,Artists,RunTimeTicks&StartIndex="+startIndex+"&Limit="+limit+letterFilter;
        return parseItems(json("GET",path,null,true).optJSONArray("Items"),false);
    }

    public List<MediaItem> favoriteSongs(int startIndex, int limit) throws Exception { return favoriteSongsFrom(null, startIndex, limit); }
    public List<MediaItem> favoriteSongsFrom(String letter, int startIndex, int limit) throws Exception {
        String filter = (letter == null || letter.length() == 0) ? "" : "&NameStartsWithOrGreater=" + enc(letter);
        String path = "/Users/" + enc(userId) + "/Items?Recursive=true&IncludeItemTypes=Audio&Filters=IsFavorite&SortBy=SortName&Fields=Album,Artists,RunTimeTicks&StartIndex=" + startIndex + "&Limit=" + limit + filter;
        return parseItems(json("GET", path, null, true).optJSONArray("Items"), false);
    }

    private List<MediaItem> parseGroups(JSONArray array,String kind){List<MediaItem> items=new ArrayList<MediaItem>();if(array==null)return items;for(int i=0;i<array.length();i++){JSONObject o=array.optJSONObject(i);if(o==null)continue;MediaItem m=new MediaItem();m.id=o.optString("Id");m.name=o.optString("Name","Untitled");m.subtitle="artist".equals(kind)?"Artist":"Genre";m.album=true;m.kind=kind;items.add(m);}return items;}

    // No-arg overload is deliberately separate from the paginated favoriteSongs(int,int) above: this one
    // is only for downloadFavorites()'s batch download, which genuinely wants the whole favorited set in
    // one go, not a browsable page at a time. Still capped at 1000 — fine for a batch-download list size
    // in practice, unlike the 11,871-song/2,705-album lists the pagination elsewhere exists for.
    public List<MediaItem> favoriteSongs() throws Exception {
        String path = "/Users/" + enc(userId) + "/Items?Recursive=true&IncludeItemTypes=Audio&Filters=IsFavorite&SortBy=SortName&Fields=Album,Artists,RunTimeTicks&Limit=1000";
        return parseItems(json("GET", path, null, true).optJSONArray("Items"), false);
    }

    public void setFavorite(String itemId, boolean favorite) throws Exception {
        json(favorite ? "POST" : "DELETE", "/Users/" + enc(userId) + "/FavoriteItems/" + enc(itemId), null, true);
    }
    // Session reporting: this is what makes the Jellyfin dashboard's "Now Playing" cards (the ones with
    // live progress bars and play/stop buttons, same as a web browser session shows) appear for this
    // device at all. Without it the session just sits there with a stale "last active" timestamp.
    public void reportPlaying(String itemId, String playMethod) throws Exception {
        JSONObject body = new JSONObject();
        body.put("ItemId", itemId); body.put("PositionTicks", 0); body.put("IsPaused", false);
        body.put("CanSeek", true); body.put("PlayMethod", playMethod);
        json("POST", "/Sessions/Playing", body, true);
    }
    public void reportProgress(String itemId, long positionTicks, boolean paused, String playMethod) throws Exception {
        JSONObject body = new JSONObject();
        body.put("ItemId", itemId); body.put("PositionTicks", positionTicks); body.put("IsPaused", paused);
        body.put("CanSeek", true); body.put("PlayMethod", playMethod);
        json("POST", "/Sessions/Playing/Progress", body, true);
    }
    public void reportStopped(String itemId, long positionTicks) throws Exception {
        JSONObject body = new JSONObject();
        body.put("ItemId", itemId); body.put("PositionTicks", positionTicks);
        json("POST", "/Sessions/Playing/Stopped", body, true);
    }
    public void download(MediaItem item, File destination) throws Exception {
        downloadUrl(streamUrl(item.id), destination);
    }

    public void downloadUrl(String url, File destination) throws Exception {
        android.util.Log.e("JMPlayback", "downloadUrl: " + url);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(60000); c.setInstanceFollowRedirects(true);
        for (java.util.Map.Entry<String,String> h : playbackHeaders().entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        int code = c.getResponseCode();
        String contentType = c.getContentType();
        long contentLength = c.getContentLength();
        android.util.Log.e("JMPlayback", "downloadUrl response: code=" + code + " contentType=" + contentType + " contentLength=" + contentLength);
        if (code < 200 || code >= 300) {
            String body = readText(c.getErrorStream());
            c.disconnect();
            android.util.Log.e("JMPlayback", "downloadUrl HTTP error body: " + body);
            throw new Exception("Download failed with HTTP " + code);
        }
        InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(destination); byte[] buffer = new byte[32768]; int count; long total = 0;
        while ((count = in.read(buffer)) != -1) { out.write(buffer, 0, count); total += count; }
        out.flush(); out.close(); in.close(); c.disconnect();
        android.util.Log.e("JMPlayback", "downloadUrl wrote " + total + " bytes to " + destination.getAbsolutePath());
        logFileHeader(destination);
    }

    private void logFileHeader(File file) {
        try {
            byte[] head = new byte[32];
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            int read = fis.read(head); fis.close();
            StringBuilder hex = new StringBuilder(); StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < Math.max(read, 0); i++) {
                int b = head[i] & 0xFF;
                hex.append(String.format("%02X ", b));
                ascii.append(b >= 32 && b < 127 ? (char) b : '.');
            }
            android.util.Log.e("JMPlayback", "file header hex=" + hex + " ascii=" + ascii);
        } catch (Exception e) {
            android.util.Log.e("JMPlayback", "could not read file header: " + e.getMessage());
        }
    }

    private List<MediaItem> parseItems(JSONArray array, boolean albums) {
        List<MediaItem> items = new ArrayList<MediaItem>();
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i); if (o == null) continue;
            MediaItem item = new MediaItem(); item.id = o.optString("Id"); item.name = o.optString("Name", "Untitled"); item.album = albums;
            item.albumId = albums ? item.id : o.optString("AlbumId", ""); item.durationTicks = o.optLong("RunTimeTicks", 0);
            String album = o.optString("Album", ""); JSONArray artists = o.optJSONArray("Artists");
            String artist = artists != null && artists.length() > 0 ? artists.optString(0) : "";
            item.subtitle = albums ? artist : (!TextUtils.isEmpty(artist) && !TextUtils.isEmpty(album) ? artist + " • " + album : artist + album);
            items.add(item);
        }
        return items;
    }

    // MaxAudioSampleRate is pinned to 44100 as a safety margin for this era of hardware decoder; the
    // MediaPlayer error 1/-2147483648 some tracks hit turned out to be an ID3v2.4 tag-parsing bug in the
    // device's MP3 extractor, fixed in PlaybackService.setDataSourceSkippingId3(), not a sample-rate issue.
    public String streamUrl(String id) { return baseUrl + "/Audio/" + id + "/universal?UserId=" + userId + "&DeviceId=" + deviceId + "&Container=mp3&AudioCodec=mp3&TranscodingContainer=mp3&TranscodingProtocol=http&AudioBitRate=192000&MaxAudioChannels=2&MaxAudioSampleRate=44100&EnableRedirection=false"; }
    public String imageUrl(MediaItem item, int size) {
        String id = item.album && !TextUtils.isEmpty(item.id) ? item.id : (!TextUtils.isEmpty(item.albumId) ? item.albumId : item.id);
        return baseUrl + "/Items/" + id + "/Images/Primary?maxWidth=" + size + "&quality=85";
    }
    public java.util.Map<String,String> playbackHeaders() {
        java.util.Map<String,String> map = new java.util.HashMap<String,String>();
        map.put("Authorization", authorization(true)); map.put("X-Emby-Authorization", authorization(true)); map.put("X-Emby-Token", token); return map;
    }
    private String enc(String value) throws Exception { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
}
