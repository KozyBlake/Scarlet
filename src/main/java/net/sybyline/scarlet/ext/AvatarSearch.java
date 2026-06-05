package net.sybyline.scarlet.ext;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import net.sybyline.scarlet.util.HttpURLInputStream;
import net.sybyline.scarlet.util.JsonAdapters;
import net.sybyline.scarlet.util.LRUMap;
import net.sybyline.scarlet.util.URLs;

public interface AvatarSearch
{

    int SEARCH_N = 5000;
    int HYDRATION_SEARCH_N = 50;
    Logger LOG = LoggerFactory.getLogger("Scarlet/AvatarSearch");
    long RATE_LIMIT_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(5L),
         TIMEOUT_BACKOFF_MILLIS = TimeUnit.SECONDS.toMillis(45L),
         ERROR_LOG_THROTTLE_MILLIS = TimeUnit.MINUTES.toMillis(1L);
    Map<String, Long> providerBlockedUntil = new ConcurrentHashMap<>(),
                      providerLastLog = new ConcurrentHashMap<>();
    String
        URL_ROOT_AVTRDB = AvatarSearch_AvtrDB.API_ROOT+"/avatar/search/vrcx",
        URL_ROOT_NEKOSUNEVR = AvatarSearch_VRCDS.API_ROOT+"/vrcx_search",
        URL_ROOT_VRCDB = "https://vrcx.vrcdb.com/avatars/Avatar/VRCX",
        URL_ROOT_WORLDBALANCER = AvatarSearch_WorldBalancer.API_ROOT+"/vrcx_search",
        URL_ROOTS[] =
        {
            URL_ROOT_AVTRDB,
            URL_ROOT_NEKOSUNEVR,
            URL_ROOT_VRCDB,
            URL_ROOT_WORLDBALANCER,
        };

    static String cacheKey(int n, String search)
    {
        return n == SEARCH_N ? search : Integer.toUnsignedString(n)+"\u0000"+search;
    }

    static boolean providerAvailable(String urlRoot)
    {
        Long until = providerBlockedUntil.get(urlRoot);
        if (until == null)
            return true;
        long now = System.currentTimeMillis();
        if (until.longValue() > now)
            return false;
        providerBlockedUntil.remove(urlRoot, until);
        return true;
    }

    static void logProviderFailure(String urlRoot, String message, Throwable throwable)
    {
        long now = System.currentTimeMillis();
        Long last = providerLastLog.put(urlRoot, Long.valueOf(now));
        if (last == null || now - last.longValue() >= ERROR_LOG_THROTTLE_MILLIS)
            LOG.warn("Avatar search provider unavailable: {} ({})", urlRoot, message);
        else
            LOG.debug("Avatar search provider unavailable: {} ({})", urlRoot, message, throwable);
    }

    static void blockProvider(String urlRoot, long millis, String message, Throwable throwable)
    {
        providerBlockedUntil.put(urlRoot, Long.valueOf(System.currentTimeMillis() + millis));
        logProviderFailure(urlRoot, message+"; backing off for "+TimeUnit.MILLISECONDS.toSeconds(millis)+"s", throwable);
    }

    static void handleSearchFailure(String urlRoot, String search, Exception ex)
    {
        String message = ex.getMessage();
        if (message == null)
            message = ex.getClass().getSimpleName();
        if (message.contains("429"))
            blockProvider(urlRoot, RATE_LIMIT_BACKOFF_MILLIS, "rate limited while searching `"+search+"`", ex);
        else if (ex instanceof SocketTimeoutException || message.toLowerCase().contains("timed out"))
            blockProvider(urlRoot, TIMEOUT_BACKOFF_MILLIS, "timed out while searching `"+search+"`", ex);
        else
            logProviderFailure(urlRoot, "search failed for `"+search+"`: "+message, ex);
    }

    class VrcxAvatar
    {
        static final VrcxAvatar[] NONE = new VrcxAvatar[0];
        static final Map<String, Map<String, VrcxAvatar[]>> searchCacheByUrlRoot = new ConcurrentHashMap<>(),
                                                            searchCacheByUrlRootByImage = new ConcurrentHashMap<>();
        
