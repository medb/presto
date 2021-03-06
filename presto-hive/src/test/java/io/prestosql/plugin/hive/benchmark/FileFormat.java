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
package io.prestosql.plugin.hive.benchmark;

import com.google.common.collect.ImmutableMap;
import io.airlift.slice.OutputStreamSliceOutput;
import io.prestosql.orc.OrcReaderOptions;
import io.prestosql.orc.OrcWriter;
import io.prestosql.orc.OrcWriterOptions;
import io.prestosql.orc.OrcWriterStats;
import io.prestosql.orc.OutputStreamOrcDataSink;
import io.prestosql.orc.metadata.OrcType;
import io.prestosql.parquet.writer.ParquetSchemaConverter;
import io.prestosql.parquet.writer.ParquetWriter;
import io.prestosql.parquet.writer.ParquetWriterOptions;
import io.prestosql.plugin.hive.FileFormatDataSourceStats;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HiveColumnHandle;
import io.prestosql.plugin.hive.HiveCompressionCodec;
import io.prestosql.plugin.hive.HiveConfig;
import io.prestosql.plugin.hive.HivePageSourceFactory;
import io.prestosql.plugin.hive.HivePageSourceFactory.ReaderPageSourceWithProjections;
import io.prestosql.plugin.hive.HiveRecordCursorProvider;
import io.prestosql.plugin.hive.HiveRecordCursorProvider.ReaderRecordCursorWithProjections;
import io.prestosql.plugin.hive.HiveStorageFormat;
import io.prestosql.plugin.hive.HiveType;
import io.prestosql.plugin.hive.HiveTypeName;
import io.prestosql.plugin.hive.RecordFileWriter;
import io.prestosql.plugin.hive.benchmark.BenchmarkHiveFileFormat.TestData;
import io.prestosql.plugin.hive.orc.OrcPageSourceFactory;
import io.prestosql.plugin.hive.parquet.ParquetPageSourceFactory;
import io.prestosql.plugin.hive.parquet.ParquetReaderConfig;
import io.prestosql.plugin.hive.rcfile.RcFilePageSourceFactory;
import io.prestosql.rcfile.AircompressorCodecFactory;
import io.prestosql.rcfile.HadoopCodecFactory;
import io.prestosql.rcfile.RcFileEncoding;
import io.prestosql.rcfile.RcFileWriter;
import io.prestosql.rcfile.binary.BinaryRcFileEncoding;
import io.prestosql.rcfile.text.TextRcFileEncoding;
import io.prestosql.spi.Page;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.RecordPageSource;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.type.Type;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.orc.OrcWriteValidation.OrcWriteValidationMode.BOTH;
import static io.prestosql.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.prestosql.plugin.hive.HiveColumnHandle.createBaseColumn;
import static io.prestosql.plugin.hive.HiveTestUtils.TYPE_MANAGER;
import static io.prestosql.plugin.hive.HiveTestUtils.createGenericHiveRecordCursorProvider;
import static io.prestosql.plugin.hive.HiveType.toHiveType;
import static io.prestosql.plugin.hive.metastore.StorageFormat.fromHiveStorageFormat;
import static io.prestosql.plugin.hive.util.CompressionConfigUtil.configureCompression;
import static java.lang.String.join;
import static java.util.stream.Collectors.joining;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.FILE_INPUT_FORMAT;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMNS;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.META_TABLE_COLUMN_TYPES;
import static org.apache.hadoop.hive.serde.serdeConstants.SERIALIZATION_LIB;
import static org.joda.time.DateTimeZone.UTC;

public enum FileFormat
{
    PRESTO_RCBINARY {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HivePageSourceFactory pageSourceFactory = new RcFilePageSourceFactory(TYPE_MANAGER, hdfsEnvironment, new FileFormatDataSourceStats(), new HiveConfig().setRcfileTimeZone("UTC"));
            return createPageSource(pageSourceFactory, session, targetFile, columnNames, columnTypes, HiveStorageFormat.RCBINARY);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
                throws IOException
        {
            return new PrestoRcFileFormatWriter(
                    targetFile,
                    columnTypes,
                    new BinaryRcFileEncoding(UTC),
                    compressionCodec);
        }
    },

