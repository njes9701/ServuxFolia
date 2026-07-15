package dev.njes.lep.placement;

import dev.njes.lep.protocol.ProtocolVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ComparatorMode;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Server-side port of the upstream Litematica/Tweakeroo placement decoders.
 * This deliberately operates on Mojang BlockState properties instead of a
 * hand-maintained list of Bukkit block-data interfaces.
 */
public final class PlacementStateDecoder {
    private static final Set<Property<?>> V3_WHITELIST = Set.of(
            BlockStateProperties.INVERTED,
            BlockStateProperties.OPEN,
            BlockStateProperties.BELL_ATTACHMENT,
            BlockStateProperties.AXIS,
            BlockStateProperties.HALF,
            BlockStateProperties.ATTACH_FACE,
            BlockStateProperties.CHEST_TYPE,
            BlockStateProperties.MODE_COMPARATOR,
            BlockStateProperties.DOOR_HINGE,
            BlockStateProperties.FACING,
            BlockStateProperties.HORIZONTAL_FACING,
            BlockStateProperties.FACING_HOPPER,
            BlockStateProperties.ORIENTATION,
            BlockStateProperties.RAIL_SHAPE,
            BlockStateProperties.RAIL_SHAPE_STRAIGHT,
            BlockStateProperties.SLAB_TYPE,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.COPPER_GOLEM_POSE,
            BlockStateProperties.BITES,
            BlockStateProperties.DELAY,
            BlockStateProperties.NOTE,
            BlockStateProperties.ROTATION_16
    );

    public Optional<BlockState> decode(
            BlockState initialState,
            int payload,
            ProtocolVersion version,
            ServerLevel level,
            BlockPos position,
            ServerPlayer player,
            boolean validate
    ) {
        if (payload < 0) {
            return Optional.of(initialState);
        }

        if (version == ProtocolVersion.V3) {
            return decodeV3(initialState, payload, level, position, player, validate);
        }

        BlockState decoded = decodeV2(initialState, payload, player);
        return validate && !decoded.canSurvive(level, position) ? Optional.empty() : Optional.of(decoded);
    }

    private BlockState decodeV2(BlockState state, int payload, ServerPlayer player) {
        Optional<Property<Direction>> directionProperty = findDirectionProperty(state);

        if (directionProperty.isPresent()) {
            state = applyDirection(state, directionProperty.get(), payload, player);
        } else if (state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis[] axes = Direction.Axis.values();
            Direction.Axis axis = axes[((payload >> 1) & 0x3) % axes.length];
            if (BlockStateProperties.AXIS.getPossibleValues().contains(axis)) {
                state = state.setValue(BlockStateProperties.AXIS, axis);
            }
        }

        int extraValue = payload >>> 5;
        if (extraValue > 0) {
            if (state.getBlock() instanceof RepeaterBlock
                    && BlockStateProperties.DELAY.getPossibleValues().contains(extraValue)) {
                state = state.setValue(BlockStateProperties.DELAY, extraValue);
            } else if (state.getBlock() instanceof ComparatorBlock) {
                state = state.setValue(BlockStateProperties.MODE_COMPARATOR, ComparatorMode.SUBTRACT);
            }
        }

        if (state.hasProperty(BlockStateProperties.HALF)) {
            state = state.setValue(BlockStateProperties.HALF, extraValue > 0 ? Half.TOP : Half.BOTTOM);
        }

        return state;
    }

    private Optional<BlockState> decodeV3(
            BlockState initialState,
            int payload,
            ServerLevel level,
            BlockPos position,
            ServerPlayer player,
            boolean validate
    ) {
        BlockState state = initialState;
        BlockState lastValidState = initialState;
        Optional<Property<Direction>> directionProperty = findDirectionProperty(state)
                .filter(property -> property != BlockStateProperties.VERTICAL_DIRECTION);

        if (directionProperty.isPresent()) {
            state = applyDirection(state, directionProperty.get(), payload, player);
            if (!validate || state.canSurvive(level, position)) {
                lastValidState = state;
            } else {
                state = lastValidState;
            }
            payload >>>= 3;
        }

        // Bit zero is intentionally unused in both upstream encoders.
        payload >>>= 1;

        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));

        for (Property<?> property : properties) {
            if (directionProperty.isPresent() && directionProperty.get().equals(property)) {
                continue;
            }

            if (V3_WHITELIST.contains(property) && !isBlacklisted(property)) {
                DecodeResult result = decodeProperty(state, property, payload);
                payload = result.remainingPayload();

                if (result.changed()) {
                    state = result.state();
                    if (!validate || state.canSurvive(level, position)) {
                        lastValidState = state;
                    } else {
                        state = lastValidState;
                    }
                }
            }
        }

        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        if (state.hasProperty(BlockStateProperties.POWERED)) {
            state = state.setValue(BlockStateProperties.POWERED, false);
        }
        if (initialState.hasProperty(BlockStateProperties.WATERLOGGED)
                && initialState.getValue(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, true);
        }

        return validate && !state.canSurvive(level, position) ? Optional.empty() : Optional.of(state);
    }

    private boolean isBlacklisted(Property<?> property) {
        return property == BlockStateProperties.WATERLOGGED || property == BlockStateProperties.POWERED;
    }

    private Optional<Property<Direction>> findDirectionProperty(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property instanceof EnumProperty<?> enumProperty
                    && !enumProperty.getPossibleValues().isEmpty()
                    && enumProperty.getPossibleValues().iterator().next() instanceof Direction) {
                @SuppressWarnings("unchecked")
                Property<Direction> directionProperty = (Property<Direction>) property;
                return Optional.of(directionProperty);
            }
        }
        return Optional.empty();
    }

    private BlockState applyDirection(
            BlockState state,
            Property<Direction> property,
            int payload,
            ServerPlayer player
    ) {
        Direction original = state.getValue(property);
        int decodedIndex = (payload & 0xF) >> 1;
        Direction requested = original;

        if (decodedIndex == 6) {
            requested = original.getOpposite();
        } else if (decodedIndex >= 0 && decodedIndex <= 5) {
            requested = Direction.from3DDataValue(decodedIndex);
            if (!property.getPossibleValues().contains(requested)) {
                requested = player.getDirection().getOpposite();
            }
        }

        return property.getPossibleValues().contains(requested) ? state.setValue(property, requested) : state;
    }

    private <T extends Comparable<T>> DecodeResult decodeProperty(BlockState state, Property<?> rawProperty, int payload) {
        @SuppressWarnings("unchecked")
        Property<T> property = (Property<T>) rawProperty;
        List<T> values = new ArrayList<>(property.getPossibleValues());
        values.sort(Comparator.naturalOrder());

        int requiredBits = Mth.log2(Mth.smallestEncompassingPowerOfTwo(values.size()));
        int bitMask = (1 << requiredBits) - 1;
        int valueIndex = payload & bitMask;
        int remaining = payload >>> requiredBits;

        if (valueIndex < 0 || valueIndex >= values.size()) {
            return new DecodeResult(state, payload, false);
        }

        T value = values.get(valueIndex);
        if (value == SlabType.DOUBLE || state.getValue(property).equals(value)) {
            return new DecodeResult(state, remaining, false);
        }

        return new DecodeResult(state.setValue(property, value), remaining, true);
    }

    private record DecodeResult(BlockState state, int remainingPayload, boolean changed) {
    }
}
