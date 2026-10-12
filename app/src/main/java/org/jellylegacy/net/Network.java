package org.jellylegacy.net;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

/** Preserve native TLS acceleration where available, with a CPU-independent fallback. */
public final class Network {
    private static volatile Context context;
    private static volatile boolean software=true;
    public static void initialize(Context application){
        context=application.getApplicationContext();software=true;
        String abi=Build.CPU_ABI;
        if("armeabi-v7a".equals(abi)||"arm64-v8a".equals(abi)||"x86".equals(abi)||"x86_64".equals(abi)){
            try{Class<?> conscrypt=Class.forName("org.conscrypt.Conscrypt");Provider provider=(Provider)conscrypt.getMethod("newProvider").invoke(null);SSLContext tls=SSLContext.getInstance("TLS",provider);tls.init(null,null,null);HttpsURLConnection.setDefaultSSLSocketFactory(tls.getSocketFactory());Security.insertProviderAt(provider,1);software=false;}catch(Throwable unavailable){software=true;}
        }
        System.out.println("JellyLegacy TLS mode: "+(software?"CPU-independent Java":"native Conscrypt"));
    }
    public static HttpURLConnection open(URL url)throws IOException{
        if(!url.getProtocol().equals("https")||!software)return(HttpURLConnection)url.openConnection();
        if(context==null)throw new IOException("Networking not initialized");return new Compatible(url,new LegacyHttp(context));
    }
    static final class Compatible extends HttpURLConnection {
        private final LegacyHttp transport;private LegacyHttp.Response response;private ByteArrayOutputStream body;
        private final Map<String,List<String>> requestHeaders=new LinkedHashMap<String,List<String>>();
        public void setRequestProperty(String key,String value){if(connected)throw new IllegalStateException("Already connected");if(key==null)throw new NullPointerException("Header name");String existing=null;for(String name:requestHeaders.keySet())if(name.equalsIgnoreCase(key)){existing=name;break;}if(existing!=null)requestHeaders.remove(existing);requestHeaders.put(key,Collections.singletonList(value));}
        public void addRequestProperty(String key,String value){setRequestProperty(key,value);}
        public String getRequestProperty(String key){for(Map.Entry<String,List<String>> h:requestHeaders.entrySet())if(h.getKey().equalsIgnoreCase(key))return h.getValue().get(0);return null;}
        public Map<String,List<String>> getRequestProperties(){if(connected)throw new IllegalStateException("Already connected");return Collections.unmodifiableMap(requestHeaders);}
        Compatible(URL url,LegacyHttp http){super(url);transport=http;}
        private void ensure()throws IOException{
            if(response!=null)return;
            Map<String,String> headers=new HashMap<String,String>();for(Map.Entry<String,List<String>> h:getRequestProperties().entrySet()){if(h.getValue().size()>0&&!h.getKey().equalsIgnoreCase("Accept-Encoding")&&!h.getKey().equalsIgnoreCase("Content-Type"))headers.put(h.getKey(),h.getValue().get(0));}
            URL target=url;String verb=method;byte[] data=body==null?null:body.toByteArray();
            for(int redirects=0;;redirects++){
                response=transport.open(verb,target.toString(),headers,data);int status=response.status;
                if(!getInstanceFollowRedirects()||!(status==301||status==302||status==303||status==307||status==308))break;
                if(redirects>=3){disconnect();throw new IOException("Too many HTTPS redirects");}
                String location=response.headers.get("location");if(location==null)break;URL next=new URL(target,location);
                if(!next.getProtocol().equals("https")||!target.getHost().equalsIgnoreCase(next.getHost())||port(target)!=port(next)){disconnect();throw new IOException("HTTPS redirect outside configured server");}
                response.close();response=null;target=next;if(status==303||((status==301||status==302)&&verb.equals("POST"))){verb="GET";data=null;}
            }
            responseCode=response.status;connected=true;
        }
        private int port(URL u){return u.getPort()<0?443:u.getPort();}
        public void connect()throws IOException{ensure();}
        public boolean usingProxy(){return false;}
        public int getResponseCode()throws IOException{ensure();return responseCode;}
        public OutputStream getOutputStream()throws IOException{if(connected)throw new IOException("Request already sent");if(body==null)body=new ByteArrayOutputStream();return body;}
        public InputStream getInputStream()throws IOException{ensure();if(responseCode>=400)throw new IOException("Server returned HTTP "+responseCode);return response;}
        public InputStream getErrorStream(){try{ensure();return responseCode>=400?response:null;}catch(IOException ignored){return null;}}
        public String getHeaderField(String name){try{ensure();return name==null?"HTTP/1.1 "+responseCode:response.headers.get(name.toLowerCase(Locale.US));}catch(IOException ignored){return null;}}
        public Map<String,List<String>> getHeaderFields(){try{ensure();Map<String,List<String>> fields=new HashMap<String,List<String>>();for(Map.Entry<String,String> h:response.headers.entrySet())fields.put(h.getKey(),Collections.singletonList(h.getValue()));return fields;}catch(IOException ignored){return Collections.emptyMap();}}
        public void disconnect(){LegacyHttp.Response r=response;response=null;connected=false;if(r!=null){r.abort();try{r.close();}catch(Exception ignored){}}}
    }
}
