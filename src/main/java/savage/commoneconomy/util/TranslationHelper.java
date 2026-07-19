package savage.commoneconomy.util;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Handles server-side translations, including embedded jar lang files,
 * external config lang files, and legacy formatting parser.
 */
public class TranslationHelper {
    private static final Map<String, String> translations = new HashMap<>();
    private static final Gson GSON = new Gson();

    public static void initialize() {
        translations.clear();

        // 1. Load embedded en_us.json as fallback
        loadEmbedded("en_us");

        // 2. Load configured language from EconomyConfig
        String configLanguage = savage.commoneconomy.config.ConfigManager.getConfig().language;
        if (configLanguage == null || configLanguage.trim().isEmpty()) {
            configLanguage = "en_us";
        }
        configLanguage = configLanguage.toLowerCase().trim();

        if (!configLanguage.equals("en_us")) {
            loadEmbedded(configLanguage);
        }

        // 3. Load external language overrides from config directory if present
        loadExternal(configLanguage);
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
        File dir = externalLangDir.toFile();
        if (!dir.exists()) {
            dir.mkdirs();
        }

        // Write a helper README or template if en_us.json doesn't exist externally to make it easy for admins
        File templateFile = externalLangDir.resolve("en_us.json").toFile();
        if (!templateFile.exists()) {
            // Write external en_us template for customizing
            try (InputStream is = TranslationHelper.class.getResourceAsStream("/assets/savs-common-economy/lang/en_us.json")) {
                if (is != null) {
                    try (FileOutputStream fos = new FileOutputStream(templateFile)) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                }
            } catch (IOException e) {
                SavsCommonEconomy.LOGGER.warn("Failed to create external translation template file.", e);
            }
        }

        File externalFile = externalLangDir.resolve(langCode + ".json").toFile();
        if (externalFile.exists()) {
            try (FileReader reader = new FileReader(externalFile, StandardCharsets.UTF_8)) {
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
