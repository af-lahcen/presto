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
package com.facebook.presto.hive.parquet;

import com.facebook.presto.hive.FileFormatDataSourceStats;
import com.facebook.presto.parquet.ParquetDataSource;
import com.facebook.presto.spi.PrestoException;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import java.io.FileNotFoundException;
import java.io.IOException;

import static com.facebook.presto.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_FILESYSTEM_ERROR;
import static com.google.common.base.Strings.nullToEmpty;
import static java.lang.String.format;

public class HdfsParquetDataSource
        implements ParquetDataSource
{
    private final String name;
    private final long size;
    private final FSDataInputStream inputStream;
    private long readBytes;
    private final FileFormatDataSourceStats stats;

    public HdfsParquetDataSource(Path path, long size, FSDataInputStream inputStream, FileFormatDataSourceStats stats)
    {
        this.name = path.toString();
        this.size = size;
        this.inputStream = inputStream;
        this.stats = stats;
    }

    @Override
    public final long getReadBytes()
    {
        return readBytes;
    }

    @Override
    public final long getSize()
    {
        return size;
    }

    @Override
    public void close()
            throws IOException
    {
        inputStream.close();
    }

    @Override
    public final void readFully(long position, byte[] buffer)
    {
        readFully(position, buffer, 0, buffer.length);
    }

    @Override
    public final void readFully(long position, byte[] buffer, int bufferOffset, int bufferLength)
    {
        readInternal(position, buffer, bufferOffset, bufferLength);
        readBytes += bufferLength;
    }

    private void readInternal(long position, byte[] buffer, int bufferOffset, int bufferLength)
    {
        try {
            long readStart = System.nanoTime();
            inputStream.readFully(position, buffer, bufferOffset, bufferLength);
            stats.readDataBytesPerSecond(bufferLength, System.nanoTime() - readStart);
        }
        catch (PrestoException e) {
            // just in case there is a Presto wrapper or hook
            throw e;
        }
        catch (Exception e) {
            throw new PrestoException(HIVE_FILESYSTEM_ERROR, format("Error reading from %s at position %s", name, position), e);
        }
    }

    public static HdfsParquetDataSource buildHdfsParquetDataSource(FileSystem fileSystem, Path path, long start, long length, long fileSize, FileFormatDataSourceStats stats)
    {
        try {
            FSDataInputStream inputStream = fileSystem.open(path);
            return new HdfsParquetDataSource(path, fileSize, inputStream, stats);
        }
        catch (Exception e) {
            if (nullToEmpty(e.getMessage()).trim().equals("Filesystem closed") ||
                    e instanceof FileNotFoundException) {
                throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, format("Error opening Hive split %s (offset=%s, length=%s): %s", path, start, length, e.getMessage()), e);
        }
    }

    public static HdfsParquetDataSource buildHdfsParquetDataSource(FSDataInputStream inputStream, Path path, long fileSize, FileFormatDataSourceStats stats)
    {
        return new HdfsParquetDataSource(path, fileSize, inputStream, stats);
    }
}
