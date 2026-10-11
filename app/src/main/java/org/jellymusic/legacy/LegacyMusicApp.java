package org.jellymusic.legacy;

import android.app.Application;
import java.security.Security;
import java.security.Provider;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.conscrypt.Conscrypt;

public class LegacyMusicApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try {
            Provider provider = Conscrypt.newProvider();
            Security.insertProviderAt(provider, 1);
            SSLContext context = SSLContext.getInstance("TLS", provider);
            context.init(null, null, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (Throwable ignored) { }
    }
}
