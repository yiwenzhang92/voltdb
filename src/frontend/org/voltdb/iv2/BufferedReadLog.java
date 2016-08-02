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

package org.voltdb.iv2;

import java.util.ArrayDeque;
import java.util.Deque;

import org.voltcore.logging.Level;
import org.voltcore.logging.VoltLogger;
import org.voltcore.messaging.Mailbox;
import org.voltcore.messaging.VoltMessage;
import org.voltdb.messaging.FragmentResponseMessage;
import org.voltdb.messaging.InitiateResponseMessage;

public class BufferedReadLog
{
    private static final VoltLogger hostLog = new VoltLogger("HOST");

    public static class Item {
        InitiateResponseMessage m_initiateMsg;
        FragmentResponseMessage m_fragmentMsg;

        Item(InitiateResponseMessage msg) {
            m_initiateMsg = msg;
            m_fragmentMsg = null;
        }

        Item(FragmentResponseMessage msg) {
            m_initiateMsg = null;
            m_fragmentMsg = msg;
        }

        long getSpHandle() {
            if (m_initiateMsg != null) {
                return m_initiateMsg.getSpHandle();
            }
            return m_fragmentMsg.getSpHandle();
        }

        long getResponseHSId() {
            if (m_initiateMsg != null) {
                return m_initiateMsg.getInitiatorHSId();
            }
            return m_fragmentMsg.getDestinationSiteId();
        }

        VoltMessage getMessage() {
            if (m_initiateMsg != null) {
                return m_initiateMsg;
            }
            return m_fragmentMsg;
        }
    }

    private static final int INIT_BUFFER_CAPACITY = 64;

    final Deque<Item> m_bufferedReadSp;
    Mailbox m_mailbox;
    int m_maxCount = -1;

    BufferedReadLog(Mailbox mailbox)
    {
        m_bufferedReadSp = new ArrayDeque<Item>(INIT_BUFFER_CAPACITY);

        assert(mailbox != null);
        m_mailbox = mailbox;
    }

    public void offerSp(InitiateResponseMessage msg, long handle)
    {
        offerSp(new Item(msg), handle);
    }

    public void offerSp(FragmentResponseMessage msg, long handle)
    {
        offerSp(new Item(msg), handle);
    }

    //  SPI offers a new message.
    private void offerSp(Item item, long handle) {
        if (item.getSpHandle() <= handle) {
            m_mailbox.send(item.getResponseHSId(), item.getMessage());
        } else {
            m_bufferedReadSp.add(item);
        }
        releaseBufferedRead(handle);

        if (m_bufferedReadSp.size() > m_maxCount) {
            m_maxCount = m_bufferedReadSp.size();
            hostLog.rateLimitedLog(10, Level.INFO, null, "max buffered read log size: " + m_maxCount);
        }
    }


    public void releaseBufferedRead(long spHandle)
    {
        Deque<Item> deq = m_bufferedReadSp;
        Item item = null;
        while ((item = deq.peek()) != null) {
            if (item.getSpHandle() <= spHandle) {
                // when the sp reads' handle is less equal than truncation handle
                // we know any previous write has been confirmed and it's safe to release.
                m_mailbox.send(item.getResponseHSId(), item.getMessage());
                deq.poll();
            } else {
                break;
            }
        }
    }
}
