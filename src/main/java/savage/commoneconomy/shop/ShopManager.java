package savage.commoneconomy.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;

public class ShopManager {
    private static ShopManager instance;
    private final Map<BlockPos, Shop> shops = new HashMap<>();
    private final Map<UUID, Set<BlockPos>> playerShops = new HashMap<>();
    private final File shopsFile;
    private final Gson gson;
    private MinecraftServer server;

    private ShopManager() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy");
        configDir.toFile().mkdirs();
        this.shopsFile = configDir.resolve("shops.json").toFile();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static ShopManager getInstance() {
        if (instance == null) {
            instance = new ShopManager();
        }
        return instance;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public Shop createShop(BlockPos pos, String worldId, UUID ownerId, String ownerName, ItemStack item,
            BigDecimal price, boolean buying, ShopType type) {
        UUID shopId = UUID.randomUUID();
        int initialStock = 0;

        Shop shop = new Shop(shopId, worldId, pos, ownerId, ownerName, type, item, price, buying, initialStock);
        shops.put(pos, shop);

        playerShops.computeIfAbsent(ownerId, k -> new HashSet<>()).add(pos);

        save();
        return shop;
    }

    public Shop getShop(BlockPos pos) {
        return shops.get(pos);
    }

    public void removeShop(BlockPos pos) {
        Shop shop = shops.remove(pos);
        if (shop != null) {
            Set<BlockPos> ownerShops = playerShops.get(shop.getOwnerId());
            if (ownerShops != null) {
                ownerShops.remove(pos);
                if (ownerShops.isEmpty()) {
                    playerShops.remove(shop.getOwnerId());
                }
            }
            save();
        }
    }

    public Set<Shop> getPlayerShops(UUID playerId) {
        Set<BlockPos> positions = playerShops.get(playerId);
        if (positions == null)
            return Collections.emptySet();

        Set<Shop> result = new HashSet<>();
        for (BlockPos pos : positions) {
            Shop shop = shops.get(pos);
            if (shop != null) {
                result.add(shop);
            }
        }
        return result;
    }

    public java.util.Collection<Shop> getAllShops() {
        return shops.values();
    }

    public boolean isShopChest(BlockPos pos) {
        return shops.containsKey(pos);
    }

    public void save() {
        try (FileWriter writer = new FileWriter(shopsFile)) {
            List<ShopData> shopDataList = new ArrayList<>();
            for (Shop shop : shops.values()) {
                shopDataList.add(new ShopData(shop, this.server));
            }
            gson.toJson(new ShopsContainer(shopDataList), writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void load() {
        if (!shopsFile.exists())
            return;

        try (FileReader reader = new FileReader(shopsFile)) {
            Type type = new TypeToken<ShopsContainer>() {
            }.getType();
            ShopsContainer container = gson.fromJson(reader, type);

            if (container != null && container.shops != null) {
                for (ShopData data : container.shops) {
                    Shop shop = data.toShop(this.server);
                    shops.put(shop.getChestLocation(), shop);
                    playerShops.computeIfAbsent(shop.getOwnerId(), k -> new HashSet<>())
                            .add(shop.getChestLocation());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class ShopsContainer {
        List<ShopData> shops;

        ShopsContainer(List<ShopData> shops) {
            this.shops = shops;
        }
    }

    private static class ShopData {
        String shopId;
        String worldId;
        BlockPosData chestLocation;
        String ownerId;
        String ownerName;
        String type;
        String itemId;
        int itemCount;
        String itemStackSnbt;
        double price;
        boolean buying;
        int stock;

        ShopData(Shop shop, MinecraftServer server) {
            this.shopId = shop.getShopId().toString();
            this.worldId = shop.getWorldId();
            // Default to overworld if null (migration support)
            if (this.worldId == null)
                this.worldId = "minecraft:overworld";

            this.chestLocation = new BlockPosData(shop.getChestLocation());
            this.ownerId = shop.getOwnerId().toString();
            this.ownerName = shop.getOwnerName();
            this.type = shop.getType().name();

            ItemStack item = shop.getItem();
            this.itemId = net.minecraft.registry.Registries.ITEM.getId(item.getItem()).toString();
            this.itemCount = item.getCount();

            if (server != null) {
                net.minecraft.nbt.NbtElement nbtElement = ItemStack.CODEC.encodeStart(
                        RegistryOps.of(net.minecraft.nbt.NbtOps.INSTANCE, server.getRegistryManager()), item)
                        .getOrThrow(IllegalStateException::new);
                try {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.writeCompressed((net.minecraft.nbt.NbtCompound) nbtElement, baos);
                    this.itemStackSnbt = java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Failed to encode NBT", e);
                }
            }

            this.price = shop.getPrice().doubleValue();
            this.buying = shop.isBuying();
            this.stock = shop.getStock();
        }

        Shop toShop(MinecraftServer server) {
            UUID shopId = UUID.fromString(this.shopId);
            BlockPos pos = chestLocation.toBlockPos();
            UUID ownerId = UUID.fromString(this.ownerId);
            ShopType shopType = ShopType.valueOf(this.type);

            // Default to overworld if missing (migration support)
            String wId = this.worldId != null ? this.worldId : "minecraft:overworld";

            ItemStack itemStack;
            if (this.itemStackSnbt != null && server != null) {
                try {
                    byte[] bytes = java.util.Base64.getDecoder().decode(this.itemStackSnbt);
                    java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes);
                    net.minecraft.nbt.NbtElement nbtCompound = net.minecraft.nbt.NbtIo.readCompressed(bais,
                            net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());
                    itemStack = ItemStack.CODEC.parse(
                            RegistryOps.of(net.minecraft.nbt.NbtOps.INSTANCE, server.getRegistryManager()), nbtCompound)
                            .getOrThrow(IllegalStateException::new);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to parse base64 NBT", e);
                }
            } else {
                net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM.get(
                        net.minecraft.util.Identifier.of(this.itemId));
                itemStack = new ItemStack(item, this.itemCount);
            }

            BigDecimal price = BigDecimal.valueOf(this.price);

            return new Shop(shopId, wId, pos, ownerId, this.ownerName, shopType,
                    itemStack, price, this.buying, this.stock);
        }
    }

    private static class BlockPosData {
        int x, y, z;

        BlockPosData(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }
    }
}
