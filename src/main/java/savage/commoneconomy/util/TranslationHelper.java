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
        String template = translations.get(key);
        if (template == null) {
            // Fallback to the raw key if not found
            return Component.literal(key);
        }

        String formatted;
        try {
            formatted = String.format(template, args);
        } catch (Exception e) {
            formatted = template;
            SavsCommonEconomy.LOGGER.error("Formatting error for key: " + key, e);
        }

        return parseLegacy(formatted);
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
        MutableComponent root = Component.literal("");
        if (text == null || text.isEmpty()) return root;

        String processed = text.replace('&', '§');
        String[] parts = processed.split("§");

        if (!parts[0].isEmpty()) {
            root.append(Component.literal(parts[0]));
        }

        ChatFormatting activeColor = null;
        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strikethrough = false;
        boolean obfuscated = false;

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            char code = part.charAt(0);
            String content = part.substring(1);

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

            if (!content.isEmpty()) {
                MutableComponent sub = Component.literal(content);
                if (activeColor != null) sub.withStyle(activeColor);
                if (bold) sub.withStyle(ChatFormatting.BOLD);
                if (italic) sub.withStyle(ChatFormatting.ITALIC);
                if (underline) sub.withStyle(ChatFormatting.UNDERLINE);
                if (strikethrough) sub.withStyle(ChatFormatting.STRIKETHROUGH);
                if (obfuscated) sub.withStyle(ChatFormatting.OBFUSCATED);
                root.append(sub);
            }
        }

        return root;
    }
}
