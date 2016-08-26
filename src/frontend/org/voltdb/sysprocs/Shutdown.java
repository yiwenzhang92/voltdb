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

package org.voltdb.sysprocs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.voltcore.logging.VoltLogger;
import org.voltcore.utils.CoreUtils;
import org.voltcore.utils.Pair;
import org.voltdb.DependencyPair;
import org.voltdb.OperationMode;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.SystemProcedureExecutionContext;
import org.voltdb.VoltDB;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.VoltType;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.export.ExportManager;
import org.voltdb.importer.ImportManager;

/**
 * A wholly improper shutdown. No promise is given to return a result to a client,
 * to finish work queued behind this procedure or to return meaningful errors for
 * those queued transactions.
 *
 * Invoking this procedure immediately attempts to terminate each node in the cluster.
 */
@ProcInfo(singlePartition = false)
public class Shutdown extends VoltSystemProcedure {

    static final VoltLogger LOGGER =  new VoltLogger("HOST");
    private static final int DEP_shutdownSync = (int) SysProcFragmentId.PF_shutdownSync
            | DtxnConstants.MULTIPARTITION_DEPENDENCY;
    private static final int DEP_shutdownSyncDone = (int) SysProcFragmentId.PF_shutdownSyncDone;

    private static AtomicBoolean m_failsafeArmed = new AtomicBoolean(false);
    private static CountDownLatch m_shutdownLatch = new CountDownLatch(1);
    private static Thread m_failsafe = new Thread() {
        @Override
        public void run() {
            try {
                m_shutdownLatch.await();
                Thread.sleep(10000);
            } catch (InterruptedException e) {}
            LOGGER.warn("VoltDB shutting down as requested by @Shutdown command.");
            System.exit(0);
        }
    };

    @Override
    public void init() {
        registerPlanFragment(SysProcFragmentId.PF_shutdownSync);
        registerPlanFragment(SysProcFragmentId.PF_shutdownSyncDone);
        registerPlanFragment(SysProcFragmentId.PF_shutdownCommand);
        registerPlanFragment(SysProcFragmentId.PF_procedureDone);
    }

    @Override
    public DependencyPair executePlanFragment(Map<Integer, List<VoltTable>> dependencies,
                                           long fragmentId,
                                           ParameterSet params,
                                           SystemProcedureExecutionContext context)
    {
        if (fragmentId == SysProcFragmentId.PF_shutdownSync) {
            VoltDB.instance().getHostMessenger().prepareForShutdown();
            if (!m_failsafeArmed.getAndSet(true)) {
                m_failsafe.start();
                if("true".equals(params.toArray()[0])) {
                    m_shutdownLatch.countDown();
                }
                LOGGER.warn("VoltDB shutdown operation requested and in progress.  Cluster will terminate shortly.");
            }
            return new DependencyPair(DEP_shutdownSync,
                    new VoltTable(new ColumnInfo[] { new ColumnInfo("HA", VoltType.STRING) } ));
        }
        else if (fragmentId == SysProcFragmentId.PF_shutdownSyncDone) {
            return new DependencyPair(DEP_shutdownSyncDone,
                    new VoltTable(new ColumnInfo[] { new ColumnInfo("HA", VoltType.STRING) } ));
        }
        else if (fragmentId == SysProcFragmentId.PF_shutdownCommand) {
            Thread shutdownThread = new Thread() {
                @Override
                public void run() {
                    VoltDB.instance().setMode(OperationMode.SHUTTINGDOWN);
                    boolean forceShutdown = "true".equals(params.toArray()[0]);
                    try {
                        if(context.isLowestSiteId()){
                            LOGGER.info("Shutting down importers and its connectors if there are any...");
                            ImportManager.instance().shutdown();

                            LOGGER.info("Shutting down exporters and its connectors if there are any...");
                            ExportManager.instance().shutdown();

                            if (!forceShutdown) {
                                //flush any buffer in the queue
                                context.getSiteProcedureConnection().quiesce();
                               // flushPendingTransactions();
                            }
                        }

                        if (VoltDB.instance().shutdown(this)){
                            LOGGER.warn("VoltDB shutting down as requested by @Shutdown command.");
                            System.exit(0);
                        }
                    } catch (InterruptedException e) {
                        LOGGER.error("Exception while attempting to shutdown VoltDB from shutdown sysproc",e);
                    } finally {
                        if (!forceShutdown) {
                            m_shutdownLatch.countDown();
                        }
                    }
                }
            };
            shutdownThread.start();
        }
        return null;
    }

