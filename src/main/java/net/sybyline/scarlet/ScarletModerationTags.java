package net.sybyline.scarlet;

import java.io.File;
import net.sybyline.scarlet.util.FileBackups;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.reflect.TypeToken;

import net.dv8tion.jda.api.interactions.commands.Command;
import net.sybyline.scarlet.util.MiscUtils;

public class ScarletModerationTags
{

    static final Logger LOG = LoggerFactory.getLogger("Scarlet/ModTags");

    public ScarletModerationTags(File moderationTagsFile)
    {
        this.moderationTagsFile = moderationTagsFile;
        this.tags = null;
        this.choicesCache = null;
    }

    final File moderationTagsFile;
    private List<Tag> tags;
    private List<Command.Choice> choicesCache;

    public static class Tag
    {
        public static final TypeToken<List<Tag>> LISTOF = new TypeToken<List<Tag>>(){};
        public Tag()
        {
            this("", "", "");
        }
        public Tag(String value, String label, String description)
        {
            this.value = value != null ? value : "";;
            this.label = label != null ? label : "";
            this.description = description != null ? description : "";
        }
        public String value, label, description;
        // Advisory translation state for the human-readable description only. The stable `value`
        // (used everywhere as the tag key) and `label` (the Discord command choice name) are never
        // translated, so tagging keeps working exactly as before. Original kept for restore.
        public String descriptionOriginal = null;
        public String translatedLang = null;
    }

    synchronized List<Tag> getJson()
    {
        List<Tag> tags = this.tags;
        if (tags != null)
            return tags;
        if (!this.moderationTagsFile.exists())
        {
            tags = new CopyOnWriteArrayList<>();
        }
        else try (Reader r = MiscUtils.reader(this.moderationTagsFile))
        {
            tags = new CopyOnWriteArrayList<>(Scarlet.GSON_PRETTY.fromJson(r, Tag.LISTOF));
        }
        catch (Exception ex)
        {
            LOG.error("Exception loading moderation tags", ex);
            tags = new CopyOnWriteArrayList<>();
        }
        this.tags = tags;
        return tags;
    }

    synchronized void saveJson()
    {
        List<Tag> tags = this.tags;
        if (tags == null)
            return;
        if (!this.moderationTagsFile.getParentFile().isDirectory())
            this.moderationTagsFile.getParentFile().mkdirs();
        try (Writer w = FileBackups.writer(this.moderationTagsFile))
        {
            Scarlet.GSON_PRETTY.toJson(tags, Tag.LISTOF.getType(), w);
        }
        catch (Exception ex)
        {
            LOG.error("Exception saving moderation tags", ex);
        }
    }

    public Tag getTag(String value)
    {
        List<Tag> tags = this.getJson();
        if (tags == null)
            return null;
        for (Tag tag : this.tags)
            if (tag.value.equals(value))
                return tag;
        return null;
    }

    public int addOrUpdateTag(String value, String label, String description)
    {
        List<Tag> tags = this.getJson();
        if (tags == null)
            return Integer.MIN_VALUE;
        Tag old = this.getTag(value);
        if (old != null)
        {
            if (label != null)
                old.label = label;
            if (description != null)
                old.description = description;
            this.saveJson();
            return 1;
        }
        if (tags.size() >= 125)
            return -2;
        Tag tag = new Tag(value, label, description);
        if (!tags.add(tag))
            return -1;
        this.choicesCache = null;
        this.saveJson();
        return 0;
    }

    public int removeTag(String value)
    {
        List<Tag> tags = this.getJson();
        if (tags == null)
            return Integer.MIN_VALUE;
        if (tags.isEmpty())
            return -2;
        Tag old = this.getTag(value);
        if (old == null)
            return -3;
        if (!tags.remove(old))
            return -1;
        this.choicesCache = null;
        this.saveJson();
        return 0;
    }

    public List<Tag> getTags()
    {
        List<Tag> tags = this.getJson();
        if (tags == null)
            return new ArrayList<>(0);
        return new ArrayList<>(this.tags);
    }

    public List<String> getTagValues()
    {
        List<Tag> tags = this.getJson();
        if (tags == null)
            return new ArrayList<>(0);
        List<String> values = new ArrayList<>(tags.size());
        for (Tag tag : tags)
            values.add(tag.value);
        return values;
    }

    public List<Command.Choice> getTagChoices()
    {
        List<Command.Choice> choices = this.choicesCache;
        if (choices != null)
            return choices;
        List<Tag> tags = this.getJson();
        if (tags == null)
            return new ArrayList<>(0);
        choices = new ArrayList<>(tags.size());
        for (Tag tag : tags)
            choices.add(new Command.Choice(tag.label != null ? tag.label : tag.value, tag.value));
        this.choicesCache = choices;
        return choices;
    }

    public String getTagLabel(String value)
    {
        Tag tag = this.getTag(value);
        if (tag == null)
            return value;
        return tag.label;
    }

    /** Number of tags currently showing a translated description. */
    public int countTranslated()
    {
        int n = 0;
        for (Tag t : this.getJson())
            if (t.translatedLang != null || t.descriptionOriginal != null)
                n++;
        return n;
    }

    /**
     * Translates each moderation tag's human-readable {@code description} into {@code targetLang}
     * via a LibreTranslate-compatible endpoint, keeping the original for restore. The tag
     * {@code value} (its key) and {@code label} (the Discord command choice name) are deliberately
     * left untouched so tagging and command routing keep working. Returns the number changed.
     */
    public int translateAdvisories(String endpoint, String apiKey, String targetLang) throws java.io.IOException
    {
        int changed = 0;
        java.io.IOException failure = null;
        try
        {
            for (Tag t : this.getJson())
            {
                String src = t.descriptionOriginal != null ? t.descriptionOriginal : t.description;
                if (src == null || src.trim().isEmpty())
                    continue;
                String tr = net.sybyline.scarlet.util.Translator.translate(endpoint, apiKey, targetLang, src);
                if (tr != null && !tr.equals(t.description))
                {
                    if (t.descriptionOriginal == null) t.descriptionOriginal = t.description;
                    t.description = tr;
                    t.translatedLang = targetLang;
                    changed++;
                }
            }
        }
        catch (java.io.IOException ex)
        {
            failure = ex;
        }
        if (changed > 0)
            this.saveJson();
        if (failure != null)
            throw failure;
        return changed;
    }

    /** Restores original (pre-translation) descriptions for every tag. Returns count restored. */
    public int restoreAdvisories()
    {
        int restored = 0;
        for (Tag t : this.getJson())
        {
            boolean any = false;
            if (t.descriptionOriginal != null) { t.description = t.descriptionOriginal; t.descriptionOriginal = null; any = true; }
            if (t.translatedLang != null)      { t.translatedLang = null; any = true; }
            if (any) restored++;
        }
        if (restored > 0)
            this.saveJson();
        return restored;
    }

}
