/*****************************************************************************
 * Copyright 2011-2012 INRIA
 * Copyright 2011-2012 Universidade Nova de Lisboa
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
 *****************************************************************************/
package swift.crdt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import swift.clocks.CausalityClock;
import swift.clocks.TripleTimestamp;
import swift.crdt.interfaces.TxnHandle;
import swift.crdt.interfaces.TxnLocalCRDT;
import swift.utils.PrettyPrint;

/**
 * CRDT set with versioning support. WARNING: When constructing txn-local
 * versions of sets, make sure that the elements in the set are either immutable
 * or that they are cloned!
 * 
 * @author vb, annettebieniusa, mzawirsk
 * 
 * @param <V>
 */
public class SetVersioned<V> extends AbstractSetVersioned<V, SetVersioned<V>> {

    private static final long serialVersionUID = 1L;
    protected Map<V, Map<TripleTimestamp, Set<TripleTimestamp>>> elems;

    public SetVersioned() {
        elems = new HashMap<V, Map<TripleTimestamp, Set<TripleTimestamp>>>();
    }

    public Map<V, Set<TripleTimestamp>> getValue(CausalityClock snapshotClock) {
        return AddWinsUtils.getValue(this.elems, snapshotClock);
    }

    public void insertU(V e, TripleTimestamp uid) {
        Map<TripleTimestamp, Set<TripleTimestamp>> entry = elems.get(e);
        // if element not present in the set, add entry for it in payload
        if (entry == null) {
            entry = new HashMap<TripleTimestamp, Set<TripleTimestamp>>(1);
            elems.put(e, entry);
        }
        entry.put(uid, new HashSet<TripleTimestamp>(1));
        registerTimestampUsage(uid);
    }

    public void removeU(V e, TripleTimestamp uid, Set<TripleTimestamp> set) {
        Map<TripleTimestamp, Set<TripleTimestamp>> s = elems.get(e);
        if (s == null) {
            return;
        }

        for (TripleTimestamp ts : set) {
            Set<TripleTimestamp> removals = s.get(ts);
            if (removals != null) {
                removals.add(uid);
                registerTimestampUsage(uid);
            }
            // else: element uid has been already removed&pruned
        }
    }

    @Override
    protected boolean mergePayload(SetVersioned<V> other) {
        final List<TripleTimestamp> newTimestampUsages = new LinkedList<TripleTimestamp>();
        final List<TripleTimestamp> releasedTimestampUsages = new LinkedList<TripleTimestamp>();
        AddWinsUtils.mergePayload(this.elems, this.getClock(), other.elems, other.getClock(), newTimestampUsages,
                releasedTimestampUsages);
        for (final TripleTimestamp ts : newTimestampUsages) {
            registerTimestampUsage(ts);
        }
        for (final TripleTimestamp ts : releasedTimestampUsages) {
            unregisterTimestampUsage(ts);
        }
        return false;
    }

    @Override
    public String toString() {
        return PrettyPrint.printMap("{", "}", ";", "->", elems);
    }

    @Override
    protected void pruneImpl(CausalityClock pruningPoint) {
        final List<TripleTimestamp> releasedTimestampUsages = AddWinsUtils.pruneImpl(this.elems, pruningPoint);
        for (final TripleTimestamp ts : releasedTimestampUsages) {
            unregisterTimestampUsage(ts);
        }
    }

    protected void copyLoad(SetVersioned<V> copy) {
        copy.elems = new HashMap<V, Map<TripleTimestamp, Set<TripleTimestamp>>>();
        for (final Entry<V, Map<TripleTimestamp, Set<TripleTimestamp>>> e : this.elems.entrySet()) {
            final V v = e.getKey();
            final Map<TripleTimestamp, Set<TripleTimestamp>> subMap = new HashMap<TripleTimestamp, Set<TripleTimestamp>>();
            for (Entry<TripleTimestamp, Set<TripleTimestamp>> subEntry : e.getValue().entrySet()) {
                subMap.put(subEntry.getKey(), new HashSet<TripleTimestamp>(subEntry.getValue()));
            }
            copy.elems.put(v, subMap);
        }
    }

    @Override
    protected TxnLocalCRDT<SetVersioned<V>> getTxnLocalCopyImpl(CausalityClock versionClock, TxnHandle txn) {
        // TODO Auto-generated method stub
        return null;
    }

}
