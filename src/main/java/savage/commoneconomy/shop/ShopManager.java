package savage.commoneconomy.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import savage.commoneconomy.SavsCommonEconomy;

import java.io.*;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * Handles shop storage, loading, and saving.
 */
public class ShopManager {
    private static class Holder {
        static final ShopManager INSTANCE = new ShopManager();
    }
    private final Map<BlockPos, Shop> shops = new HashMap<>();
    private final Map<UUID, Set<BlockPos>> playerShops = new HashMap<>();
    private final Set<BlockPos> dirtyShops = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final File shopsFile;
    private final Gson gson;
    private MinecraftServer server;

    private ShopManager() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("savs-common-economy");
        File dir = configDir.toFile();
        if (!dir.exists()) dir.mkdirs();
        this.shopsFile = configDir.resolve("shops.json").toFile();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public static ShopManager getInstance() {
        return Holder.INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    public Shop createShop(BlockPos pos, String worldId, UUID ownerId, String ownerName, ItemStack item,
                          BigDecimal price, boolean buying, ShopType type) {
        UUID shopId = UUID.randomUUID();
        Shop shop = new Shop(shopId, worldId, pos, ownerId, ownerName, type, item, price, buying, 0);
        shops.put(pos, shop);
        playerShops.computeIfAbsent(ownerId, k -> new HashSet<>()).add(pos);
        save();
        return shop;
    }

    public boolean isShopChest(BlockPos pos) {
        return shops.containsKey(pos);
    }

    /**
     * Marks a shop chest as dirty so its sign will be updated on the next tick cycle.
     * Called from the ChestBlockEntityMixin when chest contents change.
     */
    public void markDirty(BlockPos pos) {
        dirtyShops.add(pos);
    }

    /**
     * Returns and clears all dirty shop positions.
     */
    public Set<BlockPos> consumeDirtyShops() {
        if (dirtyShops.isEmpty()) return java.util.Collections.emptySet();
        Set<BlockPos> snapshot = new HashSet<>(dirtyShops);
        dirtyShops.clear();
        return snapshot;
    }

    public Collection<Shop> getAllShops() {
        return shops.values();
    }

    public Collection<Shop> getPlayerShops(UUID ownerId) {
        Set<BlockPos> positions = playerShops.get(ownerId);
        if (positions == null) return Collections.emptyList();
        List<Shop> result = new ArrayList<>();
        for (BlockPos pos : positions) {
            Shop shop = shops.get(pos);
            if (shop != null) result.add(shop);
        }
        return result;
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
                if (ownerShops.isEmpty()) playerShops.remove(shop.getOwnerId());
            }
            save();
        }
    }

    public void save() {
        if (server == null) return;

        // Write to a temporary file and move it into place, so a crash mid-write does not truncate shops.json
        Path tempPath = shopsFile.toPath().resolveSibling(shopsFile.getName() + ".tmp");
        try (FileWriter writer = new FileWriter(tempPath.toFile())) {
            List<ShopData> shopDataList = new ArrayList<>();
            for (Shop shop : shops.values()) {
                shopDataList.add(new ShopData(shop, server));
            }
            gson.toJson(new ShopsContainer(shopDataList), writer);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to save shops.json", e);
            return;
        }

        try {
            try {
                Files.move(tempPath, shopsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempPath, shopsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Failed to save shops.json", e);
        }
    }

    public void load() {
        if (!shopsFile.exists() || server == null) return;

        boolean hadProblems = false;
        try (FileReader reader = new FileReader(shopsFile)) {
            Type type = new TypeToken<ShopsContainer>() {}.getType();
            ShopsContainer container = gson.fromJson(reader, type);

            if (container != null && container.shops != null) {
                shops.clear();
                playerShops.clear();
                for (ShopData data : container.shops) {
                    try {
                        Shop shop = data.toShop(server);
                        shops.put(shop.getChestLocation(), shop);
                        playerShops.computeIfAbsent(shop.getOwnerId(), k -> new HashSet<>()).add(shop.getChestLocation());
                    } catch (Exception e) {
                        hadProblems = true;
                        SavsCommonEconomy.LOGGER.error("Failed to load a shop from shops.json", e);
                    }
                }
            }
        } catch (Exception e) {
            // Not just IOException: Gson throws unchecked exceptions for a corrupt file, which would stop the server starting
            hadProblems = true;
            SavsCommonEconomy.LOGGER.error("Failed to load shops.json", e);
        }

        if (hadProblems) {
            backUpShopsFile();
        }
    }

    /**
     * Shops that failed to load are missing from memory, so the next save would drop them from shops.json.
     * Keep a copy of the file as it was so they can be restored by hand.
     */
    private void backUpShopsFile() {
        Path backup = shopsFile.toPath().resolveSibling(shopsFile.getName() + ".broken-" + System.currentTimeMillis());
        try {
            Files.copy(shopsFile.toPath(), backup);
            SavsCommonEconomy.LOGGER.error("shops.json could not be loaded completely. A copy of the original was saved to {}; shops that failed to load are missing until restored from it.", backup);
        } catch (IOException e) {
            SavsCommonEconomy.LOGGER.error("Could not back up shops.json", e);
        }
    }

    private static class ShopsContainer {
        List<ShopData> shops;
        ShopsContainer(List<ShopData> shops) { this.shops = shops; }
    }

    private static class ShopData {
        String shopId;
        String worldId;
        BlockPosData chestLocation;
        String ownerId;
        String ownerName;
        String type;
        
        // Legacy item fields
        String itemId;
        int itemCount;
        
        // Exact 1:1 match with original mod
        String itemStackSnbt;
        
        String price;
        boolean buying;
        int stock;

        ShopData(Shop shop, MinecraftServer server) {
            this.shopId = shop.getShopId().toString();
            this.worldId = shop.getWorldId();
            this.chestLocation = new BlockPosData(shop.getChestLocation());
            this.ownerId = shop.getOwnerId().toString();
            this.ownerName = shop.getOwnerName();
            this.type = shop.getType().name();
            this.price = shop.getPrice().toString();
            this.buying = shop.isBuying();
            this.stock = shop.getStock();

            ItemStack item = shop.getItem();
            
            // Populate legacy fields for max compatibility
            if (!item.isEmpty()) {
                this.itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
                this.itemCount = item.getCount();
            } else {
                this.itemId = "minecraft:air";
                this.itemCount = 0;
            }

            // Encode ItemStack to Base64 NBT if not empty
            if (server != null && !item.isEmpty() && item.getItem() != net.minecraft.world.item.Items.AIR) {
                try {
                    RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                    CompoundTag nbt = (CompoundTag) ItemStack.CODEC.encodeStart(ops, item).getOrThrow();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    NbtIo.writeCompressed(nbt, baos);
                    this.itemStackSnbt = Base64.getEncoder().encodeToString(baos.toByteArray());
                } catch (Exception e) {
                    SavsCommonEconomy.LOGGER.error("Failed to encode item stack for shop " + shopId, e);
                }
            }
        }

        Shop toShop(MinecraftServer server) {
            UUID id = UUID.fromString(shopId);
            BlockPos pos = chestLocation.toBlockPos();
            UUID owner = UUID.fromString(ownerId);
            ShopType shopType = ShopType.valueOf(this.type);
            BigDecimal p = new BigDecimal(this.price);

            ItemStack itemStack = ItemStack.EMPTY;

            if (this.itemStackSnbt != null && server != null) {
                try {
                    byte[] bytes = Base64.getDecoder().decode(this.itemStackSnbt);
                    ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                    CompoundTag nbt = NbtIo.readCompressed(bais, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
                    RegistryOps<Tag> ops = server.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE);
                    itemStack = ItemStack.CODEC.parse(ops, nbt).getOrThrow();
                } catch (Exception e) {
                    // Do not continue with an empty item: the shop would misbehave and the next save would erase the stored item
                    throw new IllegalStateException("Failed to decode item stack for shop " + shopId, e);
                }
            } else if (this.itemId != null && !this.itemId.equals("minecraft:air")) {
                // Fallback for very old shops without base64 NBT
                net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.Identifier.parse(this.itemId))
                    .map(ref -> ref.value())
                    .orElse(net.minecraft.world.item.Items.AIR);
                itemStack = new ItemStack(item, this.itemCount);
            }

            return new Shop(id, worldId, pos, owner, ownerName, shopType, itemStack, p, buying, stock);
        }
    }

    private static class BlockPosData {
        int x, y, z;
        BlockPosData(BlockPos pos) { this.x = pos.getX(); this.y = pos.getY(); this.z = pos.getZ(); }
        BlockPos toBlockPos() { return new BlockPos(x, y, z); }
    }
}
