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
package com.facebook.presto.hive.orc;

import com.facebook.hive.orc.OrcSerde;
import com.facebook.presto.hive.FileFormatDataSourceStats;
import com.facebook.presto.hive.FileOpener;
import com.facebook.presto.hive.HdfsEnvironment;
import com.facebook.presto.hive.HiveClientConfig;
import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.HiveSelectivePageSourceFactory;
import com.facebook.presto.hive.metastore.Storage;
import com.facebook.presto.orc.StripeMetadataSource;
import com.facebook.presto.orc.cache.OrcFileTailSource;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.Subfield;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.RowExpressionService;
import com.facebook.presto.spi.type.TypeManager;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static com.facebook.presto.hive.HiveErrorCode.HIVE_BAD_DATA;
import static com.facebook.presto.hive.orc.OrcSelectivePageSourceFactory.createOrcPageSource;
import static com.facebook.presto.orc.OrcEncoding.DWRF;
import static java.util.Objects.requireNonNull;

public class DwrfSelectivePageSourceFactory
        implements HiveSelectivePageSourceFactory
{
    private final TypeManager typeManager;
    private final StandardFunctionResolution functionResolution;
    private final RowExpressionService rowExpressionService;
    private final HdfsEnvironment hdfsEnvironment;
    private final FileFormatDataSourceStats stats;
    private final int domainCompactionThreshold;
    private final OrcFileTailSource orcFileTailSource;
    private final StripeMetadataSource stripeMetadataSource;
    private final FileOpener fileOpener;

    @Inject
    public DwrfSelectivePageSourceFactory(
            TypeManager typeManager,
            StandardFunctionResolution functionResolution,
            RowExpressionService rowExpressionService,
            HiveClientConfig config,
            HdfsEnvironment hdfsEnvironment,
            FileFormatDataSourceStats stats,
            OrcFileTailSource orcFileTailSource,
            StripeMetadataSource stripeMetadataSource,
            FileOpener fileOpener)
    {
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.domainCompactionThreshold = requireNonNull(config, "config is null").getDomainCompactionThreshold();
        this.orcFileTailSource = requireNonNull(orcFileTailSource, "orcFileTailSource is null");
        this.stripeMetadataSource = requireNonNull(stripeMetadataSource, "stripeMetadataSource is null");
        this.fileOpener = requireNonNull(fileOpener, "fileOpener is null");
    }

    @Override
    public Optional<? extends ConnectorPageSource> createPageSource(
            Configuration configuration,
            ConnectorSession session,
            Path path,
            long start,
            long length,
            long fileSize,
            Storage storage,
            List<HiveColumnHandle> columns,
            Map<Integer, String> prefilledValues,
            Map<Integer, Function<Block, Block>> coercers,
            List<Integer> outputColumns,
            TupleDomain<Subfield> domainPredicate,
            RowExpression remainingPredicate,
            DateTimeZone hiveStorageTimeZone,
            Optional<byte[]> extraFileInfo)
    {
        if (!OrcSerde.class.getName().equals(storage.getStorageFormat().getSerDe())) {
            return Optional.empty();
        }

        if (fileSize == 0) {
            throw new PrestoException(HIVE_BAD_DATA, "ORC file is empty: " + path);
        }

        return Optional.of(createOrcPageSource(
                session,
                DWRF,
                hdfsEnvironment,
                configuration,
                path,
                start,
                length,
                fileSize,
                columns,
                prefilledValues,
                coercers,
                outputColumns,
                domainPredicate,
                remainingPredicate,
                false,
                hiveStorageTimeZone,
                typeManager,
                functionResolution,
                rowExpressionService,
                false,
                stats,
                domainCompactionThreshold,
                orcFileTailSource,
                stripeMetadataSource,
                extraFileInfo,
                fileOpener));
    }
}