        @SerializedName(value = "id", alternate = { "avatarId", "avatar_id", "vrcId", "vrc_id" })
        public String id;
        public String id() { return this.id; }
        
        @SerializedName(value = "name", alternate = { "display", "displayName", "display_name", "avatarName", "avatar_name", "avatarDisplay", "avatar_display", "avatarDisplayName", "avatar_display_name" })
        public String name;
        public String name() { return this.name; }
        
        @SerializedName(value = "authorId", alternate = { "author_id" })
        public String authorId;
        public String authorId() { return this.authorId; }
        
        @SerializedName(value = "authorName", alternate = { "author_name", "authorDisplay", "author_display", "authorDisplayName", "author_display_name" })
        public String authorName;
        public String authorName() { return this.authorName; }
        
        @SerializedName(value = "description", alternate = { "desc", "avatarDesc", "avatar_desc", "avatarDescription", "avatar_description" })
        public String description;
        public String description() { return this.description; }
        
        @SerializedName(value = "imageUrl", alternate = { "image_url", "image", "avatarImage", "avatar_image", "avatarImageUrl", "avatar_image_url" })
        public String imageUrl;
        public String imageUrl() { return this.imageUrl; }
        
        @SerializedName(value = "thumbnailImageUrl", alternate = { "thumbnail_image_url", "thumbnailImage", "thumbnail_image", "avatarThumbnail", "avatar_thumbnail", "avatarThumbnailImage", "avatar_thumbnail_image", "avatarThumbnailUrl", "avatar_thumbnail_url", "avatarThumbnailImageUrl", "avatar_thumbnail_image_url" }) 
        public String thumbnailImageUrl;
        public String thumbnailImageUrl() { return this.thumbnailImageUrl; }
        
        @SerializedName(value = "releaseStatus", alternate = { "release_status", "release", "status", "avatarRelease", "avatar_release", "avatarStatus", "avatar_status", "avatarReleaseStatus", "avatar_release_status" })
        public String releaseStatus;
        public String releaseStatus() { return this.releaseStatus; }
        
        @SerializedName(value = "created_at", alternate = { "createdAt", "created", "avatarCreated", "avatar_created", "avatarCreatedAt", "avatar_created_at" })
        public OffsetDateTime createdAt;
        public OffsetDateTime createdAt() { return this.createdAt; }
        
        @SerializedName(value = "updated_at", alternate = { "updatedAt", "updated", "avatarUpdated", "avatar_updated", "avatarUpdatedAt", "avatar_updated_at" })
        public OffsetDateTime updatedAt;
        public OffsetDateTime updatedAt() { return this.updatedAt; }
        
        @SerializedName(value = "performance", alternate = { "perf", "avatarPerf", "avatar_perf", "avatarPerformance", "avatar_performance" })
        public Performance performance;
        public Performance performance() { return this.performance; }
        public static class Performance
        {
            @SerializedName(value = "pc_rating", alternate = { "pcRating", "pc" })
            public String pcRating;
            public String pcRating() { return this.pcRating; }
            
            @SerializedName(value = "android_rating", alternate = { "quest_rating", "androidRating", "questRating", "android", "quest" })
            public String androidRating;
            public String androidRating() { return this.androidRating; }
            
            @SerializedName(value = "ios_rating", alternate = { "iosRating", "ios" })
            public String iosRating;
            public String iosRating() { return this.iosRating; }
            
            @SerializedName(value = "has_impostor", alternate = { "hasImpostor", "impostor" })
            public boolean hasImpostor;
            public boolean hasImpostor() { return this.hasImpostor; }
            
            @SerializedName(value = "has_security_variant", alternate = { "hasSecurityVariant", "securityVariant", "security_variant", "security", "hasSecurity", "has_security" })
            public Boolean hasSecurityVariant;
            public Boolean hasSecurityVariant() { return this.hasSecurityVariant; }
        }
        