    PRESTO_RCTEXT {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HivePageSourceFactory pageSourceFactory = new RcFilePageSourceFactory(TYPE_MANAGER, hdfsEnvironment, new FileFormatDataSourceStats(), new HiveConfig().setRcfileTimeZone("UTC"));
            return createPageSource(pageSourceFactory, session, targetFile, columnNames, columnTypes, HiveStorageFormat.RCTEXT);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
                throws IOException
        {
            return new PrestoRcFileFormatWriter(
                    targetFile,
                    columnTypes,
                    new TextRcFileEncoding(),
                    compressionCodec);
        }
    },

    PRESTO_ORC {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HivePageSourceFactory pageSourceFactory = new OrcPageSourceFactory(new OrcReaderOptions(), hdfsEnvironment, new FileFormatDataSourceStats(), UTC);
            return createPageSource(pageSourceFactory, session, targetFile, columnNames, columnTypes, HiveStorageFormat.ORC);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
                throws IOException
        {
            return new PrestoOrcFormatWriter(
                    targetFile,
                    columnNames,
                    columnTypes,
                    compressionCodec);
        }
    },

    PRESTO_PARQUET {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HivePageSourceFactory pageSourceFactory = new ParquetPageSourceFactory(hdfsEnvironment, new FileFormatDataSourceStats(), new ParquetReaderConfig(), new HiveConfig().setParquetTimeZone("UTC"));
            return createPageSource(pageSourceFactory, session, targetFile, columnNames, columnTypes, HiveStorageFormat.PARQUET);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
                throws IOException
        {
            return new PrestoParquetFormatWriter(targetFile, columnNames, columnTypes, compressionCodec);
        }
    },

    HIVE_RCBINARY {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HiveRecordCursorProvider cursorProvider = createGenericHiveRecordCursorProvider(hdfsEnvironment);
            return createPageSource(cursorProvider, session, targetFile, columnNames, columnTypes, HiveStorageFormat.RCBINARY);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
        {
            return new RecordFormatWriter(targetFile, columnNames, columnTypes, compressionCodec, HiveStorageFormat.RCBINARY, session);
        }
    },

    HIVE_RCTEXT {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HiveRecordCursorProvider cursorProvider = createGenericHiveRecordCursorProvider(hdfsEnvironment);
            return createPageSource(cursorProvider, session, targetFile, columnNames, columnTypes, HiveStorageFormat.RCTEXT);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
        {
            return new RecordFormatWriter(targetFile, columnNames, columnTypes, compressionCodec, HiveStorageFormat.RCTEXT, session);
        }
    },

    HIVE_ORC {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HiveRecordCursorProvider cursorProvider = createGenericHiveRecordCursorProvider(hdfsEnvironment);
            return createPageSource(cursorProvider, session, targetFile, columnNames, columnTypes, HiveStorageFormat.ORC);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
        {
            return new RecordFormatWriter(targetFile, columnNames, columnTypes, compressionCodec, HiveStorageFormat.ORC, session);
        }
    },

    HIVE_PARQUET {
        @Override
        public ConnectorPageSource createFileFormatReader(ConnectorSession session, HdfsEnvironment hdfsEnvironment, File targetFile, List<String> columnNames, List<Type> columnTypes)
        {
            HivePageSourceFactory pageSourceFactory = new ParquetPageSourceFactory(hdfsEnvironment, new FileFormatDataSourceStats(), new ParquetReaderConfig(), new HiveConfig().setParquetTimeZone("UTC"));
            return createPageSource(pageSourceFactory, session, targetFile, columnNames, columnTypes, HiveStorageFormat.PARQUET);
        }

        @Override
        public FormatWriter createFileFormatWriter(
                ConnectorSession session,
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec)
        {
            return new RecordFormatWriter(targetFile, columnNames, columnTypes, compressionCodec, HiveStorageFormat.PARQUET, session);
        }
    };

