package net.hollowcube.polar;

import com.github.luben.zstd.Zstd;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.builder.arguments.minecraft.ArgumentBlockState;
import net.minestom.server.command.builder.exception.ArgumentSyntaxException;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.Section;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.NetworkBufferAllocator;
import net.minestom.server.network.foreign.NetworkBufferSegmentProvider;
import net.minestom.server.utils.validate.Check;
import net.minestom.server.world.biome.Biome;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

import static net.hollowcube.polar.PolarLoader.*;
import static net.hollowcube.polar.PolarReader.*;
import static net.hollowcube.polar.UnsafeOps.*;
import static net.minestom.server.instance.Chunk.CHUNK_SECTION_SIZE;
import static net.minestom.server.network.NetworkBuffer.*;

final class StreamingPolarLoader {
    private final InstanceContainer instance;
    private final PolarDataConverter dataConverter;
    private final PolarWorldAccess worldAccess;
    private final boolean loadLighting;

    private int version, dataVersion;

    private final Object2IntMap<String> blockToStateIdCache = new Object2IntOpenHashMap<>();
    private final Object2IntMap<String> biomeToIdCache = new Object2IntOpenHashMap<>();
    private final int plainsBiomeId;

    StreamingPolarLoader(
            @NotNull InstanceContainer instance, @NotNull PolarDataConverter dataConverter,
            @Nullable PolarWorldAccess worldAccess, boolean loadLighting
    ) {
        this.instance = instance;
        this.dataConverter = dataConverter;
        this.worldAccess = worldAccess;
        this.loadLighting = loadLighting;

        var searchWorldAccess = Objects.requireNonNullElse(worldAccess, PolarWorldAccess.DEFAULT);
        this.plainsBiomeId = searchWorldAccess.getBiomeId(Biome.PLAINS.name());
        if (this.plainsBiomeId == -1) {
            throw new IllegalStateException("Plains biome not found");
        }
    }

    record CompressedDataInfo(PolarWorld.CompressionType compressionType, int dataLength) {}

