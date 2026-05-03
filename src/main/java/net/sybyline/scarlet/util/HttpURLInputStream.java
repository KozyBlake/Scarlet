package net.sybyline.scarlet.util;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.function.Predicate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import net.sybyline.scarlet.Scarlet;

public class HttpURLInputStream extends FilterInputStream
{
    public static final Predicate<String> PUBLIC_ONLY = HttpURLInputStream::isPublicHttpUrl;

    public static final Func.V1<IOException, HttpURLConnection> DISABLE_REDIRECTS = $ -> $.setInstanceFollowRedirects(false);

    public static HttpURLInputStream get(String url) throws IOException
    {
        return of(url, null, null, null);
    }
    public static HttpURLInputStream get(String url, Func.V1<IOException, HttpURLConnection> init) throws IOException
    {
        return of(url, null, init, null);
    }
    public static HttpURLInputStream get(String url, Predicate<String> validator) throws IOException
    {
        return of(url, null, null, null, validator);
    }
    public static HttpURLInputStream get(String url, Func.V1<IOException, HttpURLConnection> init, Predicate<String> validator) throws IOException
    {
        return of(url, null, init, null, validator);
    }

    public static HttpURLInputStream head(String url) throws IOException
    {
        return of(url, "HEAD", null, null);
    }
    public static HttpURLInputStream head(String url, Func.V1<IOException, HttpURLConnection> init) throws IOException
    {
        return of(url, "HEAD", init, null);
    }

