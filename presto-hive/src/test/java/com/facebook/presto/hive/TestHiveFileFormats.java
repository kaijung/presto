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

import com.facebook.presto.hive.metastore.Column;
import com.facebook.presto.hive.metastore.Storage;
import com.facebook.presto.hive.metastore.StorageFormat;
import com.facebook.presto.hive.orc.DwrfBatchPageSourceFactory;
import com.facebook.presto.hive.orc.OrcBatchPageSourceFactory;
import com.facebook.presto.hive.parquet.ParquetPageSourceFactory;
import com.facebook.presto.hive.rcfile.RcFilePageSourceFactory;
import com.facebook.presto.orc.OrcWriterOptions;
import com.facebook.presto.orc.StorageStripeMetadataSource;
import com.facebook.presto.orc.cache.StorageOrcFileTailSource;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.RecordPageSource;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.facebook.presto.spi.type.ArrayType;
import com.facebook.presto.spi.type.RowType;
import com.facebook.presto.testing.TestingConnectorSession;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.airlift.compress.lzo.LzoCodec;
import io.airlift.compress.lzo.LzopCodec;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.type.HiveVarchar;
import org.apache.hadoop.hive.serde2.objectinspector.ListObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.MapObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.PrimitiveObjectInspector.PrimitiveCategory;
import org.apache.hadoop.hive.serde2.objectinspector.StructField;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.VarcharTypeInfo;
import org.apache.hadoop.mapred.FileSplit;
import org.joda.time.DateTimeZone;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TimeZone;
import java.util.stream.Collectors;