    /**
     * Begin an un-graceful shutdown.
     * @param ctx Internal parameter not exposed to the end-user.
     * @return Never returned, no he never returned...
     */
    public VoltTable[] run(SystemProcedureExecutionContext ctx) {
        SynthesizedPlanFragment pfs[] = new SynthesizedPlanFragment[2];
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_shutdownSync;
        pfs[0].outputDepId = DEP_shutdownSync;
        pfs[0].inputDepIds = new int[]{};
        pfs[0].multipartition = true;
        pfs[0].parameters = ParameterSet.fromArrayNoCopy("false"); //temp flag for force shutdown. will get it from CLI eventually

        pfs[1] = new SynthesizedPlanFragment();
        pfs[1].fragmentId = SysProcFragmentId.PF_shutdownSyncDone;
        pfs[1].outputDepId = DEP_shutdownSyncDone;
        pfs[1].inputDepIds = new int[] { DEP_shutdownSync };
        pfs[1].multipartition = false;
        pfs[1].parameters = ParameterSet.fromArrayNoCopy("false");

        executeSysProcPlanFragments(pfs, DEP_shutdownSyncDone);

        pfs = new SynthesizedPlanFragment[1];
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_shutdownCommand;
        pfs[0].outputDepId = (int) SysProcFragmentId.PF_procedureDone | DtxnConstants.MULTIPARTITION_DEPENDENCY;
        pfs[0].inputDepIds = new int[]{};
        pfs[0].multipartition = true;
        pfs[0].parameters = ParameterSet.fromArrayNoCopy("false");

        executeSysProcPlanFragments(pfs, (int) SysProcFragmentId.PF_procedureDone);
        return new VoltTable[0];
    }

    /**
     * Class to check if there are any outstanding transactions by counting remaining bytes, pending response message
     * and transactions
     */
    class OutstandingTransactionChecker implements Runnable {

        final AtomicBoolean m_transactionComplete;
        OutstandingTransactionChecker(AtomicBoolean transactionComplete) {
            m_transactionComplete = transactionComplete;
        }

        @Override
        public void run() {
            final Map<Long, Pair<String, long[]>> stats = VoltDB.instance().getClientInterface().getLiveClientStats();
            long byteCount = 0, respCount = 0, tranCount = 0;
            for (Map.Entry<Long, Pair<String, long[]>> entry : stats.entrySet()) {
                final long[] counters = entry.getValue().getSecond();
                byteCount += counters[1];
                respCount += counters[2];
                tranCount += counters[3];
            }

            LOGGER.warn(String.format("Outstanding transactions info: request bytes=%d, response messages=%d, transactions=%d.",
                     byteCount, respCount, tranCount));
            m_transactionComplete.set(byteCount == 0 && respCount == 0 && tranCount == 0);
        }
    }

    private void flushPendingTransactions() {

        LOGGER.info("Server is in shutting down mode. Checking outstanding transactions...");
        final ScheduledThreadPoolExecutor es = CoreUtils.getScheduledThreadPoolExecutor("Outstanding-Transaction-Flush", 1, CoreUtils.SMALL_STACK_SIZE);
        final AtomicBoolean transactionComplete = new AtomicBoolean(false);
        es.execute(new OutstandingTransactionChecker(transactionComplete));
        while (!transactionComplete.get()) {
            final ScheduledFuture<?> future = es.schedule(new OutstandingTransactionChecker(transactionComplete) , 100, TimeUnit.MILLISECONDS);
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                LOGGER.error("Errors while checking outstanding transactions", e);
            }
        }
        es.shutdown();
        LOGGER.info("Outstanding transactions were flushed.");
    }
}