    public static HttpURLInputStream post(String url) throws IOException
    {
        return of(url, "POST", null, null);
    }
    public static HttpURLInputStream post(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "POST", null, send);
    }
    public static HttpURLInputStream post(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "POST", init, send);
    }

    public static HttpURLInputStream put(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PUT", null, send);
    }
    public static HttpURLInputStream put(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PUT", init, send);
    }

    public static HttpURLInputStream delete(String url) throws IOException
    {
        return of(url, "DELETE", null, null);
    }
    public static HttpURLInputStream delete(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "DELETE", null, send);
    }
    public static HttpURLInputStream delete(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "DELETE", init, send);
    }

    @Deprecated
    public static HttpURLInputStream patch(String url, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PATCH", null, send);
    }
    @Deprecated
    public static HttpURLInputStream patch(String url, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, "PATCH", init, send);
    }

    public static HttpURLInputStream of(String url, String method, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send) throws IOException
    {
        return of(url, method, init, send, null);
    }
    public static HttpURLInputStream of(String url, String method, Func.V1<IOException, HttpURLConnection> init, Func.V1<IOException, OutputStream> send, Predicate<String> validator) throws IOException
    {
        if (validator != null && !validator.test(url))
            throw new IOException("Blocked unsafe URL: " + url);
        URL url0 = new URL(url);
        HttpURLConnection connection = (HttpURLConnection)url0.openConnection();
        if (connection instanceof HttpsURLConnection)
            applyCompatTls((HttpsURLConnection)connection);
        connection.setRequestProperty("User-Agent", Scarlet.USER_AGENT);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(5_000);
        if (method != null)
            connection.setRequestMethod(method);
        try
        {
            if (init != null)
                init.invoke(connection);
            if (send != null)
            {
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream())
                {
                    send.invoke(out);
                    out.flush();
                }
            }
            return new HttpURLInputStream(connection);
        }
        catch (IOException ioex)
        {
            connection.disconnect();
            throw ioex;
        }
    }

    protected HttpURLInputStream(HttpURLConnection connection) throws IOException
    {
        super(connection.getInputStream());
        this.connection = connection;
    }

    private final HttpURLConnection connection;

    public HttpURLConnection connection()
    {
        return this.connection;
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            super.close();
        }
        finally
        {
            this.connection.disconnect();
        }
    }

    static boolean isPublicHttpUrl(String url)
    {
        try
        {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                return false;
            String host = uri.getHost();
            if (host == null || host.isEmpty())
                return false;
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0)
                return false;
            for (InetAddress address : addresses)
            {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress())
                    return false;
                byte[] bytes = address.getAddress();
                if (bytes.length == 4)
                {
                    int b0 = bytes[0] & 0xFF;
                    int b1 = bytes[1] & 0xFF;
                    if (b0 == 10 || b0 == 127)
                        return false;
                    if (b0 == 169 && b1 == 254)
                        return false;
                    if (b0 == 172 && 16 <= b1 && b1 <= 31)
                        return false;
                    if (b0 == 192 && b1 == 168)
                        return false;
                }
                else if (bytes.length == 16)
                {
                    int b0 = bytes[0] & 0xFF;
                    int b1 = bytes[1] & 0xFF;
                    if ((b0 & 0xFE) == 0xFC)
                        return false;
                    if (b0 == 0xFE && (b1 & 0xC0) == 0x80)
                        return false;
                }
            }
            return true;
        }
        catch (Exception ex)
        {
            return false;
        }
    }

    static Charset charset(Charset charset)
    {
        return charset != null ? charset : StandardCharsets.UTF_8;
    }
    static Gson gson(Gson gson)
    {
        return gson != null ? gson : Scarlet.GSON;
    }

    // In

    public static Reader reader(InputStream in, Charset charset)
    {
        return new InputStreamReader(in, charset(charset));
    }

    public static JsonReader readerJson(InputStream in, Charset charset, Gson gson)
    {
        return gson(gson).newJsonReader(reader(in, charset));
    }

    public static <T> T readJson(InputStream in, Charset charset, Gson gson, Type type)
    {
        return gson(gson).fromJson(reader(in, charset), type);
    }
    public static <T> T readJson(InputStream in, Charset charset, Gson gson, Class<T> type)
    {
        return gson(gson).fromJson(reader(in, charset), type);
    }
    public static <T> T readJson(InputStream in, Charset charset, Gson gson, TypeToken<T> type)
    {
        return gson(gson).fromJson(reader(in, charset), type);
    }

    // Out

    public static Writer writer(OutputStream out, Charset charset)
    {
        return new OutputStreamWriter(out, charset(charset));
    }

    public static JsonWriter writerJson(OutputStream out, Charset charset, Gson gson) throws IOException
    {
        return gson(gson).newJsonWriter(writer(out, charset));
    }

    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, T value) throws IOException
    {
        gson(gson).toJson(value, writer(out, charset));
    }
    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, Type type, T value) throws IOException
    {
        gson(gson).toJson(value, type, writer(out, charset));
    }
    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, Class<T> type, T value) throws IOException
    {
        gson(gson).toJson(value, type, writer(out, charset));
    }
    public static <T> void writeJson(OutputStream out, Charset charset, Gson gson, TypeToken<T> type, T value) throws IOException
    {
        gson(gson).toJson(value, type.getType(), writer(out, charset));
    }

    // In more

    public Reader asReader(Charset charset)
    {
        return reader(this, charset);
    }

    public JsonReader asReaderJson(Charset charset, Gson gson)
    {
        return readerJson(this, charset, gson);
    }
    public <T> T readAsJson(Charset charset, Gson gson, Type type)
    {
        return readJson(this, charset, gson, type);
    }
    public <T> T readAsJson(Charset charset, Gson gson, Class<T> type)
    {
        return readJson(this, charset, gson, type);
    }
    public <T> T readAsJson(Charset charset, Gson gson, TypeToken<T> type)
    {
        return readJson(this, charset, gson, type);
    }

    // Out more

    public static Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, Func.V1<? extends IOException, JsonWriter> func)
    {
        return out -> func.invoke(writerJson(out, charset, gson));
    }

    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, T value)
    {
        return out -> writeJson(out, charset, gson, value);
    }
    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, Type type, T value)
    {
        return out -> writeJson(out, charset, gson, type, value);
    }
    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, Class<T> type, T value)
    {
        return out -> writeJson(out, charset, gson, type, value);
    }
    public static <T> Func.V1<IOException, OutputStream> writeAsJson(Charset charset, Gson gson, TypeToken<T> type, T value)
    {
        return out -> writeJson(out, charset, gson, type, value);
    }

    private static final String[] LEGACY_TLS_COMPAT_CERT_RESOURCES =
    {
        "/net/sybyline/scarlet/compat/GoGetSSLECCDVSSLCA2.pem",
        "/net/sybyline/scarlet/compat/SectigoPublicServerAuthenticationRootE46.pem",
        "/net/sybyline/scarlet/compat/SectigoPublicServerAuthenticationCADVE36.pem",
        "/net/sybyline/scarlet/compat/LetsEncryptR12.pem",
        "/net/sybyline/scarlet/compat/ISRGRootX1.pem",
    };
    private static volatile javax.net.ssl.SSLSocketFactory compatSocketFactory;
    private static volatile boolean compatTlsInitAttempted;

    private static void applyCompatTls(HttpsURLConnection connection)
    {
        ensureCompatTlsInitialized();
        if (compatSocketFactory != null)
            connection.setSSLSocketFactory(compatSocketFactory);
    }
    private static synchronized void ensureCompatTlsInitialized()
    {
        if (compatTlsInitAttempted)
            return;
        compatTlsInitAttempted = true;
        try
        {
            X509TrustManager base = defaultTrustManager();
            X509TrustManager extra = compatTrustManager();
            CompositeX509TrustManager composite = new CompositeX509TrustManager(base, extra);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { composite }, null);
            compatSocketFactory = sslContext.getSocketFactory();
            Scarlet.LOG.warn("Using Scarlet's bundled HTTPS compatibility certificates for legacy Java trust stores");
        }
        catch (Exception ex)
        {
            Scarlet.LOG.debug("Unable to initialize legacy HTTPS compatibility certificates; continuing with the JVM default trust store", ex);
        }
    }
    private static X509TrustManager defaultTrustManager() throws Exception
    {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore)null);
        return pickTrustManager(tmf.getTrustManagers());
    }
    private static X509TrustManager compatTrustManager() throws Exception
    {
        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        int idx = 0;
        for (String resource : LEGACY_TLS_COMPAT_CERT_RESOURCES)
        {
            try (InputStream in = HttpURLInputStream.class.getResourceAsStream(resource))
            {
                if (in == null)
                    continue;
                X509Certificate cert = (X509Certificate)certificateFactory.generateCertificate(in);
                keyStore.setCertificateEntry("scarlet-compat-" + (idx++), cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        return pickTrustManager(tmf.getTrustManagers());
    }
    private static X509TrustManager pickTrustManager(TrustManager[] trustManagers)
    {
        for (TrustManager trustManager : trustManagers)
            if (trustManager instanceof X509TrustManager)
                return (X509TrustManager)trustManager;
        throw new IllegalStateException("No X509TrustManager available");
    }
    private static final class CompositeX509TrustManager implements X509TrustManager
    {
        private final X509TrustManager[] delegates;
        CompositeX509TrustManager(X509TrustManager... delegates)
        {
            this.delegates = delegates;
        }
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            java.security.cert.CertificateException last = null;
            for (X509TrustManager delegate : this.delegates) try
            {
                delegate.checkClientTrusted(chain, authType);
                return;
            }
            catch (java.security.cert.CertificateException ex)
            {
                last = ex;
            }
            throw last != null ? last : new java.security.cert.CertificateException("No trust manager accepted the client certificate chain");
        }
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            java.security.cert.CertificateException last = null;
            for (X509TrustManager delegate : this.delegates) try
            {
                delegate.checkServerTrusted(chain, authType);
                return;
            }
            catch (java.security.cert.CertificateException ex)
            {
                last = ex;
            }
            throw last != null ? last : new java.security.cert.CertificateException("No trust manager accepted the server certificate chain");
        }
        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            java.util.LinkedHashMap<String, X509Certificate> certs = new java.util.LinkedHashMap<>();
            for (X509TrustManager delegate : this.delegates)
                for (X509Certificate cert : delegate.getAcceptedIssuers())
                    certs.put(cert.getSubjectX500Principal().getName(), cert);
            return certs.values().toArray(new X509Certificate[certs.size()]);
        }
    }

}
