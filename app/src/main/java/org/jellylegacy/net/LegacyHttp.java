package org.jellylegacy.net;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.regex.*;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.tls.*;
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto;
import org.bouncycastle.util.encoders.Base64;

/** TLS 1.2 with chain and hostname verification, independent of old Android TLS. */
public class LegacyHttp {
    private static Provider bc; private static synchronized Provider provider(){if(bc==null)bc=new BouncyCastleProvider();return bc;}
    private static Set<TrustAnchor> roots;
    private final Context context;
    public LegacyHttp(Context c){context=c==null?null:c.getApplicationContext();}
    private synchronized Set<TrustAnchor> roots()throws Exception{
        synchronized(LegacyHttp.class){if(roots!=null)return roots;
            InputStream in=context.getAssets().open("cacert.pem");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);in.close();
            Set<TrustAnchor> loaded=new HashSet<TrustAnchor>();Matcher m=Pattern.compile("-----BEGIN CERTIFICATE-----([\\s\\S]*?)-----END CERTIFICATE-----").matcher(bytes.toString("US-ASCII"));CertificateFactory cf=CertificateFactory.getInstance("X.509",provider());
            while(m.find()){byte[] encoded=Base64.decode(m.group(1));X509Certificate cert=(X509Certificate)cf.generateCertificate(new ByteArrayInputStream(encoded));loaded.add(new TrustAnchor(cert,null));}
            if(loaded.isEmpty())throw new IOException("No HTTPS trust anchors");roots=loaded;return roots;
        }
    }
    private boolean match(String host,String name){host=host.toLowerCase(Locale.US);name=name.toLowerCase(Locale.US);if(name.equals(host))return true;if(name.startsWith("*.")){String suffix=name.substring(1);return host.endsWith(suffix)&&host.substring(0,host.length()-suffix.length()).indexOf('.')<0&&!host.substring(0,host.length()-suffix.length()).equals("");}return false;}
    private void verify(String host,org.bouncycastle.tls.Certificate chain)throws IOException{
        try{
            ArrayList<X509Certificate> certs=new ArrayList<X509Certificate>();CertificateFactory cf=CertificateFactory.getInstance("X.509",provider());
            for(int n=0;n<chain.getLength();n++)certs.add((X509Certificate)cf.generateCertificate(new ByteArrayInputStream(chain.getCertificateAt(n).getEncoded())));
            if(certs.isEmpty())throw new IOException("Server sent no certificate");
            X509Certificate leaf=certs.get(0);boolean valid=false,sanPresent=false;Collection<List<?>> names=leaf.getSubjectAlternativeNames();
            if(names!=null)for(List<?> entry:names){int type=((Integer)entry.get(0)).intValue();if(type==2){sanPresent=true;if(match(host,(String)entry.get(1)))valid=true;}if(type==7){sanPresent=true;if(host.equalsIgnoreCase((String)entry.get(1)))valid=true;}}
            if(!sanPresent){String subject=leaf.getSubjectX500Principal().getName();for(String part:subject.split(","))if(part.trim().startsWith("CN="))valid=match(host,part.trim().substring(3));}
            if(!valid)throw new IOException("HTTPS hostname does not match certificate");
            Set<TrustAnchor> anchors=roots();for(TrustAnchor a:anchors)if(a.getTrustedCert().equals(certs.get(certs.size()-1))){certs.remove(certs.size()-1);break;}
            if(certs.isEmpty()){leaf.checkValidity();return;}
            PKIXParameters params=new PKIXParameters(anchors);params.setRevocationEnabled(false);CertPathValidator.getInstance("PKIX",provider()).validate(cf.generateCertPath(certs),params);
        }catch(Exception e){throw new IOException("HTTPS certificate check failed; check the phone's clock and server certificate: "+e.getMessage());}
    }
    public Response open(String method,String address,Map<String,String> headers,byte[] data)throws IOException{
        URL url=new URL(address);if(!(url.getProtocol().equals("https")||url.getProtocol().equals("http")))throw new IOException("Use HTTP or HTTPS");
        final String host=url.getHost();int port=url.getPort();if(port<0)port=url.getProtocol().equals("https")?443:80;
        final Socket socket=new Socket();TlsClientProtocol protocol=null;
        try{
            socket.connect(new InetSocketAddress(host,port),15000);socket.setSoTimeout(30000);
            InputStream input=socket.getInputStream();OutputStream output=socket.getOutputStream();
            if(url.getProtocol().equals("https")){
                protocol=new TlsClientProtocol(input,output);
                protocol.connect(new DefaultTlsClient(new BcTlsCrypto(new SecureRandom())){
                    protected ProtocolVersion[] getSupportedVersions(){return new ProtocolVersion[]{ProtocolVersion.TLSv12};}
                    public Vector getSNIServerNames(){Vector names=new Vector();try{names.addElement(new ServerName(NameType.host_name,host.getBytes("US-ASCII")));}catch(Exception ignored){}return names;}
                    public TlsAuthentication getAuthentication(){return new TlsAuthentication(){public void notifyServerCertificate(TlsServerCertificate cert)throws IOException{verify(host,cert.getCertificate());}public TlsCredentials getClientCredentials(CertificateRequest request){return null;}};}
                });input=protocol.getInputStream();output=protocol.getOutputStream();
            }
            String path=url.getFile();if(path.length()==0)path="/";
            String request=method+" "+path+" HTTP/1.1\r\nHost: "+host+(url.getPort()<0?"":":"+port)+"\r\nConnection: close\r\nAccept-Encoding: identity\r\n";
            if(headers!=null)for(Map.Entry<String,String> h:headers.entrySet()){if(h.getValue().indexOf('\r')>=0||h.getValue().indexOf('\n')>=0)throw new IOException("Invalid header");request+=h.getKey()+": "+h.getValue()+"\r\n";}
            if(data!=null)request+="Content-Type: application/json; charset=utf-8\r\nContent-Length: "+data.length+"\r\n";
            output.write((request+"\r\n").getBytes("US-ASCII"));if(data!=null)output.write(data);output.flush();input=new BufferedInputStream(input,8192);
            String first=line(input);String[] status=first.split(" ");if(status.length<2)throw new IOException("Invalid server response");int code=Integer.parseInt(status[1]);Map<String,String> responseHeaders=new HashMap<String,String>();int total=0;
            for(;;){String l=line(input);if(l.length()==0)break;total+=l.length();if(total>32768)throw new IOException("Response headers too large");int colon=l.indexOf(':');if(colon>0)responseHeaders.put(l.substring(0,colon).toLowerCase(Locale.US),l.substring(colon+1).trim());}
            boolean chunked="chunked".equalsIgnoreCase(responseHeaders.get("transfer-encoding"));long length=-1;try{length=Long.parseLong(responseHeaders.get("content-length"));}catch(Exception ignored){}
            return new Response(socket,protocol,input,code,responseHeaders,length,chunked);
        }catch(Exception e){try{socket.close();}catch(Exception ignored){}if(e instanceof IOException)throw(IOException)e;throw new IOException(e.toString());}
    }
    private static String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int c;while((c=in.read())!=-1&&c!='\n'){if(b.size()>8192)throw new IOException("Line too long");if(c!='\r')b.write(c);}return b.toString("US-ASCII");}
    public static class Response extends InputStream {
        public final int status;public final Map<String,String> headers;public final long length;
        private final Socket socket;private final TlsClientProtocol tls;private final InputStream input;private final boolean chunked;private long remaining,chunk;private boolean done,first=true;
        Response(Socket s,TlsClientProtocol t,InputStream i,int code,Map<String,String> h,long len,boolean c){socket=s;tls=t;input=i;status=code;headers=h;length=len;remaining=len;chunked=c;}
        public int read()throws IOException{byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException{
            if(len==0)return 0;if(done)return -1;
            if(chunked&&chunk==0){if(!first)line(input);first=false;String size=line(input);int semicolon=size.indexOf(';');if(semicolon>=0)size=size.substring(0,semicolon);try{chunk=Long.parseLong(size.trim(),16);}catch(Exception e){throw new IOException("Bad chunk size");}if(chunk==0){done=true;return -1;}}
            if(!chunked&&remaining==0){done=true;return -1;}int count=input.read(b,off,(int)Math.min(len,chunked?chunk:remaining<0?len:remaining));if(count<0){if(chunked||remaining>0)throw new IOException("Incomplete response");done=true;return -1;}if(chunked)chunk-=count;else if(remaining>=0)remaining-=count;return count;
        }
        public void abort(){try{socket.close();}catch(Exception ignored){}}
        public void close()throws IOException{try{if(tls!=null)tls.close();}finally{socket.close();}}
    }
}
