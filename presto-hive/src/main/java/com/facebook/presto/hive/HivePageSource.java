/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive;

import com.facebook.presto.hive.HivePageSourceProvider.BucketAdaptation;
import com.facebook.presto.hive.HivePageSourceProvider.ColumnMapping;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.LazyBlock;
import com.facebook.presto.spi.block.LazyBlockLoader;
import com.facebook.presto.spi.block.RunLengthEncodedBlock;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.annotations.VisibleForTesting;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfo;
import org.joda.time.DateTimeZone;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.hive.HiveBucketing.getHiveBucket;
import static com.facebook.presto.hive.HiveCoercers.createCoercer;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_CURSOR_ERROR;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_BUCKET_FILES;
import static com.facebook.presto.hive.HivePageSourceProvider.ColumnMappingKind.PREFILLED;
import static com.facebook.presto.hive.HiveUtil.typedPartitionKey;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class HivePageSource
        implements ConnectorPageSource
{
    private final List<ColumnMapping> columnMappings;
    private final Optional<BucketAdapter> bucketAdapter;
    private final Object[] prefilledValues;
    private final Type[] types;
    private final Function<Block, Block>[] coercers;

    private final ConnectorPageSource delegate;

    public HivePageSource(
            List<ColumnMapping> columnMappings,
            Optional<BucketAdaptation> bucketAdaptation,
            DateTimeZone hiveStorageTimeZone,
            TypeManager typeManager,
            ConnectorPageSource delegate)
    {
        requireNonNull(columnMappings, "columnMappings is null");
        requireNonNull(hiveStorageTimeZone, "hiveStorageTimeZone is null");
        requireNonNull(typeManager, "typeManager is null");

        this.delegate = requireNonNull(delegate, "delegate is null");
        this.columnMappings = columnMappings;
        this.bucketAdapter = bucketAdaptation.map(BucketAdapter::new);

        int size = columnMappings.size();

        prefilledValues = new Object[size];
        types = new Type[size];
        coercers = new Function[size];

        for (int columnIndex = 0; columnIndex < size; columnIndex++) {
            ColumnMapping columnMapping = columnMappings.get(columnIndex);
            HiveColumnHandle column = columnMapping.getHiveColumnHandle();

            String name = column.getName();
            Type type = typeManager.getType(column.getTypeSignature());
            types[columnIndex] = type;

            if (columnMapping.getCoercionFrom().isPresent()) {
                coercers[columnIndex] = createCoercer(typeManager, columnMapping.getCoercionFrom().get(), columnMapping.getHiveColumnHandle().getHiveType());
            }

            if (columnMapping.getKind() == PREFILLED) {
                prefilledValues[columnIndex] = typedPartitionKey(columnMapping.getPrefilledValue(), type, name, hiveStorageTimeZone);
            }
        }
    }

    @Override
    public long getCompletedBytes()
    {
        return delegate.getCompletedBytes();
    }

    @Override
    public long getCompletedPositions()
    {
        return delegate.getCompletedPositions();
    }

    @Override
    public long getReadTimeNanos()
    {
        return delegate.getReadTimeNanos();
    }

    @Override
    public boolean isFinished()
    {
        return delegate.isFinished();
    }

    @Override
    public Page getNextPage()
    {
        try {
            Page dataPage = delegate.getNextPage();
            if (dataPage == null) {
                return null;
            }

            if (bucketAdapter.isPresent()) {
                IntArrayList rowsToKeep = bucketAdapter.get().computeEligibleRowIds(dataPage);
                Block[] adaptedBlocks = new Block[dataPage.getChannelCount()];
                for (int i = 0; i < adaptedBlocks.length; i++) {
                    Block block = dataPage.getBlock(i);
                    if (block instanceof LazyBlock && !((LazyBlock) block).isLoaded()) {
                        adaptedBlocks[i] = new LazyBlock(rowsToKeep.size(), new RowFilterLazyBlockLoader(dataPage.getBlock(i), rowsToKeep));
                    }
                    else {
                        adaptedBlocks[i] = block.getPositions(rowsToKeep.elements(), 0, rowsToKeep.size());
                    }
                }
                dataPage = new Page(rowsToKeep.size(), adaptedBlocks);
            }

            int batchSize = dataPage.getPositionCount();
            List<Block> blocks = new ArrayList<>();
            for (int fieldId = 0; fieldId < columnMappings.size(); fieldId++) {
                ColumnMapping columnMapping = columnMappings.get(fieldId);
                switch (columnMapping.getKind()) {
                    case PREFILLED:
                        blocks.add(RunLengthEncodedBlock.create(types[fieldId], prefilledValues[fieldId], batchSize));
                        break;
                    case REGULAR:
                        Block block = dataPage.getBlock(columnMapping.getIndex());
                        if (coercers[fieldId] != null) {
                            block = new LazyBlock(batchSize, new CoercionLazyBlockLoader(block, coercers[fieldId]));
                        }
                        blocks.add(block);
                        break;
                    case INTERIM:
                        // interim columns don't show up in output
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
            }
            return new Page(batchSize, blocks.toArray(new Block[0]));
        }
        catch (PrestoException e) {
            closeWithSuppression(e);
            throw e;
        }
        catch (RuntimeException e) {
            closeWithSuppression(e);
            throw new PrestoException(HIVE_CURSOR_ERROR, e);
        }
    }

    @Override
    public void close()
    {
        try {
            delegate.close();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString()
    {
        return delegate.toString();
    }

    @Override
    public long getSystemMemoryUsage()
    {
        return delegate.getSystemMemoryUsage();
    }

    private void closeWithSuppression(Throwable throwable)
    {
        requireNonNull(throwable, "throwable is null");
        try {
            close();
        }
        catch (RuntimeException e) {
            // Self-suppression not permitted
            if (throwable != e) {
                throwable.addSuppressed(e);
            }
        }
    }

    @VisibleForTesting
    ConnectorPageSource getPageSource()
    {
        return delegate;
    }

    private static final class CoercionLazyBlockLoader
            implements LazyBlockLoader<LazyBlock>
    {
        private final Function<Block, Block> coercer;
        private Block block;

        public CoercionLazyBlockLoader(Block block, Function<Block, Block> coercer)
        {
            this.block = requireNonNull(block, "block is null");
            this.coercer = requireNonNull(coercer, "coercer is null");
        }

        @Override
        public void load(LazyBlock lazyBlock)
        {
            if (block == null) {
                return;
            }

            lazyBlock.setBlock(coercer.apply(block.getLoadedBlock()));

            // clear reference to loader to free resources, since load was successful
            block = null;
        }
    }

    private static final class RowFilterLazyBlockLoader
            implements LazyBlockLoader<LazyBlock>
    {
        private Block block;
        private final IntArrayList rowsToKeep;

        public RowFilterLazyBlockLoader(Block block, IntArrayList rowsToKeep)
        {
            this.block = requireNonNull(block, "block is null");
            this.rowsToKeep = requireNonNull(rowsToKeep, "rowsToKeep is null");
        }

        @Override
        public void load(LazyBlock lazyBlock)
        {
            if (block == null) {
                return;
            }

            lazyBlock.setBlock(block.getPositions(rowsToKeep.elements(), 0, rowsToKeep.size()));

            // clear reference to loader to free resources, since load was successful
            block = null;
        }
    }

    private static Page extractColumns(Page page, int[] columns)
    {
        Block[] blocks = new Block[columns.length];
        for (int i = 0; i < columns.length; i++) {
            int dataColumn = columns[i];
            blocks[i] = page.getBlock(dataColumn);
        }
        return new Page(page.getPositionCount(), blocks);
    }

    public static class BucketAdapter
    {
        public final int[] bucketColumns;
        public final int bucketToKeep;
        public final int tableBucketCount;
        public final int partitionBucketCount; // for sanity check only
        private final List<TypeInfo> typeInfoList;

        public BucketAdapter(BucketAdaptation bucketAdaptation)
        {
            this.bucketColumns = bucketAdaptation.getBucketColumnIndices();
            this.bucketToKeep = bucketAdaptation.getBucketToKeep();
            this.typeInfoList = bucketAdaptation.getBucketColumnHiveTypes().stream()
                    .map(HiveType::getTypeInfo)
                    .collect(toImmutableList());
            this.tableBucketCount = bucketAdaptation.getTableBucketCount();
            this.partitionBucketCount = bucketAdaptation.getPartitionBucketCount();
        }

        public IntArrayList computeEligibleRowIds(Page page)
        {
            IntArrayList ids = new IntArrayList(page.getPositionCount());
            Page bucketColumnsPage = extractColumns(page, bucketColumns);
            for (int position = 0; position < page.getPositionCount(); position++) {
                int bucket = getHiveBucket(tableBucketCount, typeInfoList, bucketColumnsPage, position);
                if ((bucket - bucketToKeep) % partitionBucketCount != 0) {
                    throw new PrestoException(HIVE_INVALID_BUCKET_FILES, format(
                            "A row that is supposed to be in bucket %s is encountered. Only rows in bucket %s (modulo %s) are expected",
                            bucket, bucketToKeep % partitionBucketCount, partitionBucketCount));
                }
                if (bucket == bucketToKeep) {
                    ids.add(position);
                }
            }
            return ids;
        }
    }
}
