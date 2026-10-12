package org.jellymusic.legacy;
public class LegacyMusicApp extends android.app.Application {
    public void onCreate(){super.onCreate();org.jellylegacy.net.Network.initialize(this);}
}
