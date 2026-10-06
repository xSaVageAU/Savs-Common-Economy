package savage.commoneconomy.shop.model;

import java.util.Objects;

/**
 * A block in a dimension. A shop is identified by the location of its anchor block (D1),
 * so the same coordinates in two dimensions are two different locations.
 *
 * @param dimension the dimension identifier, for example "minecraft:overworld"
 */
public record BlockLocation(String dimension, Position position) {

    public BlockLocation {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
    }
}
