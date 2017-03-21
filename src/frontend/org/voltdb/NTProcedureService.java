/* This file is part of VoltDB.
 * Copyright (C) 2008-2017 VoltDB Inc.
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

package org.voltdb;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.ArrayQueue;
import org.voltcore.messaging.Mailbox;
import org.voltcore.network.Connection;
import org.voltcore.utils.CoreUtils;
import org.voltdb.AuthSystem.AuthUser;
import org.voltdb.SystemProcedureCatalog.Config;
import org.voltdb.catalog.CatalogMap;
import org.voltdb.catalog.Procedure;

import com.google_voltpatches.common.collect.ImmutableMap;
import com.google_voltpatches.common.util.concurrent.ThreadFactoryBuilder;

public class NTProcedureService {

    static class PendingInvocation {
        AuthUser user;
        Connection ccxn;
        long clientHandle;
        boolean ntPriority;
        String procName;
        ParameterSet paramListIn;

        PendingInvocation(AuthUser user, Connection ccxn, long clientHandle,
                          boolean ntPriority, String procName, ParameterSet paramListIn)
        {
            this.user = user;
            this.ccxn = ccxn;
            this.clientHandle = clientHandle;
            this.ntPriority = ntPriority;
            this.procName = procName;
            this.paramListIn = paramListIn;
        }
    }

    // user procedures.
    ImmutableMap<String, ProcedureRunnerNTGenerator> m_procs = ImmutableMap.<String, ProcedureRunnerNTGenerator>builder().build();
    ImmutableMap<String, ProcedureRunnerNTGenerator> m_sysProcs = ImmutableMap.<String, ProcedureRunnerNTGenerator>builder().build();
    Map<Long, ProcedureRunnerNT> m_outstanding = new ConcurrentHashMap<>();
    final InternalConnectionHandler m_ich;
    final Mailbox m_mailbox;
    AtomicBoolean m_paused = new AtomicBoolean(false);
    Queue<PendingInvocation> m_pendingInvocations = new ArrayQueue<>();
    final Semaphore m_outstandingNTProcSemaphore = new Semaphore(10);

    final static String NTPROC_THREADPOOL_NAMEPREFIX = "NTPServiceThread-";
    final static String NTPROC_THREADPOOL_PRIORITY_SUFFIX = "Priority-";

    private final ExecutorService m_primaryExecutorService = new ThreadPoolExecutor(
            1,
            20,
            60,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new ThreadFactoryBuilder()
                .setNameFormat(NTPROC_THREADPOOL_NAMEPREFIX + "%d")
                .build());

    private final ExecutorService m_priorityExecutorService = Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                .setNameFormat(NTPROC_THREADPOOL_NAMEPREFIX + NTPROC_THREADPOOL_PRIORITY_SUFFIX + "%d")
                .build());

    long nextProcedureRunnerId = 0;

    class ProcedureRunnerNTGenerator {

        protected final String m_procedureName;
        protected final Class<? extends VoltNTProcedure> m_procClz;
        protected final Method m_procMethod;
        protected final Class<?>[] m_paramTypes;
        protected final ProcedureStatsCollector m_statsCollector;

        ProcedureRunnerNTGenerator(Class<? extends VoltNTProcedure> clz) {
            m_procClz = clz;
            m_procedureName = m_procClz.getSimpleName();

            // reflect
            Method procMethod = null;
            Class<?>[] paramTypes = null;

            Method[] methods = m_procClz.getDeclaredMethods();

            for (final Method m : methods) {
                String name = m.getName();
                if (name.equals("run")) {
                    if (Modifier.isPublic(m.getModifiers()) == false) {
                        continue;
                    }
                    procMethod = m;
                    paramTypes = m.getParameterTypes();
                }
            }

            m_procMethod = procMethod;
            m_paramTypes = paramTypes;

            m_statsCollector = VoltDB.instance().getStatsAgent().registerProcedureStatsSource(
                    CoreUtils.getSiteIdFromHSId(m_mailbox.getHSId()),
                    new ProcedureStatsCollector(
                            CoreUtils.getSiteIdFromHSId(m_mailbox.getHSId()),
                            0,
                            m_procClz.getName(),
                            false,
                            null,
                            false)
                    );
        }

        ProcedureRunnerNT generateProcedureRunnerNT(AuthUser user, Connection ccxn, long clientHandle)
                throws InstantiationException, IllegalAccessException
        {
            long id = nextProcedureRunnerId++;

            VoltNTProcedure procedure = null;
            procedure = m_procClz.newInstance();
            ProcedureRunnerNT runner = new ProcedureRunnerNT(id,
                                                             user,
                                                             ccxn,
                                                             clientHandle,
                                                             procedure,
                                                             m_procedureName,
                                                             m_procMethod,
                                                             m_paramTypes,
                                                             // use priority to avoid deadlocks
                                                             m_priorityExecutorService,
                                                             NTProcedureService.this,
                                                             m_mailbox,
                                                             m_statsCollector);
            return runner;
        }

    }

    NTProcedureService(InternalConnectionHandler ich, Mailbox mailbox)
    {
        assert(ich != null);
        m_ich = ich;
        m_mailbox = mailbox;

        m_sysProcs = loadSystemProcedures(null);
    }

    @SuppressWarnings("unchecked")
    private ImmutableMap<String, ProcedureRunnerNTGenerator> loadSystemProcedures(ProcedureRunnerNTGenerator uacPrntg) {
        ImmutableMap.Builder<String, ProcedureRunnerNTGenerator> builder =
                ImmutableMap.<String, ProcedureRunnerNTGenerator>builder();

        Set<Entry<String,Config>> entrySet = SystemProcedureCatalog.listing.entrySet();
        for (Entry<String, Config> entry : entrySet) {
            String procName = entry.getKey();
            Config sysProc = entry.getValue();

            // transactional sysprocs handled by LoadedProcedureSet
            if (sysProc.transactional) {
                continue;
            }

            final String className = sysProc.getClassname();
            Class<? extends VoltNTProcedure> procClass = null;

            // this check is for sysprocs that don't have a procedure class
            if (className != null) {
                try {
                    procClass = (Class<? extends VoltNTProcedure>) Class.forName(className);
                }
                catch (final ClassNotFoundException e) {
                    if (sysProc.commercial) {
                        continue;
                    }
                    VoltDB.crashLocalVoltDB("Missing Java class for NT System Procedure: " + procName);
                }

                // This is a startup-time check to make sure we can instantiate
                try {
                    if ((procClass.newInstance() instanceof VoltNTSystemProcedure) == false) {
                        VoltDB.crashLocalVoltDB("NT System Procedure is incorrect class type: " + procName);
                    }
                }
                catch (InstantiationException | IllegalAccessException e) {
                    VoltDB.crashLocalVoltDB("Unable to instantiate NT System Procedure: " + procName);
                }

                ProcedureRunnerNTGenerator prntg = new ProcedureRunnerNTGenerator(procClass);
                builder.put(procName, prntg);
            }
        }
        return builder.build();
    }

    synchronized void preUpdate() {
        m_paused.set(true);
    }

    @SuppressWarnings("unchecked")
    synchronized void update(CatalogContext catalogContext) {
        CatalogMap<Procedure> procedures = catalogContext.database.getProcedures();

        Map<String, ProcedureRunnerNTGenerator> runnerGeneratorMap = new TreeMap<>();

        for (Procedure procedure : procedures) {
            if (procedure.getTransactional()) {
                continue;
            }

            String className = procedure.getClassname();
            Class<? extends VoltNTProcedure> clz = null;
            try {
                clz = (Class<? extends VoltNTProcedure>) catalogContext.classForProcedure(className);
            } catch (ClassNotFoundException e) {
                if (className.startsWith("org.voltdb.")) {
                    String msg = String.format(LoadedProcedureSet.ORGVOLTDB_PROCNAME_ERROR_FMT, className);
                    VoltDB.crashLocalVoltDB(msg, false, null);
                }
                else {
                    String msg = String.format(LoadedProcedureSet.UNABLETOLOAD_ERROR_FMT, className);
                    VoltDB.crashLocalVoltDB(msg, false, null);
                }
            }

            ProcedureRunnerNTGenerator prntg = new ProcedureRunnerNTGenerator(clz);
            runnerGeneratorMap.put(procedure.getTypeName(), prntg);
        }

        m_procs = ImmutableMap.<String, ProcedureRunnerNTGenerator>builder().putAll(runnerGeneratorMap).build();

        loadSystemProcedures(m_sysProcs.get("@UpdateApplicationCatalog"));

        m_paused.set(false);

        // release all of the pending invocations
        m_pendingInvocations
            .forEach(pi -> callProcedureNT(pi.user, pi.ccxn, pi.clientHandle, pi.ntPriority, pi.procName, pi.paramListIn));
        m_pendingInvocations.clear();
    }

    synchronized ClientResponseImpl callProcedureNT(final AuthUser user,
                                       final Connection ccxn,
                                       final long clientHandle,
                                       final boolean ntPriority,
                                       final String procName,
                                       final ParameterSet paramListIn)
    {
        if (m_paused.get()) {
            PendingInvocation pi = new PendingInvocation(user, ccxn, clientHandle, ntPriority, procName, paramListIn);
            m_pendingInvocations.add(pi);
            return null;
        }

        final ProcedureRunnerNTGenerator prntg;
        if (procName.startsWith("@")) {
            prntg = m_sysProcs.get(procName);
        }
        else {
            prntg = m_procs.get(procName);
        }

        ProcedureRunnerNT tempRunner = null;
        try {
            tempRunner = prntg.generateProcedureRunnerNT(user, ccxn, clientHandle);
        } catch (InstantiationException | IllegalAccessException e1) {
            return new ClientResponseImpl(ClientResponseImpl.UNEXPECTED_FAILURE,
                    new VoltTable[0],
                    "Could not create running context for " + procName + ".",
                    clientHandle);
        }
        final ProcedureRunnerNT runner = tempRunner;
        m_outstanding.put(runner.m_id, runner);

        Runnable invocationRunnable = new Runnable() {
            @Override
            public void run() {
                ClientResponseImpl response = runner.call(paramListIn.toArray());
                // continuation
                if (response == null) {
                    return;
                }

                handleNTProcEnd(runner);
            }
        };

        try {
            if (ntPriority) {
                m_priorityExecutorService.submit(invocationRunnable);
            }
            else {
                m_primaryExecutorService.submit(invocationRunnable);
            }
        }
        catch (RejectedExecutionException e) {
            handleNTProcEnd(runner);

            return new ClientResponseImpl(ClientResponseImpl.UNEXPECTED_FAILURE,
                    new VoltTable[0],
                    "Could not submit NT procedure " + procName + " to exec service for .",
                    clientHandle);
        }

        return null;
    }

    void handleNTProcEnd(ProcedureRunnerNT runner) {
        m_outstanding.remove(runner.m_id);
    }

    void handleCallbacksForFailedHosts(final Set<Integer> failedHosts) {
        for (ProcedureRunnerNT runner : m_outstanding.values()) {
            runner.processAnyCallbacksFromFailedHosts(failedHosts);
        }
    }
}
