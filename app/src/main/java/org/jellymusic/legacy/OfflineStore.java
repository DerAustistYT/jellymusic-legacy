package org.jellymusic.legacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public class OfflineStore {
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("offline", Context.MODE_PRIVATE); }
    private static File removableRoot() {
        File primary=Environment.getExternalStorageDirectory();
        String[] names={"external_sd","external_sdcard","extSdCard","ExternalSD"};
        for(String name:names){File f=new File(primary,name);if(f.exists()&&f.isDirectory())return f;}
        return null;
    }
    public static boolean hasRemovableStorage(){return removableRoot()!=null;}
    public static boolean usesRemovable(Context c){return prefs(c).getBoolean("use_removable",false)&&hasRemovableStorage();}
    public static void setUseRemovable(Context c,boolean value){prefs(c).edit().putBoolean("use_removable",value).apply();}
    public static File directory(Context c) {
        File d;
        if(usesRemovable(c)) d=new File(removableRoot(),"Android/data/"+c.getPackageName()+"/files/offline");
        else d=new File(c.getFilesDir(),"offline");
        if(!d.exists())d.mkdirs();return d;
    }
    public static String storageLabel(Context c){File d=directory(c);long mb=d.getUsableSpace()/1024/1024;return (usesRemovable(c)?"SD card":"Internal storage")+" ("+mb+" MB free)";}
    public static File file(Context c, String id) { return new File(directory(c), id + ".media"); }
    public static List<MediaItem> load(Context c) {
        List<MediaItem> result=new ArrayList<MediaItem>();
        try { JSONArray a=new JSONArray(prefs(c).getString("items","[]")); for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);File f=new File(o.optString("path",file(c,o.optString("id")).getAbsolutePath()));if(!f.exists())continue;MediaItem m=new MediaItem();m.id=o.optString("id");m.name=o.optString("name","Untitled");m.subtitle=o.optString("subtitle","");m.albumId=o.optString("albumId","");m.localPath=f.getAbsolutePath();result.add(m);} } catch(Exception ignored){}
        return result;
    }
    public static void save(Context c, List<MediaItem> items) {
        JSONArray a=new JSONArray();try{for(MediaItem m:items){JSONObject o=new JSONObject();o.put("id",m.id);o.put("name",m.name);o.put("subtitle",m.subtitle);o.put("albumId",m.albumId);o.put("path",m.localPath!=null?m.localPath:file(c,m.id).getAbsolutePath());a.put(o);}}catch(Exception ignored){}prefs(c).edit().putString("items",a.toString()).apply();
    }
}
