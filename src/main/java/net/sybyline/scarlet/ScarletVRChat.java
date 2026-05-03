package net.sybyline.scarlet;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.j256.twofactorauth.TimeBasedOneTimePasswordUtil;

import io.github.vrchatapi.ApiCallback;
import io.github.vrchatapi.ApiClient;
import io.github.vrchatapi.ApiException;
import io.github.vrchatapi.ApiResponse;
import io.github.vrchatapi.Configuration;
import io.github.vrchatapi.JSON;
import io.github.vrchatapi.ProgressResponseBody;
import io.github.vrchatapi.api.AuthenticationApi;
import io.github.vrchatapi.api.AvatarsApi;
import io.github.vrchatapi.api.CalendarApi;
import io.github.vrchatapi.api.FilesApi;
import io.github.vrchatapi.api.GroupsApi;
import io.github.vrchatapi.api.InstancesApi;
import io.github.vrchatapi.api.MiscellaneousApi;
import io.github.vrchatapi.api.PrintsApi;
import io.github.vrchatapi.api.PropsApi;
import io.github.vrchatapi.api.UsersApi;
import io.github.vrchatapi.api.WorldsApi;
import io.github.vrchatapi.model.Avatar;
import io.github.vrchatapi.model.BanGroupMemberRequest;
import io.github.vrchatapi.model.CalendarEvent;
import io.github.vrchatapi.model.CreateCalendarEventRequest;
import io.github.vrchatapi.model.CreateGroupInviteRequest;
import io.github.vrchatapi.model.CreateInstanceRequest;
import io.github.vrchatapi.model.CurrentUser;
import io.github.vrchatapi.model.FileAnalysis;
import io.github.vrchatapi.model.Group;
import io.github.vrchatapi.model.GroupAuditLogEntry;
import io.github.vrchatapi.model.GroupGalleryImage;
import io.github.vrchatapi.model.GroupInstance;
import io.github.vrchatapi.model.GroupJoinRequestAction;
import io.github.vrchatapi.model.GroupMember;
import io.github.vrchatapi.model.GroupMemberStatus;
import io.github.vrchatapi.model.GroupPermissions;
import io.github.vrchatapi.model.GroupRole;
import io.github.vrchatapi.model.Instance;
import io.github.vrchatapi.model.InventoryItem;
import io.github.vrchatapi.model.LimitedUserGroups;
import io.github.vrchatapi.model.LimitedUserSearch;
import io.github.vrchatapi.model.LimitedWorld;
import io.github.vrchatapi.model.ModelFile;
import io.github.vrchatapi.model.OrderOption;
import io.github.vrchatapi.model.PaginatedGroupAuditLogEntryList;
import io.github.vrchatapi.model.Print;
import io.github.vrchatapi.model.Prop;
import io.github.vrchatapi.model.RespondGroupJoinRequest;
import io.github.vrchatapi.model.SortOption;
import io.github.vrchatapi.model.TwoFactorAuthCode;
import io.github.vrchatapi.model.TwoFactorEmailCode;
import io.github.vrchatapi.model.UpdateGroupMemberRequest;
import io.github.vrchatapi.model.User;
import io.github.vrchatapi.model.World;

import net.sybyline.scarlet.ext.ExtendedUserAgent;
import net.sybyline.scarlet.util.EnumHelper;
import net.sybyline.scarlet.util.MiscUtils;
import net.sybyline.scarlet.util.VersionedFile;
import net.sybyline.scarlet.util.VrcAllGroupPermissions;
import net.sybyline.scarlet.util.VrcWeb;

