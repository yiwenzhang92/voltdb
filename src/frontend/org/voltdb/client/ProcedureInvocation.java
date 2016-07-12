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

package org.voltdb.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.voltdb.ParameterSet;
import org.voltdb.utils.SerializationHelper;

/**
 * Client stored procedure invocation object. Server uses an internal
 * format compatible with this wire protocol format.
 */
public class ProcedureInvocation {

    public static final Pattern PROC_NAME_PATTERN = Pattern.compile("^(#(?<tracename>.*)#)?(?<procname>.*)$");
    public static String extractProcName(String s) {
        final Matcher matcher = PROC_NAME_PATTERN.matcher(s);
        if (matcher.matches()) {
            return matcher.group("procname");
        } else {
            return null;
        }
    }

    private final long m_clientHandle;
    private final String m_fullProcName;
    private byte m_procNameBytes[];
    private final ParameterSet m_parameters;

    // used for replicated procedure invocations
    private final long m_originalTxnId;
    private final long m_originalUniqueId;
    private final ProcedureInvocationType m_type;

    private int m_batchTimeout;

    // transient state
    private String m_procName = null;

    public ProcedureInvocation(long handle, String procName, Object... parameters) {
        this(-1, -1, handle, procName, parameters);
    }

    public ProcedureInvocation(long handle, int batchTimeout, String procName, Object... parameters) {
        this(-1, -1, handle, batchTimeout, procName, parameters);
    }

    ProcedureInvocation(long originalTxnId, long originalUniqueId, long handle,
                        String procName, Object... parameters) {
        this(originalTxnId, originalUniqueId, handle, BatchTimeoutOverrideType.NO_TIMEOUT, procName, parameters);
    }

    ProcedureInvocation(long originalTxnId, long originalUniqueId, long handle,
            int batchTimeout, String procName, Object... parameters) {
        super();
        m_originalTxnId = originalTxnId;
        m_originalUniqueId = originalUniqueId;
        m_clientHandle = handle;
        m_fullProcName = procName;
        m_parameters = (parameters != null
                            ? ParameterSet.fromArrayWithCopy(parameters)
                            : ParameterSet.emptyParameterSet());

        // auto-set the type if both txn IDs are set
        if (m_originalTxnId == -1 && m_originalUniqueId == -1) {
            if (BatchTimeoutOverrideType.isUserSetTimeout(batchTimeout)) {
                m_type = ProcedureInvocationType.VERSION1;
            } else {
                m_type = ProcedureInvocationType.ORIGINAL;
            }
        } else {
            m_type = ProcedureInvocationType.REPLICATED;
        }

        m_batchTimeout = batchTimeout;
    }

    /** return the clientHandle value */
    long getHandle() {
        return m_clientHandle;
    }

    public String getProcName() {
        if (m_procName != null) {
            return m_procName;
        } else {
            m_procName = extractProcName(m_fullProcName);
            return m_procName;
        }
    }

    public int getSerializedSize() {
        try {
            m_procNameBytes = m_fullProcName.getBytes("UTF-8");
        } catch (Exception e) {/*No UTF-8? Really?*/}

        int timeoutSize = 0;
        if (m_type.getValue() >= BatchTimeoutOverrideType.BATCH_TIMEOUT_VERSION) {
            // Adding 1 for NO_BATCH_TIMEOUT/HAS_BATCH_TIMEOUT flag.
            // In the most common case, the default value, BatchTimeoutType.NO_BATCH_TIMEOUT, does not get serialized.
            timeoutSize = 1 + (m_batchTimeout == BatchTimeoutOverrideType.NO_TIMEOUT ? 0 : 4);
        }
        // 16 is the size of the m_originalTxnId and m_originalUniqueId values
        // that are required by DR internal invocations prior to DR v2.
        int size =
            1 + (ProcedureInvocationType.isDeprecatedInternalDRType(m_type)? 16 : 0) +
            timeoutSize +
            m_procNameBytes.length + 4 + 8 + m_parameters.getSerializedSize();
        return size;
    }

    public int getPassedParamCount() {
        return m_parameters.size();
    }

    public Object getPartitionParamValue(int index) {
        return m_parameters.getParam(index);
    }

    public ByteBuffer flattenToBuffer(ByteBuffer buf) throws IOException {
        buf.put(m_type.getValue());//Version
        if (ProcedureInvocationType.isDeprecatedInternalDRType(m_type)) {
            buf.putLong(m_originalTxnId);
            buf.putLong(m_originalUniqueId);
        }

        if (m_type.getValue() >= BatchTimeoutOverrideType.BATCH_TIMEOUT_VERSION) {
            if (m_batchTimeout == BatchTimeoutOverrideType.NO_TIMEOUT) {
                buf.put(BatchTimeoutOverrideType.NO_OVERRIDE_FOR_BATCH_TIMEOUT.getValue());
            } else {
                buf.put(BatchTimeoutOverrideType.HAS_OVERRIDE_FOR_BATCH_TIMEOUT.getValue());
                buf.putInt(m_batchTimeout);
            }
        }

        SerializationHelper.writeVarbinary(m_procNameBytes, buf);
        buf.putLong(m_clientHandle);
        m_parameters.flattenToBuffer(buf);
        return buf;
    }
}
