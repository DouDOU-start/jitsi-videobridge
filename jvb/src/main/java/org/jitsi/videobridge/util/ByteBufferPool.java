/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
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

package org.jitsi.videobridge.util;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

/**
 * A pool of reusable byte[]
 *
 * @author Brian Baldino
 * @author Boris Grozev
 */
public class ByteBufferPool
{
    /**
     * The pool of buffers is segmented by size to make the overall memory
     * footprint smaller.
     * These thresholds were chosen to minimize the used memory for a trace of
     * requests from a test conference. Using finer segmentation (4 thresholds)
     * results in only a marginal improvement.
     */
    private static int T1 = 220;
    private static int T2 = 775;
    private static int T3 = 1500;

    /**
     * The pool of buffers with size <= T1
     */
    private static final PartitionedByteBufferPool pool1
            = new PartitionedByteBufferPool(T1);
    /**
     * The pool of buffers with size in (T1, T2]
     */
    private static final PartitionedByteBufferPool pool2
            = new PartitionedByteBufferPool(T2);
    /**
     * The pool of buffers with size in (T2, T3]
     */
    private static final PartitionedByteBufferPool pool3
            = new PartitionedByteBufferPool(T3);

    /**
     * The {@link Logger}
     */
    private static final Logger logger = new LoggerImpl(ByteBufferPool.class.getName());

