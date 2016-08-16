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
		int32_t partitionId,
        int64_t totalTuples) :
				TableStreamerContext(table, surgeon, partitionId),
             m_backedUpTuples(TableFactory::buildCopiedTempTable("COW of " + table.name() + " " + index.getName(),
                                                                 &table, NULL)),
             m_table(table),
             m_surgeon(surgeon),
			 m_index(index),
			 m_indexInserts(TableIndexFactory::cloneEmptyTreeIndex(index)),
			 m_indexDeletes(TableIndexFactory::cloneEmptyTreeIndex(index)),
			 m_indexCursor(index.getTupleSchema()),
			 m_insertsCursor(index.getTupleSchema()),
			 m_deletesCursor(index.getTupleSchema()),
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
TableStreamerContext::ActivationReturnCode
IndexCopyOnWriteContext::handleActivation(TableStreamType streamType)
{
	std::cout << "IndexCopyOnWriteContext::handleActivation" << std::endl;
    if (m_finished && m_tuplesRemaining == 0) {
        return ACTIVATION_FAILED;
    }
    //m_surgeon.activateSnapshot();

    //m_iterator.reset(new CopyOnWriteIterator(&m_table, &m_surgeon));
    return ACTIVATION_SUCCEEDED;
}

bool
IndexCopyOnWriteContext::adjustCursors(int type) {
	std::cout << "IndexCopyOnWriteContext::adjustCursors " << type << std::endl;
	m_indexLookupType = static_cast<IndexLookupType>(type);
	TableTuple tuple;

	//XXX do we need to lookup equal keys?
	//XXX what if m_tuple has not been initialized?

	// Taken in large part from indexScanExecutor
    if (m_indexLookupType == INDEX_LOOKUP_TYPE_EQ ||
    		m_indexLookupType == INDEX_LOOKUP_TYPE_GT ||
			m_indexLookupType == INDEX_LOOKUP_TYPE_GTE
    		) {
    	// In tests we may not have initialized the cursors
    	if (m_tuple.isNullTuple()) {
    		std::cout << "green" << std::endl;
    		m_index.moveToEnd(true, m_indexCursor);
    		std::cout << "eggs" << std::endl;
    		m_indexDeletes->moveToEnd(true, m_deletesCursor);
    		std::cout << "ham" << std::endl;
    	}
    	else {
    		/*
        	std::cout << "hello" << std::endl;
    		m_index.moveToGreaterThanKey(&m_tuple, m_indexCursor);
    		std::cout << "world" << std::endl;
    		m_indexDeletes->moveToGreaterThanKey(&m_tuple, m_deletesCursor);
    		std::cout << "done" << std::endl;
    		*/
        	std::cout << "hello" << std::endl;
    		m_index.moveToKeyByTuple(&m_tuple, m_indexCursor);
    		std::cout << "world" << std::endl;
    		m_indexDeletes->moveToKeyByTuple(&m_tuple, m_deletesCursor);
    		std::cout << "done" << std::endl;
    	}
    }
    else if (m_indexLookupType == INDEX_LOOKUP_TYPE_LT ||
    		m_indexLookupType == INDEX_LOOKUP_TYPE_LTE
    		) {
    	if (m_tuple.isNullTuple()) {
    		m_index.moveToEnd(false, m_indexCursor);
    		m_indexDeletes->moveToEnd(false, m_deletesCursor);
    	}
    	else {
    		m_index.moveToLessThanKey(&m_tuple, m_indexCursor);
    		m_indexDeletes->moveToLessThanKey(&m_tuple, m_deletesCursor);
    	}
    }
    else if (m_indexLookupType == INDEX_LOOKUP_TYPE_GEO_CONTAINS) {
    	// moveToCoveringCell  finds the exact tuple... so we need a way to find the next one m_tuple should be at
    	// also, the exact tuple will either be in m_index or m_indexDeletes, but not both, so we need to find
    	// a way to get to the "next" value
		m_index.moveToCoveringCell(&m_tuple, m_indexCursor);
		m_indexDeletes->moveToCoveringCell(&m_tuple, m_deletesCursor);
    }
    return true;
}

/**
 * Advance the COW iterator and return the next tuple
 */
