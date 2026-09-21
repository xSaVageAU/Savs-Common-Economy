package savage.commoneconomy.shopv2.storage;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import savage.commoneconomy.shopv2.model.BlockLocation;
import savage.commoneconomy.shopv2.model.Position;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopMode;
import savage.commoneconomy.shopv2.model.ShopType;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Converts a {@link Shop} to and from its JSON. Reading is strict: anything missing
 * or invalid throws, and the caller leaves that file unchanged instead of guessing (D7).
 */
final class ShopJson {

    private ShopJson() {}

    static JsonObject toJson(Shop shop) {
        JsonObject json = new JsonObject();
        json.addProperty("id", shop.id().toString());
        json.addProperty("dimension", shop.anchor().dimension());
        json.add("anchor", positionToJson(shop.anchor().position()));
        json.addProperty("owner", shop.owner().toString());
        json.addProperty("ownerName", shop.ownerName());
        json.addProperty("type", shop.type().name());
        json.addProperty("mode", shop.mode().name());
        json.addProperty("price", shop.price().toPlainString());
        if (shop.hasSign()) {
            json.add("sign", positionToJson(shop.sign()));
        }
        json.addProperty("needsSignLookup", shop.needsSignLookup());
        return json;
    }

    static Shop fromJson(JsonObject json) {
        JsonElement sign = json.get("sign");
        return new Shop(
                UUID.fromString(text(json, "id")),
                new BlockLocation(text(json, "dimension"), position(json.get("anchor"), "anchor")),
                UUID.fromString(text(json, "owner")),
                text(json, "ownerName"),
                ShopType.valueOf(text(json, "type")),
                ShopMode.valueOf(text(json, "mode")),
                new BigDecimal(text(json, "price")),
                sign == null || sign.isJsonNull() ? null : position(sign, "sign"),
                flag(json, "needsSignLookup"));
    }

    private static JsonObject positionToJson(Position position) {
        JsonObject json = new JsonObject();
        json.addProperty("x", position.x());
        json.addProperty("y", position.y());
        json.addProperty("z", position.z());
        return json;
    }

    private static Position position(JsonElement element, String name) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException("Missing or invalid position: " + name);
        }
        JsonObject json = element.getAsJsonObject();
        return new Position(whole(json, "x"), whole(json, "y"), whole(json, "z"));
    }

    private static String text(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing or invalid text field: " + key);
        }
        return element.getAsString();
    }

    private static int whole(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("Missing or invalid number field: " + key);
        }
        return element.getAsBigDecimal().intValueExact();
    }

    private static boolean flag(JsonObject json, String key) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException("Invalid true/false field: " + key);
        }
        return element.getAsBoolean();
    }
}