    private static final Set<byte[]> outstandingBuffers =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    private static final Set<byte[]> returnedBuffers =
        Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));

    /**
     * A debug data structure which tracks all events (requests and returns) for a given
     * buffer
     */
    private static final Map<byte[], Queue<BufferEvent>> bufferEvents =
        Collections.synchronizedMap(new IdentityHashMap<>());

    /**
     * Whether to enable keeping track of statistics.
     */
    private static boolean enableStatistics = false;

    /**
     * Whether to enable or disable book keeping.
     */
    private static Boolean bookkeepingEnabled = false;

    /**
     * Total number of buffers requested.
     */
    private static final LongAdder numRequests = new LongAdder();

    /**
     * The number of requests which were larger than our threshold and were
     * allocated from java instead.
     */
    private static final LongAdder numLargeRequests = new LongAdder();

    /**
     * Total number of buffers returned.
     */
    private static final LongAdder numReturns = new LongAdder();

    /**
     * Gets the current thread ID.
     */
    private static long threadId()
    {
        return Thread.currentThread().getId();
    }

    /**
     * Gets a stack trace as a multi-line string.
     */
    private static String stackTraceAsString(StackTraceElement[] stack)
    {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement ste : stack)
        {
            sb.append(ste.toString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Returns a buffer from the pool.
     *
     * @param size the minimum size.
     */
    public static byte[] getBuffer(int size)
    {
        if (enableStatistics)
        {
            numRequests.increment();
        }

        byte[] buf;
        if (size <= T1)
        {
            buf = pool1.getBuffer(size);
        }
        else if (size <= T2)
        {
            buf = pool2.getBuffer(size);
        }
        else if (size <= T3)
        {
            buf = pool3.getBuffer(size);
        }
        else
        {
            buf = new byte[size];
            numLargeRequests.increment();
        }

        if (bookkeepingEnabled)
        {
            Exception stackTrace = new Exception();
            outstandingBuffers.add(buf);
            returnedBuffers.remove(buf);
            bufferEvents.computeIfAbsent(buf, k -> new LinkedBlockingQueue<>())
                .add(new BufferEvent(BufferEvent.ALLOCATION, System.currentTimeMillis(), stackTrace));
        }
        return buf;
    }

    /**
     * Returns a buffer to the pool.
     * @param buf
     */
    public static void returnBuffer(@NotNull byte[] buf)
    {
        if (enableStatistics)
        {
            numReturns.increment();
        }

        int len = buf.length;

        if (bookkeepingEnabled)
        {
            int arrayId = System.identityHashCode(buf);
            Exception s;
            Exception stackTrace = new Exception();
            bufferEvents.computeIfAbsent(buf, k -> new LinkedBlockingQueue<>())
                .add(new BufferEvent(BufferEvent.RETURN, System.currentTimeMillis(), stackTrace));
            if (outstandingBuffers.remove(buf))
            {
                returnedBuffers.add(buf);
            }
            else if (returnedBuffers.contains(buf))
            {
                String bufferTimeline = bufferEvents.get(buf).stream()
                    .map(BufferEvent::toString)
                    .collect(Collectors.joining());
                logger.error("Thread " + threadId()
                    + " returned a previously-returned " + len + "-byte buffer " + arrayId
                    + " its timeline:\n" + bufferTimeline);
            }
            else
            {
                logger.error("Thread " + threadId()
                    + " returned a " + len + "-byte buffer we didn't give out from\n"
                    + stackTraceAsString(stackTrace.getStackTrace()));
            }
        }

        if (len <= T1)
        {
            pool1.returnBuffer(buf);
        }
        else if (len <= T2)
        {
            pool2.returnBuffer(buf);
        }
        else if (len <= T3)
        {
            pool3.returnBuffer(buf);
        }
        else
        {
            logger.warn(
                "Received a suspiciously large buffer (size = " + len + ")\n +" +
                    UtilKt.getStackTrace());
        }
    }

    /**
     * Gets a JSON representation of the statistics about the pool.
     */
    public static OrderedJsonObject getStatsJson()
    {
        OrderedJsonObject stats = new OrderedJsonObject();
        long numLargeRequestsSum = numLargeRequests.sum();

        stats.put("num_large_requests", numLargeRequestsSum);

        if (enableStatistics)
        {
            long numRequestsSum = numRequests.sum();
            long allAllocations = numLargeRequestsSum + pool1.getNumAllocations()
                + pool2.getNumAllocations() + pool3.getNumAllocations();
            long storedBytes = pool1.getStoredBytes() + pool2.getStoredBytes()
                + pool3.getStoredBytes();
            stats.put("num_requests", numRequestsSum);
            stats.put("num_returns", numReturns.sum());
            stats.put("num_allocations", allAllocations);
            stats.put(
                "allocation_percent",
                (100.0 * allAllocations) / numRequestsSum);
            stats.put("stored_bytes", storedBytes);

            stats.put("pool1", pool1.getStats());
            stats.put("pool2", pool2.getStats());
            stats.put("pool3", pool3.getStats());
        }

        if (bookkeepingEnabled)
        {
            stats.put("outstanding_buffers", outstandingBuffers.size());
        }

        return stats;
    }

    /**
     * Enables of disables tracking of statistics for the pool.
     * @param enable whether to enable it or disable it.
     */
    public static void enableStatistics(boolean enable)
    {
        enableStatistics = enable;
        pool1.enableStatistics(enable);
        pool2.enableStatistics(enable);
        pool3.enableStatistics(enable);
    }

    public static boolean statisticsEnabled()
    {
        return enableStatistics;
    }

    public static void enableBookkeeping(boolean enable)
    {
        bookkeepingEnabled = enable;
        if (!enable)
        {
            outstandingBuffers.clear();
            returnedBuffers.clear();
            bufferEvents.clear();
        }
    }

    public static boolean bookkeepingEnabled()
    {
        return bookkeepingEnabled;
    }

    private static class BufferEvent
    {
        final String context;
        final Long timestamp;
        final Exception trace;

        public BufferEvent(String context, Long timestamp, Exception trace)
        {
            this.context = context;
            this.timestamp = timestamp;
            this.trace = trace;
        }

        @Override
        public String toString()
        {
            return context + " BufferEvent: timestamp=" + timestamp +
                "\n" + stackTraceAsString(trace.getStackTrace());
        }

        public static final String ALLOCATION = "allocation";
        public static final String RETURN = "return";
    }
}