    public boolean supportsDate()
    {
        return true;
    }

    public abstract ConnectorPageSource createFileFormatReader(
            ConnectorSession session,
            HdfsEnvironment hdfsEnvironment,
            File targetFile,
            List<String> columnNames,
            List<Type> columnTypes);

    public abstract FormatWriter createFileFormatWriter(
            ConnectorSession session,
            File targetFile,
            List<String> columnNames,
            List<Type> columnTypes,
            HiveCompressionCodec compressionCodec)
            throws IOException;

    private static final JobConf conf;

    static {
        conf = new JobConf(new Configuration(false));
        conf.set("fs.file.impl", "org.apache.hadoop.fs.RawLocalFileSystem");
    }

    public boolean supports(TestData testData)
    {
        return true;
    }

    private static ConnectorPageSource createPageSource(
            HiveRecordCursorProvider cursorProvider,
            ConnectorSession session, File targetFile, List<String> columnNames, List<Type> columnTypes, HiveStorageFormat format)
    {
        List<HiveColumnHandle> columnHandles = new ArrayList<>(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            String columnName = columnNames.get(i);
            Type columnType = columnTypes.get(i);
            columnHandles.add(createBaseColumn(columnName, i, toHiveType(columnType), columnType, REGULAR, Optional.empty()));
        }

        Optional<ReaderRecordCursorWithProjections> recordCursorWithProjections = cursorProvider
                .createRecordCursor(
                        conf,
                        session,
                        new Path(targetFile.getAbsolutePath()),
                        0,
                        targetFile.length(),
                        targetFile.length(),
                        createSchema(format, columnNames, columnTypes),
                        columnHandles,
                        TupleDomain.all(),
                        TYPE_MANAGER,
                        false);

        checkState(recordCursorWithProjections.isPresent(), "recordCursorWithProjections is not present");
        checkState(recordCursorWithProjections.get().getProjectedReaderColumns().isEmpty(), "projections should not be required");

        return new RecordPageSource(columnTypes, recordCursorWithProjections.get().getRecordCursor());
    }

    private static ConnectorPageSource createPageSource(
            HivePageSourceFactory pageSourceFactory,
            ConnectorSession session,
            File targetFile,
            List<String> columnNames,
            List<Type> columnTypes,
            HiveStorageFormat format)
    {
        List<HiveColumnHandle> columnHandles = new ArrayList<>(columnNames.size());
        for (int i = 0; i < columnNames.size(); i++) {
            String columnName = columnNames.get(i);
            Type columnType = columnTypes.get(i);
            columnHandles.add(createBaseColumn(columnName, i, toHiveType(columnType), columnType, REGULAR, Optional.empty()));
        }

        Optional<ReaderPageSourceWithProjections> readerPageSourceWithProjections = pageSourceFactory
                .createPageSource(
                        conf,
                        session,
                        new Path(targetFile.getAbsolutePath()),
                        0,
                        targetFile.length(),
                        targetFile.length(),
                        createSchema(format, columnNames, columnTypes),
                        columnHandles,
                        TupleDomain.all(),
                        Optional.empty());

        checkState(readerPageSourceWithProjections.isPresent(), "readerPageSourceWithProjections is not present");
        checkState(readerPageSourceWithProjections.get().getProjectedReaderColumns().isEmpty(), "projection should not be required");

        return readerPageSourceWithProjections.get().getConnectorPageSource();
    }