bool IndexCopyOnWriteContext::advanceIterator(TableTuple &tuple) {
	PersistentTable &table = m_table;
	std::cout << "advanceIterator remaining " << m_tuplesRemaining << std::endl;
	// Compare cursors and start from the lowest between deletes and current index
	TableTuple deletesTuple(table.schema());
	TableTuple indexTuple(table.schema());
	deletesTuple = m_indexDeletes->currentValue(m_deletesCursor);
	indexTuple = m_index.currentValue(m_indexCursor);
	while (!indexTuple.isNullTuple() || !deletesTuple.isNullTuple()) {
		if (indexTuple.isNullTuple() || m_indexDeletes->compare(&indexTuple, m_deletesCursor) > 0) {
			std::cout << "advanceIterator deletes1 " << deletesTuple.debugNoHeader() << std::endl;
			// found the next tuple to scan in the delete records...return it
			if (m_indexLookupType == INDEX_LOOKUP_TYPE_EQ
		            || m_indexLookupType == INDEX_LOOKUP_TYPE_GEO_CONTAINS) {
				deletesTuple = m_indexDeletes->nextValueAtKey(m_deletesCursor);
			}
			else {
				deletesTuple = m_indexDeletes->nextValue(m_deletesCursor);
			}
			m_tuple = m_indexDeletes->currentValue(m_deletesCursor);

			if (m_indexInserts->hasKey(&indexTuple)) {
				std::cout << "found key in inserts" << std::endl;
				deletesTuple = m_indexDeletes->currentValue(m_deletesCursor);
				continue;
			}
			std::cout << "advanceIterator deletes2 " << deletesTuple.debugNoHeader() << std::endl;
			m_tuplesRemaining--;
			tuple = m_tuple;
			return true;
		}
		else {
			// found the next tuple to scan in the normal index.
			// check if this tuple can be found in the insert keys
			std::cout << "advanceIterator index1 " << indexTuple.debugNoHeader() << std::endl;
			if (m_indexLookupType == INDEX_LOOKUP_TYPE_EQ
		            || m_indexLookupType == INDEX_LOOKUP_TYPE_GEO_CONTAINS) {
				indexTuple = m_index.nextValueAtKey(m_indexCursor);
			}
			else {
				indexTuple = m_index.nextValue(m_indexCursor);
			}
			m_tuple = m_index.currentValue(m_indexCursor);
			if (!indexTuple.isNullTuple()) {
				std::cout << "advanceIterator index2 " << indexTuple.debugNoHeader() << std::endl;
			}
			else {
				std::cout << "advanceIterator index2 NULL" << std::endl;
			}
			if (m_indexInserts->exists(&indexTuple)) {
				std::cout << "found key in inserts" << std::endl;
				indexTuple = m_index.currentValue(m_indexCursor);
				continue;
			}

			std::cout << "advanceIterator index3" << std::endl;
			m_tuplesRemaining--;
			tuple = m_tuple;
			return true;
		}

		break;
	}
	m_finished = true;
	std::cout << "advanceIterator DONE" << std::endl;
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
	std::cout << "notifyTupleDelete " << tuple.debugNoHeader() << std::endl;
	PersistentTable &table = m_table;
	TableTuple conflict(table.schema());

    m_deletes++;

    if (!m_indexInserts->exists(&tuple)) {
    	// Copy data
    	m_backedUpTuples->insertTempTupleDeepCopy(tuple, &m_pool);
    	// Add to delete tree
    	m_indexDeletes->addEntry(&tuple, &conflict);
    }

    return true;
}

void IndexCopyOnWriteContext::notifyBlockWasCompactedAway(TBPtr block) {
    return;
}

bool IndexCopyOnWriteContext::notifyTupleInsert(TableTuple &tuple) {
	std::cout << "notifyTupleInsert " << tuple.debugNoHeader() << std::endl;
	PersistentTable &table = m_table;
	TableTuple conflict(table.schema());
	// Add to insert tree
	m_indexInserts->addEntry(&tuple, &conflict);
    return true;
}

bool IndexCopyOnWriteContext::notifyTupleUpdate(TableTuple &tuple) {
	PersistentTable &table = m_table;
	TableTuple conflict(table.schema());
	std::cout << "notifyTupleUpdate " << tuple.debugNoHeader() << std::endl;
	// Copy data
	m_backedUpTuples->insertTempTupleDeepCopy(tuple, &m_pool);
	// Add to delete tree
	m_indexDeletes->addEntry(&tuple, &conflict);
	// Add new to insert tree
	m_indexInserts->addEntry(&tuple, &conflict);
    return true;
}

}
