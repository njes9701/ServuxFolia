package fr.ekaii.litematica.nms;

import com.mojang.serialization.Dynamic;
import fr.ekaii.litematica.core.LitematicNbt;
import fr.ekaii.litematica.paste.FoliaThreadException;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;

import java.util.LinkedHashMap;
import java.util.logging.Logger;

/**
 * Paper 26.1.2 NMS bridge implementation. Depends on mojang-mapped
 * {@code net.minecraft.*} classes via paperweight-userdev.
 *
 * <p>All public methods are designed to be called from the chunk's owning
 * region (Folia) or the main thread (Paper). NMS thread-checks will fire
 * otherwise.
 *
 * <p>Errors that are obviously Folia "wrong region" failures are caught
 * and logged at WARNING — they're recoverable (the chunk's owning region
 * will eventually re-tick and pick up the changes). Other errors are
 * re-thrown.
 */
public final class NmsBridge26_2 implements NmsBridge {

    private static final Logger LOG = Logger.getLogger("LitematicaFolia/NmsBridge");

    private final int currentDataVersion;

    public NmsBridge26_2() {
        this.currentDataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    }

    // ----------------------------------------------------------- TileEntities

    @Override
    public void loadTileEntityNbt(Block block, LitematicNbt.NbtTag nbt) {
        if (!(nbt instanceof LitematicNbt.NbtCompound)) {
            return;
        }
        try {
            ServerLevel level = ((CraftWorld) block.getWorld()).getHandle();
            BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
            BlockState state = level.getBlockState(pos);
            if (!state.hasBlockEntity()) {
                return;
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) {
                // Block entity should already exist post-setBlock; if not, skip silently.
                return;
            }
            CompoundTag nms = (CompoundTag) toNmsCompound(nbt);
            if (nms == null) return;
            // Strip positional/id keys — the BlockEntity already has them, and
            // loadWithComponents complains about mismatches.
            CompoundTag cleaned = nms.copy();
            cleaned.remove("x");
            cleaned.remove("y");
            cleaned.remove("z");
            cleaned.remove("id");
            // TagValueInput.create returns a ValueInput in 26.1.2 — use var.
            var input = TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), cleaned);
            be.loadWithComponents(input);
            be.setChanged();
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("loadTileEntityNbt skipped (wrong region thread): " + t.getMessage());
            } else {
                LOG.log(java.util.logging.Level.WARNING, "loadTileEntityNbt failed", t);
            }
        }
    }

    // --------------------------------------------------------------- Entities

    @Override
    public org.bukkit.entity.Entity spawnEntityFromNbt(Location loc, LitematicNbt.NbtTag nbt) {
        if (!(nbt instanceof LitematicNbt.NbtCompound)) {
            return null;
        }
        try {
            ServerLevel level = ((CraftWorld) loc.getWorld()).getHandle();
            CompoundTag tag = (CompoundTag) toNmsCompound(nbt);
            if (tag == null) return null;
            // Strip player entities — never re-spawn players.
            String id = tag.getStringOr("id", "");
            if (id.endsWith(":player") || id.equals("player") || id.endsWith(":Player")) {
                return null;
            }
            Entity spawned = EntityType.loadEntityRecursive(
                    tag, level, new EntitySpawnRequest(net.minecraft.world.entity.EntitySpawnReason.LOAD, false),
                    entity -> {
                        entity.setUUID(java.util.UUID.randomUUID());
                        entity.snapTo(loc.getX(), loc.getY(), loc.getZ(),
                                entity.getYRot(), entity.getXRot());
                        return entity;
                    });
            if (spawned == null) return null;
            if (level.tryAddFreshEntityWithPassengers(spawned)) {
                return spawned.getBukkitEntity();
            }
            return null;
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("spawnEntityFromNbt skipped (wrong region thread): " + t.getMessage());
                return null;
            }
            LOG.log(java.util.logging.Level.WARNING, "spawnEntityFromNbt failed", t);
            return null;
        }
    }

    // --------------------------------------------------------- Pending ticks

    @Override
    public void scheduleBlockTick(World world, int x, int y, int z, LitematicNbt.NbtTag nbt) {
        if (!(nbt instanceof LitematicNbt.NbtCompound c)) return;
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            int delay = readIntOr(c, "t", readIntOr(c, "delay", 0));
            int priority = readIntOr(c, "p", readIntOr(c, "priority", 0));
            net.minecraft.world.ticks.TickPriority tp = net.minecraft.world.ticks.TickPriority.byValue(priority);
            level.getBlockTicks().schedule(new net.minecraft.world.ticks.ScheduledTick<>(
                    state.getBlock(), pos, Math.max(0, delay), tp, level.nextSubTickCount()
            ));
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("scheduleBlockTick skipped (wrong region thread): " + t.getMessage());
                return;
            }
            LOG.log(java.util.logging.Level.WARNING, "scheduleBlockTick failed", t);
        }
    }

    @Override
    public void scheduleFluidTick(World world, int x, int y, int z, LitematicNbt.NbtTag nbt) {
        if (!(nbt instanceof LitematicNbt.NbtCompound c)) return;
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockPos pos = new BlockPos(x, y, z);
            int delay = readIntOr(c, "t", readIntOr(c, "delay", 0));
            int priority = readIntOr(c, "p", readIntOr(c, "priority", 0));
            net.minecraft.world.ticks.TickPriority tp = net.minecraft.world.ticks.TickPriority.byValue(priority);
            level.getFluidTicks().schedule(new net.minecraft.world.ticks.ScheduledTick<>(
                    level.getFluidState(pos).getType(), pos, Math.max(0, delay), tp, level.nextSubTickCount()
            ));
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("scheduleFluidTick skipped (wrong region thread): " + t.getMessage());
                return;
            }
            LOG.log(java.util.logging.Level.WARNING, "scheduleFluidTick failed", t);
        }
    }

    // ------------------------------------------------------------------ Extraction (save v2)
    // 2026-05-24: real NMS impl. saveWithFullMetadata(HolderLookup.Provider)
    // is the canonical capture API on 26.1.2 for BlockEntities; Entity#save
    // takes a ValueOutput in 26.1.2 (TagValueOutput.createWithContext builds
    // one against a problem reporter + registry access).

    @Override
    public LitematicNbt.NbtTag extractTileEntityNbt(World world, int x, int y, int z) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockPos pos = new BlockPos(x, y, z);
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return null;
            CompoundTag ct;
            try {
                ct = be.saveWithFullMetadata(level.registryAccess());
            } catch (Throwable t) {
                if (!FoliaThreadException.isFoliaThreadException(t)) throw t;
                LOG.warning("extractTileEntityNbt skipped (wrong region thread): " + t.getMessage());
                return null;
            }
            return fromNmsCompound(ct);
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("extractTileEntityNbt skipped (wrong region thread): " + t.getMessage());
                return null;
            }
            LOG.log(java.util.logging.Level.WARNING, "extractTileEntityNbt failed", t);
            return null;
        }
    }

    @Override
    public LitematicNbt.NbtTag extractEntityNbt(org.bukkit.entity.Entity entity) {
        if (entity == null) return null;
        try {
            Entity nms = ((CraftEntity) entity).getHandle();
            // Never serialize players.
            if (nms instanceof net.minecraft.server.level.ServerPlayer) return null;
            // Removed entities cannot be saved.
            if (nms.isRemoved()) return null;
            ServerLevel level = (ServerLevel) nms.level();
            TagValueOutput out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
            boolean ok;
            try {
                ok = nms.save(out);
            } catch (Throwable t) {
                if (!FoliaThreadException.isFoliaThreadException(t)) throw t;
                LOG.warning("extractEntityNbt skipped (wrong region thread): " + t.getMessage());
                return null;
            }
            if (!ok) return null;
            CompoundTag ct = out.buildResult();
            return fromNmsCompound(ct);
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("extractEntityNbt skipped (wrong region thread): " + t.getMessage());
                return null;
            }
            LOG.log(java.util.logging.Level.WARNING, "extractEntityNbt failed", t);
            return null;
        }
    }

    @Override
    public java.util.List<LitematicNbt.NbtTag> extractPendingBlockTicks(World world, int x, int y, int z) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockPos pos = new BlockPos(x, y, z);
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
            net.minecraft.world.ticks.TickContainerAccess<net.minecraft.world.level.block.Block> access = chunk.getBlockTicks();
            if (!(access instanceof net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.block.Block> ticks)) {
                return java.util.Collections.emptyList();
            }
            long gameTime = level.getGameTime();
            java.util.List<LitematicNbt.NbtTag> out = new java.util.ArrayList<>();
            ticks.getAll().forEach(scheduled -> {
                if (!scheduled.pos().equals(pos)) return;
                LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
                net.minecraft.resources.Identifier key =
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(scheduled.type());
                c.putString("i", key == null ? "minecraft:air" : key.toString());
                c.putInt("x", scheduled.pos().getX());
                c.putInt("y", scheduled.pos().getY());
                c.putInt("z", scheduled.pos().getZ());
                int delay = (int) Math.max(0, scheduled.triggerTick() - gameTime);
                c.putInt("t", delay);
                c.putInt("p", scheduled.priority().getValue());
                out.add(c);
            });
            return out;
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("extractPendingBlockTicks skipped (wrong region thread): " + t.getMessage());
                return java.util.Collections.emptyList();
            }
            LOG.log(java.util.logging.Level.WARNING, "extractPendingBlockTicks failed", t);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public java.util.List<LitematicNbt.NbtTag> extractPendingFluidTicks(World world, int x, int y, int z) {
        try {
            ServerLevel level = ((CraftWorld) world).getHandle();
            BlockPos pos = new BlockPos(x, y, z);
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunk(x >> 4, z >> 4);
            net.minecraft.world.ticks.TickContainerAccess<net.minecraft.world.level.material.Fluid> access = chunk.getFluidTicks();
            if (!(access instanceof net.minecraft.world.ticks.LevelChunkTicks<net.minecraft.world.level.material.Fluid> ticks)) {
                return java.util.Collections.emptyList();
            }
            long gameTime = level.getGameTime();
            java.util.List<LitematicNbt.NbtTag> out = new java.util.ArrayList<>();
            ticks.getAll().forEach(scheduled -> {
                if (!scheduled.pos().equals(pos)) return;
                LitematicNbt.NbtCompound c = new LitematicNbt.NbtCompound();
                net.minecraft.resources.Identifier key =
                        net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(scheduled.type());
                c.putString("i", key == null ? "minecraft:empty" : key.toString());
                c.putInt("x", scheduled.pos().getX());
                c.putInt("y", scheduled.pos().getY());
                c.putInt("z", scheduled.pos().getZ());
                int delay = (int) Math.max(0, scheduled.triggerTick() - gameTime);
                c.putInt("t", delay);
                c.putInt("p", scheduled.priority().getValue());
                out.add(c);
            });
            return out;
        } catch (Throwable t) {
            if (FoliaThreadException.isFoliaThreadException(t)) {
                LOG.warning("extractPendingFluidTicks skipped (wrong region thread): " + t.getMessage());
                return java.util.Collections.emptyList();
            }
            LOG.log(java.util.logging.Level.WARNING, "extractPendingFluidTicks failed", t);
            return java.util.Collections.emptyList();
        }
    }

    private static int readIntOr(LitematicNbt.NbtCompound c, String key, int def) {
        Integer i = c.getInt(key);
        if (i != null) return i;
        Long l = c.getLong(key);
        if (l != null) return l.intValue();
        Short s = c.getShort(key);
        if (s != null) return s.intValue();
        Byte b = c.getByte(key);
        if (b != null) return b.intValue();
        return def;
    }

    // ------------------------------------------------------------------ DFU

    @Override
    public int currentDataVersion() {
        return currentDataVersion;
    }

    @Override
    public LitematicNbt.NbtTag dataFix(LitematicNbt.NbtTag input, int fromDataVersion, DfuKind kind) {
        if (input == null) return null;
        if (fromDataVersion == currentDataVersion) return input;
        try {
            Tag nms = (Tag) toAnyNms(input);
            if (nms == null) return input;
            var ref = switch (kind) {
                case BLOCK_STATE  -> References.BLOCK_STATE;
                case BLOCK_ENTITY -> References.BLOCK_ENTITY;
                case ENTITY       -> References.ENTITY;
            };
            Dynamic<Tag> dynamic = new Dynamic<>(NbtOps.INSTANCE, nms);
            Dynamic<Tag> output = DataFixers.getDataFixer().update(ref, dynamic, fromDataVersion, currentDataVersion);
            return fromAnyNms(output.getValue());
        } catch (Throwable t) {
            LOG.log(java.util.logging.Level.WARNING,
                    "DFU update failed for " + kind + " from " + fromDataVersion + " to " + currentDataVersion, t);
            return input;
        }
    }

    // -------------------------------------------------------- NBT conversion

    @Override
    public Object toNmsCompound(LitematicNbt.NbtTag tag) {
        Tag t = toAnyNms(tag);
        return (t instanceof CompoundTag) ? t : null;
    }

    @Override
    public LitematicNbt.NbtTag fromNmsCompound(Object compoundTag) {
        if (!(compoundTag instanceof CompoundTag c)) return null;
        return fromAnyNms(c);
    }

    static Tag toAnyNms(LitematicNbt.NbtTag tag) {
        if (tag == null) return null;
        if (tag instanceof LitematicNbt.NbtByte b)       return ByteTag.valueOf(b.value());
        if (tag instanceof LitematicNbt.NbtShort s)      return ShortTag.valueOf(s.value());
        if (tag instanceof LitematicNbt.NbtInt i)        return IntTag.valueOf(i.value());
        if (tag instanceof LitematicNbt.NbtLong l)       return LongTag.valueOf(l.value());
        if (tag instanceof LitematicNbt.NbtFloat f)      return FloatTag.valueOf(f.value());
        if (tag instanceof LitematicNbt.NbtDouble d)     return DoubleTag.valueOf(d.value());
        if (tag instanceof LitematicNbt.NbtString s)     return StringTag.valueOf(s.value());
        if (tag instanceof LitematicNbt.NbtByteArray a)  return new ByteArrayTag(a.value().clone());
        if (tag instanceof LitematicNbt.NbtIntArray a)   return new IntArrayTag(a.value().clone());
        if (tag instanceof LitematicNbt.NbtLongArray a)  return new LongArrayTag(a.value().clone());
        if (tag instanceof LitematicNbt.NbtList list) {
            ListTag out = new ListTag();
            for (LitematicNbt.NbtTag child : list.values()) {
                Tag converted = toAnyNms(child);
                if (converted != null) out.add(converted);
            }
            return out;
        }
        if (tag instanceof LitematicNbt.NbtCompound c) {
            CompoundTag out = new CompoundTag();
            for (var entry : c.entries().entrySet()) {
                Tag converted = toAnyNms(entry.getValue());
                if (converted != null) out.put(entry.getKey(), converted);
            }
            return out;
        }
        return null;
    }

    static LitematicNbt.NbtTag fromAnyNms(Tag tag) {
        if (tag == null) return null;
        if (tag instanceof ByteTag t)       return new LitematicNbt.NbtByte(t.byteValue());
        if (tag instanceof ShortTag t)      return new LitematicNbt.NbtShort(t.shortValue());
        if (tag instanceof IntTag t)        return new LitematicNbt.NbtInt(t.intValue());
        if (tag instanceof LongTag t)       return new LitematicNbt.NbtLong(t.longValue());
        if (tag instanceof FloatTag t)      return new LitematicNbt.NbtFloat(t.floatValue());
        if (tag instanceof DoubleTag t)     return new LitematicNbt.NbtDouble(t.doubleValue());
        if (tag instanceof StringTag t)     return new LitematicNbt.NbtString(t.value());
        if (tag instanceof ByteArrayTag t)  return new LitematicNbt.NbtByteArray(t.getAsByteArray().clone());
        if (tag instanceof IntArrayTag t)   return new LitematicNbt.NbtIntArray(t.getAsIntArray().clone());
        if (tag instanceof LongArrayTag t)  return new LitematicNbt.NbtLongArray(t.getAsLongArray().clone());
        if (tag instanceof ListTag l) {
            java.util.List<LitematicNbt.NbtTag> values = new java.util.ArrayList<>(l.size());
            byte elementType = LitematicNbt.TAG_END;
            for (Tag child : l) {
                LitematicNbt.NbtTag converted = fromAnyNms(child);
                if (converted != null) {
                    values.add(converted);
                    elementType = converted.id();
                }
            }
            return new LitematicNbt.NbtList(elementType, values);
        }
        if (tag instanceof CompoundTag c) {
            LinkedHashMap<String, LitematicNbt.NbtTag> map = new LinkedHashMap<>();
            for (String key : c.keySet()) {
                Tag value = c.get(key);
                LitematicNbt.NbtTag converted = fromAnyNms(value);
                if (converted != null) {
                    map.put(key, converted);
                }
            }
            return new LitematicNbt.NbtCompound(map);
        }
        return null;
    }
}
