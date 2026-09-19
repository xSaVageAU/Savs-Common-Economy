package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import savage.commoneconomy.SavsCommonEconomy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which blocks can hold a shop, and how to reach the inventory of one (D2, D3).
 * The allowed blocks come from the shopAllowedContainers setting: block ids, and block tags with a "#" prefix.
 * Every check looks at the block that is there now, never at an id stored with a shop.
 * Nothing here loads a chunk (D8): a position in an unloaded chunk is treated as having no container.
 */
public final class ContainerRegistry {

    private final Set<Block> blocks = new HashSet<>();
    private final List<TagKey<Block>> tags = new ArrayList<>();

    /**
     * An entry that cannot be used (bad syntax, or an id or tag that does not exist) is logged and left out.
     * Build this once the server is starting, so that tags are loaded.
     */
    public ContainerRegistry(List<String> entries) {
        for (String raw : entries) {
            String entry = raw.trim();
            boolean isTag = entry.startsWith("#");
            Identifier id = Identifier.tryParse(isTag ? entry.substring(1) : entry);
            if (id == null) {
                SavsCommonEconomy.LOGGER.warn("shopAllowedContainers: '{}' is not a valid block id or tag, ignoring it.", entry);
            } else if (isTag) {
                addTag(TagKey.create(Registries.BLOCK, id), entry);
            } else {
                BuiltInRegistries.BLOCK.getOptional(id).ifPresentOrElse(blocks::add,
                        () -> SavsCommonEconomy.LOGGER.warn("shopAllowedContainers: there is no block '{}', ignoring it.", entry));
            }
        }
    }

    private void addTag(TagKey<Block> tag, String entry) {
        if (BuiltInRegistries.BLOCK.getTags().anyMatch(named -> named.key().equals(tag))) {
            tags.add(tag);
        } else {
            SavsCommonEconomy.LOGGER.warn("shopAllowedContainers: there is no block tag '{}', ignoring it.", entry);
        }
    }

    public boolean isAllowed(BlockState state) {
        if (blocks.contains(state.getBlock())) {
            return true;
        }
        for (TagKey<Block> tag : tags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The inventory of the container at a position: both halves combined for a double chest, the block's own
     * inventory for anything else.
     *
     * @return null if the chunk is not loaded (the partner half's chunk included), the block is not an allowed
     *         container, or it has no inventory
     */
    public Container resolve(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!isAllowed(state)) {
            return null;
        }
        if (state.getBlock() instanceof ChestBlock chest) {
            BlockPos partner = partnerOf(state, pos);
            if (partner != null && !level.hasChunkAt(partner)) {
                return null;
            }
            // override = true: a block on top of the chest, or a cat sitting on it, does not stop the shop (D3)
            return ChestBlock.getContainer(chest, state, level, pos, true);
        }
        return level.getBlockEntity(pos) instanceof Container container ? container : null;
    }

    /**
     * @return the position of the other half if this block is one half of a double chest, otherwise null
     */
    public static BlockPos partnerOf(BlockState state, BlockPos pos) {
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return ChestBlock.getConnectedBlockPos(pos, state);
        }
        return null;
    }
}
