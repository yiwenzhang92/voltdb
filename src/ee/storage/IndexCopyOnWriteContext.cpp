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
#include "IndexCopyOnWriteContext.h"

#include "indexes/tableindex.h"
#include "indexes/tableindexfactory.h"
#include "storage/temptable.h"
#include "storage/tablefactory.h"
#include "storage/CopyOnWriteIterator.h"
#include "storage/persistenttable.h"
#include "storage/tableiterator.h"
#include "common/TupleOutputStream.h"
#include "common/FatalException.hpp"
#include "logging/LogManager.h"
#include <algorithm>
#include <cassert>
#include <iostream>

namespace voltdb {

/**
 * Constructor.
 */
IndexCopyOnWriteContext::IndexCopyOnWriteContext(
        PersistentTable &table,
        PersistentTableSurgeon &surgeon,
		TableIndex &index,
        int64_t totalTuples) :
             m_backedUpTuples(TableFactory::buildCopiedTempTable("COW of " + table.name() + " " + index.getName(),
                                                                 &table, NULL)),
             m_table(table),
             m_surgeon(surgeon),
			 m_index(index),
			 m_indexInserts(TableIndexFactory::cloneEmptyTreeIndex(index)),
			 m_indexDeletes(TableIndexFactory::cloneEmptyTreeIndex(index)),
             m_pool(2097152, 320),
             m_tuple(table.schema()),
             m_finished(false),
             m_totalTuples(totalTuples),
             m_tuplesRemaining(totalTuples),
             m_blocksCompacted(0),
             m_serializationBatches(0),
             m_inserts(0),
             m_deletes(0),
             m_updates(0)
{
}

/**
 * Destructor.
 */
IndexCopyOnWriteContext::~IndexCopyOnWriteContext()
{}

/**
 * Activation handler.
 */
void
IndexCopyOnWriteContext::handleActivation()
{
    if (m_finished && m_tuplesRemaining == 0) {
        return;
    }
    //m_surgeon.activateSnapshot();

    //m_iterator.reset(new CopyOnWriteIterator(&m_table, &m_surgeon));
}

void
IndexCopyOnWriteContext::adjustCursors(int type) {
	if (type == 0) {

	} else {
		m_index.moveToGreaterThanKey(&m_tuple, *m_indexCursor);
		m_indexDeletes->moveToGreaterThanKey(&m_tuple, *m_deletesCursor);
	}
}

/**
 * Advance the COW iterator and return the next tuple
 */
bool IndexCopyOnWriteContext::advanceIterator(TableTuple &tuple) {
	PersistentTable &table = m_table;
	// Compare cursors and start from the lowest between deletes and current index
	TableTuple deletesTuple(table.schema());
	TableTuple indexTuple(table.schema());
	while (!m_indexCursor->m_match.isNullTuple() && !m_deletesCursor->m_match.isNullTuple()) {
		if (m_indexCursor->m_match.isNullTuple() || m_deletesCursor->m_match.compare(m_indexCursor->m_match) < 0) {
			// found the next tuple to scan in the delete records...return it
			tuple = m_indexDeletes->nextValue(*m_deletesCursor);
			m_tuple = tuple;
			return true;
		}
		else {
			// found the next tuple to scan in the normal index.
			// check if this tuple can be found in the insert keys
			if (m_indexInserts->hasKey(&(m_indexCursor->m_match))) {
				continue;
			}
			tuple = m_index.nextValue(*m_indexCursor);
			m_tuple = tuple;
			return true;
		}

		break;
	}
	m_finished = true;
	return false;

}

/**
 * Returns true for success, false if there was a serialization error
 */
bool IndexCopyOnWriteContext::cleanup() {
    PersistentTable &table = m_table;
    size_t allPendingCnt = m_surgeon.getSnapshotPendingBlockCount();
    size_t pendingLoadCnt = m_surgeon.getSnapshotPendingLoadBlockCount();
    if (m_tuplesRemaining > 0 || allPendingCnt > 0 || pendingLoadCnt > 0) {
        int32_t skippedDirtyRows = 0;
        int32_t skippedInactiveRows = 0;
        if (!m_finished) {
            skippedDirtyRows = reinterpret_cast<CopyOnWriteIterator*>(m_iterator.get())->m_skippedDirtyRows;
            skippedInactiveRows = reinterpret_cast<CopyOnWriteIterator*>(m_iterator.get())->m_skippedInactiveRows;
        }

        char message[1024 * 16];
        snprintf(message, 1024 * 16,
                 "serializeMore(): tuple count > 0 after streaming:\n"
                 "Table name: %s\n"
                 "Table type: %s\n"
                 "Original tuple count: %jd\n"
                 "Active tuple count: %jd\n"
                 "Remaining tuple count: %jd\n"
                 "Pending block count: %jd\n"
                 "Pending load block count: %jd\n"
                 "Compacted block count: %jd\n"
                 "Dirty insert count: %jd\n"
                 "Dirty delete count: %jd\n"
                 "Dirty update count: %jd\n"
                 "Partition column: %d\n"
                 "Skipped dirty rows: %d\n"
                 "Skipped inactive rows: %d\n",
                 table.name().c_str(),
                 table.tableType().c_str(),
                 (intmax_t)m_totalTuples,
                 (intmax_t)table.activeTupleCount(),
                 (intmax_t)m_tuplesRemaining,
                 (intmax_t)allPendingCnt,
                 (intmax_t)pendingLoadCnt,
                 (intmax_t)m_blocksCompacted,
                 (intmax_t)m_inserts,
                 (intmax_t)m_deletes,
                 (intmax_t)m_updates,
                 table.partitionColumn(),
                 skippedDirtyRows,
                 skippedInactiveRows);

        // If m_tuplesRemaining is not 0, we somehow corrupted the iterator. To make a best effort
        // at continuing unscathed, we will make sure all the blocks are back in the non-pending snapshot
        // lists and hope that the next snapshot handles everything correctly. We assume that the iterator
        // at least returned it's currentBlock to the lists.
        if (allPendingCnt > 0) {
            // We have orphaned or corrupted some tables. Let's make them pristine.
            TBMapI iter = m_surgeon.getData().begin();
            while (iter != m_surgeon.getData().end()) {
                m_surgeon.snapshotFinishedScanningBlock(iter.data(), TBPtr());
                iter++;
            }
        }
        if (!m_surgeon.blockCountConsistent()) {
            throwFatalException("%s", message);
        }
        else {
            LogManager::getThreadLogger(LOGGERID_HOST)->log(LOGLEVEL_ERROR, message);
            m_tuplesRemaining = 0;
            return false;
        }
    } else if (m_tuplesRemaining < 0)  {
        // -1 is used for tests when we don't bother counting. Need to force it to 0 here.
        m_tuplesRemaining = 0;
    }
    return true;
}

bool IndexCopyOnWriteContext::notifyTupleDelete(TableTuple &tuple) {
	PersistentTable &table = m_table;
	TableTuple conflict(table.schema());
	// Copy data
	m_backedUpTuples->insertTempTupleDeepCopy(tuple, &m_pool);
	// Add to delete tree

    m_deletes++;

    m_indexDeletes->addEntry(&tuple, &conflict);

    return true;
}

void IndexCopyOnWriteContext::notifyBlockWasCompactedAway(TBPtr block) {
    return;
}

bool IndexCopyOnWriteContext::notifyTupleInsert(TableTuple &tuple) {
	PersistentTable &table = m_table;
	TableTuple conflict(table.schema());
	// Add to insert tree
	m_indexInserts->addEntry(&tuple, &conflict);
    return true;
}

bool IndexCopyOnWriteContext::notifyTupleUpdate(TableTuple &tuple) {
	PersistentTable &table = m_table;
	TableTuple conflict(table.schema());
	// Copy data
	m_backedUpTuples->insertTempTupleDeepCopy(tuple, &m_pool);
	// Add to delete tree
	m_indexDeletes->addEntry(&tuple, &conflict);
	// Add new to insert tree
	m_indexInserts->addEntry(&tuple, &conflict);
    return true;
}

}