        public VrcxAvatar merge(VrcxAvatar found)
        {
            if (found == null)
                return this;
            if (!Objects.equals(this.id, found.id))
                return this;
            
            if (this.authorId == null)
                this.authorId = found.authorId;
            
            if (this.authorName == null)
                this.authorName = found.authorName;
            
            if (this.description == null)
                this.description = found.description;
            
            if (this.imageUrl == null)
                this.imageUrl = found.imageUrl;
            
            if (this.thumbnailImageUrl == null)
                this.thumbnailImageUrl = found.thumbnailImageUrl;
            
            if (this.createdAt == null)
                this.createdAt = found.createdAt;
            
            if (this.updatedAt == null)
                this.updatedAt = found.updatedAt;
            
            if (this.releaseStatus == null)
                this.releaseStatus = found.releaseStatus;
            
            if (this.performance == null)
                this.performance = found.performance;
            else if (found.performance != null)
            {
                if (this.performance.pcRating == null)
                    this.performance.pcRating = found.performance.pcRating;
                
                if (this.performance.androidRating == null)
                    this.performance.androidRating = found.performance.androidRating;
                
                if (this.performance.iosRating == null)
                    this.performance.iosRating = found.performance.iosRating;
                
                this.performance.hasImpostor |= found.performance.hasImpostor;
                
                if (this.performance.hasSecurityVariant == null)
                    this.performance.hasSecurityVariant = found.performance.hasSecurityVariant;
            }
            
            return this;
        }
        @Override
        public int hashCode()
        {
            return Objects.hashCode(this.id);
        }
        @Override
        public boolean equals(Object obj)
        {
            return obj instanceof VrcxAvatar && Objects.equals(this.id, ((VrcxAvatar)obj).id);
        }
        private String toString;
        @Override
        public String toString()
        {
            String toString = this.toString;
            if (toString == null)
            {
                StringBuilder sb = new StringBuilder();
                
                sb.append(this.name).append(" (").append(this.id)
                .append(") by ").append(this.authorName);
                if (this.authorId != null)
                {
                    sb.append(" (").append(this.authorId).append(")");
                }
                
                if (this.description != null && !this.description.trim().isEmpty())
                {
                    sb.append(": ").append(this.description);
                }
                
                this.toString = toString = sb.toString();
            }
            return toString;
        }
    }
    public static class VrcxAvatarTypeAdapter extends TypeAdapter<VrcxAvatar>
    {
        @Override
        public void write(JsonWriter out, VrcxAvatar value) throws IOException
        {
            if (value == null)
            {
                out.nullValue();
                return;
            }
            out.beginObject();
            {
                out.endObject();
            }
        }
        @Override
        public VrcxAvatar read(JsonReader in) throws IOException
        {
            // handles nulls implicitly
            if (in.peek() != JsonToken.BEGIN_OBJECT)
            {
                in.skipValue();
                return null;
            }
            in.beginObject();
            VrcxAvatar ret = new VrcxAvatar();
            while (in.peek() != JsonToken.END_OBJECT)
            {
                String prop = in.nextName();
                switch (prop.toLowerCase().replace("_", ""))
                {
                case "id":
                case "avatarid":
                case "vrcid": {
                    ret.id = in.nextString();
                } break;
                case "name":
                case "display":
                case "displayname":
                case "avatarname":
                case "avatardisplay":
                case "avatardisplayname": {
                    ret.name = in.nextString();
                } break;
                case "userid":
                case "authorid":{
                    ret.authorId = in.nextString();
                } break;
                case "username":
                case "userdisplay":
                case "userdisplayname":
                case "authorname":
                case "authordisplay":
                case "authordisplayname": {
                    ret.authorName = in.nextString();
                } break;
                case "user":
                case "author": {
                    if (in.peek() == JsonToken.BEGIN_OBJECT)
                    {
                        in.beginObject();
                        while (in.peek() != JsonToken.END_OBJECT)
                        {
                            String authorProp = in.nextName();
                            switch (authorProp.toLowerCase().replace("_", ""))
                            {
                            case "id":
                            case "vrcid":
                            case "userid":
                            case "authorid": {
                                ret.authorId = in.nextString();
                            } break;
                            case "name":
                            case "display":
                            case "displayname":
                            case "username":
                            case "userdisplay":
                            case "userdisplayname":
                            case "authorname":
                            case "authordisplay":
                            case "authordisplayname": {
                                ret.authorName = in.nextString();
                            } break;
                            default: {
                                in.skipValue();
                            }
                            }
                        }
                        in.endObject();
                    }
                    else if (in.peek() == JsonToken.STRING)
                    {
                        ret.authorName = in.nextString();
                    }
                } break;
                case "desc":
                case "description":
                case "avatardesc":
                case "avatardescription": {
                    ret.description = in.nextString();
                } break;
                case "image":
                case "imageurl":
                case "avatarimage":
                case "avatarimageurl": {
                    ret.imageUrl = in.nextString();
                } break;
                case "thumbnail":
                case "thumbnailimage":
                case "thumbnailurl":
                case "thumbnailimageurl":
                case "avatarthumbnail":
                case "avatarthumbnailimage":
                case "avatarthumbnailurl":
                case "avatarthumbnailimageurl": {
                    ret.imageUrl = in.nextString();
                } break;
                case "created":
                case "createdat":
                case "avatarcreated":
                case "avatarcreatedat": {
                    ret.createdAt = JsonAdapters.json2offsetDateTime(in.nextString());
                } break;
                case "updated":
                case "updatedat":
                case "avatarupdated":
                case "avatarupdatedat": {
                    ret.updatedAt = JsonAdapters.json2offsetDateTime(in.nextString());
                } break;
                case "release":
                case "status":
                case "releasestatus":
                case "avatarrelease":
                case "avatarstatus":
                case "avatarreleasestatus": {
                    ret.releaseStatus = in.nextString();
                } break;
                case "performance":
                case "perf":
                case "avatarperformance":
                case "avatarperf": {
                    if (in.peek() == JsonToken.BEGIN_OBJECT)
                    {
                        in.beginObject();
                        if (ret.performance == null)
                            ret.performance = new VrcxAvatar.Performance();
                        while (in.peek() != JsonToken.END_OBJECT)
                        {
                            String authorProp = in.nextName();
                            switch (authorProp.toLowerCase().replace("_", ""))
                            {
                            case "pc":
                            case "pcrating": {
                                ret.performance.pcRating = in.nextString();
                            } break;
                            case "android":
                            case "androidrating":
                            case "quest":
                            case "questrating": {
                                ret.performance.androidRating = in.nextString();
                            } break;
                            case "ios":
                            case "iosrating": {
                                ret.performance.pcRating = in.nextString();
                            } break;
                            case "hasimpostor":
                            case "impostor": {
                                if (in.peek() == JsonToken.BOOLEAN)
                                {
                                    ret.performance.hasImpostor = in.nextBoolean();
                                }
                                else if (in.peek() == JsonToken.STRING)
                                {
                                    String hasImpostor = in.nextString();
                                    if (!"null".equalsIgnoreCase(hasImpostor) && !"none".equalsIgnoreCase(hasImpostor))
                                    {
                                        ret.performance.hasImpostor = !hasImpostor.isEmpty() && !"false".equalsIgnoreCase(hasImpostor);
                                    }
                                }
                                else
                                {
                                    in.skipValue();
                                }
                            } break;
                            case "hassecurityvariant":
                            case "securityvariant":
                            case "hassecurity":
                            case "security": {
                                if (in.peek() == JsonToken.BOOLEAN)
                                {
                                    ret.performance.hasSecurityVariant = in.nextBoolean();
                                }
                                else if (in.peek() == JsonToken.STRING)
                                {
                                    String hasSecurityVariant = in.nextString();
                                    if (!"null".equalsIgnoreCase(hasSecurityVariant) && !"none".equalsIgnoreCase(hasSecurityVariant))
                                    {
                                        ret.performance.hasSecurityVariant = !hasSecurityVariant.isEmpty() && !"false".equalsIgnoreCase(hasSecurityVariant);
                                    }
                                }
                                else
                                {
                                    in.skipValue();
                                }
                            } break;
                            default: {
                                in.skipValue();
                            }
                            }
                        }
                        in.endObject();
                    }
                    else if (in.peek() == JsonToken.STRING)
                    {
                        ret.authorName = in.nextString();
                    }
                } break;
                default: {
                    in.skipValue();
                }
                }
            }
            in.endObject();
            return ret;
        }
    }

