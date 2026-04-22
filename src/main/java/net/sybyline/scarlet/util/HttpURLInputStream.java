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
import java.util.function.Predicate;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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

}
