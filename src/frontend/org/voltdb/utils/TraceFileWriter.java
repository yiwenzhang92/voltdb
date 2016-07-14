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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.codehaus.jackson.map.ObjectMapper;


/**
 *TODO:
 */
public class TraceFileWriter implements Runnable {
    public static final String PURGE_SECONDS_DELAY_PROP = "TRACE_PURGE_DELAY_SECONDS";
    public static final int PURGE_SECONDS_DELAY = Integer.getInteger(PURGE_SECONDS_DELAY_PROP, 30);
    static long PURGE_MILLIS_DELAY = PURGE_SECONDS_DELAY*1000;
    public static final int MAX_OPEN_TRACES = 16;

    private final ObjectMapper m_jsonMapper = new ObjectMapper();
    private final VoltTrace m_voltTrace;
    private boolean m_shutdown;
    private Map<String, BufferedWriter> m_fileWriters = new LinkedHashMap<>(16, 0.75F, true);
    private Map<String, FileTimeInfo> m_fileTimeInfo = new HashMap<>();

    public TraceFileWriter(VoltTrace voltTrace) {
        m_voltTrace = voltTrace;
    }

    public void run() {
        while (!m_shutdown) {
            try {
                VoltTrace.TraceEvent event = m_voltTrace.takeEvent();
                boolean firstRow = false;
                if (event.getType()==VoltTrace.TraceEventType.VOLT_INTERNAL_CLOSE) {
                    handleCloseEvent(event.getFileName());
                } else {
                    if (m_fileWriters.get(event.getFileName()) == null) {
                        firstRow = true;
                    }
                    startTraceFile(event);
                }
                BufferedWriter bw = m_fileWriters.get(event.getFileName());
                if (bw != null) {
                    m_fileTimeInfo.get(event.getFileName()).updateAccessMillis();
                    event.setSyncNanos(m_fileTimeInfo.get(event.getFileName()).m_syncNanos);
                    String json = m_jsonMapper.writeValueAsString(event);
                    if (!firstRow) bw.write(",");
                    bw.newLine();
                    bw.write(json);
                    bw.flush();
                }
            } catch(InterruptedException e) {
                e.printStackTrace();
                //TODO: log that thread got interrupted
                m_shutdown = true;
            } catch(IOException e) { // also catches JSON exceptions
                e.printStackTrace();
                // TODO: OK to assume something went really bad?
                //TODO: log exception and log that thread is exiting
                m_shutdown = true;
            }
        }
    }

    private void handleCloseEvent(String fileName) {
        BufferedWriter bw = m_fileWriters.get(fileName);
        if (bw==null) return;

        try {
            bw.newLine();
            bw.write("]");
            bw.newLine();
            bw.flush();
            bw.close();
        } catch(IOException e) {
            //TODO: Debug log
        }
        m_fileWriters.remove(fileName);
        m_fileTimeInfo.remove(fileName);
    }

    private void startTraceFile(VoltTrace.TraceEvent event) {
        BufferedWriter bw = m_fileWriters.get(event.getFileName());
        if (bw != null) return;

        File file = new File(event.getFileName());
        // if the file exists already, we don't want to overwrite
        if (file.exists()) {
            // TODO: log
            return;
        }

        if (m_fileWriters.size() == MAX_OPEN_TRACES) {
            // Check if the earliest accessed file can be removed
            Entry<String, BufferedWriter> entry = m_fileWriters.entrySet().iterator().next();
            String key = entry.getKey();
            if ((System.currentTimeMillis() - m_fileTimeInfo.get(key).m_accessMillis)
                    > PURGE_MILLIS_DELAY) {
                handleCloseEvent(key);
            } else { // no entries to remove. Don't allow this trace
                //TODO: rate limited log
                return;
            }
        }

        try {
            //TODO: Path and full file name
            // Uses the default platform encoding for now.
            bw = new BufferedWriter(new FileWriter(event.getFileName()));
            m_fileWriters.put(event.getFileName(), bw);
            m_fileTimeInfo.put(event.getFileName(), new FileTimeInfo(event.getNanos()));
            bw.write("[");
            bw.flush();
        } catch(IOException e) {
            //TODO: rate limited log
        }
    }

    private static class FileTimeInfo {
        final long m_syncNanos;
        long m_accessMillis;

        public FileTimeInfo(long syncNanos) {
            m_syncNanos = syncNanos;
            m_accessMillis = System.currentTimeMillis();
        }

        public void updateAccessMillis() {
            m_accessMillis = System.currentTimeMillis();
        }
    }
}