    static VrcxAvatar[] vrcxSearch0(String urlRoot, int n, String search)
    {
        if (!urlRoot.startsWith("http://") && !urlRoot.startsWith("https://"))
            return VrcxAvatar.NONE;
        if (!providerAvailable(urlRoot))
            return null;
        try (HttpURLInputStream in = HttpURLInputStream.get(urlRoot + "?n=" + Integer.toUnsignedString(n) + "&search=" + URLs.encode(search), ExtendedUserAgent.init_conn))
        {
            return in.readAsJson(null, null, VrcxAvatar[].class);
        }
        catch (Exception ex)
        {
            handleSearchFailure(urlRoot, search, ex);
            return null;
        }
    }
    static VrcxAvatar[] vrcxFindInCache(String urlRoot, String search)
    {
        return VrcxAvatar.searchCacheByUrlRoot.getOrDefault(urlRoot, Collections.emptyMap()).get(search);
    }
    static VrcxAvatar[] vrcxPutInCache(String urlRoot, String search, VrcxAvatar[] results)
    {
        if (results == null)
            return null;
        VrcxAvatar.searchCacheByUrlRoot.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized()).put(search, results);
        return results;
    }
    static VrcxAvatar[] vrcxSearchCached(String urlRoot, String search)
    {
        return vrcxSearchCached(urlRoot, SEARCH_N, search);
    }
    static VrcxAvatar[] vrcxSearchCached(String urlRoot, int n, String search)
    {
        Map<String, VrcxAvatar[]> urlCache;
        synchronized (VrcxAvatar.searchCacheByUrlRoot) {
            urlCache = VrcxAvatar.searchCacheByUrlRoot.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized());
        }
        
        synchronized (urlCache) {
            String key = cacheKey(n, search);
            VrcxAvatar[] cached = urlCache.get(key);
            if (cached == null) {
                cached = AvatarSearch.vrcxSearch(urlRoot, n, search);
                if (cached != null) {
                    urlCache.put(key, cached);
                }
            }
            return cached;
        }
    }
    static VrcxAvatar[] vrcxSearch(String urlRoot, String search)
    {
        return vrcxSearch(urlRoot, SEARCH_N, search);
    }
    static VrcxAvatar[] vrcxSearch(String urlRoot, int n, String search)
    {
        VrcxAvatar[] results = vrcxSearch0(urlRoot, n, search);
        if (n == SEARCH_N && results != null)
            vrcxPutInCache(urlRoot, cacheKey(n, search), results);
        return results;
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(String search)
    {
        return vrcxSearchAllCached(URL_ROOTS, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(String[] urlRoots, String search)
    {
        return vrcxSearchAllCached(urlRoots, SEARCH_N, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAllCached(String[] urlRoots, int n, String search)
    {
        return Arrays.stream(urlRoots)
            .filter(Objects::nonNull)
            .map($ -> vrcxSearchCached($, n, search))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .filter(Objects::nonNull)
        ;
    }
    static Stream<VrcxAvatar> vrcxSearchAll(String search)
    {
        return vrcxSearchAll(URL_ROOTS, search);
    }
    static Stream<VrcxAvatar> vrcxSearchAll(String[] urlRoots, String search)
    {
        return Arrays.stream(urlRoots)
            .filter(Objects::nonNull)
            .map($ -> vrcxSearch($, SEARCH_N, search))
            .filter(Objects::nonNull)
            .flatMap(Arrays::stream)
            .filter(Objects::nonNull)
        ;
    }

    interface ByImage
    {
        String BY_IMAGE_URL_ROOTS[] =
        {
            URL_ROOT_AVTRDB,
        };
        static VrcxAvatar[] vrcxSearch0ByImage(String urlRoot, int n, String imageFileId)
        {
            if (!urlRoot.startsWith("http://") && !urlRoot.startsWith("https://"))
                return VrcxAvatar.NONE;
            if (!providerAvailable(urlRoot))
                return null;
            try (HttpURLInputStream in = HttpURLInputStream.get(urlRoot + "?n=" + Integer.toUnsignedString(n) + "&fileId=" + imageFileId, ExtendedUserAgent.init_conn))
            {
                return in.readAsJson(null, null, VrcxAvatar[].class);
            }
            catch (Exception ex)
            {
                handleSearchFailure(urlRoot, imageFileId, ex);
                return null;
            }
        }
        static VrcxAvatar[] vrcxFindInCacheByImage(String urlRoot, String imageFileId)
        {
            return VrcxAvatar.searchCacheByUrlRootByImage.getOrDefault(urlRoot, Collections.emptyMap()).get(imageFileId);
        }
        static VrcxAvatar[] vrcxPutInCacheByImage(String urlRoot, String imageFileId, VrcxAvatar[] results)
        {
            if (results == null)
                return null;
            VrcxAvatar.searchCacheByUrlRootByImage.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized()).put(imageFileId, results);
            return results;
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(String urlRoot, String imageFileId)
        {
            return vrcxSearchCachedByImage(urlRoot, SEARCH_N, imageFileId);
        }
        static VrcxAvatar[] vrcxSearchCachedByImage(String urlRoot, int n, String imageFileId)
        {
            Map<String, VrcxAvatar[]> urlCache;
            synchronized (VrcxAvatar.searchCacheByUrlRootByImage) {
                urlCache = VrcxAvatar.searchCacheByUrlRootByImage.computeIfAbsent(urlRoot, urlRoot0 -> LRUMap.ofSynchronized());
            }
            
            synchronized (urlCache) {
                String key = cacheKey(n, imageFileId);
                VrcxAvatar[] cached = urlCache.get(key);
                if (cached == null) {
                    cached = AvatarSearch.ByImage.vrcxSearchByImage(urlRoot, n, imageFileId);
                    if (cached != null) {
                        urlCache.put(key, cached);
                    }
                }
                return cached;
            }
        }
        static VrcxAvatar[] vrcxSearchByImage(String urlRoot, String imageFileId)
        {
            return vrcxSearchByImage(urlRoot, SEARCH_N, imageFileId);
        }
        static VrcxAvatar[] vrcxSearchByImage(String urlRoot, int n, String imageFileId)
        {
            VrcxAvatar[] results = vrcxSearch0ByImage(urlRoot, n, imageFileId);
            if (n == SEARCH_N && results != null)
                vrcxPutInCacheByImage(urlRoot, cacheKey(n, imageFileId), results);
            return results;
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String imageFileId)
        {
            return vrcxSearchAllCachedByImage(BY_IMAGE_URL_ROOTS, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllCachedByImage(String[] urlRoots, String imageFileId)
        {
            return Arrays.stream(urlRoots)
                .filter(Objects::nonNull)
                .map($ -> vrcxSearchCachedByImage($, SEARCH_N, imageFileId))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
            ;
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String imageFileId)
        {
            return vrcxSearchAllByImage(BY_IMAGE_URL_ROOTS, imageFileId);
        }
        static Stream<VrcxAvatar> vrcxSearchAllByImage(String[] urlRoots, String imageFileId)
        {
            return Arrays.stream(urlRoots)
                .filter(Objects::nonNull)
                .map($ -> vrcxSearchByImage($, SEARCH_N, imageFileId))
                .filter(Objects::nonNull)
                .flatMap(Arrays::stream)
                .filter(Objects::nonNull)
            ;
        }
    }

}
