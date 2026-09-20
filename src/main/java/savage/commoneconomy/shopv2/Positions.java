package savage.commoneconomy.shopv2;

import net.minecraft.core.BlockPos;
import savage.commoneconomy.shopv2.model.Position;

/**
 * Converts between the shop model's {@link Position} and the game's {@link BlockPos}.
 */
final class Positions {

    private Positions() {}

    static BlockPos toBlockPos(Position position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    static Position toPosition(BlockPos pos) {
        return new Position(pos.getX(), pos.getY(), pos.getZ());
    }
}
