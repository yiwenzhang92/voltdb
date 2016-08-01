/* This file is part of VoltDB.
 * Copyright (C) 2008-2016 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.voltdb.utils;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.collect.EvictingQueue;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.annotate.JsonIgnore;
import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.annotate.JsonSerialize;
import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;

/**
 * Utility class to log Chrome Trace Event format trace messages into files.
 * Trace events are queued in this class, which are then picked up TraceFileWriter.
 * When the queue reaches its limit, events are dropped on the floor with a rate limited log.
 */
public class VoltTrace {
    private static final VoltLogger s_logger = new VoltLogger("TRACER");

    // Current process id. Used by all trace events.
    private static int s_pid = -1;
    static {
        try {
            s_pid = Integer.parseInt(CoreUtils.getPID());
        } catch(NumberFormatException e) {
            s_logger.warn("Error getting current process id. Trace events will record incorrect process id", e);
        }
    }

    public enum Category {
        CI, MPI, MPSITE, SPI, SPSITE, EE
    }

    private static Map<Character, TraceEventType> s_typeMap = new HashMap<>();
    public enum TraceEventType {

        ASYNC_BEGIN('b'),
        ASYNC_END('e'),
        ASYNC_INSTANT('n'),
        CLOCK_SYNC('c'),
        COMPLETE('X'),
        CONTEXT(','),
        COUNTER('C'),
        DURATION_BEGIN('B'),
        DURATION_END('E'),
        FLOW_END('f'),
        FLOW_START('s'),
        FLOW_STEP('t'),
        INSTANT('i'),
        MARK('R'),
        MEMORY_DUMP_GLOBAL('V'),
        MEMORY_DUMP_PROCESS('v'),
        METADATA('M'),
        OBJECT_CREATED('N'),
        OBJECT_DESTROYED('D'),
        OBJECT_SNAPSHOT('O'),
        SAMPLE('P'),

        VOLT_INTERNAL_CLOSE('z'),
        VOLT_INTERNAL_CLOSE_ALL('Z');

        private final char m_typeChar;

        TraceEventType(char typeChar) {
            m_typeChar = typeChar;
            s_typeMap.put(typeChar, this);
        }

        public char getTypeChar() {
            return m_typeChar;
        }

        public static TraceEventType fromTypeChar(char ch) {
            return s_typeMap.get(ch);
        }
    }

    /**
     * Trace event class annotated with JSON annotations to serialize events in the exact format
     * required by Chrome.
     */
    @JsonSerialize(include=JsonSerialize.Inclusion.NON_NULL)
    public static class TraceEvent {
        private String m_fileName;
        private TraceEventType m_type;
        private String m_name;
        private Category m_category;
        private String m_id;
        private long m_tid;
        private long m_nanos;
        private double m_ts;
        private String[] m_argsArr;
        private Map<String, String> m_args;

        // Empty constructor and setters for jackson deserialization for ease of testing
        public TraceEvent() {
        }

        public TraceEvent(String fileName,
                TraceEventType type,
                String name,
                Category category,
                String asyncId,
                String... args) {
            m_fileName = fileName;
            m_type = type;
            m_name = name;
            m_category = category;
            m_id = asyncId;
            m_argsArr = args;
            m_tid = Thread.currentThread().getId();
            m_nanos = System.nanoTime();
        }

        private void mapFromArgArray() {
            m_args = new HashMap<>();
            if (m_argsArr == null) {
                return;
            }

            for (int i=0; i<m_argsArr.length; i+=2) {
                if (i+1 == m_argsArr.length) break;
                m_args.put(m_argsArr[i], m_argsArr[i+1]);
            }
        }

        /**
         * Use the nanoTime of the first event for this file to set the sync time.
         * This is used to sync first event time on all volt nodes to zero and thus
         * make it easy to visualize multiple volt node traces.
         *
         * @param syncNanos
         */
        public void setSyncNanos(long syncNanos) {
            m_ts = (m_nanos - syncNanos)/1000.0;
        }

        @JsonIgnore
        public String getFileName() {
            return m_fileName;
        }

        public void setFileName(String fileName) {
            m_fileName = fileName;
        }

        @JsonIgnore
        public TraceEventType getType() {
            return m_type;
        }

        @JsonProperty("ph")
        public char getTypeChar() {
            return m_type.getTypeChar();
        }

        @JsonProperty("ph")
        public void setTypeChar(char ch) {
            m_type = TraceEventType.fromTypeChar(ch);
        }

        public String getName() {
            if (m_name != null) {
                return m_name;
            } else {
                return null;
            }
        }

        public void setName(String name) {
            m_name = name;
        }

        @JsonProperty("cat")
        public String getCategory() {
            if (m_category != null) {
                return m_category.name();
            } else {
                return null;
            }
        }

        @JsonProperty("cat")
        public void setCategory(Category cat) {
            m_category = cat;
        }

        public String getId() {
            return m_id;
        }

        public void setId(String id) {
            m_id = id;
        }

        public int getPid() {
            return s_pid;
        }

        public void setPid(int pid) {
        }

        public long getTid() {
            return m_tid;
        }