    @SuppressWarnings({"UnstableApiUsage"}) // Arena api is marked as experimental
    public void loadAllSequential(@NotNull ReadableByteChannel channel, long fileSize) throws IOException {
        try (Arena dstArena = Arena.ofConfined()) {
            final var dstAllocator = NetworkBufferAllocator.staticAllocator()
                    .arena(dstArena)
                    .registry(MinecraftServer.process());
            final NetworkBuffer dst;
            try (Arena srcArena = Arena.ofConfined()) {
                final NetworkBuffer src; // We branch on the type of channel to avoid unnecessary copying to user space.
                if (channel instanceof FileChannel fileChannel) {
                    var mappedSegment = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize, srcArena);
                    src = NetworkBuffer.wrap(mappedSegment, 0, fileSize).readOnly();
                } else {
                    var buffer = NetworkBufferAllocator.staticAllocator()
                            .arena(srcArena)
                            .registry(MinecraftServer.process())
                            .allocate(fileSize);
                    buffer.readChannel(channel);
                    src = buffer.readOnly();
                }
                final var compressedData = readHeader(src);
                switch (compressedData.compressionType()) {
                    case NONE -> {
                        readData(src);
                        return;
                    }
                    // src should be unreachable following the dst copy.
                    case ZSTD -> dst = decompressZstdData(dstAllocator, src, compressedData.dataLength());
                    default -> throw new UnsupportedOperationException("Unsupported compression type: " + compressedData.compressionType());
                }
            } // src is deallocated
            // Now we can just read the dst buffer without having to worry about the extra footprint of src
            readData(dst);
        }
    }

    /**
     * Reads the header and returns the compression information for the data block.
     *
     * <p>Always populates {@link #version} and {@link #dataVersion}.</p>
     */
    private CompressedDataInfo readHeader(NetworkBuffer buffer) {
        var magicNumber = buffer.read(INT);
        assertThat(magicNumber == PolarWorld.MAGIC_NUMBER, "Invalid magic number");

        this.version = buffer.read(SHORT);
        validateVersion(this.version);
        this.dataVersion = version >= PolarWorld.VERSION_DATA_CONVERTER
                ? buffer.read(VAR_INT)
                : dataConverter.defaultDataVersion();

        var compressionType = PolarWorld.CompressionType.fromId(buffer.read(BYTE));
        assertThat(compressionType != null, "Invalid compression type");
        int dataLength = buffer.read(VAR_INT);
        return new CompressedDataInfo(compressionType, dataLength);
    }

    /**
     * Decompresses the Zstd data from the source buffer into a buffer using the provided allocator.
     *
     * @param dstAllocator the network buffer allocator to use for decompression
     * @param src the network buffer containing the compressed data
     * @param dataLength the length of the decompressed data, in bytes.
     * @return a network buffer containing the decompressed data
     */
    @SuppressWarnings({"UnstableApiUsage"}) // Provider#segment api is marked as experimental
    private NetworkBuffer decompressZstdData(@NotNull NetworkBufferAllocator dstAllocator, @NotNull NetworkBuffer src, int dataLength) {
        // This is using some internals of Minestom, so worth an explanation. As of 26.1, network buffer is
        // backed by a directly backed via MemorySegment. Zstd supports direct decompression using Unsafe,
        // so we can use the direct addresses of the two buffers for decompression.
        final var dst = dstAllocator.allocate(dataLength);
        final var srcAddress = NetworkBufferSegmentProvider.segment(src).address() + src.readIndex();
        final var dstAddress = NetworkBufferSegmentProvider.segment(dst).address();
        long count = Zstd.decompressUnsafe(dstAddress, dataLength, srcAddress,
                src.readableBytes());
        if (Zstd.isError(count)) {
            throw new RuntimeException("decompression failed: " + Zstd.getErrorName(count));
        }
        dst.writeIndex(dataLength);
        return dst.readOnly();
    }

    /**
     * Loads all chunks in the instance and user data.
     *
     * @param buffer the network buffer containing the decompressed data
     */
    private void readData(@NotNull NetworkBuffer buffer) {
        byte minSection = buffer.read(BYTE), maxSection = buffer.read(BYTE);
        assertThat(minSection < maxSection, "Invalid section range");

        // User (world) data
        if (version > PolarWorld.VERSION_WORLD_USERDATA) {
            int userDataLength = buffer.read(VAR_INT);
            if (worldAccess != null) {
                var worldDataView = buffer.slice(buffer.readIndex(), userDataLength, 0, userDataLength).readOnly();
                worldAccess.loadWorldData(instance, worldDataView);
            }
            buffer.advanceRead(userDataLength);
        }

        // Chunk data
        int chunkCount = buffer.read(VAR_INT);
        for (int i = 0; i < chunkCount; i++) {
            readChunk(buffer, minSection, maxSection);
        }

        Check.stateCondition(buffer.readableBytes() > 0, "Unexpected extra data at end of buffer");
    }

    private void readChunk(@NotNull NetworkBuffer buffer, int minSection, int maxSection) {
        final var chunkX = buffer.read(VAR_INT);
        final var chunkZ = buffer.read(VAR_INT);
        final var chunk = instance.getChunkSupplier().createChunk(instance, chunkX, chunkZ);
        unsafeSetNeedsCompleteHeightmapRefresh(chunk, false);
        var chunkEntries = unsafeGetEntries(chunk);
        var chunkTickables = unsafeGetTickableMap(chunk);

        // Load block data
        synchronized (chunk) {
            for (int sectionY = minSection; sectionY <= maxSection; sectionY++) {
                readSection(buffer, chunk.getSection(sectionY), sectionY, chunkEntries);
            }

            // Load block entities
            final int blockEntityCount = buffer.read(VAR_INT);
            for (int i = 0; i < blockEntityCount; i++) {
                final var blockEntity = readBlockEntity(dataConverter, version, dataVersion, buffer);
                if (chunkEntries != null && chunkTickables != null) {
                    final var block = createBlockEntity(chunk, blockEntity);
                    final int index = CoordConversion.chunkBlockIndex(
                            blockEntity.x(), blockEntity.y(), blockEntity.z());
                    chunkEntries.put(index, block);
                    if (block.handler() != null && block.handler().isTickable())
                        chunkTickables.put(index, block);
                } else {
                    loadBlockEntity(chunk, blockEntity);
                }
            }

            // Load heightmaps (if we have world data, otherwise we can skip)
            int[][] heightmaps = readHeightmapData(buffer, worldAccess == null);
            if (worldAccess != null) worldAccess.loadHeightmaps(chunk, heightmaps);
            else unsafeSetNeedsCompleteHeightmapRefresh(chunk, true);
        }

        unsafeChunkOnLoad(chunk);
        unsafeCacheChunk(instance, chunk);

        // Load user data
        if (version > PolarWorld.VERSION_USERDATA_OPT_BLOCK_ENT_NBT) {
            int userDataLength = buffer.read(VAR_INT);
            if (worldAccess != null) {
                var chunkDataView = buffer.slice(buffer.readIndex(), userDataLength, 0, userDataLength).readOnly();
                worldAccess.loadChunkData(chunk, chunkDataView);
            }
            buffer.advanceRead(userDataLength);
        }
    }

    private void readSection(
            @NotNull NetworkBuffer buffer, @NotNull Section section, int sectionY,
            @Nullable Int2ObjectMap<Block> chunkEntires
    ) {
        if (buffer.read(BOOLEAN)) return; // Empty section

        int[] blockPalette = readBlockPalette(buffer);
        if (blockPalette.length == 1) {
            // We just created the palette, no need to set air blocks.
            if (blockPalette[0] != 0) {
                section.blockPalette().fill(blockPalette[0]);
            }
        } else {
            var blockData = new int[PolarSection.BLOCK_PALETTE_SIZE];

            var rawBlockData = buffer.read(LONG_ARRAY);
            var bitsPerEntry = (int) Math.ceil(Math.log(blockPalette.length) / Math.log(2));
            PaletteUtil.unpack(blockData, rawBlockData, bitsPerEntry);

            for (int y = 0; y < CHUNK_SECTION_SIZE; y++) {
                for (int z = 0; z < CHUNK_SECTION_SIZE; z++) {
                    for (int x = 0; x < CHUNK_SECTION_SIZE; x++) {
                        int index = y * CHUNK_SECTION_SIZE * CHUNK_SECTION_SIZE + z * CHUNK_SECTION_SIZE + x;
                        int blockStateId = blockPalette[blockData[index]];
                        section.blockPalette().set(x, y, z, blockStateId);

                        // Vanilla block entities must be tracked in the chunk entries so they are sent to the client.
                        var block = Block.fromStateId(blockStateId);
                        if (chunkEntires != null && block.registry().isBlockEntity()) {
                            int chunkY = sectionY * CHUNK_SECTION_SIZE + y;
                            chunkEntires.putIfAbsent(CoordConversion.chunkBlockIndex(x, chunkY, z), block);
                        }
                    }
                }
            }
            //            section.blockPalette().setAll((x, y, z) -> {
            //                int index = y * CHUNK_SECTION_SIZE * CHUNK_SECTION_SIZE + z * CHUNK_SECTION_SIZE + x;
            //                return blockPalette[blockData[index]];
            //            });

            // Below was some previous logic, leaving it around for now I would like to fix it up.
            //            System.out.println(Arrays.toString(blockPalette));
            //            var rawBlockData = buffer.read(LONG_ARRAY);
            //            var bitsPerEntry = (int) Math.ceil(Math.log(blockPalette.length) / Math.log(2));
            //
            ////            int count = computeCount(blockPalette, rawBlockData, bitsPerEntry);
            //            int count = 16 * 16 * 16;
            //            directReplaceInnerPaletteBlock(section.blockPalette(), (byte) bitsPerEntry, count,
            //                    blockPalette, rawBlockData);
        }

        int[] biomePalette = readBiomePalette(buffer);
        if (biomePalette.length == 1) {
            section.biomePalette().fill(biomePalette[0]);
        } else {
            var biomeData = new int[PolarSection.BIOME_PALETTE_SIZE];

            var rawBiomeData = buffer.read(LONG_ARRAY);
            var bitsPerEntry = (int) Math.ceil(Math.log(biomePalette.length) / Math.log(2));
            PaletteUtil.unpack(biomeData, rawBiomeData, bitsPerEntry);

            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) {
                    for (int x = 0; x < 4; x++) {
                        int index = x + z * 4 + y * 16;
                        section.biomePalette().set(x, y, z, biomePalette[biomeData[index]]);
                    }
                }
            }
            //            section.biomePalette().setAll((x, y, z) -> {
            //                int index = x / 4 + (z / 4) * 4 + (y / 4) * 16;
            //                return biomePalette[biomeData[index]];
            //            });

            //            var rawBiomeData = buffer.read(LONG_ARRAY);
            //            var bitsPerEntry = (int) Math.ceil(Math.log(biomePalette.length) / Math.log(2));
            //            // Biome count is irrelevant to the client. Though it might be worth computing it anyway here
            //            // in case a server implementation uses it for anything.
            //            directReplaceInnerPaletteBiome(section.biomePalette(), (byte) bitsPerEntry, 4 * 4 * 4,
            //                    biomePalette, rawBiomeData);
        }

        if (version > PolarWorld.VERSION_UNIFIED_LIGHT) {
            var blockLightContent = version >= PolarWorld.VERSION_IMPROVED_LIGHT
                    ? PolarSection.LightContent.VALUES[buffer.read(BYTE)]
                    : (buffer.read(BOOLEAN) ? PolarSection.LightContent.PRESENT : PolarSection.LightContent.MISSING);
            byte[] blockLight = blockLightContent == PolarSection.LightContent.PRESENT ? buffer.read(LIGHT_DATA) : null;
            if (loadLighting && blockLightContent != PolarSection.LightContent.MISSING)
                unsafeUpdateBlockLightArray(section.blockLight(), getLightArray(blockLightContent, blockLight));

            var skyLightContent = version >= PolarWorld.VERSION_IMPROVED_LIGHT
                    ? PolarSection.LightContent.VALUES[buffer.read(BYTE)]
                    : (buffer.read(BOOLEAN) ? PolarSection.LightContent.PRESENT : PolarSection.LightContent.MISSING);
            byte[] skyLight = skyLightContent == PolarSection.LightContent.PRESENT ? buffer.read(LIGHT_DATA) : null;
            if (loadLighting && skyLightContent != PolarSection.LightContent.MISSING)
                unsafeUpdateSkyLightArray(section.skyLight(), getLightArray(skyLightContent, skyLight));
        } else if (buffer.read(BOOLEAN)) {
            if (loadLighting) {
                unsafeUpdateBlockLightArray(section.blockLight(), buffer.read(LIGHT_DATA));
                unsafeUpdateSkyLightArray(section.skyLight(), buffer.read(LIGHT_DATA));
            } else {
                buffer.advanceRead(2048 * 2); // Skip the data
            }
        }
    }

    private int[] readBlockPalette(@NotNull NetworkBuffer buffer) {
        var rawBlockPalette = buffer.read(STRING_ARRAY);
        if (dataVersion < dataConverter.dataVersion()) {
            dataConverter.convertBlockPalette(rawBlockPalette, dataVersion, dataConverter.dataVersion());
        }
        upgradeGrassInPalette(rawBlockPalette, version);
        int[] blockPalette = new int[rawBlockPalette.length];
        for (int i = 0; i < rawBlockPalette.length; i++) {
            blockPalette[i] = blockToStateIdCache.computeIfAbsent(rawBlockPalette[i], (String key) -> {
                try {
                    //noinspection deprecation
                    return ArgumentBlockState.staticParse(key).stateId();
                } catch (ArgumentSyntaxException e) {
                    throw new RuntimeException("Failed to parse block state: " + key, e);
                }
            });
        }
        return blockPalette;
    }

    private int[] readBiomePalette(@NotNull NetworkBuffer buffer) {
        var rawBiomePalette = buffer.read(STRING_ARRAY);
        int[] biomePalette = new int[rawBiomePalette.length];
        for (int i = 0; i < rawBiomePalette.length; i++) {
            biomePalette[i] = biomeToIdCache.computeIfAbsent(rawBiomePalette[i], (String name) -> {
                PolarWorldAccess searchWorldAccess = Objects.requireNonNullElse(this.worldAccess,
                                                                                PolarWorldAccess.DEFAULT);
                var biomeId = searchWorldAccess.getBiomeId(name);
                if (biomeId == -1) {
                    logger.error("Failed to find biome: {}", name);
                    biomeId = this.plainsBiomeId;
                }
                return biomeId;
            });
        }
        return biomePalette;
    }

    private int computeCount(int[] palette, long[] rawData, int bitsPerEntry) {
        int zeroIndex = -1;
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == 0) {
                zeroIndex = i;
                break;
            }
        }

        int count = 0;
        var intsPerLong = Math.floor(64d / bitsPerEntry);
        var intsPerLongCeil = (int) Math.ceil(intsPerLong);
        long mask = (1L << bitsPerEntry) - 1L;
        for (int i = 0; i < PolarSection.BLOCK_PALETTE_SIZE; i++) {
            int longIndex = i / intsPerLongCeil;
            int subIndex = i % intsPerLongCeil;

            int index = (int) ((rawData[longIndex] >>> (bitsPerEntry * subIndex)) & mask);
            if (index != zeroIndex) {
                count++;
            }
        }

        return count;
    }

    private static final NetworkBuffer.Type<String[]> STRING_ARRAY = new NetworkBuffer.Type<>() {

        @Override
        public void write(@NotNull NetworkBuffer buffer, String[] value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String[] read(@NotNull NetworkBuffer buffer) {
            final String[] array = new String[buffer.read(VAR_INT)];
            for (int i = 0; i < array.length; i++) {
                array[i] = buffer.read(STRING);
            }
            return array;
        }
    };
}
