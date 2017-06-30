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

#ifndef SYNCHRONIZEDTHREADLOCK_H_
#define SYNCHRONIZEDTHREADLOCK_H_

//#include "boost/scoped_ptr.hpp"
//#include "boost/unordered_map.hpp"

#include <cassert>
#include <map>
#include <stack>
#include <string>
#include <vector>
#include <atomic>
#include <pthread.h>
#include <atomic>

namespace voltdb {
struct EngineLocals;
class UndoQuantum;
class UndoAction;
class UndoQuantumReleaseInterest;
typedef std::map<int32_t, EngineLocals> SharedEngineLocalsType;

class SynchronizedThreadLock {
public:
    static void create();
    static void destroy();
    static void init(int32_t sitesPerHost, EngineLocals& newEngineLocals);
    /**
     * Cross-site synchronization functions
     */
    static bool countDownGlobalTxnStartCount(bool lowestSite);
    static void signalLowestSiteFinished();

    static void lockReplicatedResource();
    static void unlockReplicatedResource();

    static void addUndoAction(bool replicated, UndoQuantum *uq, UndoAction* action,
            UndoQuantumReleaseInterest *interest = NULL);

    static bool isInRepTableContext();
private:
    static bool s_inMpContext;
    static pthread_mutex_t s_sharedEngineMutex;
    static pthread_cond_t s_sharedEngineCondition;
    static pthread_cond_t s_wakeLowestEngineCondition;
    static int32_t s_globalTxnStartCountdownLatch;
    static int32_t s_SITES_PER_HOST;
public:
    static SharedEngineLocalsType s_enginesByPartitionId;
};

}


#endif
