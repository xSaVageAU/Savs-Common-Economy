package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
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
     * Rewrites the text on the shop's recorded sign. Does nothing if the shop has no sign, the sign's chunk (or the
     * container's) is not loaded, or there is no sign block at the recorded position. Never loads a chunk.
     *
     * @param level the level of the shop's dimension
     * @param item  the shop's item template
     */
    public void refresh(ServerLevel level, Shop shop, ItemStack item) {
        if (!shop.hasSign()) {
            return;
        }
        BlockPos signPos = blockPos(shop.sign());
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
        Container container = containers.resolve(level, blockPos(shop.anchor().position()));
        if (container == null) {
            return null;
        }
        if (shop.mode() == ShopMode.BUY) {
            return TranslationHelper.translate("shop.sign.space_line", ContainerStock.space(container, item));
        }
        return TranslationHelper.translate("shop.sign.stock_line", ContainerStock.count(container, item));
    }

    private static BlockPos blockPos(Position position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }
}
