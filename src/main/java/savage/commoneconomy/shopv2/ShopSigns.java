package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.block.state.BlockState;
import savage.commoneconomy.core.EconomyService;
import savage.commoneconomy.core.i18n.TranslationHelper;
import savage.commoneconomy.shopv2.model.Position;
import savage.commoneconomy.shopv2.model.Shop;
import savage.commoneconomy.shopv2.model.ShopMode;
import savage.commoneconomy.shopv2.model.ShopType;

/**
 * A shop's sign (D5). The sign is only a display: its text is generated from the shop and can always be regenerated.
 * Uses the same sign language keys as v1, so admins' custom wording carries over (D12).
 */
public final class ShopSigns {

    private final ContainerRegistry containers;

    public ShopSigns(ContainerRegistry containers) {
        this.containers = containers;
    }

    /**
     * Which side of the container the sign goes on (D5): the face the player is aiming at, or if that is the top or
     * bottom, the side opposite the direction the player is facing, so the sign faces them.
     *
     * @param playerFacing the direction the player looks in, horizontal
     * @return a horizontal direction
     */
    public static Direction chooseSide(Direction aimedFace, Direction playerFacing) {
        return aimedFace.getAxis().isHorizontal() ? aimedFace : playerFacing.getOpposite();
    }

    /**
     * Whether a wall sign can go on that side of the container: the spot must be empty or replaceable and
     * a wall sign must be able to stand there. Nothing else is tried, and it never loads a chunk.
     */
    public static boolean canPlace(ServerLevel level, BlockPos anchor, Direction side) {
        BlockPos signPos = anchor.relative(side);
        if (!level.hasChunkAt(signPos)) {
            return false;
        }
        BlockState existing = level.getBlockState(signPos);
        return (existing.isAir() || existing.canBeReplaced()) && wallSign(side).canSurvive(level, signPos);
    }

    /**
     * Places a blank wall sign on that side of the container, facing outwards. The text is written by {@link #refresh}.
     *
     * @return where the sign is, or null if it cannot be placed there (see {@link #canPlace})
     */
    public static Position place(ServerLevel level, BlockPos anchor, Direction side) {
        if (!canPlace(level, anchor, side)) {
            return null;
        }
        BlockPos signPos = anchor.relative(side);
        level.setBlock(signPos, wallSign(side), 3);
        return Positions.toPosition(signPos);
    }

    /**
     * @return true if the block at that position is a wall sign; false if it is anything else or its chunk is not loaded
     */
    public static boolean isPresent(ServerLevel level, Position signPosition) {
        BlockPos pos = Positions.toBlockPos(signPosition);
        return level.hasChunkAt(pos) && level.getBlockState(pos).getBlock() instanceof WallSignBlock;
    }

    /**
     * Removes a shop's sign without dropping an item: the sign was placed for free when the shop was created,
     * so a normal break would hand out a sign that nobody paid for. Does nothing if there is no wall sign there.
     */
    public static void clear(ServerLevel level, Position signPosition) {
        if (isPresent(level, signPosition)) {
            level.setBlock(Positions.toBlockPos(signPosition), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static BlockState wallSign(Direction side) {
        if (!side.getAxis().isHorizontal()) {
            throw new IllegalArgumentException("A wall sign goes on a side face, not " + side);
        }
        return Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, side);
    }

    /**
     * Rewrites the text on the shop's recorded sign. Does nothing if the shop has no sign or no readable item, the
     * sign's chunk (or the container's) is not loaded, or there is no sign block at the recorded position.
     * Never loads a chunk.
     *
     * @param level the level of the shop's dimension
     * @param item  the shop's item template, or null if it could not be read
     */
    public void refresh(ServerLevel level, Shop shop, ItemStack item) {
        if (!shop.hasSign() || item == null) {
            return;
        }
        BlockPos signPos = Positions.toBlockPos(shop.sign());
        if (!level.hasChunkAt(signPos) || !(level.getBlockEntity(signPos) instanceof SignBlockEntity sign)) {
            return;
        }
        Component stockLine = stockLine(level, shop, item);
        if (stockLine == null) {
            return;
        }

        String action = TranslationHelper.translateString(shop.mode() == ShopMode.BUY ? "shop.sign.action_buying" : "shop.sign.action_selling");
        Component header = shop.type() == ShopType.ADMIN
                ? TranslationHelper.translate("shop.sign.admin_header")
                : TranslationHelper.translate("shop.sign.owner_header", shop.ownerName());
        // The item's translatable name is left as it is: the server cannot know how long it renders in each client's language
        Component itemName = item.getHoverName();
        Component price = TranslationHelper.translate("shop.sign.price_line", action, EconomyService.get().format(shop.price()));

        sign.setText(sign.getText(SignTextSlot.FRONT).asMutable()
                .setLine(0, header)
                .setLine(1, itemName)
                .setLine(2, price)
                .setLine(3, stockLine)
                .asImmutable(), SignTextSlot.FRONT);
        BlockState state = level.getBlockState(signPos);
        level.sendBlockUpdated(signPos, state, state, 3);
    }

    /**
     * @return the fourth line, or null if the container's inventory cannot be reached right now
     */
    private Component stockLine(ServerLevel level, Shop shop, ItemStack item) {
        if (shop.type() == ShopType.ADMIN) {
            return TranslationHelper.translate("shop.sign.stock_infinite");
        }
        Container container = containers.resolve(level, Positions.toBlockPos(shop.anchor().position()));
        if (container == null) {
            return null;
        }
        if (shop.mode() == ShopMode.BUY) {
            return TranslationHelper.translate("shop.sign.space_line", ContainerStock.space(container, item));
        }
        return TranslationHelper.translate("shop.sign.stock_line", ContainerStock.count(container, item));
    }
}
