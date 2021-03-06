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
package com.facebook.presto.spiller;

import com.facebook.presto.execution.buffer.PagesSerde;
import com.facebook.presto.execution.buffer.PagesSerdeUtil;
import com.facebook.presto.execution.buffer.SerializedPage;
import com.facebook.presto.memory.LocalMemoryContext;
import com.facebook.presto.operator.SpillContext;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.util.PrestoIterators;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.airlift.slice.InputStreamSliceInput;
import io.airlift.slice.OutputStreamSliceOutput;
import io.airlift.slice.SliceOutput;

import javax.annotation.concurrent.NotThreadSafe;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static com.facebook.presto.execution.buffer.PagesSerdeUtil.writeSerializedPage;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spiller.FileSingleStreamSpillerFactory.SPILL_FILE_PREFIX;
import static com.facebook.presto.spiller.FileSingleStreamSpillerFactory.SPILL_FILE_SUFFIX;
import static com.facebook.presto.util.PrestoCloseables.combineCloseables;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.util.Objects.requireNonNull;

@NotThreadSafe
public class FileSingleStreamSpiller
        implements SingleStreamSpiller
{
    @VisibleForTesting
    static final int BUFFER_SIZE = 4 * 1024;

    private final FileHolder targetFile;
    private final Closer closer = Closer.create();
    private final PagesSerde serde;
    private final SpillerStats spillerStats;
    private final SpillContext localSpillContext;
    private final LocalMemoryContext memoryContext;

    private final ListeningExecutorService executor;

    private boolean writable = true;
    private long spilledPagesInMemorySize;
    private ListenableFuture<?> spillInProgress = Futures.immediateFuture(null);

    public FileSingleStreamSpiller(
            PagesSerde serde,
            ListeningExecutorService executor,
            Path spillPath,
            SpillerStats spillerStats,
            SpillContext spillContext,
            LocalMemoryContext memoryContext)
    {
        this.serde = requireNonNull(serde, "serde is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.spillerStats = requireNonNull(spillerStats, "spillerStats is null");
        this.localSpillContext = spillContext.newLocalSpillContext();
        this.memoryContext = requireNonNull(memoryContext, "memoryContext can not be null");
        try {
            this.targetFile = closer.register(new FileHolder(Files.createTempFile(spillPath, SPILL_FILE_PREFIX, SPILL_FILE_SUFFIX)));
        }
        catch (IOException e) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed to create spill file", e);
        }
    }

    @Override
    public ListenableFuture<?> spill(Iterator<Page> pageIterator)
    {
        requireNonNull(pageIterator, "pageIterator is null");
        checkNoSpillInProgress();
        spillInProgress = executor.submit(() -> writePages(pageIterator));
        return spillInProgress;
    }

    @Override
    public long getSpilledPagesInMemorySize()
    {
        return spilledPagesInMemorySize;
    }

    @Override
    public Iterator<Page> getSpilledPages()
    {
        checkNoSpillInProgress();
        return readPages();
    }

    @Override
    public ListenableFuture<List<Page>> getAllSpilledPages()
    {
        return executor.submit(() -> ImmutableList.copyOf(getSpilledPages()));
    }

    private void writePages(Iterator<Page> pageIterator)
    {
        checkState(writable, "Spilling no longer allowed. The spiller has been made non-writable on first read for subsequent reads to be consistent");

        try (SliceOutput output = new OutputStreamSliceOutput(targetFile.newOutputStream(APPEND), BUFFER_SIZE)) {
            memoryContext.setBytes(BUFFER_SIZE);
            while (pageIterator.hasNext()) {
                Page page = pageIterator.next();
                spilledPagesInMemorySize += page.getSizeInBytes();
                SerializedPage serializedPage = serde.serialize(page);
                long pageSize = serializedPage.getSizeInBytes();
                localSpillContext.updateBytes(pageSize);
                spillerStats.addToTotalSpilledBytes(pageSize);
                writeSerializedPage(output, serializedPage);
            }
        }
        catch (UncheckedIOException | IOException e) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed to spill pages", e);
        }
        finally {
            memoryContext.setBytes(0);
        }
    }

    private Iterator<Page> readPages()
    {
        checkState(writable, "Repeated reads are disallowed to prevent potential resource leaks");
        writable = false;

        try {
            InputStream input = targetFile.newInputStream();
            Closeable resources = closer.register(combineCloseables(input, () -> memoryContext.setBytes(0)));
            memoryContext.setBytes(BUFFER_SIZE);
            Iterator<Page> pages = PagesSerdeUtil.readPages(serde, new InputStreamSliceInput(input, BUFFER_SIZE));
            return PrestoIterators.closeWhenExhausted(pages, resources);
        }
        catch (IOException e) {
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Failed to read spilled pages", e);
        }
    }

    @Override
    public void close()
    {
        closer.register(localSpillContext);

        try {
            closer.close();
        }
        catch (Exception e) {
            throw new PrestoException(
                    GENERIC_INTERNAL_ERROR,
                    "Failed to close spiller",
                    e);
        }
    }

    private void checkNoSpillInProgress()
    {
        checkState(spillInProgress.isDone(), "spill in progress");
    }
}