import okhttp3.Credentials;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ScarletVRChat implements Closeable
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/VRChat");
    private static final String LEGACY_TLS_COMPAT_SETTING = "vrchat_legacy_tls_compat_enabled";
    private static final String[] LEGACY_TLS_COMPAT_CERT_RESOURCES = {
        "/net/sybyline/scarlet/compat/GoGetSSLECCDVSSLCA2.pem",
        "/net/sybyline/scarlet/compat/SectigoPublicServerAuthenticationRootE46.pem"
    };

    public static final Comparator<GroupAuditLogEntry> OLDEST_TO_NEWEST = Comparator.comparing(GroupAuditLogEntry::getCreatedAt);

    static void initJson()
    {
        Gson prev = JSON.getGson();
        JSON.setGson(JSON.createGson().registerTypeAdapterFactory(new TypeAdapterFactory() {
            @Override
            public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
                TypeAdapter<T> fbTA = Scarlet.GSON.getAdapter(type);
                TypeAdapter<T> prevTA = prev.getAdapter(type);
                TypeAdapter<JsonElement> prevJE = prev.getAdapter(JsonElement.class);
                return new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        if (value == null) {
                            out.nullValue();
                            return;
                        }
                        JsonElement je;
                        try {
                            je = prevTA.toJsonTree(value);
                        } catch (Exception ex) {
                            je = fbTA.toJsonTree(value);
                        }
                        prevJE.write(out, je);
                    }
                    @Override
                    public T read(JsonReader in) throws IOException {
                        if (in.peek() == JsonToken.NULL) {
                            in.nextNull();
                            return null;
                        }
                        JsonElement je = prevJE.read(in);
                        T value;
                        try {
                            value = prevTA.fromJsonTree(je);
                        } catch (Exception ex) {
                            @SuppressWarnings("rawtypes")
                            Class enumType = type.getRawType();
                            if (enumType.isEnum()) try {
                                value = fbTA.fromJsonTree(je);
                            } catch (Exception ex1) {
                                if (enumType.isEnum() && enumType.getName().replace('/', '.').startsWith("io.github.vrchatapi.model.")) {
                                    String enumValue = je.getAsString();
                                    LOG.warn("Encountering new API enum value for "+enumType.getName()+": `"+enumValue+"`, implicitly instantiating instance...");
                                    @SuppressWarnings("unchecked")
                                    Object newValue = EnumHelper.addJsonStringEnum(enumType, enumValue);
                                    try {
                                        value = fbTA.fromJsonTree(je);
                                        if (newValue != value) {
                                            LOG.error("Mismatch in parsed values: expected `"+newValue+"`, got `"+value+"`");
                                        }
                                    } catch (Exception ex2) {
                                        LOG.error("Failed to instantiate encountered enum, returning null instead");
                                        value = null;
                                    }
                                } else {
                                    LOG.error("Unknown enum value for `"+je.getAsString()+"`, returning null instead");
                                    value = null;
                                }
                            } else {
                                value = fbTA.fromJsonTree(je);
                            }
                        }
                        return value;
                    }
                };
            }
        }).create());
    }

    public ScarletVRChat(Scarlet scarlet, String domain, File cookieFile)
    {
        scarlet.splash.splashSubtext("Configuring VRChat Api");
        // ensure default ApiClient initialized
        // the ApiClient constructor resets JSON.gson
        Configuration.getDefaultApiClient();
        
        this.scarlet = scarlet;
        this.username = scarlet.settings.new RegistryStringEncrypted(domain+":username", true);
        this.password = scarlet.settings.new RegistryStringEncrypted(domain+":password", true);
        this.totpsecret = scarlet.settings.new RegistryStringEncrypted(domain+":totpsecret", true);
        this.cookies = new ScarletVRChatCookieJar(scarlet, domain, cookieFile);
        this.legacyTrustCompatEnabled = Boolean.TRUE.equals(this.scarlet.settings.getObject(LEGACY_TLS_COMPAT_SETTING, Boolean.class));
        OkHttpClient.Builder okHttpBuilder = new OkHttpClient.Builder();
        if (this.legacyTrustCompatEnabled)
            this.applyLegacyTlsCompat(okHttpBuilder);
        this.client = new ApiClient(okHttpBuilder
                // ── VRChat Basic Auth encoding fix ────────────────────────────────────────
                // VRChat's API is non-standard: it expects credentials to be URL-encoded
                // BEFORE Base64-encoding for HTTP Basic Auth. Their web client does this
                // automatically, which is why logging in via browser works with any password,
                // but API clients that follow RFC 7617 (raw UTF-8 bytes in Basic Auth) fail
                // for passwords containing characters that URL-encoding changes — most
                // visibly % (→ %25) and & (→ %26), but also others like + (→ %2B) and
                // space (→ %20).
                //
                // Standard Basic Auth (RFC 7617): Base64(username + ":" + password)
                // VRChat's expected form:         Base64(url(username) + ":" + url(password))
                //
                // We URL-encode both username and password using percent-encoding before
                // passing them to Credentials.basic(). The result is then Base64-encoded
                // by Credentials.basic() as normal. This matches what VRChat's own web
                // client sends.
                .addInterceptor(chain ->
                {
                    Request req = chain.request();
                    String u = ScarletVRChat.this.pendingUsername;
                    String p = ScarletVRChat.this.pendingPassword;
                    if (u != null && !u.isEmpty() && p != null && !p.isEmpty())
                    {
                        // URL-encode first, then wrap in Basic Auth with UTF-8
                        String uEnc = java.net.URLEncoder.encode(u, "UTF-8");
                        String pEnc = java.net.URLEncoder.encode(p, "UTF-8");
                        String cred = okhttp3.Credentials.basic(uEnc, pEnc, java.nio.charset.StandardCharsets.UTF_8);
                        req = req.newBuilder()
                            .header("Authorization", cred)
                            .build();
                    }
                    return chain.proceed(req);
                })
                .addNetworkInterceptor(this::intercept)
                .cookieJar(this.cookies)
                .build())
            .setBasePath(Scarlet.API_BASE_2)
            .setUserAgent(Scarlet.USER_AGENT);
        // override default Gson
        initJson();
        // override default ApiClient
        Configuration.setDefaultApiClient(this.client);
        this.cookies.setup(this.client);
        this.groupId = MiscUtils.extractTypedUuid("grp", "", scarlet.settings.getStringOrRequireInput("vrchat_group_id", "VRChat Group ID", false));
        ExtendedUserAgent.setCurrentGroupId(this.groupId);
        this.groupOwnerId = null;
        this.group = null;
        this.groupLimitedMember = null;
        this.groupRoles = Collections.synchronizedMap(new LinkedHashMap<>());
        this.allGroupPermissions = new VrcAllGroupPermissions(null);
        this.currentUser = null;
        this.currentUserId = null;
        this.secureStoreReady = false;
        this.cachedUsers = new ScarletJsonCache<>("usr", User.class);
        this.cachedWorlds = new ScarletJsonCache<>("wrld", World.class);
        this.cachedGroups = new ScarletJsonCache<>("grp", Group.class);
        this.cachedUserGroups = new ScarletJsonCache<>("gmem", new TypeToken<List<LimitedUserGroups>>(){});
        this.cachedAvatars = new ScarletJsonCache<>("avtr", Avatar.class);
        this.cachedPrints = new ScarletJsonCache<>("prnt", Print.class);
        this.cachedProps = new ScarletJsonCache<>("prop", Prop.class);
        this.cachedInventoryItems = new ScarletJsonCache<>("inv", InventoryItem.class);
        this.cachedModelFiles = new ScarletJsonCache<>("file", ModelFile.class);
        this.cachedFileAnalyses = new ScarletJsonCache<>("file.analysis", FileAnalysis.class);
    }

    final Scarlet scarlet;
    final ScarletSettings.RegistryStringEncrypted username, password, totpsecret;
    final ScarletVRChatCookieJar cookies;
    final ApiClient client;
    volatile boolean secureStoreReady;
    final boolean legacyTrustCompatEnabled;
    volatile boolean legacyTrustCompatPrompted;
    /** Mirrors the last value passed to client.setUsername() for use in the UTF-8 Auth interceptor. */
    volatile String pendingUsername = null;
    /** Mirrors the last value passed to client.setPassword() for use in the UTF-8 Auth interceptor. */
    volatile String pendingPassword = null;
    String groupId;
    String groupOwnerId;
    Group group;
    GroupMember groupLimitedMember;
    final Map<String, GroupRole> groupRoles;
    VrcAllGroupPermissions allGroupPermissions;
    CurrentUser currentUser;
    String currentUserId;
    final ScarletJsonCache<User> cachedUsers;
    final ScarletJsonCache<World> cachedWorlds;
    final ScarletJsonCache<Group> cachedGroups;
    final ScarletJsonCache<List<LimitedUserGroups>> cachedUserGroups;
    final ScarletJsonCache<Avatar> cachedAvatars;
    final ScarletJsonCache<Print> cachedPrints;
    final ScarletJsonCache<Prop> cachedProps;
    final ScarletJsonCache<InventoryItem> cachedInventoryItems;
    final ScarletJsonCache<ModelFile> cachedModelFiles;
    final ScarletJsonCache<FileAnalysis> cachedFileAnalyses;
    long localDriftMillis = 0L,
         latencyMillis = 0L;

    public ApiClient getClient()
    {
        return this.client;
    }

    private Response intercept(Interceptor.Chain chain) throws IOException
    {
        Request request = chain.request();
        Response originalResponse = chain.proceed(request);
        Object tag = request.tag();
        return tag instanceof ApiCallback
            ? originalResponse.newBuilder().body(new ProgressResponseBody(originalResponse.body(), (ApiCallback<?>)tag)).build()
            : originalResponse;
    }

    private void applyLegacyTlsCompat(OkHttpClient.Builder builder)
    {
        try
        {
            X509TrustManager systemTrust = defaultTrustManager();
            X509TrustManager compatTrust = compatTrustManager();
            X509TrustManager composite = new CompositeX509TrustManager(systemTrust, compatTrust);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { composite }, null);
            builder.sslSocketFactory(sslContext.getSocketFactory(), composite);
            LOG.warn("Using Scarlet's bundled VRChat TLS compatibility certificates");
        }
        catch (GeneralSecurityException | IOException ex)
        {
            LOG.error("Failed to initialize bundled VRChat TLS compatibility certificates", ex);
        }
    }

    private static X509TrustManager defaultTrustManager() throws GeneralSecurityException
    {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore)null);
        return pickTrustManager(tmf.getTrustManagers());
    }

    private static X509TrustManager compatTrustManager() throws GeneralSecurityException, IOException
    {
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try
        {
            ks.load(null, null);
        }
        catch (IOException ioex)
        {
            throw ioex;
        }
        catch (Exception ex)
        {
            throw new GeneralSecurityException("Failed to initialize compatibility keystore", ex);
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < LEGACY_TLS_COMPAT_CERT_RESOURCES.length; i++)
        {
            String resource = LEGACY_TLS_COMPAT_CERT_RESOURCES[i];
            try (InputStream in = ScarletVRChat.class.getResourceAsStream(resource))
            {
                if (in == null)
                    throw new IOException("Missing compatibility certificate resource " + resource);
                X509Certificate cert = (X509Certificate)cf.generateCertificate(in);
                ks.setCertificateEntry("scarlet-compat-" + i, cert);
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        return pickTrustManager(tmf.getTrustManagers());
    }

    private static X509TrustManager pickTrustManager(TrustManager[] trustManagers) throws GeneralSecurityException
    {
        for (TrustManager trustManager : trustManagers)
            if (trustManager instanceof X509TrustManager)
                return (X509TrustManager)trustManager;
        throw new GeneralSecurityException("No X509TrustManager available");
    }

    private boolean maybeEnableLegacyTrustCompatibility(Throwable t)
    {
        if (this.legacyTrustCompatEnabled || !isLegacyTrustFailure(t) || this.legacyTrustCompatPrompted)
            return false;
        this.legacyTrustCompatPrompted = true;
        LOG.warn("Detected TLS trust-store failure while talking to VRChat; offering compatibility certificates", t);
        boolean enable = this.scarlet.settings.requireConfirmYesNo(
            "Scarlet detected that this Java runtime cannot validate VRChat's HTTPS certificate chain.\n\n"
            + "This usually happens on older Windows and Java installs with outdated root certificates.\n\n"
            + "Would you like Scarlet to enable Scarlet's built-in VRChat compatibility certificates on next launch?\n\n"
            + "This only affects Scarlet's VRChat HTTPS client.\n"
            + "It does NOT modify your Windows certificate store or Java installation.",
            "Enable VRChat TLS compatibility?");
        if (!enable)
            return false;
        this.scarlet.settings.setObject(LEGACY_TLS_COMPAT_SETTING, Boolean.class, Boolean.TRUE);
        if (!GraphicsEnvironment.isHeadless())
        {
            JOptionPane.showMessageDialog(null,
                "Scarlet saved the VRChat TLS compatibility setting.\nPlease restart Scarlet and try logging in again.",
                "Restart Scarlet",
                JOptionPane.INFORMATION_MESSAGE);
        }
        else
        {
            LOG.warn("Scarlet saved the VRChat TLS compatibility setting. Restart Scarlet and try logging in again.");
        }
        return true;
    }

    private static boolean isLegacyTrustFailure(Throwable t)
    {
        for (Throwable cur = t; cur != null; cur = cur.getCause())
        {
            if (cur instanceof SSLHandshakeException)
                return true;
            String msg = cur.getMessage();
            if (msg != null && (msg.contains("PKIX path building failed")
                || msg.contains("unable to find valid certification path")
                || msg.contains("unable to find valid certification path to requested target")))
                return true;
        }
        return false;
    }

    private static final class CompositeX509TrustManager implements X509TrustManager
    {
        private final X509TrustManager primary;
        private final X509TrustManager fallback;
        private final X509Certificate[] issuers;
        CompositeX509TrustManager(X509TrustManager primary, X509TrustManager fallback)
        {
            this.primary = primary;
            this.fallback = fallback;
            X509Certificate[] a = primary.getAcceptedIssuers(),
                              b = fallback.getAcceptedIssuers();
            this.issuers = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, this.issuers, a.length, b.length);
        }
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            this.primary.checkClientTrusted(chain, authType);
        }
        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException
        {
            try
            {
                this.primary.checkServerTrusted(chain, authType);
            }
            catch (java.security.cert.CertificateException ex)
            {
                this.fallback.checkServerTrusted(chain, authType);
            }
        }
        @Override
        public X509Certificate[] getAcceptedIssuers()
        {
            return this.issuers.clone();
        }
    }

    public long getLocalDriftMillis()
    {
        return this.localDriftMillis;
    }

    public void login()
    {
        this.ensureSecureStoreReady();
        this.login(false);
        for (String alt : this.cookies.alts())
            this.loginAlt(alt, false);
    }

    private synchronized void ensureSecureStoreReady()
    {
        if (this.secureStoreReady)
            return;
        this.scarlet.splash.splashSubtext("Opening secure credential store");
        LOG.info("Opening secure credential store");
        this.cookies.load();
        this.scarlet.settings.setNamespace(MiscUtils.blank(this.groupId) ? "" : this.groupId);
        this.secureStoreReady = true;
        LOG.info("Secure credential store ready");
    }

    private void login(boolean isRefresh)
    {
try
{
        try
        {
            long timePre = System.currentTimeMillis(),
                 timeServer = new MiscellaneousApi(this.client).getSystemTime().toInstant().toEpochMilli(),
                 timePost = System.currentTimeMillis(),
                 drift = (timePre + timePost) / 2 - timeServer,
                 latency = Math.max(timePost - timePre, 0L) / 2,
                 driftAbs = Math.abs(drift);
            if (driftAbs >= 1_000L)
            {
                LOG.warn("Local system time is "+driftAbs+(drift > 0 ? "ms ahead of VRChat servers" : "ms behind VRChat servers"));
            }
            this.localDriftMillis = drift;
            this.latencyMillis = latency;
        }
        catch (ApiException apiex)
        {
            if (this.maybeEnableLegacyTrustCompatibility(apiex))
                return;
            LOG.error("Exception calculating drift", apiex);
        }
        {
            String username = this.scarlet.settings.getString("vrc_username"),
                   password = this.scarlet.settings.getString("vrc_password");
            if (username != null)
            {
                this.username.set(username);
                this.scarlet.settings.getJson().remove("vrc_username");
            }
            if (password != null)
            {
                this.password.set(password);
                this.scarlet.settings.getJson().remove("vrc_password");
            }
        }
        try
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            try
            {
                if (auth.verifyAuthToken().getOk().booleanValue())
                {
                    LOG.info("Logged in (cached-verified)");
                    try
                    {
                        this.currentUserId = (this.currentUser = this.getCurrentUser(auth)).getId();
                    }
                    catch (ApiException apiex)
                    {
                        LOG.info("Exception getting current user even though cached auth should be valid", apiex);
                    }
                    return;
                }
                else
                {
                    if (!isRefresh) LOG.info("Auth declared invalid");
                }
            }
            catch (ApiException apiex)
            {
                if (this.maybeEnableLegacyTrustCompatibility(apiex))
                    return;
                if (!isRefresh) LOG.info("Auth discovered invalid", apiex);
            }
            JsonObject data;
            try
            {
                data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                if (data.has("id"))
                {
                    LOG.info("Logged in (cached)");
                    this.currentUserId = (this.currentUser = JSON.getGson().fromJson(data, CurrentUser.class)).getId();
                    return;
                }
            }
            catch (Exception ex)
            {
                if (this.maybeEnableLegacyTrustCompatibility(ex))
                    return;
                if (!isRefresh) LOG.info("Cached auth not valid", ex);
                do try
                {
                    String username = this.username.getOrNull(),
                           password = this.password.getOrNull();
                    if (username == null)
                    {
                        username = this.scarlet.settings.getStringOrRequireInput("vrc_username", "VRChat Username", false);
                        this.scarlet.settings.getJson().remove("vrc_username");
                        this.username.set(username);
                    }
                    if (password == null)
                    {
                        password = this.scarlet.settings.getStringOrRequireInput("vrc_password", "VRChat Password", true);
                        this.scarlet.settings.getJson().remove("vrc_password");
                        this.password.set(password);
                    }
                    this.client.setUsername(username);
                    this.client.setPassword(password);
                    this.pendingUsername = username;
                    this.pendingPassword = password;
                    data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                    if (data.has("id"))
                    {
                        LOG.info("Logged in (credentials)");
                        this.currentUserId = (this.currentUser = JSON.getGson().fromJson(data, CurrentUser.class)).getId();
                        return;
                    }
                }
                catch (ApiException apiex)
                {
                    if (this.maybeEnableLegacyTrustCompatibility(apiex))
                        return;
                    this.username.set(null);
                    this.password.set(null);
                    LOG.error("Invalid credentials");
                    // Only offer the reset dialog when stored credentials silently
                    // failed — not when the user just typed them in this iteration
                    // (in which case the loop will simply re-prompt). Using the
                    // synchronous form so the do-while waits for the user's answer
                    // before looping back, preventing a tight spin/hang on Windows.
                    boolean hadStoredCredentials;
                    try { hadStoredCredentials = (this.username.getOrNull() != null || this.password.getOrNull() != null); }
                    catch (Exception ignored) { hadStoredCredentials = false; }
                    if (hadStoredCredentials)
                    {
                        boolean reset = this.scarlet.settings.requireConfirmYesNo(
                            "The stored credentials were rejected by VRChat.\n\n"
                            + "Would you like to reset all stored credentials?\n"
                            + "This safely removes them from the encrypted store\n"
                            + "so you can enter them fresh on the next attempt.\n\n"
                            + "(You do NOT need to touch the Registry or AppData.)",
                            "Invalid credentials \u2014 reset?");
                        if (reset)
                            this.clearCredentials();
                    }
                    data = null;
                }
                finally
                {
                    this.client.setUsername(null);
                    this.client.setPassword(null);
                    this.pendingUsername = null;
                    this.pendingPassword = null;
                }
                while (data == null);
            }
            
            List<String> twoFactorMethods = data.get("requiresTwoFactorAuth")
                    .getAsJsonArray()
                    .asList()
                    .stream()
                    .map(JsonElement::getAsJsonPrimitive)
                    .map(JsonPrimitive::getAsString)
                    .collect(Collectors.toList());
            if (twoFactorMethods.contains("totp"))
            {
                String secret = this.scarlet.settings.getString("vrc_secret");
                if (secret == null)
                    secret = this.totpsecret.getOrNull();
                else
                {
                    this.totpsecret.set(secret);
                    this.scarlet.settings.getJson().remove("vrc_secret");
                }
                if (secret != null && (secret = secret.replaceAll("[^A-Za-z2-7=]", "")).length() == 32)
                {
                    boolean authed = false;
                    for (int tries = 2; !authed && tries --> 0; MiscUtils.sleep(3_000L)) try
                    {
                        // use VRChatAPI time to work around potential local system time drift
                        long now = new MiscellaneousApi(this.client).getSystemTime().toInstant().toEpochMilli();
                        String code = TimeBasedOneTimePasswordUtil.generateNumberString(secret, now, TimeBasedOneTimePasswordUtil.DEFAULT_TIME_STEP_SECONDS, TimeBasedOneTimePasswordUtil.DEFAULT_OTP_LENGTH);
                        authed = auth.verify2FA(new TwoFactorAuthCode().code(code)).getVerified().booleanValue();
                    }
                    catch (GeneralSecurityException gsex)
                    {
                        LOG.error("Exception generating totp secret", gsex);
                    }
                    catch (ApiException apiex)
                    {
                        LOG.error("Exception using totp secret", apiex);
                    }
                }
                
                for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                {
                    if (needsTotp = !auth.verify2FA(new TwoFactorAuthCode().code(this.scarlet.settings.requireInput("Totp code", true))).getVerified().booleanValue())
                        LOG.error("Invalid totp code");
                }
                catch (ApiException apiex)
                {
                    LOG.error("Exception using totp", apiex);
                }
                
                LOG.info("Logged in (2fa-totp)");
            }
            else if (twoFactorMethods.contains("emailOtp"))
            {
                for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                {
                    if (needsTotp = !auth.verify2FAEmailCode(new TwoFactorEmailCode().code(this.scarlet.settings.requireInput("EmailOtp code", true))).getVerified().booleanValue())
                        LOG.error("Invalid emailOtp code");
                }
                catch (ApiException apiex)
                {
                    LOG.error("Exception using emailOtp", apiex);
                }
                
                LOG.info("Logged in (2fa-emailOtp)");
            }
            else
            {
                String message = "Unsupported 2fa methods: "+twoFactorMethods;
                LOG.error(message);
                throw new UnsupportedOperationException(message);
            }

            try
            {
                this.currentUserId = (this.currentUser = this.getCurrentUser(auth)).getId();
            }
            catch (ApiException apiex)
            {
                LOG.info("Exception getting current user even though current auth should be valid", apiex);
            }
            
            return;
        }
        finally
        {
            this.save();
            this.updateGroupInfo();
        }
}
finally
{
    ExtendedUserAgent.setCurrentUserId(this.currentUserId);
}
    }

    class AltCred extends ScarletVRChatCookieJar.AltCredContext
    {
        final boolean isNew;
        ScarletSettings.RegistryStringEncrypted altUsername, altPassword, altSecret;
        public AltCred(String altUserId)
        {
            ScarletVRChat.this.cookies.super(altUserId);
            this.isNew = altUserId == null;
            this.init(altUserId);
        }
        boolean init(String altUserId)
        {
            if (altUserId == null)
                return false;
            if (!this.isNew)
                return false;
            if (this.altUsername == null)
                this.altUsername = ScarletVRChat.this.scarlet.settings.new RegistryStringEncrypted("alt:"+altUserId+":username", true);
            if (this.altPassword == null)
                this.altPassword = ScarletVRChat.this.scarlet.settings.new RegistryStringEncrypted("alt:"+altUserId+":password", true);
            if (this.altSecret == null)
                this.altSecret = ScarletVRChat.this.scarlet.settings.new RegistryStringEncrypted("alt:"+altUserId+":totpsecret", true);
            return true;
        }
        void updateUP(String altUserId, String username, String password)
        {
            if (!this.init(altUserId))
                return;
            this.altUsername.set(username);
            this.altPassword.set(password);
        }
        void updateS(String altUserId, String totpsecret)
        {
            if (!this.init(altUserId))
                return;
            this.altSecret.set(totpsecret);
        }
        String getU() { return this.altUsername == null ? null : this.altUsername.getOrNull(); }
        String getP() { return this.altPassword == null ? null : this.altPassword.getOrNull(); }
        String getS() { return this.altSecret == null ? null : this.altSecret.getOrNull(); }
    }
    private String loginAlt(String context, boolean isRefresh)
    {
        try (AltCred alt = new AltCred(context))
        {
            try
            {
                AuthenticationApi auth = new AuthenticationApi(this.client);
                if (!alt.isNew) try
                {
                    if (auth.verifyAuthToken().getOk().booleanValue())
                    {
                        LOG.info("Logged in (cached-verified); alt: "+context);
                        String altUserId = null;
                        try
                        {
                            altUserId = this.getCurrentUser(auth).getId();
                        }
                        catch (ApiException apiex)
                        {
                            LOG.info("Exception getting current user even though cached auth should be valid; alt: "+context+"/"+altUserId, apiex);
                        }
                        return altUserId;
                    }
                    else
                    {
                        if (!isRefresh) LOG.info("Auth declared invalid; alt: "+context);
                    }
                }
                catch (ApiException apiex)
                {
                    if (this.maybeEnableLegacyTrustCompatibility(apiex))
                        return null;
                    if (!isRefresh) LOG.info("Auth discovered invalid; alt: "+context, apiex);
                }
                JsonObject data;
                String username = null, password = null;
                try
                {
                    data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                    if (data.has("id"))
                    {
                        String altUserId = data.getAsJsonPrimitive("id").getAsString();
                        LOG.info("Logged in (cached); alt: "+altUserId);
                        if (alt.isNew)
                            alt.transferTo(altUserId);
                        return altUserId;
                    }
                }
                catch (Exception ex)
                {
                    if (this.maybeEnableLegacyTrustCompatibility(ex))
                        return null;
                    if (!isRefresh) LOG.info("Cached auth not valid: "+context, ex);
                    do try
                    {
                        username = alt.altUsername == null ? null : alt.altUsername.getOrNull();
                        password = alt.altPassword == null ? null : alt.altPassword.getOrNull();
                        if (username == null)
                        {
                            username = this.scarlet.settings.requireInput("Alternate VRChat Username", false);
                            if (alt.altUsername != null)
                                alt.altUsername.set(username);
                        }
                        if (password == null)
                        {
                            password = this.scarlet.settings.requireInput("Alternate VRChat Password", true);
                            if (alt.altPassword != null)
                                alt.altPassword.set(password);
                        }
                        this.client.setUsername(username);
                        this.client.setPassword(password);
                        this.pendingUsername = username;
                        this.pendingPassword = password;
                        data = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData();
                        if (data.has("id"))
                        {
                            String altUserId = data.getAsJsonPrimitive("id").getAsString();
                            LOG.info("Logged in (credentials); alt: "+altUserId);
                            alt.updateUP(altUserId, username, password);
                            if (alt.isNew)
                                alt.transferTo(altUserId);
                            return altUserId;
                        }
                      }
                      catch (ApiException apiex)
                      {
                          if (this.maybeEnableLegacyTrustCompatibility(apiex))
                              return null;
                          if (alt.altUsername != null)
                              alt.altUsername.set(null);
                          if (alt.altPassword != null)
                            alt.altPassword.set(null);
                        data = null;
                        if ((username == null || username.isEmpty()) && (password == null || password.isEmpty()))
                        {
                            LOG.info("Canceling credentials; alt: "+context);
                            return null;
                        }
                        LOG.error("Invalid credentials; alt: "+context);
                        // Only offer reset when stored alt credentials silently failed.
                        // username/password are non-null here (checked above), so check
                        // whether they came from the registry rather than being freshly typed.
                        boolean hadStoredAltCredentials =
                            (alt.altUsername != null && alt.altUsername.getOrNull() != null)
                            || (alt.altPassword != null && alt.altPassword.getOrNull() != null);
                        if (hadStoredAltCredentials)
                        {
                            boolean reset = this.scarlet.settings.requireConfirmYesNo(
                                "The stored alternate credentials for \""+context+"\" were rejected by VRChat.\n\n"
                                + "Would you like to reset ALL stored credentials?\n"
                                + "This safely removes them from the encrypted store\n"
                                + "so you can enter them fresh on the next attempt.\n\n"
                                + "(You do NOT need to touch the Registry or AppData.)",
                                "Invalid credentials \u2014 reset?");
                            if (reset)
                                this.clearCredentials();
                        }
                    }
                    finally
                    {
                        this.client.setUsername(null);
                        this.client.setPassword(null);
                        this.pendingUsername = null;
                        this.pendingPassword = null;
                    }
                    while (data == null);
                }
                
                List<String> twoFactorMethods = data.get("requiresTwoFactorAuth")
                        .getAsJsonArray()
                        .asList()
                        .stream()
                        .map(JsonElement::getAsJsonPrimitive)
                        .map(JsonPrimitive::getAsString)
                        .collect(Collectors.toList());
                String code = null;
                if (twoFactorMethods.contains("totp"))
                {
                    String secret = alt.altSecret == null ? null : alt.altSecret.getOrNull();
                    if (secret != null && (secret = secret.replaceAll("[^A-Za-z2-7=]", "")).length() == 32)
                    {
                        boolean authed = false;
                        for (int tries = 2; !authed && tries --> 0; MiscUtils.sleep(3_000L)) try
                        {
                            // use VRChatAPI time to work around potential local system time drift
                            long now = new MiscellaneousApi(this.client).getSystemTime().toInstant().toEpochMilli();
                            code = TimeBasedOneTimePasswordUtil.generateNumberString(secret, now, TimeBasedOneTimePasswordUtil.DEFAULT_TIME_STEP_SECONDS, TimeBasedOneTimePasswordUtil.DEFAULT_OTP_LENGTH);
                            authed = auth.verify2FA(new TwoFactorAuthCode().code(code)).getVerified().booleanValue();
                        }
                        catch (GeneralSecurityException gsex)
                        {
                            LOG.error("Exception generating totp secret; alt: "+context, gsex);
                        }
                        catch (ApiException apiex)
                        {
                            LOG.error("Exception using totp secret; alt: "+context, apiex);
                        }
                    }
                    
                    for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                    {
                        if (needsTotp = !auth.verify2FA(new TwoFactorAuthCode().code(code = this.scarlet.settings.requireInput("Totp code", true))).getVerified().booleanValue())
                            LOG.error("Invalid totp code; alt: "+context);
                    }
                    catch (ApiException apiex)
                    {
                        if (code == null || code.isEmpty())
                        {
                            LOG.info("Canceling using totp; alt: "+context);
                            return null;
                        }
                        LOG.error("Exception using totp; alt: "+context, apiex);
                    }
                    
                    LOG.info("Logged in (2fa-totp); alt: "+context);
                }
                else if (twoFactorMethods.contains("emailOtp"))
                {
                    for (boolean needsTotp = true; needsTotp && this.scarlet.running;) try
                    {
                        if (needsTotp = !auth.verify2FAEmailCode(new TwoFactorEmailCode().code(code = this.scarlet.settings.requireInput("EmailOtp code", true))).getVerified().booleanValue())
                            LOG.error("Invalid emailOtp code; alt: "+context);
                    }
                    catch (ApiException apiex)
                    {
                        if (code == null || code.isEmpty())
                        {
                            LOG.info("Canceling using emailOtp; alt: "+context);
                            return null;
                        }
                        LOG.error("Exception using emailOtp; alt: "+context, apiex);
                    }
                    
                    LOG.info("Logged in (2fa-emailOtp); alt: "+context);
                }
                else
                {
                    String message = "Unsupported 2fa methods: "+twoFactorMethods+"; alt: "+context;
                    LOG.error(message);
                    throw new UnsupportedOperationException(message);
                }
    
                String altUserId = null;
                try
                {
                    altUserId = this.getCurrentUser(auth).getId();
                    alt.updateUP(altUserId, username, password);
                    if (alt.isNew)
                        alt.transferTo(altUserId);
                }
                catch (ApiException apiex)
                {
                    LOG.info("Exception getting current user even though current auth should be valid; alt: "+context+"/"+altUserId, apiex);
                }
    
                return altUserId;
            }
            finally
            {
                this.save();
            }
        }
    }
    void updateGroupInfo()
    {
        // Check if we have a valid currentUserId before proceeding
        if (this.currentUserId == null)
        {
            LOG.warn("Cannot update group info: currentUserId is null (login may have failed)");
            return;
        }
        this.resolveActiveGroupId();
        if (MiscUtils.blank(this.groupId))
        {
            LOG.error("Cannot update group info: no valid VRChat group id is configured or could be auto-resolved for user `{}`", this.currentUserId);
            return;
        }
        Group group;
        try
        {
            group = this.getGroup(this.groupId, Boolean.TRUE);
        }
        catch (RuntimeException ex)
        {
            LOG.error("Cannot update group info because VRChat returned an unexpected group payload", ex);
            return;
        }
        if (group != null)
        {
            this.groupOwnerId = group.getOwnerId();
            this.group = group;
            this.groupLimitedMember = this.getGroupMembership(this.groupId, this.currentUserId);
            if (this.groupLimitedMember == null)
            {
                if (this.scarlet.settings.requireConfirmYesNo("The main VRChat account does not appear\nto be part of the group, delete credentials?", "Wrong account?"))
                {
                    this.cookies.clear();
                    this.cookies.save();
                    throw new IllegalStateException();
                }
            }
            if (group.getRoles() == null)
            {
                group.setRoles(this.getGroupRoles(this.groupId));
                this.groupRoles.clear();
                group.getRoles().forEach(role -> this.groupRoles.put(role.getId(), role));
            }
        }
        try
        {
            Map<String, String> localVarHeaderParams = new HashMap<>();
            localVarHeaderParams.put("Accept", "application/json");
            okhttp3.Call localVarCall = this.client.buildCall(null, "/users/"+this.currentUserId+"/groups/permissions", "GET", new ArrayList<>(), new ArrayList<>(), null, localVarHeaderParams, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            JsonObject json = this.client.<JsonObject>execute(localVarCall, JsonObject.class).getData();
            this.allGroupPermissions = new VrcAllGroupPermissions(json);
        }
        catch (Exception ex)
        {
            LOG.warn("Failed to get group permissions: {}", ex.getMessage());
        }
    }

    private void resolveActiveGroupId()
    {
        if (!MiscUtils.blank(this.groupId))
            return;
        String resolvedGroupId = this.getRepresentedGroupId(this.currentUserId);
        if (MiscUtils.blank(resolvedGroupId))
        {
            List<LimitedUserGroups> userGroups = this.getUserGroups(this.currentUserId);
            if (userGroups != null)
            {
                resolvedGroupId = userGroups.stream()
                    .filter(Objects::nonNull)
                    .filter(group -> Boolean.TRUE.equals(group.getIsRepresenting()))
                    .map(LimitedUserGroups::getGroupId)
                    .filter(groupId -> !MiscUtils.blank(groupId))
                    .findFirst()
                    .orElseGet(() -> userGroups.size() == 1 ? userGroups.get(0).getGroupId() : null);
            }
        }
        if (!MiscUtils.blank(resolvedGroupId))
        {
            this.groupId = MiscUtils.extractTypedUuid("grp", "", resolvedGroupId);
            ExtendedUserAgent.setCurrentGroupId(this.groupId);
            this.scarlet.settings.setString("vrchat_group_id", this.groupId);
            this.scarlet.settings.setNamespace(this.groupId);
            LOG.warn("Recovered missing VRChat group id from the logged-in user's group context: `{}`", this.groupId);
        }
    }

    private String getRepresentedGroupId(String userId)
    {
        if (MiscUtils.blank(userId))
            return null;
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            JsonObject represented = this.client.<JsonObject>execute(
                this.client.buildCall(
                    null,
                    "/users/"+userId+"/groups/represented",
                    "GET",
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null,
                    headers,
                    new HashMap<>(),
                    new HashMap<>(),
                    new String[]{"authCookie"},
                    null
                ),
                JsonObject.class
            ).getData();
            if (represented == null)
                return null;
            JsonElement groupId = represented.get("groupId");
            return groupId == null || groupId.isJsonNull() ? null : groupId.getAsString();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.warn("Unable to resolve represented VRChat group for `{}`: {}", userId, apiex.getMessage());
            return null;
        }
    }
    public boolean checkGroupHasAdminTag(GroupAdminTag adminTag)
    {
        if (adminTag == null)
            return false;
        Group group = this.group;
        if (group == null)
            return false;
        List<String> tags = group.getTags();
        if (tags == null || tags.isEmpty())
            return false;
        return tags.contains(adminTag.value);
    }
    /**
     * <u><i><b>REMOVE AFTER API SDK UPDATE</b></i></u>
     */