    private static class RecordFormatWriter
            implements FormatWriter
    {
        private final RecordFileWriter recordWriter;

        public RecordFormatWriter(
                File targetFile,
                List<String> columnNames,
                List<Type> columnTypes,
                HiveCompressionCodec compressionCodec,
                HiveStorageFormat format,
                ConnectorSession session)
        {
            JobConf config = new JobConf(conf);
            configureCompression(config, compressionCodec);

            recordWriter = new RecordFileWriter(
                    new Path(targetFile.toURI()),
                    columnNames,
                    fromHiveStorageFormat(format),
                    createSchema(format, columnNames, columnTypes),
                    format.getEstimatedWriterSystemMemoryUsage(),
                    config,
                    TYPE_MANAGER,
                    UTC,
                    session);
        }

        @Override
        public void writePage(Page page)
        {
            for (int position = 0; position < page.getPositionCount(); position++) {
                recordWriter.appendRow(page, position);
            }
        }

        @Override
        public void close()
        {
            recordWriter.commit();
        }
    }

    private static Properties createSchema(HiveStorageFormat format, List<String> columnNames, List<Type> columnTypes)
    {
        Properties schema = new Properties();
        schema.setProperty(SERIALIZATION_LIB, format.getSerDe());
        schema.setProperty(FILE_INPUT_FORMAT, format.getInputFormat());
        schema.setProperty(META_TABLE_COLUMNS, join(",", columnNames));
        schema.setProperty(META_TABLE_COLUMN_TYPES, columnTypes.stream()
                .map(HiveType::toHiveType)
                .map(HiveType::getHiveTypeName)
                .map(HiveTypeName::toString)
                .collect(joining(":")));
        return schema;
    }

    private static class PrestoRcFileFormatWriter
            implements FormatWriter
    {
        private final RcFileWriter writer;

        public PrestoRcFileFormatWriter(File targetFile, List<Type> types, RcFileEncoding encoding, HiveCompressionCodec compressionCodec)
                throws IOException
        {
            writer = new RcFileWriter(
                    new OutputStreamSliceOutput(new FileOutputStream(targetFile)),
                    types,
                    encoding,
                    compressionCodec.getCodec().map(Class::getName),
                    new AircompressorCodecFactory(new HadoopCodecFactory(getClass().getClassLoader())),
                    ImmutableMap.of(),
                    true);
        }

        @Override
        public void writePage(Page page)
                throws IOException
        {
            writer.write(page);
        }

        @Override
        public void close()
                throws IOException
        {
            writer.close();
        }
    }

    private static class PrestoOrcFormatWriter
            implements FormatWriter
    {
        private final OrcWriter writer;

        public PrestoOrcFormatWriter(File targetFile, List<String> columnNames, List<Type> types, HiveCompressionCodec compressionCodec)
                throws IOException
        {
            writer = new OrcWriter(
                    new OutputStreamOrcDataSink(new FileOutputStream(targetFile)),
                    columnNames,
                    types,
                    OrcType.createRootOrcType(columnNames, types),
                    compressionCodec.getOrcCompressionKind(),
                    new OrcWriterOptions(),
                    false,
                    ImmutableMap.of(),
                    false,
                    BOTH,
                    new OrcWriterStats());
        }

        @Override
        public void writePage(Page page)
                throws IOException
        {
            writer.write(page);
        }

        @Override
        public void close()
                throws IOException
        {
            writer.close();
        }
    }

    private static class PrestoParquetFormatWriter
            implements FormatWriter
    {
        private final ParquetWriter writer;

        public PrestoParquetFormatWriter(File targetFile, List<String> columnNames, List<Type> types, HiveCompressionCodec compressionCodec)
                throws IOException
        {
            ParquetSchemaConverter schemaConverter = new ParquetSchemaConverter(types, columnNames);

            writer = new ParquetWriter(
                    new FileOutputStream(targetFile),
                    schemaConverter.getMessageType(),
                    schemaConverter.getPrimitiveTypes(),
                    ParquetWriterOptions.builder().build(),
                    compressionCodec.getParquetCompressionCodec());
        }

        @Override
        public void writePage(Page page)
                throws IOException
        {
            writer.write(page);
        }

        @Override
        public void close()
                throws IOException
        {
            writer.close();
        }
    }
}