        public void setTid(long tid) {
            m_tid = tid;
        }

        @JsonIgnore
        public long getNanos() {
            return m_nanos;
        }

        /**
         * The event timestamp in microseconds.
         * @return
         */
        @JsonSerialize(using = CustomDoubleSerializer.class)
        public double getTs() {
            return m_ts;
        }

        public void setTs(long ts) {
            m_ts = ts;
        }

        public Map<String, String> getArgs() {
            if (m_args==null) {
                mapFromArgArray();
            }
            return m_args;
        }

        public void setArgs(Map<String, String> args) {
            m_args = args;
        }
    }

    /**
     * Custom serializer to seralize doubles in an easily readable format in the trace file.
     */
    private static class CustomDoubleSerializer extends JsonSerializer<Double> {

        private DecimalFormat m_format = new DecimalFormat("#0.00");

        @Override
        public void serialize(Double value, JsonGenerator jsonGen, SerializerProvider sp)
                throws IOException {
            if (value == null) {
                jsonGen.writeNull();
            } else {
                jsonGen.writeNumber(m_format.format(value));
            }
        }
    }

    private static int QUEUE_SIZE = 1024;
    private static VoltTrace s_tracer;
    // Events from trace producers are put into this queue.
    // TraceFileWriter takes events from this queue and writes them to files.
    private EvictingQueue<Supplier<TraceEvent>> m_traceEvents = EvictingQueue.create(QUEUE_SIZE);
    private final Thread m_writerThread;

    private VoltTrace() {
        m_writerThread = new Thread(new TraceFileWriter(this));
        m_writerThread.setDaemon(true);
        m_writerThread.start();
    }

    private synchronized void queueEvent(Supplier<TraceEvent> s) {
        m_traceEvents.offer(s);
        // If queue is full, drop events
    }

    public synchronized TraceEvent takeEvent() throws InterruptedException {
        final Supplier<TraceEvent> e = m_traceEvents.poll();
        if (e != null) {
            return e.get();
        } else {
            return null;
        }
    }

    public static void add(Supplier<TraceEvent> s) {
        s_tracer.queueEvent(s);
    }
    /**
     * Logs a metadata trace event.
     */
    public static TraceEvent meta(String fileName, String name, String... args) {
        return new TraceEvent(fileName, TraceEventType.METADATA, name, null, null, args);
    }

    /**
     * Logs an instant trace event.
     */
    public static TraceEvent instant(String fileName, String name, Category category, String... args) {
        return new TraceEvent(fileName, TraceEventType.INSTANT, name, category, null, args);
    }

    /**
     * Logs a begin duration trace event.
     */
    public static TraceEvent beginDuration(String fileName, String name, Category category, String... args) {
        return new TraceEvent(fileName, TraceEventType.DURATION_BEGIN, name, category, null, args);
    }

    /**
     * Logs an end duration trace event.
     */
    public static TraceEvent endDuration(String fileName) {
        return new TraceEvent(fileName, TraceEventType.DURATION_END, null, null, null);
    }

    /**
     * Logs a begin async trace event.
     */
    public static TraceEvent beginAsync(String fileName, String name, Category category, Object id, String... args) {
        return new TraceEvent(fileName, TraceEventType.ASYNC_BEGIN, name, category, String.valueOf(id), args);
    }

    /**
     * Logs an end async trace event.
     */
    public static TraceEvent endAsync(String fileName, String name, Category category, Object id, String... args) {
        return new TraceEvent(fileName, TraceEventType.ASYNC_END, name, category, String.valueOf(id), args);
    }

    /**
     * Logs an async instant trace event.
     */
    public static TraceEvent instantAsync(String fileName, String name, Category category, Object id, String... args) {
        return new TraceEvent(fileName, TraceEventType.ASYNC_INSTANT, name, category, String.valueOf(id), args);
    }

    /**
     * Closes the given file. Further trace events to this file will be ignored.
     */
    public static void close(String fileName) {
        s_tracer.queueEvent(() -> new TraceEvent(fileName, TraceEventType.VOLT_INTERNAL_CLOSE, null, null, null));
    }

    /**
     * Close all open files and wait for shutdown.
     * @param timeOutMillis    Timeout in milliseconds. Negative to not wait,
     *                         0 to wait forever, positive to wait for given
     *                         number of milliseconds.
     */
    public static void closeAllAndShutdown(long timeOutMillis) {
        s_tracer.queueEvent(() -> new TraceEvent(null, TraceEventType.VOLT_INTERNAL_CLOSE_ALL, null, null, null));
        if (timeOutMillis >= 0) {
            try { s_tracer.m_writerThread.join(timeOutMillis); } catch (InterruptedException e) {}
        }
    }

    /**
     * Returns true if there are events in the tracer's queue. False otherwise.
     * Used by tests only.
     */
    static boolean hasEvents() {
        synchronized (s_tracer) {
            return !s_tracer.m_traceEvents.isEmpty();
        }
    }

    public static void startTracer() {
        if (s_tracer == null) {
            s_tracer = new VoltTrace();
        }
    }
}