@Deprecated
CurrentUser getCurrentUser(AuthenticationApi auth) throws ApiException
{
    JsonObject json = this.client.<JsonObject>execute(auth.getCurrentUserCall(null), JsonObject.class).getData(),
               presence = json.getAsJsonObject("presence");

    if (presence != null)
    {
        JsonElement cat = presence.get("currentAvatarTags");

        if (cat != null && cat.isJsonPrimitive())
        {
            // Convert comma-separated string → array (what Gson expects)
            String tagsStr = cat.getAsString();
            JsonArray tagsArray = new JsonArray();

            if (tagsStr != null && !tagsStr.isEmpty())
            {
                for (String tag : tagsStr.split(","))
                {
                    tagsArray.add(tag.trim());
                }
            }

            presence.add("currentAvatarTags", tagsArray);
        }
    }

    return JSON.getGson().fromJson(json, CurrentUser.class);
}

    public boolean logout()
    {
        return this.logout(false);
    }

    private boolean logout(boolean isRefresh)
    {
        try
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            if (!isRefresh) LOG.info("Log out: "+auth.logout().getSuccess().getMessage());
            return true;
        }
        catch (ApiException apiex)
        {
            LOG.error("Error logging out", apiex);
            return false;
        }
    }

    private boolean logoutAlt(String context, boolean isRefresh)
    {
        try (ScarletVRChatCookieJar.AltCredContext alt = this.cookies.new AltCredContext(context))
        {
            AuthenticationApi auth = new AuthenticationApi(this.client);
            if (!isRefresh) LOG.info("Log out: "+auth.logout().getSuccess().getMessage()+": "+context);
            return true;
        }
        catch (ApiException apiex)
        {
            LOG.error("Error logging out: "+context, apiex);
            return false;
        }
    }

    public void refresh()
    {
        LOG.info("Refreshing auth");
        this.logout(true);
        this.login(true);
        for (String alt : this.cookies.alts())
        {
            this.logoutAlt(alt, true);
            this.loginAlt(alt, true);
        }
    }

    Map<String, String> getConfigLanguageOptions()
    {
        MiscellaneousApi misc = new MiscellaneousApi(this.client);
        try
        {
            return misc.getConfig().getConstants().getLANGUAGE().getSPOKENLANGUAGEOPTIONS();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during audit query: "+apiex.getMessage());
            return null;
        }
    }

    public List<GroupAuditLogEntry> auditQuery(OffsetDateTime from, OffsetDateTime to)
    {
        return this.auditQuery(from, to, null, null, null);
    }
    public List<GroupAuditLogEntry> auditQueryTargeting(String targetIds, int daysBack)
    {
        OffsetDateTime to = OffsetDateTime.now(),
                       from = to.minusDays(daysBack);
        return this.auditQuery(from, to, null, null, targetIds);
    }
    public List<GroupAuditLogEntry> auditQueryActored(String actorIds, int daysBack)
    {
        OffsetDateTime to = OffsetDateTime.now(),
                       from = to.minusDays(daysBack);
        return this.auditQuery(from, to, actorIds, null, null);
    }
    public List<GroupAuditLogEntry> auditQuery(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupAuditLogEntry> audits = new ArrayList<>();
            int offset = 0, batchSize = 100;
            PaginatedGroupAuditLogEntryList pgalel;
            pgalel = groups.getGroupAuditLogs(this.groupId, batchSize, offset, from, to, actorIds, eventTypes, targetIds);
            while (pgalel.getHasNext().booleanValue())
            {
                audits.addAll(pgalel.getResults());
                offset += batchSize;
                MiscUtils.sleep(250L);
                pgalel = groups.getGroupAuditLogs(this.groupId, batchSize, offset, from, to, actorIds, eventTypes, targetIds);
            }
            audits.addAll(pgalel.getResults());
            audits.sort(OLDEST_TO_NEWEST);
            return audits;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (this.isAuditEndpointNotImplemented(apiex))
            {
                List<GroupAuditLogEntry> fallback = this.auditQueryFallback(from, to, actorIds, eventTypes, targetIds);
                if (fallback != null)
                    return fallback;
            }
            LOG.error("Error during audit query: "+apiex.getMessage());
            return null;
        }
    }
    public Integer auditQueryCount(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupAuditLogs(this.groupId, 0, 0, from, to, actorIds, eventTypes, targetIds).getTotalCount();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (this.isAuditEndpointNotImplemented(apiex))
            {
                Integer fallback = this.auditQueryCountFallback(from, to, actorIds, eventTypes, targetIds);
                if (fallback != null)
                    return fallback;
            }
            LOG.error("Error during audit query: "+apiex.getMessage());
            return null;
        }
    }

    private boolean isAuditEndpointNotImplemented(ApiException apiex)
    {
        if (apiex == null || apiex.getCode() != 404)
            return false;
        String message = apiex.getMessage();
        return message != null && message.toLowerCase().contains("not implemented by our system");
    }

    private List<GroupAuditLogEntry> auditQueryFallback(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        for (AuditFallbackMode mode : AuditFallbackMode.values())
        {
            try
            {
                LOG.warn("VRChat rejected the generated audit-log request for group `{}`; retrying with Scarlet audit fallback mode `{}`", this.groupId, mode.name());
                List<GroupAuditLogEntry> audits = new ArrayList<>();
                int offset = 0, batchSize = 100;
                PaginatedGroupAuditLogEntryList page = this.getGroupAuditLogsRaw(batchSize, offset, from, to, actorIds, eventTypes, targetIds, mode);
                if (page == null)
                    return Collections.emptyList();
                while (Boolean.TRUE.equals(page.getHasNext()))
                {
                    if (page.getResults() != null)
                        audits.addAll(page.getResults());
                    offset += batchSize;
                    MiscUtils.sleep(250L);
                    page = this.getGroupAuditLogsRaw(batchSize, offset, from, to, actorIds, eventTypes, targetIds, mode);
                    if (page == null)
                        break;
                }
                if (page != null && page.getResults() != null)
                    audits.addAll(page.getResults());
                audits.sort(OLDEST_TO_NEWEST);
                LOG.warn("Scarlet audit fallback mode `{}` succeeded for group `{}` with {} entrie(s)", mode.name(), this.groupId, Integer.valueOf(audits.size()));
                return audits;
            }
            catch (ApiException apiex)
            {
                if (!this.isAuditEndpointNotImplemented(apiex))
                {
                    LOG.error("Scarlet audit fallback mode `{}` failed", mode.name(), apiex);
                    return null;
                }
                LOG.warn("Scarlet audit fallback mode `{}` still hit VRChat's `not implemented` response", mode.name());
            }
            catch (Exception ex)
            {
                LOG.error("Scarlet audit fallback mode `{}` failed", mode.name(), ex);
                return null;
            }
        }
        return null;
    }

    private Integer auditQueryCountFallback(OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds)
    {
        for (AuditFallbackMode mode : AuditFallbackMode.values())
        {
            try
            {
                PaginatedGroupAuditLogEntryList page = this.getGroupAuditLogsRaw(1, 0, from, to, actorIds, eventTypes, targetIds, mode);
                if (page != null)
                    return page.getTotalCount();
            }
            catch (ApiException apiex)
            {
                if (!this.isAuditEndpointNotImplemented(apiex))
                {
                    LOG.error("Scarlet audit-count fallback mode `{}` failed", mode.name(), apiex);
                    return null;
                }
            }
            catch (Exception ex)
            {
                LOG.error("Scarlet audit-count fallback mode `{}` failed", mode.name(), ex);
                return null;
            }
        }
        return null;
    }

    private PaginatedGroupAuditLogEntryList getGroupAuditLogsRaw(int n, int offset, OffsetDateTime from, OffsetDateTime to, String actorIds, String eventTypes, String targetIds, AuditFallbackMode mode) throws ApiException
    {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");

        List<io.github.vrchatapi.Pair> query = new ArrayList<>();
        query.addAll(this.client.parameterToPair("n", Integer.valueOf(n)));
        query.addAll(this.client.parameterToPair("offset", Integer.valueOf(offset)));

        if (mode.includeDates && from != null)
            query.addAll(this.client.parameterToPair("startDate", from));
        if (mode.includeDates && to != null)
            query.addAll(this.client.parameterToPair("endDate", to));
        if (mode.includeFilters && !MiscUtils.blank(actorIds))
            query.addAll(this.client.parameterToPair("actorIds", actorIds));
        if (mode.includeFilters && !MiscUtils.blank(eventTypes))
            query.addAll(this.client.parameterToPair("eventTypes", eventTypes));
        if (mode.includeFilters && !MiscUtils.blank(targetIds))
            query.addAll(this.client.parameterToPair("targetIds", targetIds));

        okhttp3.Call call = this.client.buildCall(
            null,
            "/groups/"+this.groupId+"/auditLogs",
            "GET",
            query,
            new ArrayList<>(),
            null,
            headers,
            new HashMap<>(),
            new HashMap<>(),
            new String[]{"authCookie"},
            null
        );
        return this.client.<PaginatedGroupAuditLogEntryList>execute(call, PaginatedGroupAuditLogEntryList.class).getData();
    }

    private enum AuditFallbackMode
    {
        FULL_FILTERS(true, true),
        NO_OPTIONAL_FILTERS(true, false),
        NO_DATES_OR_FILTERS(false, false);

        final boolean includeDates;
        final boolean includeFilters;

        AuditFallbackMode(boolean includeDates, boolean includeFilters)
        {
            this.includeDates = includeDates;
            this.includeFilters = includeFilters;
        }
    }
    public List<LimitedUserSearch> searchUsers(String name, Integer n, Integer offset)
    {
        UsersApi users = new UsersApi(this.client);
        try
        {
            return users.searchUsers(name, null, n, offset, null);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            return null;
        }
    }
    public String searchUserId(String name)
    {
        List<LimitedUserSearch> search = this.searchUsers(name, 100, 0);
        return search == null ? null : search.stream().filter(user -> name.equals(user.getDisplayName())).findFirst().map(LimitedUserSearch::getId).orElse(null);
    }

    public String getUserDisplayName(String userId)
    {
        if (userId == null)
            return null;
        User user = this.getUser(userId, Long.MIN_VALUE);
        return user == null ? userId : user.getDisplayName();
    }
    public User getUser(String userId)
    {
        return this.getUser(userId, Long.MAX_VALUE);
    }
    public User getUser(String userId, long minEpoch)
    {
        User user = this.cachedUsers.get(userId, minEpoch);
        if (user != null)
            return user;
        if (this.cachedUsers.is404(userId))
            return null;
        UsersApi users = new UsersApi(this.client);
        try
        {
            user = users.getUser(userId);
            this.cachedUsers.put(userId, user);
            return user;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUsers.add404(userId);
            else
                LOG.error("Error during get user: "+apiex.getMessage());
            return null;
        }
    }

    public List<LimitedWorld> searchWorlds(String name, Integer n, Integer offset)
    {
        WorldsApi worlds = new WorldsApi(this.client);
        try
        {
            return worlds.searchWorlds(null, SortOption.RELEVANCE, null, null, n, OrderOption.DESCENDING, offset, null, null, null, null, null, null, null, null, null, null);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            return null;
        }
    }
    public World getWorld(String worldId)
    {
        return this.getWorld(worldId, Long.MAX_VALUE);
    }
    public World getWorld(String worldId, long minEpoch)
    {
        World world = this.cachedWorlds.get(worldId, minEpoch);
        if (world != null)
            return world;
        if (this.cachedWorlds.is404(worldId))
            return null;
        WorldsApi worlds = new WorldsApi(this.client);
        try
        {
            world = worlds.getWorld(worldId);
            this.cachedWorlds.put(worldId, world);
            return world;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedWorlds.add404(worldId);
            else
                LOG.error("Error during get world: "+apiex.getMessage());
            return null;
        }
    }
    public Instance getInstance(String worldId, String instanceId)
    {
        InstancesApi instances = new InstancesApi(this.client);
        try
        {
            return instances.getInstance(worldId, instanceId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during get instance: "+apiex.getMessage());
        }
        
        WorldsApi worlds = new WorldsApi(this.client);
        try
        {
            return worlds.getWorldInstance(worldId, instanceId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during get world instance: "+apiex.getMessage());
        }
        return null;
    }
    public Instance closeInstance(String worldId, String instanceId, Boolean hardClose, OffsetDateTime closedAt)
    {
        InstancesApi instances = new InstancesApi(this.client);
        try
        {
            return instances.closeInstance(worldId, instanceId, hardClose, closedAt);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during close instance: "+apiex.getMessage());
            return null;
        }
    }

    public Group getGroup(String groupId)
    {
        return this.getGroup(groupId, null, Long.MAX_VALUE);
    }
    public Group getGroup(String groupId, long minEpoch)
    {
        return this.getGroup(groupId, null, minEpoch);
    }
    public Group getGroup(String groupId, Boolean includeRoles)
    {
        return this.getGroup(groupId, includeRoles, Long.MAX_VALUE);
    }
    public Group getGroup(String groupId, Boolean includeRoles, long minEpoch)
    {
        Group group = this.cachedGroups.get(groupId, minEpoch);
        if (group != null)
            return group;
        if (this.cachedGroups.is404(groupId))
            return null;
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            group = groups.getGroup(groupId, includeRoles);
            this.cachedGroups.put(groupId, group);
            return group;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedGroups.add404(groupId);
            else
                LOG.error("Error during get group: "+apiex.getMessage());
            return null;
        }
        catch (RuntimeException rex)
        {
            LOG.warn("VRChat returned an unexpected payload shape for group `{}`; attempting raw response normalization", groupId, rex);
            Group recovered = this.getGroupFromUnexpectedPayload(groups, groupId, includeRoles);
            if (recovered != null)
            {
                this.cachedGroups.put(groupId, recovered);
                return recovered;
            }
            LOG.error("Error during get group: unexpected VRChat response while resolving group `{}`", groupId, rex);
            return null;
        }
    }
    private Group getGroupFromUnexpectedPayload(GroupsApi groups, String groupId, Boolean includeRoles)
    {
        try
        {
            JsonElement element = this.client.<JsonElement>execute(groups.getGroupCall(groupId, includeRoles, null), JsonElement.class).getData();
            JsonObject normalized = this.normalizeGroupPayload(groupId, element);
            if (normalized == null)
                return null;
            return JSON.getGson().fromJson(normalized, Group.class);
        }
        catch (Exception ex)
        {
            LOG.error("Failed to recover group `{}` from unexpected VRChat payload", groupId, ex);
            return null;
        }
    }
    private JsonObject normalizeGroupPayload(String groupId, JsonElement element)
    {
        if (element == null || element.isJsonNull())
            return null;
        if (element.isJsonObject())
            return element.getAsJsonObject();
        if (element.isJsonArray())
        {
            JsonArray array = element.getAsJsonArray();
            if (array.size() == 1 && array.get(0).isJsonObject())
            {
                LOG.warn("Recovered group `{}` from a single-element array payload", groupId);
                return array.get(0).getAsJsonObject();
            }
            for (JsonElement child : array)
                if (child != null && child.isJsonObject())
                {
                    JsonObject object = child.getAsJsonObject();
                    JsonPrimitive id = object.getAsJsonPrimitive("id");
                    if (id != null && groupId.equals(id.getAsString()))
                    {
                        LOG.warn("Recovered group `{}` from an array payload containing multiple objects", groupId);
                        return object;
                    }
                }
            LOG.error("Unexpected array payload while resolving group `{}`: {}", groupId, element);
            return null;
        }
        LOG.error("Unexpected non-object payload while resolving group `{}`: {}", groupId, element);
        return null;
    }

    public List<GroupInstance> getGroupInstances(String groupId)
    {
        if (this.cachedGroups.is404(groupId))
            return null;
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupInstances(groupId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedGroups.add404(groupId);
            else
                LOG.error("Error during get group instances: "+apiex.getMessage());
            return null;
        }
    }

    public List<LimitedUserGroups> getUserGroups(String userId)
    {
        return this.getUserGroups(userId, Long.MAX_VALUE);
    }
    public List<LimitedUserGroups> getUserGroups(String userId, long minEpoch)
    {
        List<LimitedUserGroups> userGroups = this.cachedUserGroups.get(userId, minEpoch);
        if (userGroups != null)
            return userGroups;
        if (this.cachedUserGroups.is404(userId))
            return null;
        UsersApi users = new UsersApi(this.client);
        try
        {
            userGroups = users.getUserGroups(userId);
            this.cachedUserGroups.put(userId, userGroups);
            return userGroups;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUserGroups.add404(userId);
            else
                LOG.error("Error during get user groups: "+apiex.getMessage());
            return null;
        }
    }

    static final Type _LimitedUserGroups_Array = new TypeToken<List<LimitedUserGroups>>(){}.getType();
    public List<LimitedUserGroups> getMutualsGroups(String userId)
    {
        if (this.cachedUserGroups.is404(userId))
            return null;
        try
        {
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            return this.client.<List<LimitedUserGroups>>execute(this.client.buildCall(null, "/users/"+userId+"/mutuals/groups", "GET", new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null), _LimitedUserGroups_Array).getData();
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedUserGroups.add404(userId);
            else
                LOG.error("Error during get user mutual groups: "+apiex.getMessage());
            return null;
        }
    }

    public GroupMemberStatus getGroupMembershipStatus(String groupId, String targetUserId)
    {
        GroupMember member = this.getGroupMembership(groupId, targetUserId);
        return member == null ? null : member.getMembershipStatus();
    }
    public GroupMember getGroupMembership(String groupId, String targetUserId)
    {
        // Validate required parameters
        if (groupId == null || groupId.isEmpty())
        {
            LOG.error("getGroupMembership called with null or empty groupId");
            return null;
        }
        if (targetUserId == null || targetUserId.isEmpty())
        {
            LOG.error("getGroupMembership called with null or empty targetUserId");
            return null;
        }
        
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupMember(groupId, targetUserId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            // Only return null for 404 (user not in group), propagate other errors
            if (apiex.getCode() == 404)
            {
                LOG.debug("User {} is not a member of group {}", targetUserId, groupId);
                return null;
            }
            LOG.error("Error getting group member group: "+apiex.getMessage());
            throw new RuntimeException("Failed to get group membership: " + apiex.getMessage(), apiex);
        }
    }
    public GroupMember updateGroupMembershipNotes(String groupId, String targetUserId, String managerNotes)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.updateGroupMember(groupId, targetUserId, new UpdateGroupMemberRequest().managerNotes(managerNotes));
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error updating group member notes: "+apiex.getMessage());
            return null;
        }
    }
    public List<String> addGroupRole(String groupId, String targetUserId, String groupRoleId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.addGroupMemberRole(groupId, targetUserId, groupRoleId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error updating group member notes: "+apiex.getMessage());
            return null;
        }
    }
    public List<String> removeGroupRole(String groupId, String targetUserId, String groupRoleId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.removeGroupMemberRole(groupId, targetUserId, groupRoleId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error updating group member notes: "+apiex.getMessage());
            return null;
        }
    }
    public List<GroupRole> getGroupRoles(String groupId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            return groups.getGroupRoles(groupId);
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group roles: "+apiex.getMessage());
            return null;
        }
    }

    public List<GroupGalleryImage> getGroupGalleryImages(String groupId, String galleryId, Boolean approved)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            List<GroupGalleryImage> images = new ArrayList<>();
            int offset = 0, batchSize = 100;
            List<GroupGalleryImage> ggil;
            ggil = groups.getGroupGalleryImages(groupId, galleryId, batchSize, offset, approved);
            while (ggil != null && !ggil.isEmpty())
            {
                images.addAll(ggil);
                offset += ggil.size();
                MiscUtils.sleep(250L);
                ggil = groups.getGroupGalleryImages(groupId, galleryId, batchSize, offset, approved);
            }
            return images;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error getting group gallery images: "+apiex.getMessage());
            return null;
        }
    }

    public boolean banFromGroup(String targetUserId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.banGroupMember(this.groupId, new BanGroupMemberRequest().userId(targetUserId));
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during ban from group: "+apiex.getMessage());
            return false;
        }
    }

    public boolean unbanFromGroup(String targetUserId)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.unbanGroupMember(this.groupId, targetUserId);
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during unban from group: "+apiex.getMessage());
            return false;
        }
    }

    public boolean inviteToGroup(String targetUserId, Boolean confirmOverrideBlock)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.createGroupInvite(this.groupId, new CreateGroupInviteRequest().userId(targetUserId).confirmOverrideBlock(confirmOverrideBlock));
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during invite to group: "+apiex.getMessage());
            return false;
        }
    }

    public boolean respondToGroupJoinRequest(String targetUserId, GroupJoinRequestAction action, Boolean block)
    {
        GroupsApi groups = new GroupsApi(this.client);
        try
        {
            groups.respondGroupJoinRequest(this.groupId, targetUserId, new RespondGroupJoinRequest().action(action).block(block));
            return true;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            LOG.error("Error during response to group join request: "+apiex.getMessage());
            return false;
        }
    }

    public boolean checkSelfUserHasVRChatPermission(GroupPermissions vrchatPermission)
    {
        if (vrchatPermission == null || this.groupId == null || this.groupId.isEmpty())
            return false;

        if (this.allGroupPermissions.has(this.groupId, vrchatPermission))
            return true;

        if (this.currentUserId != null && this.currentUserId.equals(this.groupOwnerId))
        {
            LOG.debug("VRChat permission `{}` granted by group owner fallback for `{}` in group `{}`", vrchatPermission.getValue(), this.currentUserId, this.groupId);
            return true;
        }

        GroupMember selfMembership = this.groupLimitedMember;
        if (selfMembership == null && this.currentUserId != null)
        {
            selfMembership = this.getGroupMembership(this.groupId, this.currentUserId);
            if (selfMembership != null)
                this.groupLimitedMember = selfMembership;
        }
        if (selfMembership != null && this.checkUserHasVRChatPermission(selfMembership, vrchatPermission))
        {
            LOG.debug("VRChat permission `{}` granted by local role fallback for `{}` in group `{}`", vrchatPermission.getValue(), this.currentUserId, this.groupId);
            return true;
        }

        if (!this.allGroupPermissions.hasGroup(this.groupId))
            LOG.warn("VRChat permission cache did not contain group `{}` for `{}`; local role fallback also did not confirm `{}`", this.groupId, this.currentUserId, vrchatPermission.getValue());

        return false;
    }
    public boolean checkUserHasVRChatPermission(GroupPermissions vrchatPermission, String userId)
    {
        if (userId == null)
            return false;
        GroupMember glm = this.getGroupMembership(this.groupId, userId);
        return this.checkUserHasVRChatPermission(glm, vrchatPermission);
    }
    public boolean checkUserHasVRChatPermission(GroupMember glm, GroupPermissions vrchatPermission)
    {
        if (glm == null)
            return false;
        List<GroupRole> grl = this.group.getRoles();
        return grl != null && grl.stream().filter(Objects::nonNull).filter($ -> glm.getRoleIds().contains($.getId())).map(GroupRole::getPermissions).filter(Objects::nonNull).anyMatch($ -> $.contains(GroupPermissions.group_all) || $.contains(vrchatPermission));
    }

    public Avatar getAvatar(String avatarId)
    {
        return this.getAvatar(avatarId, Long.MAX_VALUE);
    }
    public Avatar getAvatar(String avatarId, long minEpoch)
    {
        Avatar avatar = this.cachedAvatars.get(avatarId, minEpoch);
        if (avatar != null)
            return avatar;
        if (this.cachedAvatars.is404(avatarId))
            return null;
        AvatarsApi avatars = new AvatarsApi(this.client);
        try
        {
            avatar = avatars.getAvatar(avatarId);
            this.cachedAvatars.put(avatarId, avatar);
            return avatar;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedAvatars.add404(avatarId);
            else
                LOG.error("Error during get avatar: ", apiex);//LOG.error("Error during get avatar: "+apiex.getMessage());
            return null;
        }
    }

    public Print getPrint(String printId)
    {
        return this.getPrint(printId, Long.MAX_VALUE);
    }
    public Print getPrint(String printId, long minEpoch)
    {
        Print print = this.cachedPrints.get(printId, minEpoch);
        if (print != null)
            return print;
        if (this.cachedPrints.is404(printId))
            return null;
        PrintsApi prints = new PrintsApi(this.client);
        try
        {
            print = prints.getPrint(printId);
            this.cachedPrints.put(printId, print);
            return print;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedPrints.add404(printId);
            else
                LOG.error("Error during get print: "+apiex.getMessage());
            return null;
        }
    }

    public Prop getProp(String propId)
    {
        return this.getProp(propId, Long.MAX_VALUE);
    }
    public Prop getProp(String propId, long minEpoch)
    {
        Prop prop = this.cachedProps.get(propId, minEpoch);
        if (prop != null)
            return prop;
        if (this.cachedProps.is404(propId))
            return null;
        PropsApi props = new PropsApi(this.client);
        try
        {
            prop = props.getProp(propId);
            this.cachedProps.put(propId, prop);
            return prop;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedProps.add404(propId);
            else
                LOG.error("Error during get prop: "+apiex.getMessage());
            return null;
        }
    }

    public InventoryItem getInventoryItem(String userId, String invId)
    {
        return this.getInventoryItem(userId, invId, Long.MAX_VALUE);
    }
    public InventoryItem getInventoryItem(String userId, String invId, long minEpoch)
    {
        InventoryItem item = this.cachedInventoryItems.get(invId, minEpoch);
        if (item != null)
            return item;
        if (this.cachedInventoryItems.is404(invId))
            return null;
//        InventoryApi inventory = new InventoryApi(this.client);
        try
        {
//            item = inventory.getInventoryItem(userId, invId);
            item = this.getInventoryItemEx(userId, invId);
            this.cachedInventoryItems.put(invId, item);
            return item;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedInventoryItems.add404(invId);
            else
                LOG.error("Error during get inventory item: "+apiex.getMessage());
            return null;
        }
    }
    public InventoryItem getInventoryItemEx(String userId, String invId) throws ApiException
    {
        Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
        okhttp3.Call localVarCall = this.client.buildCall(null, "/user/"+userId+"/inventory/"+invId, "GET", new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
        ApiResponse<InventoryItem> localVarResp = this.client.execute(localVarCall, InventoryItem.class);
        return localVarResp.getData();
    }

    public ModelFile getModelFile(String fileId)
    {
        return this.getModelFile(fileId, Long.MAX_VALUE);
    }
    public ModelFile getModelFile(String fileId, long minEpoch)
    {
        ModelFile file = this.cachedModelFiles.get(fileId, minEpoch);
        if (file != null)
            return file;
        if (this.cachedModelFiles.is404(fileId))
            return null;
        FilesApi files = new FilesApi(this.client);
        try
        {
            file = files.getFile(fileId);
            this.cachedModelFiles.put(fileId, file);
            return file;
        }
        catch (ApiException apiex)
        {
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedModelFiles.add404(fileId);
            else
                LOG.error("Error during get file: "+apiex.getMessage());
            return null;
        }
    }

    public FileAnalysis getFileAnalysis(VersionedFile versionedFile)
    {
        return this.getFileAnalysis(versionedFile, Long.MAX_VALUE);
    }
    public FileAnalysis getFileAnalysis(VersionedFile versionedFile, long minEpoch)
    {
        String stringified = versionedFile.toString('#'); // avoid ':' and '/' for file path semantic reasons
        FileAnalysis analysis = this.cachedFileAnalyses.get(stringified, minEpoch);
        if (analysis != null)
            return analysis;
        if (this.cachedFileAnalyses.is404(stringified))
        {
            LOG.warn("file 404: "+versionedFile);
            return null;
        }
        FilesApi files = new FilesApi(this.client);
        try
        {
            if ("security".equals(versionedFile.qualifier))
                analysis = files.getFileAnalysisSecurity(versionedFile.id, versionedFile.version);
            else if ("standard".equals(versionedFile.qualifier))
                analysis = files.getFileAnalysisStandard(versionedFile.id, versionedFile.version);
            else
                analysis = files.getFileAnalysis(versionedFile.id, versionedFile.version);
            this.cachedFileAnalyses.put(stringified, analysis);
            return analysis;
        }
        catch (ApiException apiex)
        {
            LOG.warn("file exception: "+versionedFile, apiex);
            this.scarlet.checkVrcRefresh(apiex);
            if (apiex.getMessage().contains("HTTP response code: 404"))
                this.cachedFileAnalyses.add404(stringified);
            else
                LOG.error("Error during get file analysis: "+apiex.getMessage());
            return null;
        }
    }

    public Instance createInstanceEx(JsonObject createInstanceRequest) throws ApiException
    {
        Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
        okhttp3.Call localVarCall = this.client.buildCall(null, "/instances", "POST", new ArrayList<>(), new ArrayList<>(), createInstanceRequest, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
        ApiResponse<Instance> localVarResp = this.client.execute(localVarCall, Instance.class);
        return localVarResp.getData();
    }

    public Instance createInstance(CreateInstanceRequest createInstanceRequest) throws ApiException
    {
        return new InstancesApi(this.client).createInstance(createInstanceRequest);
    }

    public CalendarEvent createCalendarEvent(String groupId, CreateCalendarEventRequest createCalendarEventRequest) throws ApiException
    {
        return new CalendarApi(this.client).createGroupCalendarEvent(groupId, createCalendarEventRequest);
    }

    public String getStickerFileId(String userId, String stickerId) throws ApiException
    {
        if (stickerId.startsWith("file_"))
            return stickerId;
        if (!stickerId.startsWith("inv_"))
            return null;
        Map<String, String> headers = new HashMap<>();
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json");
        okhttp3.Call localVarCall = this.client.buildCall(null, "/user/"+userId+"/inventory/"+stickerId, "GET", new ArrayList<>(), new ArrayList<>(), null, headers, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
        ApiResponse<JsonObject> localVarResp = this.client.execute(localVarCall, JsonObject.class);
        return VersionedFile.parse(localVarResp.getData().getAsJsonPrimitive("imageUrl").getAsString()).id;
    }

    static final TypeToken<List<LimitedUserGroups>> LIST_LUGROUPS = new TypeToken<List<LimitedUserGroups>>(){};
    public List<LimitedUserGroups> snapshot(ScarletData.AuditEntryMetadata entryMeta)
    {
        List<LimitedUserGroups> lugs = null;
        try
        {
            Map<String, String> localVarHeaderParams = new HashMap<>();
            localVarHeaderParams.put("Accept", "application/json");
            okhttp3.Call localVarCall = this.client.buildCall(null, "/users/"+entryMeta.entry.getTargetId(), "GET", new ArrayList<>(), new ArrayList<>(), null, localVarHeaderParams, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            entryMeta.snapshotTargetUser = this.client.<JsonObject>execute(localVarCall, JsonObject.class).getData();
        }
        catch (Exception ex)
        {
            LOG.error("Exception whilst fetching user", ex);
        }
        try
        {
            Map<String, String> localVarHeaderParams = new HashMap<>();
            localVarHeaderParams.put("Accept", "application/json");
            okhttp3.Call localVarCall = this.client.buildCall(null, "/users/"+entryMeta.entry.getTargetId()+"/groups", "GET", new ArrayList<>(), new ArrayList<>(), null, localVarHeaderParams, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            entryMeta.snapshotTargetUserGroups = this.client.<JsonArray>execute(localVarCall, JsonArray.class).getData();
            try
            {
                lugs = JSON.getGson().fromJson(entryMeta.snapshotTargetUserGroups, LIST_LUGROUPS);
            }
            catch (Exception ex)
            {
                LOG.error("Exception whilst deserializing user groups", ex);
            }
        }
        catch (Exception ex)
        {
            LOG.error("Exception whilst fetching user groups", ex);
        }
        try
        {
            Map<String, String> localVarHeaderParams = new HashMap<>();
            localVarHeaderParams.put("Accept", "application/json");
            okhttp3.Call localVarCall = this.client.buildCall(null, "/users/"+entryMeta.entry.getTargetId()+"/groups/represented", "GET", new ArrayList<>(), new ArrayList<>(), null, localVarHeaderParams, new HashMap<>(), new HashMap<>(), new String[]{"authCookie"}, null);
            entryMeta.snapshotTargetUserRepresentedGroup = this.client.<JsonObject>execute(localVarCall, JsonObject.class).getData();
        }
        catch (Exception ex)
        {
            LOG.error("Exception whilst fetching user represented group", ex);
        }
        return lugs;
    }

    public void addAlternateCredentials()
    {
        this.scarlet.execModal.submit(() ->
        {
            String altUserId = this.loginAlt(null, false);
            if (altUserId == null)
                this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Canceed", "alt credentials", Color.PINK);
            else
                this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Added: "+this.getUserDisplayName(altUserId), altUserId);
        });
    }

    public void removeAlternateCredentials()
    {
        class AltDisplay
        {
            AltDisplay(String id)
            {
                this.id = id;
                this.name = ScarletVRChat.this.getUserDisplayName(id);
                this.display = this.name+" ("+this.id+")";
            }
            final String id, name, display;
            @Override
            public String toString()
            {
                return this.display;
            }
        }
        AltDisplay[] alts = this.cookies.alts().stream().map(AltDisplay::new).toArray(AltDisplay[]::new);
        this.scarlet.settings.requireSelectAsync("Select the credentials to remove", "Remove alternate credentials", alts, null, selected ->
        {
            if (selected == null)
                return;
            boolean loggedOut = this.logoutAlt(selected.id, false);
            boolean removed = this.cookies.removeAlt(selected.id);
            this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Removed "+selected.name, selected.id, removed ? loggedOut ? Color.WHITE : Color.YELLOW : loggedOut ? Color.ORANGE : Color.RED);
        });
    }

    public void listAlternateCredentials()
    {
        if (GraphicsEnvironment.isHeadless())
        {
            StringBuilder sb = new StringBuilder("Alternate credentials:");
            for (String alt : this.cookies.alts())
                sb.append('\n').append('\t').append(this.getUserDisplayName(alt)).append(':').append(alt);
            LOG.info(sb.toString());
            return;
        }
        JPanel panel = new JPanel(new GridBagLayout());
        {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridheight = 1;
            constraints.gridwidth = 1;
            constraints.gridx = 0;
            constraints.gridy = 0;
            constraints.insets = new Insets(1, 1, 1, 1);
            constraints.weightx = 0.0D;
            constraints.weighty = 0.0D;
            for (String alt : this.cookies.alts())
            {
                constraints.gridx = 0;
                constraints.anchor = GridBagConstraints.EAST;
                panel.add(new JLabel(this.getUserDisplayName(alt)+":", JLabel.RIGHT), constraints);
                constraints.gridx = 1;
                constraints.anchor = GridBagConstraints.WEST;
                panel.add(new JLabel(alt, JLabel.LEFT), constraints);
                constraints.gridy++;
            }
        }
        JScrollPane scroll = new JScrollPane(panel);
        scroll.setSize(new Dimension(500, 300));
        scroll.setPreferredSize(new Dimension(500, 300));
        scroll.setMaximumSize(new Dimension(500, 300));
        this.scarlet.execModal.execute(() -> JOptionPane.showMessageDialog(null, scroll, "Alternate credentials", JOptionPane.INFORMATION_MESSAGE));
    }

    /**
     * Clears all stored VRChat credentials (username, password, TOTP secret,
     * and session cookies) from the encrypted registry store and from memory.
     * The user will be prompted to re-enter credentials on the next login.
     * This is safe to call at any time and does not require the user to
     * manually touch the Registry or AppData folders.
     */
    public void clearCredentials()
    {
        this.username.clear();
        this.password.clear();
        this.totpsecret.clear();
        this.cookies.clear();
        this.cookies.save();
        LOG.info("Credentials cleared by user request");
        this.scarlet.splash.queueFeedbackPopup(null, 5_000L, "Credentials cleared", "You will be prompted to log in again.", Color.CYAN);
    }

    public void modalNeedPerms(GroupPermissions perms)
    {
        this.scarlet.settings.requireConfirmYesNoAsync(
            "The bot VRChat account `"+this.currentUserId+"` is missing the necessary permission `"+perms.getValue()+"`",
            "Missing permissions",
            () -> MiscUtils.AWTDesktop.browse(URI.create(VrcWeb.Home.groupSettings(this.groupId))),
            null);
    }
    public String messageNeedPerms(GroupPermissions perms)
    {
        return String.format("The [bot VRChat account](<%s>) is missing the [necessary permission `%s`](<%s>)", VrcWeb.Home.user(this.currentUserId), perms.getValue(), VrcWeb.Home.groupSettings(this.groupId));
    }
    public boolean checkLogNeedPerms(GroupPermissions perms)
    {
        if (this.checkSelfUserHasVRChatPermission(perms))
            return true;
        LOG.error(String.format("The bot VRChat account `%s` is missing the necessary permission `%s`", this.currentUserId, perms.getValue()));
        return false;
    }

    public boolean hasValidGroupId()
    {
        return !MiscUtils.blank(this.groupId);
    }

    public void modalNeedGroupId()
    {
        this.scarlet.settings.requireConfirmYesNoAsync(
            "Scarlet could not determine a valid VRChat group ID for the logged-in account `"+this.currentUserId+"`.\n\n"
          + "Please set the correct `vrchat_group_id` in Scarlet's settings, or make sure the account is representing the intended group before launching Scarlet.",
            "Missing group ID",
            () -> MiscUtils.AWTDesktop.browse(URI.create("https://vrchat.com/home/groups")),
            null);
    }

    public void save()
    {
        this.cookies.save();
    }

    @Override
    public void close() throws IOException
    {
        this.cookies.close();
    }

}
