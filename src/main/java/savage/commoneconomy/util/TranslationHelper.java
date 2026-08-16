package savage.commoneconomy.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles server-side translations, including embedded jar lang files,
 * external config lang files, and legacy formatting parser.
 */
public class TranslationHelper {
    private static final Map<String, String> translations = new HashMap<>();
    private static final Gson GSON = new Gson();
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    // Recognizes the only format tokens this codebase actually uses (%%, %s/%S, %N$s/%N$S).
    // Any other '%' is a literal the translator wrote (e.g. "10% off") and gets escaped to "%%"
    // so it survives String.format instead of throwing. Matched as whole tokens, not a per-'%'
    // lookahead, so an already-escaped "%%" is never split and re-escaped into "%%%".
    private static final Pattern FORMAT_TOKEN = Pattern.compile("%%|%\\d+\\$[sS]|%[sS]|%");

    private static String escapeStrayPercent(String template) {
        Matcher m = FORMAT_TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(token.equals("%") ? "%%" : token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static void initialize() {
        // 1. Automatically sync all embedded language files to external lang folder & merge missing keys
        syncEmbeddedLanguages();

        translations.clear();

        // 2. Load embedded en_us.json as baseline fallback
        loadEmbedded("en_us");

        // 3. Load configured language from EconomyConfig if different
        String configLanguage = savage.commoneconomy.config.ConfigManager.getConfig().language;
        if (configLanguage == null || configLanguage.trim().isEmpty()) {
            configLanguage = "en_us";
        }
        configLanguage = configLanguage.toLowerCase().trim();

        if (!configLanguage.equals("en_us")) {
            loadEmbedded(configLanguage);
        }

        // 4. Load external language overrides from config/savs-common-economy/lang/<configLanguage>.json
        loadExternal(configLanguage);
    }

    /**
     * Auto-syncs all embedded language files inside the mod JAR to the external lang folder.
     * Extracts missing files and merges any newly added keys from mod updates without overwriting existing admin edits.
     */
    private static void syncEmbeddedLanguages() {
        Path externalLangDir = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("lang");
        File dir = externalLangDir.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        List<String> langCodes = new ArrayList<>();

        // Dynamically discover all embedded .json lang files from JAR resources using FabricLoader
        Optional<Path> langFolderPath = FabricLoader.getInstance().getModContainer("savs-common-economy")
                .flatMap(container -> container.findPath("assets/savs-common-economy/lang"));

        if (langFolderPath.isPresent()) {
            try (var stream = Files.list(langFolderPath.get())) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(p -> {
                            String filename = p.getFileName().toString();
                            langCodes.add(filename.substring(0, filename.length() - 5));
                        });
            } catch (Exception e) {
                SavsCommonEconomy.LOGGER.warn("Could not list embedded lang folder paths, using fallback list.", e);
            }
        }

        // Fallback if dynamic discovery found no files
        if (langCodes.isEmpty()) {
            langCodes.add("en_us");
            langCodes.add("zh_cn");
        }

        Type mapType = new TypeToken<LinkedHashMap<String, String>>() {}.getType();

        for (String langCode : langCodes) {
            String resourcePath = "/assets/savs-common-economy/lang/" + langCode + ".json";
            LinkedHashMap<String, String> embeddedMap = null;

            try (InputStream is = TranslationHelper.class.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                        embeddedMap = GSON.fromJson(reader, mapType);
                    }
                }
            } catch (Exception e) {
                SavsCommonEconomy.LOGGER.error("Failed to read embedded translation resource: " + resourcePath, e);
            }

            if (embeddedMap == null || embeddedMap.isEmpty()) {
                continue;
            }

            File externalFile = externalLangDir.resolve(langCode + ".json").toFile();

            if (!externalFile.exists()) {
                // Case 1: External file does not exist -> dump template directly to disk
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(externalFile), StandardCharsets.UTF_8)) {
                    PRETTY_GSON.toJson(embeddedMap, writer);
                    SavsCommonEconomy.LOGGER.info("Extracted translation template: lang/" + langCode + ".json");
                } catch (Exception e) {
                    SavsCommonEconomy.LOGGER.error("Failed to extract translation template: " + externalFile.getPath(), e);
                }
            } else {
                // Case 2: External file already exists -> check for missing keys from mod updates
                LinkedHashMap<String, String> externalMap = null;
                try (Reader reader = new InputStreamReader(new FileInputStream(externalFile), StandardCharsets.UTF_8)) {
                    externalMap = GSON.fromJson(reader, mapType);
                } catch (Exception e) {
                    SavsCommonEconomy.LOGGER.error("Failed to read external translation file: " + externalFile.getPath(), e);
                }

                if (externalMap == null) {
                    externalMap = new LinkedHashMap<>();
                }

                boolean modified = false;
                int addedCount = 0;

                for (Map.Entry<String, String> entry : embeddedMap.entrySet()) {
                    if (!externalMap.containsKey(entry.getKey())) {
                        externalMap.put(entry.getKey(), entry.getValue());
                        modified = true;
                        addedCount++;
                    }
                }

                if (modified) {
                    try (Writer writer = new OutputStreamWriter(new FileOutputStream(externalFile), StandardCharsets.UTF_8)) {
                        PRETTY_GSON.toJson(externalMap, writer);
                        SavsCommonEconomy.LOGGER.info("Synced " + addedCount + " missing translation key(s) to external lang/" + langCode + ".json");
                    } catch (Exception e) {
                        SavsCommonEconomy.LOGGER.error("Failed to update external translation file: " + externalFile.getPath(), e);
                    }
                }
            }
        }
    }

    private static void loadEmbedded(String langCode) {
        String resourcePath = "/assets/savs-common-economy/lang/" + langCode + ".json";
        try (InputStream is = TranslationHelper.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                    Type type = new TypeToken<Map<String, String>>() {}.getType();
                    Map<String, String> loaded = GSON.fromJson(reader, type);
                    if (loaded != null) {
                        translations.putAll(loaded);
                        SavsCommonEconomy.LOGGER.info("Loaded embedded translation file: " + langCode + ".json");
                    }
                }
            } else if (langCode.equals("en_us")) {
                SavsCommonEconomy.LOGGER.error("Failed to find fallback translation file: en_us.json in jar resources!");
            }
        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.error("Failed to load embedded translation file: " + resourcePath, e);
        }
    }

    private static void loadExternal(String langCode) {
        Path externalLangDir = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy").resolve("lang");
        File externalFile = externalLangDir.resolve(langCode + ".json").toFile();

        if (externalFile.exists()) {
            try (Reader reader = new InputStreamReader(new FileInputStream(externalFile), StandardCharsets.UTF_8)) {
                Type type = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> loaded = GSON.fromJson(reader, type);
                if (loaded != null) {
                    translations.putAll(loaded);
                    SavsCommonEconomy.LOGGER.info("Loaded external translation overrides: lang/" + langCode + ".json");
                }
            } catch (Exception e) {
                SavsCommonEconomy.LOGGER.error("Failed to load external translation file: " + externalFile.getPath(), e);
            }
        }
    }

    /**
     * Translates a language key, formatting it with parameters, and parses any legacy color codes.
     */
    public static Component translate(String key, Object... args) {
        if (args == null || args.length == 0) {
            return parseLegacy(translateString(key));
        }

        Map<String, Component> componentMap = new HashMap<>();
        Object[] stringArgs = new Object[args.length];
        boolean hasComponents = false;

        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof Component comp) {
                String marker = "__CMP_" + i + "__";
                componentMap.put(marker, comp);
                stringArgs[i] = marker;
                hasComponents = true;
            } else {
                stringArgs[i] = args[i];
            }
        }

        String formatted = translateString(key, stringArgs);
        return parseLegacy(formatted, componentMap);
    }

    /**
     * Translates a language key and formats it with parameters, returning the formatted raw String.
     */
    public static String translateString(String key, Object... args) {
        String template = translations.get(key);
        if (template == null) {
            return key;
        }

        String safeTemplate = escapeStrayPercent(template);
        try {
            return String.format(safeTemplate, args);
        } catch (Exception e) {
            SavsCommonEconomy.LOGGER.error("Formatting error for key: " + key, e);
            return template;
        }
    }

    /**
     * Helper to check if a translation key exists.
     */
    public static boolean hasKey(String key) {
        return translations.containsKey(key);
    }

    /**
     * Parses legacy formatting codes (& or § followed by 0-9, a-f, k-o, r) into structured Components.
     */
    public static MutableComponent parseLegacy(String text) {
        return parseLegacy(text, Collections.emptyMap());
    }

    // Matches only genuine legacy code sequences ('&' or '§' followed by a real code character),
    // so a literal '&' in translated text (e.g. "Buy & Sell") is left in place instead of being
    // consumed as a code marker and silently dropping the character after it.
    private static final Pattern LEGACY_CODE = Pattern.compile("[&§]([0-9a-fk-or])");

    /**
     * Parses legacy formatting codes and integrates embedded Component parameters.
     */
    public static MutableComponent parseLegacy(String text, Map<String, Component> componentMap) {
        MutableComponent root = Component.literal("");
        if (text == null || text.isEmpty()) return root;

        ChatFormatting activeColor = null;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strikethrough = false;
        boolean obfuscated = false;

        Matcher matcher = LEGACY_CODE.matcher(text);
        int lastEnd = 0;

        while (matcher.find()) {
            String before = text.substring(lastEnd, matcher.start());
            if (!before.isEmpty()) {
                appendContentWithComponents(root, before, componentMap, activeColor, bold, italic, underline, strikethrough, obfuscated);
            }

            char code = matcher.group(1).charAt(0);
            switch (code) {
                case '0' -> { activeColor = ChatFormatting.BLACK; }
                case '1' -> { activeColor = ChatFormatting.DARK_BLUE; }
                case '2' -> { activeColor = ChatFormatting.DARK_GREEN; }
                case '3' -> { activeColor = ChatFormatting.DARK_AQUA; }
                case '4' -> { activeColor = ChatFormatting.DARK_RED; }
                case '5' -> { activeColor = ChatFormatting.DARK_PURPLE; }
                case '6' -> { activeColor = ChatFormatting.GOLD; }
                case '7' -> { activeColor = ChatFormatting.GRAY; }
                case '8' -> { activeColor = ChatFormatting.DARK_GRAY; }
                case '9' -> { activeColor = ChatFormatting.BLUE; }
                case 'a' -> { activeColor = ChatFormatting.GREEN; }
                case 'b' -> { activeColor = ChatFormatting.AQUA; }
                case 'c' -> { activeColor = ChatFormatting.RED; }
                case 'd' -> { activeColor = ChatFormatting.LIGHT_PURPLE; }
                case 'e' -> { activeColor = ChatFormatting.YELLOW; }
                case 'f' -> { activeColor = ChatFormatting.WHITE; }
                case 'k' -> { obfuscated = true; }
                case 'l' -> { bold = true; }
                case 'm' -> { strikethrough = true; }
                case 'n' -> { underline = true; }
                case 'o' -> { italic = true; }
                case 'r' -> {
                    activeColor = null;
                    bold = false;
                    italic = false;
                    underline = false;
                    strikethrough = false;
                    obfuscated = false;
                }
            }

            lastEnd = matcher.end();
        }

        String remaining = text.substring(lastEnd);
        if (!remaining.isEmpty()) {
            appendContentWithComponents(root, remaining, componentMap, activeColor, bold, italic, underline, strikethrough, obfuscated);
        }

        return root;
    }

    private static void appendContentWithComponents(MutableComponent root, String content, Map<String, Component> componentMap,
                                                     ChatFormatting activeColor, boolean bold, boolean italic,
                                                     boolean underline, boolean strikethrough, boolean obfuscated) {
        if (content.isEmpty()) return;

        if (componentMap == null || componentMap.isEmpty()) {
            MutableComponent sub = Component.literal(content);
            applyStyles(sub, activeColor, bold, italic, underline, strikethrough, obfuscated);
            root.append(sub);
            return;
        }

        String matchedMarker = null;
        int idx = -1;
        for (String marker : componentMap.keySet()) {
            int markerIdx = content.indexOf(marker);
            if (markerIdx != -1 && (matchedMarker == null || markerIdx < idx)) {
                matchedMarker = marker;
                idx = markerIdx;
            }
        }

        if (matchedMarker == null) {
            MutableComponent sub = Component.literal(content);
            applyStyles(sub, activeColor, bold, italic, underline, strikethrough, obfuscated);
            root.append(sub);
            return;
        }

        String before = content.substring(0, idx);
        String after = content.substring(idx + matchedMarker.length());

        if (!before.isEmpty()) {
            MutableComponent subBefore = Component.literal(before);
            applyStyles(subBefore, activeColor, bold, italic, underline, strikethrough, obfuscated);
            root.append(subBefore);
        }

        Component compArg = componentMap.get(matchedMarker);
        if (compArg != null) {
            root.append(compArg);
        }

        if (!after.isEmpty()) {
            appendContentWithComponents(root, after, componentMap, activeColor, bold, italic, underline, strikethrough, obfuscated);
        }
    }

    private static void applyStyles(MutableComponent sub, ChatFormatting activeColor, boolean bold, boolean italic,
                                    boolean underline, boolean strikethrough, boolean obfuscated) {
        if (activeColor != null) sub.withStyle(activeColor);
        if (bold) sub.withStyle(ChatFormatting.BOLD);
        if (italic) sub.withStyle(ChatFormatting.ITALIC);
        if (underline) sub.withStyle(ChatFormatting.UNDERLINE);
        if (strikethrough) sub.withStyle(ChatFormatting.STRIKETHROUGH);
        if (obfuscated) sub.withStyle(ChatFormatting.OBFUSCATED);
    }
}
