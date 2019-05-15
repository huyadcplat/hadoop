/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Interface for class that can tell estimate much space
 * is used in a directory.
 * <p>
 * The implementor is fee to cache space used. As such there
 * are methods to update the cached value with any known changes.
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "MapReduce"})
@InterfaceStability.Evolving
public abstract class CachingGetSpaceUsed implements Closeable, GetSpaceUsed {
    static final Logger LOG = LoggerFactory.getLogger(CachingGetSpaceUsed.class);

    protected final AtomicLong used = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final long refreshInterval;
    private final long jitter;
    private final String dirPath;
    private Thread refreshUsed;
    private File duCacheFile;

    public File getDuCacheFile() {
        return duCacheFile;
    }

    public void setDuCacheFile(File duCacheFile) {
        this.duCacheFile = duCacheFile;
    }

    /**
     * This is the constructor used by the builder.
     * All overriding classes should implement this.
     */
    public CachingGetSpaceUsed(CachingGetSpaceUsed.Builder builder)
            throws IOException {
        this(builder.getPath(),
                builder.getInterval(),
                builder.getJitter(),
                builder.getInitialUsed(),builder.getDuCacheFile());
    }

    /**
     * Keeps track of disk usage.
     *
     * @param path        the path to check disk usage in
     * @param interval    refresh the disk usage at this interval
     * @param initialUsed use this value until next refresh
     * @throws IOException if we fail to refresh the disk usage
     */
    CachingGetSpaceUsed(File path,
                        long interval,
                        long jitter,
                        long initialUsed,File duCacheFile) throws IOException {
        this.dirPath = path.getCanonicalPath();
        this.refreshInterval = interval;
        this.jitter = jitter;
        this.used.set(initialUsed);
        this.duCacheFile = duCacheFile;
    }

    public void init() {
        if (used.get() < 0) {
            used.set(0);
            refresh();
        }

        if (refreshInterval > 0) {
            refreshUsed = new Thread(new RefreshThread(this),
                    "refreshUsed-" + dirPath);
            refreshUsed.setDaemon(true);
            refreshUsed.start();
        } else {
            running.set(false);
            refreshUsed = null;
        }
    }

    protected abstract void refresh();

    /**
     * @return an estimate of space used in the directory path.
     */
    @Override public long getUsed() throws IOException {
        return Math.max(used.get(), 0);
    }

    /**
     * @return The directory path being monitored.
     */
    public String getDirPath() {
        return dirPath;
    }

    /**
     * Increment the cached value of used space.
     */
    public void incDfsUsed(long value) {
        used.addAndGet(value);
    }

    /**
     * Is the background thread running.
     */
    boolean running() {
        return running.get();
    }

    /**
     * How long in between runs of the background refresh.
     */
    long getRefreshInterval() {
        return refreshInterval;
    }

    /**
     * Reset the current used data amount. This should be called
     * when the cached value is re-computed.
     *
     * @param usedValue new value that should be the disk usage.
     */
    protected void setUsed(long usedValue) {
        this.used.set(usedValue);
    }

    @Override
    public void saveSpaceUsed() throws IOException {
        if (duCacheFile == null) {
            throw new IOException("duCacheFile is null");
        }
        if (duCacheFile.exists() && !duCacheFile.delete()) {
            LOG.warn("Failed to delete old dfsUsed file in " + duCacheFile.getParent()+"");
        }
        FileWriter out = null;
        try {
            if (used.get() > 0) {
                out = new FileWriter(duCacheFile);
                // mtime is written last, so that truncated writes won't be valid.
                out.write(Long.toString(used.get()) + " " + Long.toString(Time.now()));
                out.flush();
                out.close();
                out = null;
            }
        }catch (IOException ioe) {
            LOG.warn("Failed to write dfsUsed to " + duCacheFile, ioe);
        }finally {
            IOUtils.cleanup(null,out);
        }
    }

    @Override
    public void close() throws IOException {
        running.set(false);
        if (refreshUsed != null) {
            refreshUsed.interrupt();
        }
    }

    private static final class RefreshThread implements Runnable {

        final CachingGetSpaceUsed spaceUsed;

        RefreshThread(CachingGetSpaceUsed spaceUsed) {
            this.spaceUsed = spaceUsed;
        }

        @Override
        public void run() {
            while (spaceUsed.running()) {
                try {
                    long refreshInterval = spaceUsed.refreshInterval;

                    if (spaceUsed.jitter > 0) {
                        long jitter = spaceUsed.jitter;
                        // add/subtract the jitter.
                        refreshInterval +=
                                ThreadLocalRandom.current()
                                        .nextLong(-jitter, jitter);
                    }
                    // Make sure that after the jitter we didn't end up at 0.
                    refreshInterval = Math.max(refreshInterval, 1);
                    Thread.sleep(refreshInterval);
                    // update the used variable
                    spaceUsed.refresh();
                    spaceUsed.saveSpaceUsed();
                } catch (InterruptedException e) {
                    LOG.warn("Thread Interrupted waiting to refresh disk information: "
                            + e.getMessage());
                    Thread.currentThread().interrupt();
                }catch (IOException e){
                    LOG.error("save space used error",e);
                }
            }
        }
    }
}
