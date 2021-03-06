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
package swift.client;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import swift.clocks.CausalityClock;
import swift.clocks.Timestamp;
import swift.crdt.core.CRDTIdentifier;
import swift.crdt.core.ManagedCRDT;
import swift.utils.DatabaseSizeStats;

/**
 * Local cache of CRDT objects with LRU eviction policy. Elements get evicted
 * when not used for a defined period of time or size of the cache is exceeded.
 * <p>
 * Thread unsafe (requires external synchronization).
 * 
 * @author smduarte, mzawirski
 */
class LRUObjectsCache {

    public static interface EvictionListener {
        void onEviction(CRDTIdentifier id);
    }

    private static Logger logger = Logger.getLogger(LRUObjectsCache.class.getName());

    private final int maxElements;
    private final long evictionTimeMillis;
    private Map<CRDTIdentifier, Entry> entries;
    // TODO: what are shadow entries for?
    private Map<CRDTIdentifier, Entry> shadowEntries;
    private Set<Long> evictionProtections;
    private DatabaseSizeStats stats;

    private EvictionListener evictionListener = new EvictionListener() {
        @Override
        public void onEviction(CRDTIdentifier id) {
        }
    };

    synchronized public void removeProtection(long serial) {
        evictionProtections.remove(serial);
        evictExcess();
        evictOutdated();
    }

    /**
     * @param evictionTimeMillis
     *            maximum life-time for object entries (exclusive) in
     *            milliseconds
     */
    @SuppressWarnings("serial")
    public LRUObjectsCache(final long evictionTimeMillis, final int maxElements, final DatabaseSizeStats sizeStats) {

        this.evictionTimeMillis = evictionTimeMillis;
        this.maxElements = maxElements;
        this.stats = sizeStats;

        entries = new LinkedHashMap<CRDTIdentifier, Entry>(32, 0.75f, true) {
            protected boolean removeEldestEntry(Map.Entry<CRDTIdentifier, Entry> eldest) {
                Entry e = eldest.getValue();
                if (!evictionProtections.contains(e.id()) && size() > maxElements) {
                    shadowEntries.remove(eldest.getKey());
                    handleEvicted(eldest.getKey());

                    // System.err.println(eldest.getKey() +
                    // " evicted from the cache due to size limit, acesses:"
                    // + e.getNumberOfAccesses());

                    logger.info("Object evicted from the cache due to size limit, acesses:" + e.getNumberOfAccesses());
                    return true;
                } else
                    return false;
            }
        };
        shadowEntries = new HashMap<CRDTIdentifier, Entry>();
        evictionProtections = new HashSet<Long>();
    }

    void setEvictionListener(EvictionListener evictionListener) {
        this.evictionListener = evictionListener;
    }

    private void handleEvicted(CRDTIdentifier evicted) {
        evictionListener.onEviction(evicted);
        stats.removeObject(evicted);
    }

    /**
     * Adds object to the cache, possibly overwriting old entry. May cause
     * evictoin due to size limit in the cache.
     * 
     * @param object
     *            object to add
     */
    synchronized public void add(final ManagedCRDT<?> object, long txnSerial) {
        if (txnSerial >= 0)
            evictionProtections.add(txnSerial);

        Entry e = new Entry(object, txnSerial);
        entries.put(object.getUID(), e);
        shadowEntries.put(object.getUID(), e);
        stats.updateObject(object.getUID(), object);
    }

    /**
     * Returns object for given id and records access to the cache.
     * 
     * @param id
     *            object id
     * @return object or null if object is absent in the cache
     */
    synchronized public ManagedCRDT<?> getAndTouch(final CRDTIdentifier id) {
        final Entry entry = entries.get(id);
        if (entry == null) {
            return null;
        }
        entry.touch();
        return entry.getObject();
    }

    /**
     * Returns object for given id without recording access to the cache (in
     * terms of eviction policy).
     * 
     * @param id
     *            object id
     * @return object or null if object is absent in the cache
     */
    synchronized public ManagedCRDT<?> getWithoutTouch(final CRDTIdentifier id) {
        final Entry entry = shadowEntries.get(id);
        return entry == null ? null : entry.getObject();
    }

    public synchronized List<ManagedCRDT> getAllWithoutTouch() {
        final List<ManagedCRDT> result = new LinkedList<>();
        for (final Entry entry : shadowEntries.values()) {
            result.add(entry.getObject());
        }
        return result;
    }