import static com.facebook.presto.hive.HiveErrorCode.HIVE_INVALID_PARTITION_VALUE;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_PARTITION_SCHEMA_MISMATCH;
import static com.facebook.presto.hive.HiveStorageFormat.AVRO;
import static com.facebook.presto.hive.HiveStorageFormat.DWRF;
import static com.facebook.presto.hive.HiveStorageFormat.JSON;
import static com.facebook.presto.hive.HiveStorageFormat.ORC;
import static com.facebook.presto.hive.HiveStorageFormat.PARQUET;
import static com.facebook.presto.hive.HiveStorageFormat.RCBINARY;
import static com.facebook.presto.hive.HiveStorageFormat.RCTEXT;
import static com.facebook.presto.hive.HiveStorageFormat.SEQUENCEFILE;
import static com.facebook.presto.hive.HiveStorageFormat.TEXTFILE;
import static com.facebook.presto.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static com.facebook.presto.hive.HiveTestUtils.HIVE_CLIENT_CONFIG;
import static com.facebook.presto.hive.HiveTestUtils.SESSION;
import static com.facebook.presto.hive.HiveTestUtils.TYPE_MANAGER;
import static com.facebook.presto.hive.HiveTestUtils.getTypes;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.BooleanType.BOOLEAN;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.spi.type.IntegerType.INTEGER;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static com.facebook.presto.tests.StructuralTestUtil.arrayBlockOf;
import static com.facebook.presto.tests.StructuralTestUtil.mapBlockOf;
import static com.facebook.presto.tests.StructuralTestUtil.rowBlockOf;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.filter;
import static io.airlift.slice.Slices.utf8Slice;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardListObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardMapObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory.getStandardStructObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.getPrimitiveJavaObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaBooleanObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaDoubleObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaFloatObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaIntObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaLongObjectInspector;
import static org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory.javaStringObjectInspector;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class TestHiveFileFormats
        extends AbstractTestHiveFileFormats
{
    private static final FileFormatDataSourceStats STATS = new FileFormatDataSourceStats();
    private static TestingConnectorSession parquetPageSourceSession = new TestingConnectorSession(new HiveSessionProperties(createParquetHiveClientConfig(false), new OrcFileWriterConfig(), new ParquetFileWriterConfig()).getSessionProperties());
    private static TestingConnectorSession parquetPageSourceSessionUseName = new TestingConnectorSession(new HiveSessionProperties(createParquetHiveClientConfig(true), new OrcFileWriterConfig(), new ParquetFileWriterConfig()).getSessionProperties());

    private static final DateTimeZone HIVE_STORAGE_TIME_ZONE = DateTimeZone.forID("America/Bahia_Banderas");

    @DataProvider(name = "rowCount")
    public static Object[][] rowCountProvider()
    {
        return new Object[][] {{0}, {1000}};
    }

    @BeforeClass(alwaysRun = true)
    public void setUp()
    {
        // ensure the expected timezone is configured for this VM
        assertEquals(TimeZone.getDefault().getID(),
                "America/Bahia_Banderas",
                "Timezone not configured correctly. Add -Duser.timezone=America/Bahia_Banderas to your JVM arguments");
    }

    @Test(dataProvider = "rowCount")
    public void testTextFile(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                .filter(column -> !column.getName().equals("t_map_null_key_complex_key_value"))
                .collect(toList());

        assertThatFileFormat(TEXTFILE)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));
    }

    @Test(dataProvider = "rowCount")
    public void testJson(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                // binary is not supported
                .filter(column -> !column.getName().equals("t_binary"))
                // non-string map keys are not supported
                .filter(column -> !column.getName().equals("t_map_tinyint"))
                .filter(column -> !column.getName().equals("t_map_smallint"))
                .filter(column -> !column.getName().equals("t_map_int"))
                .filter(column -> !column.getName().equals("t_map_bigint"))
                .filter(column -> !column.getName().equals("t_map_float"))
                .filter(column -> !column.getName().equals("t_map_double"))
                // null map keys are not supported
                .filter(column -> !column.getName().equals("t_map_null_key"))
                .filter(column -> !column.getName().equals("t_map_null_key_complex_key_value"))
                .filter(column -> !column.getName().equals("t_map_null_key_complex_value"))
                // decimal(38) is broken or not supported
                .filter(column -> !column.getName().equals("t_decimal_precision_38"))
                .filter(column -> !column.getName().equals("t_map_decimal_precision_38"))
                .filter(column -> !column.getName().equals("t_array_decimal_precision_38"))
                .collect(toList());

        assertThatFileFormat(JSON)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));
    }

    @Test(dataProvider = "rowCount")
    public void testRCText(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = ImmutableList.copyOf(filter(TEST_COLUMNS, testColumn -> {
            // TODO: This is a bug in the RC text reader
            // RC file does not support complex type as key of a map
            return !testColumn.getName().equals("t_struct_null")
                    && !testColumn.getName().equals("t_map_null_key_complex_key_value");
        }));
        assertThatFileFormat(RCTEXT)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));
    }

    @Test(dataProvider = "rowCount")
    public void testRcTextPageSource(int rowCount)
            throws Exception
    {
        assertThatFileFormat(RCTEXT)
                .withColumns(TEST_COLUMNS)
                .withRowsCount(rowCount)
                .isReadableByPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testRcTextOptimizedWriter(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                // t_map_null_key_* must be disabled because Presto can not produce maps with null keys so the writer will throw
                .filter(TestHiveFileFormats::withoutNullMapKeyTests)
                .collect(toImmutableList());

        TestingConnectorSession session = new TestingConnectorSession(
                new HiveSessionProperties(new HiveClientConfig().setRcfileOptimizedWriterEnabled(true), new OrcFileWriterConfig(), new ParquetFileWriterConfig()).getSessionProperties());

        assertThatFileFormat(RCTEXT)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .withSession(session)
                .withFileWriterFactory(new RcFileFileWriterFactory(HDFS_ENVIRONMENT, TYPE_MANAGER, new NodeVersion("test"), HIVE_STORAGE_TIME_ZONE, STATS))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT))
                .isReadableByPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testRCBinary(int rowCount)
            throws Exception
    {
        // RCBinary does not support complex type as key of a map and interprets empty VARCHAR as nulls
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                .filter(testColumn -> {
                    String name = testColumn.getName();
                    return !name.equals("t_map_null_key_complex_key_value") && !name.equals("t_empty_varchar");
                }).collect(toList());
        assertThatFileFormat(RCBINARY)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));
    }

    @Test(dataProvider = "rowCount")
    public void testRcBinaryPageSource(int rowCount)
            throws Exception
    {
        // RCBinary does not support complex type as key of a map and interprets empty VARCHAR as nulls
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                .filter(testColumn -> !testColumn.getName().equals("t_empty_varchar"))
                .collect(toList());

        assertThatFileFormat(RCBINARY)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .isReadableByPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testRcBinaryOptimizedWriter(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                // RCBinary interprets empty VARCHAR as nulls
                .filter(testColumn -> !testColumn.getName().equals("t_empty_varchar"))
                // t_map_null_key_* must be disabled because Presto can not produce maps with null keys so the writer will throw
                .filter(TestHiveFileFormats::withoutNullMapKeyTests)
                .collect(toList());

        TestingConnectorSession session = new TestingConnectorSession(
                new HiveSessionProperties(new HiveClientConfig().setRcfileOptimizedWriterEnabled(true), new OrcFileWriterConfig(), new ParquetFileWriterConfig()).getSessionProperties());

        assertThatFileFormat(RCBINARY)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .withSession(session)
                .withFileWriterFactory(new RcFileFileWriterFactory(HDFS_ENVIRONMENT, TYPE_MANAGER, new NodeVersion("test"), HIVE_STORAGE_TIME_ZONE, STATS))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT))
                .isReadableByPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testOrc(int rowCount)
            throws Exception
    {
        assertThatFileFormat(ORC)
                .withColumns(TEST_COLUMNS)
                .withRowsCount(rowCount)
                .isReadableByPageSource(new OrcBatchPageSourceFactory(TYPE_MANAGER, false, HDFS_ENVIRONMENT, STATS, 100, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testOrcOptimizedWriter(int rowCount)
            throws Exception
    {
        TestingConnectorSession session = new TestingConnectorSession(
                new HiveSessionProperties(
                        new HiveClientConfig()
                                .setOrcOptimizedWriterEnabled(true)
                                .setOrcWriterValidationPercentage(100.0),
                        new OrcFileWriterConfig(),
                        new ParquetFileWriterConfig()).getSessionProperties());

        // A Presto page can not contain a map with null keys, so a page based writer can not write null keys
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                .filter(testColumn -> !testColumn.getName().equals("t_map_null_key") && !testColumn.getName().equals("t_map_null_key_complex_value") && !testColumn.getName().equals("t_map_null_key_complex_key_value"))
                .collect(toList());

        assertThatFileFormat(ORC)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .withSession(session)
                .withFileWriterFactory(new OrcFileWriterFactory(HDFS_ENVIRONMENT, TYPE_MANAGER, new NodeVersion("test"), HIVE_STORAGE_TIME_ZONE, STATS, new OrcWriterOptions()))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT))
                .isReadableByPageSource(new OrcBatchPageSourceFactory(TYPE_MANAGER, false, HDFS_ENVIRONMENT, STATS, 100, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testOrcUseColumnNames(int rowCount)
            throws Exception
    {
        TestingConnectorSession session = new TestingConnectorSession(new HiveSessionProperties(new HiveClientConfig(), new OrcFileWriterConfig(), new ParquetFileWriterConfig()).getSessionProperties());

        assertThatFileFormat(ORC)
                .withWriteColumns(TEST_COLUMNS)
                .withRowsCount(rowCount)
                .withReadColumns(Lists.reverse(TEST_COLUMNS))
                .withSession(session)
                .isReadableByPageSource(new OrcBatchPageSourceFactory(TYPE_MANAGER, true, HDFS_ENVIRONMENT, STATS, 100, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testAvro(int rowCount)
            throws Exception
    {
        assertThatFileFormat(AVRO)
                .withColumns(getTestColumnsSupportedByAvro())
                .withRowsCount(rowCount)
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));
    }

    private static List<TestColumn> getTestColumnsSupportedByAvro()
    {
        // Avro only supports String for Map keys, and doesn't support smallint or tinyint.
        return TEST_COLUMNS.stream()
                .filter(column -> !column.getName().startsWith("t_map_") || column.getName().equals("t_map_string"))
                .filter(column -> !column.getName().endsWith("_smallint"))
                .filter(column -> !column.getName().endsWith("_tinyint"))
                .collect(toList());
    }

    @Test(dataProvider = "rowCount")
    public void testParquetPageSource(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = getTestColumnsSupportedByParquet();
        assertThatFileFormat(PARQUET)
                .withColumns(testColumns)
                .withSession(parquetPageSourceSession)
                .withRowsCount(rowCount)
                .isReadableByPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testParquetPageSourceSchemaEvolution(int rowCount)
            throws Exception
    {
        List<TestColumn> writeColumns = getTestColumnsSupportedByParquet();

        // test index-based access
        List<TestColumn> readColumns = writeColumns.stream()
                .map(column -> new TestColumn(
                        column.getName() + "_new",
                        column.getObjectInspector(),
                        column.getWriteValue(),
                        column.getExpectedValue(),
                        column.isPartitionKey()))
                .collect(toList());
        assertThatFileFormat(PARQUET)
                .withWriteColumns(writeColumns)
                .withReadColumns(readColumns)
                .withSession(parquetPageSourceSession)
                .withRowsCount(rowCount)
                .isReadableByPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));

        // test name-based access
        readColumns = Lists.reverse(writeColumns);
        assertThatFileFormat(PARQUET)
                .withWriteColumns(writeColumns)
                .withReadColumns(readColumns)
                .withSession(parquetPageSourceSessionUseName)
                .isReadableByPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));
    }

    private static List<TestColumn> getTestColumnsSupportedByParquet()
    {
        // Write of complex hive data to Parquet is broken
        // TODO: empty arrays or maps with null keys don't seem to work
        // Parquet does not support DATE
        return TEST_COLUMNS.stream()
                .filter(column -> !ImmutableSet.of("t_null_array_int", "t_array_empty", "t_map_null_key", "t_map_null_key_complex_value", "t_map_null_key_complex_key_value")
                        .contains(column.getName()))
                .filter(column -> column.isPartitionKey() || (
                        !hasType(column.getObjectInspector(), PrimitiveCategory.DATE)) &&
                        !hasType(column.getObjectInspector(), PrimitiveCategory.SHORT) &&
                        !hasType(column.getObjectInspector(), PrimitiveCategory.BYTE))
                .collect(toList());
    }

    @Test(dataProvider = "rowCount")
    public void testDwrf(int rowCount)
            throws Exception
    {
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                .filter(testColumn -> !hasType(testColumn.getObjectInspector(), PrimitiveCategory.DATE, PrimitiveCategory.VARCHAR, PrimitiveCategory.CHAR, PrimitiveCategory.DECIMAL))
                .collect(Collectors.toList());
        assertThatFileFormat(DWRF)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .isReadableByPageSource(new DwrfBatchPageSourceFactory(TYPE_MANAGER, HIVE_CLIENT_CONFIG, HDFS_ENVIRONMENT, STATS, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()));
    }

    @Test(dataProvider = "rowCount")
    public void testDwrfOptimizedWriter(int rowCount)
            throws Exception
    {
        TestingConnectorSession session = new TestingConnectorSession(
                new HiveSessionProperties(
                        new HiveClientConfig()
                                .setOrcOptimizedWriterEnabled(true)
                                .setOrcWriterValidationPercentage(100.0),
                        new OrcFileWriterConfig(),
                        new ParquetFileWriterConfig()).getSessionProperties());

        // DWRF does not support modern Hive types
        // A Presto page can not contain a map with null keys, so a page based writer can not write null keys
        List<TestColumn> testColumns = TEST_COLUMNS.stream()
                .filter(testColumn -> !hasType(testColumn.getObjectInspector(), PrimitiveCategory.DATE, PrimitiveCategory.VARCHAR, PrimitiveCategory.CHAR, PrimitiveCategory.DECIMAL))
                .filter(testColumn -> !testColumn.getName().equals("t_map_null_key") && !testColumn.getName().equals("t_map_null_key_complex_value") && !testColumn.getName().equals("t_map_null_key_complex_key_value"))
                .collect(toList());

        assertThatFileFormat(DWRF)
                .withColumns(testColumns)
                .withRowsCount(rowCount)
                .withSession(session)
                .withFileWriterFactory(new OrcFileWriterFactory(HDFS_ENVIRONMENT, TYPE_MANAGER, new NodeVersion("test"), HIVE_STORAGE_TIME_ZONE, STATS, new OrcWriterOptions()))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT))
                .isReadableByPageSource(new DwrfBatchPageSourceFactory(TYPE_MANAGER, HIVE_CLIENT_CONFIG, HDFS_ENVIRONMENT, STATS, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()));
    }

    @Test
    public void testTruncateVarcharColumn()
            throws Exception
    {
        TestColumn writeColumn = new TestColumn("varchar_column", getPrimitiveJavaObjectInspector(new VarcharTypeInfo(4)), new HiveVarchar("test", 4), utf8Slice("test"));
        TestColumn readColumn = new TestColumn("varchar_column", getPrimitiveJavaObjectInspector(new VarcharTypeInfo(3)), new HiveVarchar("tes", 3), utf8Slice("tes"));

        assertThatFileFormat(RCTEXT)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .isReadableByPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));

        assertThatFileFormat(RCBINARY)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .isReadableByPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));

        assertThatFileFormat(ORC)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .isReadableByPageSource(new OrcBatchPageSourceFactory(TYPE_MANAGER, false, HDFS_ENVIRONMENT, STATS, 100, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()));

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .withSession(parquetPageSourceSession)
                .isReadableByPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()));

        assertThatFileFormat(AVRO)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));

        assertThatFileFormat(SEQUENCEFILE)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));

        assertThatFileFormat(TEXTFILE)
                .withWriteColumns(ImmutableList.of(writeColumn))
                .withReadColumns(ImmutableList.of(readColumn))
                .isReadableByRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT));
    }

    @Test
    public void testFailForLongVarcharPartitionColumn()
            throws Exception
    {
        TestColumn partitionColumn = new TestColumn("partition_column", getPrimitiveJavaObjectInspector(new VarcharTypeInfo(3)), "test", utf8Slice("tes"), true);
        TestColumn varcharColumn = new TestColumn("varchar_column", getPrimitiveJavaObjectInspector(new VarcharTypeInfo(3)), new HiveVarchar("tes", 3), utf8Slice("tes"));

        List<TestColumn> columns = ImmutableList.of(partitionColumn, varcharColumn);

        HiveErrorCode expectedErrorCode = HIVE_INVALID_PARTITION_VALUE;
        String expectedMessage = "Invalid partition value 'test' for varchar(3) partition key: partition_column";

        assertThatFileFormat(RCTEXT)
                .withColumns(columns)
                .isFailingForPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessage)
                .isFailingForRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT), expectedErrorCode, expectedMessage);

        assertThatFileFormat(RCBINARY)
                .withColumns(columns)
                .isFailingForPageSource(new RcFilePageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessage)
                .isFailingForRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT), expectedErrorCode, expectedMessage);

        assertThatFileFormat(ORC)
                .withColumns(columns)
                .isFailingForPageSource(new OrcBatchPageSourceFactory(TYPE_MANAGER, false, HDFS_ENVIRONMENT, STATS, 100, new StorageOrcFileTailSource(), new StorageStripeMetadataSource(), new HadoopFileOpener()), expectedErrorCode, expectedMessage);

        assertThatFileFormat(PARQUET)
                .withColumns(columns)
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessage);

        assertThatFileFormat(SEQUENCEFILE)
                .withColumns(columns)
                .isFailingForRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT), expectedErrorCode, expectedMessage);

        assertThatFileFormat(TEXTFILE)
                .withColumns(columns)
                .isFailingForRecordCursor(new GenericHiveRecordCursorProvider(HDFS_ENVIRONMENT), expectedErrorCode, expectedMessage);
    }

    @Test
    public void testSchemaMismatch()
            throws Exception
    {
        TestColumn floatColumn = new TestColumn("column_name", javaFloatObjectInspector, 5.1f, 5.1f);
        TestColumn doubleColumn = new TestColumn("column_name", javaDoubleObjectInspector, 5.1, 5.1);
        TestColumn booleanColumn = new TestColumn("column_name", javaBooleanObjectInspector, true, true);
        TestColumn stringColumn = new TestColumn("column_name", javaStringObjectInspector, "test", utf8Slice("test"));
        TestColumn intColumn = new TestColumn("column_name", javaIntObjectInspector, 3, 3);
        TestColumn longColumn = new TestColumn("column_name", javaLongObjectInspector, 4L, 4L);
        TestColumn mapLongColumn = new TestColumn("column_name",
                getStandardMapObjectInspector(javaLongObjectInspector, javaLongObjectInspector),
                ImmutableMap.of(4L, 4L),
                mapBlockOf(BIGINT, BIGINT, 4L, 4L));
        TestColumn mapDoubleColumn = new TestColumn("column_name",
                getStandardMapObjectInspector(javaDoubleObjectInspector, javaDoubleObjectInspector),
                ImmutableMap.of(5.1, 5.2),
                mapBlockOf(DOUBLE, DOUBLE, 5.1, 5.2));
        TestColumn arrayStringColumn = new TestColumn("column_name",
                getStandardListObjectInspector(javaStringObjectInspector),
                ImmutableList.of("test"),
                arrayBlockOf(createUnboundedVarcharType(), "test"));
        TestColumn arrayBooleanColumn = new TestColumn("column_name",
                getStandardListObjectInspector(javaBooleanObjectInspector),
                ImmutableList.of(true),
                arrayBlockOf(BOOLEAN, true));
        TestColumn rowLongColumn = new TestColumn("column_name",
                getStandardStructObjectInspector(ImmutableList.of("s_bigint"), ImmutableList.of(javaLongObjectInspector)),
                new Long[] {1L},
                rowBlockOf(ImmutableList.of(BIGINT), 1));
        TestColumn nestColumn = new TestColumn("column_name",
                getStandardMapObjectInspector(
                    javaStringObjectInspector,
                    getStandardListObjectInspector(
                        getStandardStructObjectInspector(
                            ImmutableList.of("s_int"),
                            ImmutableList.of(javaIntObjectInspector)))),
                ImmutableMap.of("test", ImmutableList.<Object>of(new Integer[] {1})),
                mapBlockOf(createUnboundedVarcharType(), new ArrayType(RowType.anonymous(ImmutableList.of(INTEGER))),
                    "test", arrayBlockOf(RowType.anonymous(ImmutableList.of(INTEGER)), rowBlockOf(ImmutableList.of(INTEGER), 1L))));

        HiveErrorCode expectedErrorCode = HIVE_PARTITION_SCHEMA_MISMATCH;
        String expectedMessageFloatDouble = "The column column_name is declared as type double, but the Parquet file declares the column as type FLOAT";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(floatColumn))
                .withReadColumns(ImmutableList.of(doubleColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageFloatDouble);

        String expectedMessageDoubleLong = "The column column_name is declared as type bigint, but the Parquet file declares the column as type DOUBLE";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(doubleColumn))
                .withReadColumns(ImmutableList.of(longColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageDoubleLong);

        String expectedMessageFloatInt = "The column column_name is declared as type int, but the Parquet file declares the column as type FLOAT";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(floatColumn))
                .withReadColumns(ImmutableList.of(intColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageFloatInt);

        String expectedMessageIntBoolean = "The column column_name is declared as type boolean, but the Parquet file declares the column as type INT32";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(intColumn))
                .withReadColumns(ImmutableList.of(booleanColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageIntBoolean);

        String expectedMessageStringLong = "The column column_name is declared as type string, but the Parquet file declares the column as type INT64";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(longColumn))
                .withReadColumns(ImmutableList.of(stringColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageStringLong);

        String expectedMessageIntString = "The column column_name is declared as type int, but the Parquet file declares the column as type BINARY";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(stringColumn))
                .withReadColumns(ImmutableList.of(intColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageIntString);

        String expectedMessageMapLongLong = "The column column_name is declared as type map<bigint,bigint>, but the Parquet file declares the column as type INT64";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(longColumn))
                .withReadColumns(ImmutableList.of(mapLongColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageMapLongLong);

        String expectedMessageMapLongMapDouble = "The column column_name is declared as type map<bigint,bigint>, but the Parquet file declares the column as type optional group column_name (MAP) {\n"
                + "  repeated group map (MAP_KEY_VALUE) {\n"
                + "    required double key;\n"
                + "    optional double value;\n"
                + "  }\n"
                + "}";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(mapDoubleColumn))
                .withReadColumns(ImmutableList.of(mapLongColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageMapLongMapDouble);

        String expectedMessageArrayStringArrayBoolean = "The column column_name is declared as type array<string>, but the Parquet file declares the column as type optional group column_name (LIST) {\n"
                + "  repeated group bag {\n"
                + "    optional boolean array_element;\n"
                + "  }\n"
                + "}";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(arrayBooleanColumn))
                .withReadColumns(ImmutableList.of(arrayStringColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageArrayStringArrayBoolean);

        String expectedMessageBooleanArrayBoolean = "The column column_name is declared as type array<boolean>, but the Parquet file declares the column as type BOOLEAN";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(booleanColumn))
                .withReadColumns(ImmutableList.of(arrayBooleanColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageBooleanArrayBoolean);

        String expectedMessageRowLongLong = "The column column_name is declared as type bigint, but the Parquet file declares the column as type optional group column_name {\n"
                + "  optional int64 s_bigint;\n"
                + "}";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(rowLongColumn))
                .withReadColumns(ImmutableList.of(longColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageRowLongLong);

        String expectedMessageMapLongRowLong = "The column column_name is declared as type struct<s_bigint:bigint>, but the Parquet file declares the column as type optional group column_name (MAP) {\n"
                + "  repeated group map (MAP_KEY_VALUE) {\n"
                + "    required int64 key;\n"
                + "    optional int64 value;\n"
                + "  }\n"
                + "}";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(mapLongColumn))
                .withReadColumns(ImmutableList.of(rowLongColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageMapLongRowLong);

        String expectedMessageRowLongNest = "The column column_name is declared as type map<string,array<struct<s_int:int>>>, but the Parquet file declares the column as type optional group column_name {\n"
                + "  optional int64 s_bigint;\n"
                + "}";

        assertThatFileFormat(PARQUET)
                .withWriteColumns(ImmutableList.of(rowLongColumn))
                .withReadColumns(ImmutableList.of(nestColumn))
                .withSession(parquetPageSourceSession)
                .isFailingForPageSource(new ParquetPageSourceFactory(TYPE_MANAGER, HDFS_ENVIRONMENT, STATS, new HadoopFileOpener()), expectedErrorCode, expectedMessageRowLongNest);
    }

    private void testCursorProvider(HiveRecordCursorProvider cursorProvider,
            FileSplit split,
            HiveStorageFormat storageFormat,
            List<TestColumn> testColumns,
            ConnectorSession session,
            int rowCount)
    {
        List<HivePartitionKey> partitionKeys = testColumns.stream()
                .filter(TestColumn::isPartitionKey)
                .map(input -> new HivePartitionKey(input.getName(), (String) input.getWriteValue()))
                .collect(toList());

        List<HiveColumnHandle> partitionKeyColumnHandles = getColumnHandles(testColumns.stream().filter(TestColumn::isPartitionKey).collect(toImmutableList()));
        List<Column> tableDataColumns = testColumns.stream()
                .filter(column -> !column.isPartitionKey())
                .map(column -> new Column(column.getName(), HiveType.valueOf(column.getType()), Optional.empty()))
                .collect(toImmutableList());

        Configuration configuration = new Configuration();
        configuration.set("io.compression.codecs", LzoCodec.class.getName() + "," + LzopCodec.class.getName());
        Optional<ConnectorPageSource> pageSource = HivePageSourceProvider.createHivePageSource(
                ImmutableSet.of(cursorProvider),
                ImmutableSet.of(),
                configuration,
                session,
                split.getPath(),
                OptionalInt.empty(),
                split.getStart(),
                split.getLength(),
                split.getLength(),
                new Storage(
                        StorageFormat.create(storageFormat.getSerDe(), storageFormat.getInputFormat(), storageFormat.getOutputFormat()),
                        "location",
                        Optional.empty(),
                        false,
                        ImmutableMap.of()),
                TupleDomain.all(),
                getColumnHandles(testColumns),
                partitionKeys,
                DateTimeZone.getDefault(),
                TYPE_MANAGER,
                new SchemaTableName("schema", "table"),
                partitionKeyColumnHandles,
                tableDataColumns,
                ImmutableMap.of(),
                tableDataColumns.size(),
                ImmutableMap.of(),
                Optional.empty(),
                false,
                Optional.empty());

        RecordCursor cursor = ((RecordPageSource) pageSource.get()).getCursor();

        checkCursor(cursor, testColumns, rowCount);
    }

    private void testPageSourceFactory(HiveBatchPageSourceFactory sourceFactory,
            FileSplit split,
            HiveStorageFormat storageFormat,
            List<TestColumn> testColumns,
            ConnectorSession session,
            int rowCount)
            throws IOException
    {
        List<HivePartitionKey> partitionKeys = testColumns.stream()
                .filter(TestColumn::isPartitionKey)
                .map(input -> new HivePartitionKey(input.getName(), (String) input.getWriteValue()))
                .collect(toList());

        List<HiveColumnHandle> partitionKeyColumnHandles = getColumnHandles(testColumns.stream().filter(TestColumn::isPartitionKey).collect(toImmutableList()));
        List<Column> tableDataColumns = testColumns.stream()
                .filter(column -> !column.isPartitionKey())
                .map(column -> new Column(column.getName(), HiveType.valueOf(column.getType()), Optional.empty()))
                .collect(toImmutableList());

        List<HiveColumnHandle> columnHandles = getColumnHandles(testColumns);

        Optional<ConnectorPageSource> pageSource = HivePageSourceProvider.createHivePageSource(
                ImmutableSet.of(),
                ImmutableSet.of(sourceFactory),
                new Configuration(),
                session,
                split.getPath(),
                OptionalInt.empty(),
                split.getStart(),
                split.getLength(),
                split.getLength(),
                new Storage(
                        StorageFormat.create(storageFormat.getSerDe(), storageFormat.getInputFormat(), storageFormat.getOutputFormat()),
                        "location",
                        Optional.empty(),
                        false,
                        ImmutableMap.of()),
                TupleDomain.all(),
                columnHandles,
                partitionKeys,
                DateTimeZone.getDefault(),
                TYPE_MANAGER,
                new SchemaTableName("schema", "table"),
                partitionKeyColumnHandles,
                tableDataColumns,
                ImmutableMap.of(),
                tableDataColumns.size(),
                ImmutableMap.of(),
                Optional.empty(),
                false,
                Optional.empty());

        assertTrue(pageSource.isPresent());

        checkPageSource(pageSource.get(), testColumns, getTypes(columnHandles), rowCount);
    }

    public static boolean hasType(ObjectInspector objectInspector, PrimitiveCategory... types)
    {
        if (objectInspector instanceof PrimitiveObjectInspector) {
            PrimitiveObjectInspector primitiveInspector = (PrimitiveObjectInspector) objectInspector;
            PrimitiveCategory primitiveCategory = primitiveInspector.getPrimitiveCategory();
            for (PrimitiveCategory type : types) {
                if (primitiveCategory == type) {
                    return true;
                }
            }
            return false;
        }
        if (objectInspector instanceof ListObjectInspector) {
            ListObjectInspector listInspector = (ListObjectInspector) objectInspector;
            return hasType(listInspector.getListElementObjectInspector(), types);
        }
        if (objectInspector instanceof MapObjectInspector) {
            MapObjectInspector mapInspector = (MapObjectInspector) objectInspector;
            return hasType(mapInspector.getMapKeyObjectInspector(), types) ||
                    hasType(mapInspector.getMapValueObjectInspector(), types);
        }
        if (objectInspector instanceof StructObjectInspector) {
            for (StructField field : ((StructObjectInspector) objectInspector).getAllStructFieldRefs()) {
                if (hasType(field.getFieldObjectInspector(), types)) {
                    return true;
                }
            }
            return false;
        }
        throw new IllegalArgumentException("Unknown object inspector type " + objectInspector);
    }

    private static boolean withoutNullMapKeyTests(TestColumn testColumn)
    {
        String name = testColumn.getName();
        return !name.equals("t_map_null_key") &&
                !name.equals("t_map_null_key_complex_key_value") &&
                !name.equals("t_map_null_key_complex_value");
    }

    private FileFormatAssertion assertThatFileFormat(HiveStorageFormat hiveStorageFormat)
    {
        return new FileFormatAssertion(hiveStorageFormat.name())
                .withStorageFormat(hiveStorageFormat);
    }

    private static HiveClientConfig createParquetHiveClientConfig(boolean useParquetColumnNames)
    {
        HiveClientConfig config = new HiveClientConfig();
        config.setUseParquetColumnNames(useParquetColumnNames);
        return config;
    }

    private class FileFormatAssertion
    {
        private final String formatName;
        private HiveStorageFormat storageFormat;
        private HiveCompressionCodec compressionCodec = HiveCompressionCodec.NONE;
        private List<TestColumn> writeColumns;
        private List<TestColumn> readColumns;
        private ConnectorSession session = SESSION;
        private int rowsCount = 1000;
        private HiveFileWriterFactory fileWriterFactory;

        private FileFormatAssertion(String formatName)
        {
            this.formatName = requireNonNull(formatName, "formatName is null");
        }

        public FileFormatAssertion withStorageFormat(HiveStorageFormat storageFormat)
        {
            this.storageFormat = requireNonNull(storageFormat, "storageFormat is null");
            return this;
        }

        public FileFormatAssertion withCompressionCodec(HiveCompressionCodec compressionCodec)
        {
            this.compressionCodec = requireNonNull(compressionCodec, "compressionCodec is null");
            return this;
        }

        public FileFormatAssertion withFileWriterFactory(HiveFileWriterFactory fileWriterFactory)
        {
            this.fileWriterFactory = requireNonNull(fileWriterFactory, "fileWriterFactory is null");
            return this;
        }

        public FileFormatAssertion withColumns(List<TestColumn> inputColumns)
        {
            withWriteColumns(inputColumns);
            withReadColumns(inputColumns);
            return this;
        }

        public FileFormatAssertion withWriteColumns(List<TestColumn> writeColumns)
        {
            this.writeColumns = requireNonNull(writeColumns, "writeColumns is null");
            return this;
        }

        public FileFormatAssertion withReadColumns(List<TestColumn> readColumns)
        {
            this.readColumns = requireNonNull(readColumns, "readColumns is null");
            return this;
        }

        public FileFormatAssertion withRowsCount(int rowsCount)
        {
            this.rowsCount = rowsCount;
            return this;
        }

        public FileFormatAssertion withSession(ConnectorSession session)
        {
            this.session = requireNonNull(session, "session is null");
            return this;
        }

        public FileFormatAssertion isReadableByPageSource(HiveBatchPageSourceFactory pageSourceFactory)
                throws Exception
        {
            assertRead(Optional.of(pageSourceFactory), Optional.empty());
            return this;
        }

        public FileFormatAssertion isReadableByRecordCursor(HiveRecordCursorProvider cursorProvider)
                throws Exception
        {
            assertRead(Optional.empty(), Optional.of(cursorProvider));
            return this;
        }

        public FileFormatAssertion isFailingForPageSource(HiveBatchPageSourceFactory pageSourceFactory, HiveErrorCode expectedErrorCode, String expectedMessage)
                throws Exception
        {
            assertFailure(Optional.of(pageSourceFactory), Optional.empty(), expectedErrorCode, expectedMessage);
            return this;
        }

        public FileFormatAssertion isFailingForRecordCursor(HiveRecordCursorProvider cursorProvider, HiveErrorCode expectedErrorCode, String expectedMessage)
                throws Exception
        {
            assertFailure(Optional.empty(), Optional.of(cursorProvider), expectedErrorCode, expectedMessage);
            return this;
        }

        private void assertRead(Optional<HiveBatchPageSourceFactory> pageSourceFactory, Optional<HiveRecordCursorProvider> cursorProvider)
                throws Exception
        {
            assertNotNull(storageFormat, "storageFormat must be specified");
            assertNotNull(writeColumns, "writeColumns must be specified");
            assertNotNull(readColumns, "readColumns must be specified");
            assertNotNull(session, "session must be specified");
            assertTrue(rowsCount >= 0, "rowsCount must be greater than zero");

            String compressionSuffix = compressionCodec.getCodec()
                    .map(codec -> {
                        try {
                            return codec.getConstructor().newInstance().getDefaultExtension();
                        }
                        catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .orElse("");

            File file = File.createTempFile("presto_test", formatName + compressionSuffix);
            file.delete();
            try {
                FileSplit split;
                if (fileWriterFactory != null) {
                    split = createTestFile(file.getAbsolutePath(), storageFormat, compressionCodec, writeColumns, session, rowsCount, fileWriterFactory);
                }
                else {
                    split = createTestFile(file.getAbsolutePath(), storageFormat, compressionCodec, writeColumns, rowsCount);
                }
                if (pageSourceFactory.isPresent()) {
                    testPageSourceFactory(pageSourceFactory.get(), split, storageFormat, readColumns, session, rowsCount);
                }
                if (cursorProvider.isPresent()) {
                    testCursorProvider(cursorProvider.get(), split, storageFormat, readColumns, session, rowsCount);
                }
            }
            finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        private void assertFailure(
                Optional<HiveBatchPageSourceFactory> pageSourceFactory,
                Optional<HiveRecordCursorProvider> cursorProvider,
                HiveErrorCode expectedErrorCode,
                String expectedMessage)
                throws Exception
        {
            try {
                assertRead(pageSourceFactory, cursorProvider);
                fail("failure is expected");
            }
            catch (PrestoException prestoException) {
                assertEquals(prestoException.getErrorCode(), expectedErrorCode.toErrorCode());
                assertEquals(prestoException.getMessage(), expectedMessage);
            }
        }
    }
}