    public synchronized void markUpdatedWithoutTouch(final CRDTIdentifier id, boolean clocksOnly) {
        final ManagedCRDT<?> crdt = getWithoutTouch(id);
        if (crdt != null) {
            updateStats(id, crdt, clocksOnly);
        }
    }

    private void updateStats(final CRDTIdentifier id, final ManagedCRDT<?> crdt, boolean clocksOnly) {
        if (clocksOnly) {
            // TODO: record it depending on a configuration flag?
        } else {
            stats.updateObject(id, crdt);
        }
    }

    /**
     * Evicts all objects that have not been accessed for over
     * evictionTimeMillis specified for this cache.
     */
    private void evictExcess() {
        int evictedObjects = 0;
        int excess = entries.size() - maxElements;
        for (Iterator<Map.Entry<CRDTIdentifier, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            if (evictedObjects < excess) {
                Map.Entry<CRDTIdentifier, Entry> e = it.next();
                final Entry val = e.getValue();
                if (!evictionProtections.contains(val.id())) {
                    it.remove();
                    evictedObjects++;
                    shadowEntries.remove(e.getKey());
                    handleEvicted(e.getKey());
                    // System.err.println( e.getKey() +
                    // " evicted from the cache due to size limit, acesses:" +
                    // e.getValue().getNumberOfAccesses());
                }
            } else
                break;
        }
        if (evictedObjects > 0) {
            // System.err.printf("Objects evicted from the cache due to excessive size: %s / %s / %s\n",
            // evictedObjects,
            // entries.size(), maxElements);
            logger.info(evictedObjects + " objects evicted from the cache due to timeout");
        }
    }

    /**
     * Evicts all objects that have not been accessed for over
     * evictionTimeMillis specified for this cache.
     */
    private void evictOutdated() {
        long now = System.currentTimeMillis();
        final long evictionThreashold = now - evictionTimeMillis;

        int evictedObjects = 0;
        for (Iterator<Map.Entry<CRDTIdentifier, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            Map.Entry<CRDTIdentifier, Entry> e = it.next();
            final Entry entry = e.getValue();
            if (entry.getLastAcccessTimeMillis() <= evictionThreashold) {
                it.remove();
                evictedObjects++;
                shadowEntries.remove(e.getKey());
                handleEvicted(e.getKey());
            } else {
                break;
            }
        }
        // if (evictedObjects > 0)
        // System.err.println(evictedObjects +
        // " objects evicted from the cache due to timeout");

        logger.info(evictedObjects + " objects evicted from the cache due to timeout");
    }

    // TODO: update stats in this case or not?
    synchronized void augmentAllWithDCCausalClockWithoutMappings(final CausalityClock causalClock) {
        for (final Entry entry : entries.values()) {
            entry.object.augmentWithDCClockWithoutMappings(causalClock);
            updateStats(entry.object.getUID(), entry.object, true);
        }
    }

    // synchronized void pruneAll(CausalityClock nextPruneClock) {
    // for (final Entry entry : entries.values()) {
    // entry.object.prune(nextPruneClock, true);
    // }
    // }

    synchronized void augmentAllWithScoutTimestampWithoutMappings(Timestamp clientTimestamp) {
        for (final Entry entry : entries.values()) {
            entry.object.augmentWithScoutTimestamp(clientTimestamp);
            updateStats(entry.object.getUID(), entry.object, true);
        }
    }

    synchronized void printStats() {
        SortedSet<Entry> se = new TreeSet<Entry>(entries.values());
        for (Entry i : se)
            System.err.println(i.object.getUID() + "/" + i.accesses);
    }

    static AtomicLong g_serial = new AtomicLong();

    private final class Entry implements Comparable<Entry> {
        private final ManagedCRDT<?> object;
        private long lastAccessTimeMillis;
        private long accesses;
        private long txnId;
        private long serial = g_serial.incrementAndGet();

        public Entry(final ManagedCRDT<?> object, long txnId) {
            this.object = object;
            this.txnId = txnId;
            touch();
        }

        public long id() {
            return txnId;
        }

        public ManagedCRDT<?> getObject() {
            return object;
        }

        public long getLastAcccessTimeMillis() {
            return lastAccessTimeMillis;
        }

        public long getNumberOfAccesses() {
            return accesses;
        }

        public void touch() {
            accesses++;
            lastAccessTimeMillis = System.currentTimeMillis();
        }

        @Override
        public int compareTo(Entry other) {
            if (accesses == other.accesses)
                return serial < other.serial ? -1 : 1;
            else
                return accesses < other.accesses ? -1 : 1;
        }
    }
}
