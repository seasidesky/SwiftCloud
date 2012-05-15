package swift.client;

import static sys.net.api.Networking.Networking;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import swift.client.proto.CommitUpdatesReply;
import swift.client.proto.CommitUpdatesReply.CommitStatus;
import swift.client.proto.CommitUpdatesReplyHandler;
import swift.client.proto.CommitUpdatesRequest;
import swift.client.proto.FastRecentUpdatesReply;
import swift.client.proto.FastRecentUpdatesReply.ObjectSubscriptionInfo;
import swift.client.proto.FastRecentUpdatesReply.SubscriptionStatus;
import swift.client.proto.FastRecentUpdatesReplyHandler;
import swift.client.proto.FastRecentUpdatesRequest;
import swift.client.proto.FetchObjectDeltaRequest;
import swift.client.proto.FetchObjectVersionReply;
import swift.client.proto.FetchObjectVersionReply.FetchStatus;
import swift.client.proto.FetchObjectVersionReplyHandler;
import swift.client.proto.FetchObjectVersionRequest;
import swift.client.proto.GenerateTimestampReply;
import swift.client.proto.GenerateTimestampReplyHandler;
import swift.client.proto.GenerateTimestampRequest;
import swift.client.proto.LatestKnownClockReply;
import swift.client.proto.LatestKnownClockReplyHandler;
import swift.client.proto.LatestKnownClockRequest;
import swift.client.proto.SubscriptionType;
import swift.client.proto.UnsubscribeUpdatesRequest;
import swift.clocks.CausalityClock;
import swift.clocks.CausalityClock.CMP_CLOCK;
import swift.clocks.ClockFactory;
import swift.clocks.IncrementalTimestampGenerator;
import swift.clocks.Timestamp;
import swift.crdt.BaseCRDT;
import swift.crdt.CRDTIdentifier;
import swift.crdt.interfaces.CRDT;
import swift.crdt.interfaces.CRDTOperationDependencyPolicy;
import swift.crdt.interfaces.CachePolicy;
import swift.crdt.interfaces.IsolationLevel;
import swift.crdt.interfaces.ObjectUpdatesListener;
import swift.crdt.interfaces.Swift;
import swift.crdt.interfaces.TxnLocalCRDT;
import swift.crdt.interfaces.TxnStatus;
import swift.crdt.operations.CRDTObjectOperationsGroup;
import swift.exceptions.NetworkException;
import swift.exceptions.NoSuchObjectException;
import swift.exceptions.SwiftException;
import swift.exceptions.VersionNotFoundException;
import swift.exceptions.WrongTypeException;
import swift.utils.CallableWithDeadline;
import swift.utils.ExponentialBackoffTaskExecutor;
import sys.net.api.Endpoint;
import sys.net.api.rpc.RpcEndpoint;
import sys.net.api.rpc.RpcHandle;

/**
 * Implementation of Swift client and transactions manager.
 * 
 * @see Swift, TxnManager
 * @author mzawirski
 */
public class SwiftImpl implements Swift, TxnManager {
    // TODO: server failover

    // WISHME: This class uses very coarse-grained locking, but given the
    // complexity of causality tracking and timestamps remapping, unless we
    // prove it is a real issue for a client application, I would rather keep it
    // this way. In any case, locking should not affect responsiveness to
    // pendingTxn requests.

    // WISHME: decouple "object store" from the rest of transactions and
    // notifications processing

    // WISHME: subscribe updates of frequently accessed objects

    public static final int RPC_RETRY_WAIT_TIME_MULTIPLIER = 2;
    public static final int INIT_RPC_RETRY_WAIT_TIME_MILLIS = 10;
    // The two above yield the following sequence of wait times: 10, 20, 40...
    public static final int DEFAULT_TIMEOUT_MILLIS = 20 * 1000;
    public static final int DEFAULT_DEADLINE_MILLIS = DEFAULT_TIMEOUT_MILLIS;
    public static final int DEFAULT_NOTIFICATION_TIMEOUT_MILLIS = 2 * 60 * 1000;
    public static final long DEFAULT_CACHE_EVICTION_MILLIS = 60 * 1000;
    public static final long BACKOFF_WAIT_TIME_MULTIPLIER = 2;
    private static final String CLIENT_CLOCK_ID = "client";
    private static Logger logger = Logger.getLogger(SwiftImpl.class.getName());

    /**
     * Creates new instance of Swift using provided network settings and
     * otherwise default settings.
     * 
     * @param serverHostname
     *            hostname of storage server
     * @param serverPort
     *            TCP port of storage server
     * @return instance of Swift client
     */
    public static SwiftImpl newInstance(String serverHostname, int serverPort) {
        return newInstance(serverHostname, serverPort, DEFAULT_TIMEOUT_MILLIS, DEFAULT_DEADLINE_MILLIS,
                DEFAULT_CACHE_EVICTION_MILLIS);
    }

    /**
     * Creates new instance of Swift using provided network and timeout settings
     * and default cache parameters.
     * 
     * @param serverHostname
     *            hostname of storage server
     * @param serverPort
     *            TCP port of storage server
     * @param timeoutMillis
     *            socket-level timeout for server replies in milliseconds
     * @param deadlineMillis
     *            deadline for fulfilling user-triggered requests (get, refresh
     *            etc)
     * @param cacheEvictionTimeMillis
     *            eviction time for non-accessed objects in the cache
     * @return instance of Swift client
     */
    public static SwiftImpl newInstance(String serverHostname, int serverPort, int timeoutMillis, int deadlineMillis,
            long cacheEvictionTimeMillis) {
        return new SwiftImpl(Networking.rpcBind(0, null), Networking.resolve(serverHostname, serverPort),
                new TimeBoundedObjectsCache(cacheEvictionTimeMillis), timeoutMillis,
                DEFAULT_NOTIFICATION_TIMEOUT_MILLIS, deadlineMillis);
    }

    private static String generateClientId() {
        final Random random = new Random(System.currentTimeMillis());
        return Long.toHexString(System.identityHashCode(random) + random.nextLong());
    }

    private boolean stopFlag;
    private boolean stopGracefully;

    private final String clientId;
    private final RpcEndpoint localEndpoint;
    private final Endpoint serverEndpoint;

    // Cache of objects.
    // Best-effort invariant: if object is in the cache, it must include all
    // updates of locally and globally committed locally-originating
    // transactions.
    private final TimeBoundedObjectsCache objectsCache;

    // Invariant: committedVersion only grows.
    private final CausalityClock committedVersion;
    private IncrementalTimestampGenerator clientTimestampGenerator;

    // Invariant: there is at most one pending (open) transaction.
    private AbstractTxnHandle pendingTxn;
    // Locally committed transactions (in commit order), the first one is
    // possibly committing to the store.
    private final LinkedList<AbstractTxnHandle> locallyCommittedTxnsQueue;
    // Thread sequentially committing transactions from the queue.
    private final CommitterThread committerThread;
    // Local dependencies of a pending transaction.
    private final LinkedList<AbstractTxnHandle> pendingTxnLocalDependencies;

    // Update subscriptions stuff.
    // id -> update subscription information
    private final Map<CRDTIdentifier, UpdateSubscription> objectUpdateSubscriptions;
    // map from tripletimestamp of an uncommitted update to objects that may
    // await notification.
    private final Map<Timestamp, Set<CRDTIdentifier>> uncommittedUpdatesObjectsToNotify;
    private final NotoficationsProcessorThread notificationsThread;
    private final ExecutorService notificationsCallbacksExecutor;
    private final ExecutorService notificationsSubscriberExecutor;
    private final ExponentialBackoffTaskExecutor retryableTaskExecutor;

    private final int timeoutMillis;
    private final int notificationTimeoutMillis;
    private final int deadlineMillis;

    SwiftImpl(final RpcEndpoint localEndpoint, final Endpoint serverEndpoint, TimeBoundedObjectsCache objectsCache,
            int timeoutMillis, final int notificationTimeoutMillis, int deadlineMillis) {
        this.clientId = generateClientId();
        this.timeoutMillis = timeoutMillis;
        this.deadlineMillis = deadlineMillis;
        this.notificationTimeoutMillis = notificationTimeoutMillis;
        this.localEndpoint = localEndpoint;
        this.serverEndpoint = serverEndpoint;
        this.objectsCache = objectsCache;
        this.locallyCommittedTxnsQueue = new LinkedList<AbstractTxnHandle>();
        this.pendingTxnLocalDependencies = new LinkedList<AbstractTxnHandle>();
        this.committedVersion = ClockFactory.newClock();
        this.clientTimestampGenerator = new IncrementalTimestampGenerator(CLIENT_CLOCK_ID);
        this.retryableTaskExecutor = new ExponentialBackoffTaskExecutor("client->server request",
                INIT_RPC_RETRY_WAIT_TIME_MILLIS, RPC_RETRY_WAIT_TIME_MULTIPLIER);
        this.committerThread = new CommitterThread();
        this.committerThread.start();
        this.objectUpdateSubscriptions = new HashMap<CRDTIdentifier, UpdateSubscription>();
        this.uncommittedUpdatesObjectsToNotify = new HashMap<Timestamp, Set<CRDTIdentifier>>();
        this.notificationsCallbacksExecutor = Executors.newFixedThreadPool(1);
        this.notificationsSubscriberExecutor = Executors.newFixedThreadPool(1);
        this.notificationsThread = new NotoficationsProcessorThread();
        this.notificationsThread.start();
    }

    @Override
    public void stop(boolean waitForCommit) {
        logger.info("stopping client");
        synchronized (this) {
            stopFlag = true;
            stopGracefully = waitForCommit;
            this.notifyAll();
        }
        try {
            committerThread.join();
            for (final CRDTIdentifier id : new ArrayList<CRDTIdentifier>(objectUpdateSubscriptions.keySet())) {
                removeUpdateSubscriptionAsyncUnsubscribe(id);
            }
            notificationsSubscriberExecutor.shutdown();
            notificationsCallbacksExecutor.shutdown();
            notificationsSubscriberExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            notificationsCallbacksExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS);
            // Do not wait for notifications thread, as it is hard to interrupt
            // pending notification reply.
            // if (stopGracefully) {
            // notificationsThread.join();
            // }
        } catch (InterruptedException e) {
            logger.warning(e.getMessage());
        }
        logger.info("client stopped");
    }

    @Override
    public synchronized AbstractTxnHandle beginTxn(IsolationLevel isolationLevel, CachePolicy cachePolicy,
            boolean readOnly) throws NetworkException {
        // FIXME: Ooops, readOnly is present here at API level, respect it here
        // and in TxnHandleImpl or remove it from API.
        assertNoPendingTransaction();
        assertRunning();

        final Timestamp localTimestamp = clientTimestampGenerator.generateNew();
        switch (isolationLevel) {
        case SNAPSHOT_ISOLATION:
            if (cachePolicy == CachePolicy.MOST_RECENT || cachePolicy == CachePolicy.STRICTLY_MOST_RECENT) {
                final Boolean reply = retryableTaskExecutor.execute(new CallableWithDeadline<Boolean>(deadlineMillis) {
                    @Override
                    protected Boolean callOrFailWithNull() {
                        final AtomicBoolean doneFlag = new AtomicBoolean(false);
                        localEndpoint.send(serverEndpoint, new LatestKnownClockRequest(clientId),
                                new LatestKnownClockReplyHandler() {
                                    @Override
                                    public void onReceive(RpcHandle conn, LatestKnownClockReply reply) {
                                        updateCommittedVersion(reply.getClock());
                                        doneFlag.set(true);
                                    }
                                }, Math.min(timeoutMillis, getDeadlineLeft()));
                        return doneFlag.get();
                    }
                });

                if (reply == null && cachePolicy == CachePolicy.STRICTLY_MOST_RECENT) {
                    throw new NetworkException("timed out to get transcation snapshot point");
                }
            }
            // Invariant: for SI snapshotClock of a new transaction dominates
            // clock of all previous SI transaction (monotonic reads), since
            // commitedVersion only grows.
            final CausalityClock snapshotClock = committedVersion.clone();
            setPendingTxn(new SnapshotIsolationTxnHandle(this, cachePolicy, localTimestamp, snapshotClock));
            logger.info("SI transaction " + localTimestamp + " started with global snapshot point: " + snapshotClock);
            return pendingTxn;

        case REPEATABLE_READS:
            setPendingTxn(new RepeatableReadsTxnHandle(this, cachePolicy, localTimestamp));
            logger.info("REPEATABLE READS transaction " + localTimestamp + " started");
            return pendingTxn;

        case READ_COMMITTED:
        case READ_UNCOMMITTED:
            // TODO: implement!
        default:
            throw new UnsupportedOperationException("isolation level " + isolationLevel + " unsupported");
        }
    }

    private synchronized void updateCommittedVersion(final CausalityClock clock) {
        if (clock == null) {
            logger.warning("server returned null clock");
            return;
        }

        committedVersion.merge(clock);
        final AbstractTxnHandle commitingTxn = locallyCommittedTxnsQueue.peekFirst();
        final Timestamp commitingTxnTimestamp = commitingTxn == null ? null : commitingTxn.getGlobalTimestamp();
        if (commitingTxnTimestamp != null && clock.includes(commitingTxnTimestamp)) {
            // We observe global visibility (and possibly updates) of locally
            // committed transaction before the CommitUpdatesReply has been
            // received.
            applyGloballyCommittedTxn(commitingTxn);
        }

        // Go through updates to notify and see if any become committed.
        final Iterator<Entry<Timestamp, Set<CRDTIdentifier>>> iter = uncommittedUpdatesObjectsToNotify.entrySet()
                .iterator();
        while (iter.hasNext()) {
            final Entry<Timestamp, Set<CRDTIdentifier>> entry = iter.next();
            if (committedVersion.includes(entry.getKey())) {
                iter.remove();
                for (final CRDTIdentifier id : entry.getValue()) {
                    final UpdateSubscription subscription = objectUpdateSubscriptions.get(id);
                    if (subscription != null && subscription.hasListener()) {
                        notificationsCallbacksExecutor.execute(subscription.generateListenerNotification(id));
                    }
                }
            }
        }
    }

    @Override
    public synchronized <V extends CRDT<V>> TxnLocalCRDT<V> getObjectLatestVersionTxnView(AbstractTxnHandle txn,
            CRDTIdentifier id, CachePolicy cachePolicy, boolean create, Class<V> classOfV,
            final ObjectUpdatesListener updatesListener) throws WrongTypeException, NoSuchObjectException,
            VersionNotFoundException, NetworkException {
        assertPendingTransaction(txn);

        TxnLocalCRDT<V> localView;
        if (cachePolicy == CachePolicy.CACHED) {
            localView = getCachedObjectForTxn(id, null, classOfV, updatesListener);
            if (localView != null) {
                return localView;
            }
        }

        // Try to get the latest one.
        final boolean fetchRequired = (cachePolicy != CachePolicy.MOST_RECENT || objectsCache.getAndTouch(id) == null);
        final CausalityClock globalVersion = clockWithLocallyCommittedDependencies(committedVersion);
        globalVersion.drop(CLIENT_CLOCK_ID);
        try {
            fetchObjectVersion(id, create, classOfV, globalVersion, false, updatesListener != null, true);
        } catch (VersionNotFoundException x) {
            if (fetchRequired) {
                throw x;
            }
        } catch (NetworkException x) {
            if (fetchRequired) {
                throw x;
            }
        }
        // Pass other exceptions through.

        localView = getCachedObjectForTxn(id, null, classOfV, updatesListener);
        if (localView == null) {
            // It should not happen normally.
            throw new VersionNotFoundException("Retrieved object unavailable in appropriate version in the cache");
        }
        return localView;
    }

    @Override
    public synchronized <V extends CRDT<V>> TxnLocalCRDT<V> getObjectVersionTxnView(AbstractTxnHandle txn,
            final CRDTIdentifier id, final CausalityClock version, final boolean create, Class<V> classOfV,
            final ObjectUpdatesListener updatesListener) throws WrongTypeException, NoSuchObjectException,
            VersionNotFoundException, NetworkException {
        assertPendingTransaction(txn);
        assertIsGlobalClock(version);

        TxnLocalCRDT<V> localView = getCachedObjectForTxn(id, version, classOfV, updatesListener);
        if (localView != null) {
            return localView;
        }

        final CausalityClock globalVersion = clockWithLocallyCommittedDependencies(version);
        globalVersion.drop(CLIENT_CLOCK_ID);
        fetchObjectVersion(id, create, classOfV, globalVersion, true, updatesListener != null, true);

        localView = getCachedObjectForTxn(id, version, classOfV, updatesListener);
        if (localView == null) {
            // It should not happen normally.
            throw new VersionNotFoundException("Retrieved object unavailable in appropriate version in the cache");
        }
        return localView;
    }

    private CausalityClock clockWithLocallyCommittedDependencies(CausalityClock clock) {
        clock = clock.clone();
        for (final AbstractTxnHandle dependentTxn : pendingTxnLocalDependencies) {
            // Include in clock those dependent transactions that already
            // committed globally after the pending transaction started.
            if (dependentTxn.getStatus() == TxnStatus.COMMITTED_GLOBAL) {
                clock.record(dependentTxn.getGlobalTimestamp());
            } else {
                clock.record(dependentTxn.getLocalTimestamp());
            }
        }
        return clock;
    }

    @SuppressWarnings("unchecked")
    private synchronized <V extends CRDT<V>> TxnLocalCRDT<V> getCachedObjectForTxn(CRDTIdentifier id,
            CausalityClock clock, Class<V> classOfV, ObjectUpdatesListener updatesListener) throws WrongTypeException {
        V crdt;
        try {
            crdt = (V) objectsCache.getAndTouch(id);
        } catch (ClassCastException x) {
            throw new WrongTypeException(x.getMessage());
        }
        if (crdt == null) {
            return null;
        }

        if (clock == null) {
            // Return the latest committed version or, if unavailable, old
            // cached version if it is all committed.
            switch (crdt.getClock().compareTo(committedVersion)) {
            case CMP_ISDOMINATED:
            case CMP_EQUALS:
                clock = crdt.getClock().clone();
                break;
            case CMP_DOMINATES:
                clock = committedVersion.clone();
                break;
            case CMP_CONCURRENT:
                // FIXME: consider intersection of both!
                // need to think a bit if it is safe or not?
                return null;
            default:
                throw new UnsupportedOperationException();
            }

        }
        clock = clockWithLocallyCommittedDependencies(clock);
        final CausalityClock globalClock = clock.clone();
        globalClock.drop(CLIENT_CLOCK_ID);

        if (crdt.getClock().compareTo(globalClock).is(CMP_CLOCK.CMP_CONCURRENT, CMP_CLOCK.CMP_ISDOMINATED)
                || globalClock.compareTo(crdt.getPruneClock()).is(CMP_CLOCK.CMP_ISDOMINATED, CMP_CLOCK.CMP_CONCURRENT)) {
            // No appropriate version found.
            return null;
        }

        final V crdtReturned;
        // Are there any local dependencies to apply on the cached object?
        if (clock.hasEventFrom(CLIENT_CLOCK_ID)) {
            crdtReturned = crdt.copy();
            // Apply them on sandboxed copy of an object, since these operations
            // use local timestamps.
            for (final AbstractTxnHandle dependentTxn : pendingTxnLocalDependencies) {
                final CRDTObjectOperationsGroup<V> localOps;
                try {
                    localOps = (CRDTObjectOperationsGroup<V>) dependentTxn.getObjectLocalOperations(id);
                } catch (ClassCastException x) {
                    throw new WrongTypeException(x.getMessage());
                }
                if (localOps != null) {
                    crdtReturned.execute(localOps, CRDTOperationDependencyPolicy.IGNORE);
                }
            }
        } else {
            crdtReturned = crdt;
        }
        final TxnLocalCRDT<V> crdtView = crdtReturned.getTxnLocalCopy(clock, pendingTxn);
        if (updatesListener != null) {
            final UpdateSubscription subscription = addUpdateSubscription(crdtReturned, crdtView, updatesListener);
            if (subscription.hasListener()) {
                handleObjectNewVersionTryNotify(id, subscription, crdtReturned);
            }
        }
        return crdtView;
    }

    private <V extends CRDT<V>> void fetchObjectVersion(CRDTIdentifier id, boolean create, Class<V> classOfV,
            final CausalityClock version, final boolean strictUnprunedVersion, final boolean subscribeUpdates,
            final boolean txnTriggered) throws WrongTypeException, NoSuchObjectException, VersionNotFoundException,
            NetworkException {
        final V crdt;
        synchronized (this) {
            try {
                crdt = (V) objectsCache.getWithoutTouch(id);
            } catch (ClassCastException x) {
                throw new WrongTypeException(x.getMessage());
            }
        }

        if (crdt == null) {
            fetchObjectFromScratch(id, create, classOfV, version, strictUnprunedVersion, subscribeUpdates, txnTriggered);
        } else {
            fetchObjectByRefresh(id, create, classOfV, crdt, version, strictUnprunedVersion, subscribeUpdates,
                    txnTriggered);
        }
    }

    @SuppressWarnings("unchecked")
    private <V extends CRDT<V>> void fetchObjectFromScratch(CRDTIdentifier id, boolean create, Class<V> classOfV,
            CausalityClock version, boolean strictUnprunedVersion, boolean subscribeUpdates, boolean txnTriggered)
            throws NoSuchObjectException, WrongTypeException, VersionNotFoundException, NetworkException {
        final SubscriptionType subscriptionType = subscribeUpdates ? SubscriptionType.UPDATES : SubscriptionType.NONE;
        final FetchObjectVersionRequest fetchRequest = new FetchObjectVersionRequest(clientId, id, version,
                strictUnprunedVersion, subscriptionType);
        doFetchObjectVersionOrTimeout(fetchRequest, classOfV, create, txnTriggered);
    }

    @SuppressWarnings("unchecked")
    private <V extends CRDT<V>> void fetchObjectByRefresh(CRDTIdentifier id, boolean create, Class<V> classOfV,
            V cachedCrdt, CausalityClock version, boolean strictUnrpunedVersion, boolean subscribeUpdates,
            boolean txnTriggered) throws NoSuchObjectException, WrongTypeException, VersionNotFoundException,
            NetworkException {
        final CausalityClock oldCrdtClock;
        synchronized (this) {
            oldCrdtClock = cachedCrdt.getClock().clone();
        }

        // WISHME: we should replace it with deltas or operations list
        final SubscriptionType subscriptionType = subscribeUpdates ? SubscriptionType.UPDATES : SubscriptionType.NONE;
        final FetchObjectDeltaRequest fetchRequest = new FetchObjectDeltaRequest(clientId, id, oldCrdtClock, version,
                strictUnrpunedVersion, subscriptionType);
        doFetchObjectVersionOrTimeout(fetchRequest, classOfV, create, txnTriggered);
    }

    private <V extends CRDT<V>> void doFetchObjectVersionOrTimeout(final FetchObjectVersionRequest fetchRequest,
            Class<V> classOfV, boolean create, boolean txnTriggered) throws NetworkException, NoSuchObjectException,
            WrongTypeException {
        FetchObjectVersionReply reply;
        do {
            reply = retryableTaskExecutor.execute(new CallableWithDeadline<FetchObjectVersionReply>(deadlineMillis) {
                @Override
                protected FetchObjectVersionReply callOrFailWithNull() {
                    final AtomicReference<FetchObjectVersionReply> replyRef = new AtomicReference<FetchObjectVersionReply>();
                    localEndpoint.send(serverEndpoint, fetchRequest, new FetchObjectVersionReplyHandler() {
                        @Override
                        public void onReceive(RpcHandle handle, FetchObjectVersionReply reply) {
                            replyRef.set(reply);
                        }
                    }, Math.min(timeoutMillis, getDeadlineLeft()));
                    return replyRef.get();
                }
            });
            if (reply == null) {
                throw new NetworkException("Fetching object version exceeded the deadline");
            }
            if (stopFlag) {
                throw new NetworkException("Fetching object version was interrupted by client shutdown.");
            }
        } while (!processFetchObjectReply(fetchRequest, reply, classOfV, create, txnTriggered));
    }

    /**
     * @return when the request was successful
     */
    private <V extends CRDT<V>> boolean processFetchObjectReply(final FetchObjectVersionRequest request,
            final FetchObjectVersionReply fetchReply, Class<V> classOfV, boolean create, boolean clientTriggered)
            throws NoSuchObjectException, WrongTypeException {
        final V crdt;
        switch (fetchReply.getStatus()) {
        case OBJECT_NOT_FOUND:
            if (!create) {
                throw new NoSuchObjectException("object " + request.getUid() + " not found");
            }
            try {
                crdt = classOfV.newInstance();
            } catch (Exception e) {
                throw new WrongTypeException(e.getMessage());
            }
            crdt.init(request.getUid(), fetchReply.getVersion(), fetchReply.getPruneClock(), false);
            break;
        case VERSION_NOT_FOUND:
        case OK:
            try {
                crdt = (V) fetchReply.getCrdt();
            } catch (Exception e) {
                throw new WrongTypeException(e.getMessage());
            }
            crdt.init(request.getUid(), fetchReply.getVersion(), fetchReply.getPruneClock(), true);
            break;
        default:
            throw new IllegalStateException("Unexpected status code" + fetchReply.getStatus());
        }

        synchronized (this) {
            updateCommittedVersion(fetchReply.getEstimatedLatestKnownClock());

            final V cacheCRDT;
            try {
                if (clientTriggered) {
                    cacheCRDT = (V) objectsCache.getAndTouch(request.getUid());
                } else {
                    cacheCRDT = (V) objectsCache.getWithoutTouch(request.getUid());
                }
            } catch (Exception e) {
                throw new WrongTypeException(e.getMessage());
            }

            if (cacheCRDT == null
                    || crdt.getClock().compareTo(cacheCRDT.getClock())
                            .is(CMP_CLOCK.CMP_DOMINATES, CMP_CLOCK.CMP_EQUALS)) {
                // If clock >= cacheCrdt.clock, it 1) does not make sense to
                // merge, 2) received version may have desirable pruneClock.
                objectsCache.add(crdt);
            } else {
                try {
                    cacheCRDT.merge(crdt);
                } catch (IllegalStateException x) {
                    logger.warning("cannot merge in received object version due to concurrent pruning, retrying fetch");
                    // It should happen only temporarily during failover etc.
                    return false;
                }
            }
            UpdateSubscription subscription = objectUpdateSubscriptions.get(request.getUid());
            if (request.getSubscriptionType() != SubscriptionType.NONE && subscription == null) {
                // Add temporary subscription entry without specifying full
                // information on what value has been read.
                subscription = addUpdateSubscription(crdt, null, null);
            }

            if (subscription != null && subscription.hasListener()) {
                handleObjectNewVersionTryNotify(request.getUid(), subscription, cacheCRDT);
            }
        }

        if (fetchReply.getStatus() == FetchStatus.VERSION_NOT_FOUND) {
            logger.warning("requested object version not found in the store, retrying fetch");
            return false;
        }
        return true;
    }

    private void fetchSubscribedNotifications() {
        final FastRecentUpdatesReply notifications = retryableTaskExecutor
                .execute(new CallableWithDeadline<FastRecentUpdatesReply>() {
                    @Override
                    protected FastRecentUpdatesReply callOrFailWithNull() {
                        final AtomicReference<FastRecentUpdatesReply> replyRef = new AtomicReference<FastRecentUpdatesReply>();
                        localEndpoint.send(
                                serverEndpoint,
                                new FastRecentUpdatesRequest(clientId, Math.max(0, notificationTimeoutMillis
                                        - timeoutMillis)), new FastRecentUpdatesReplyHandler() {
                                    @Override
                                    public void onReceive(RpcHandle conn, FastRecentUpdatesReply reply) {
                                        replyRef.set(reply);
                                    }
                                }, notificationTimeoutMillis);
                        return replyRef.get();
                    }
                });
        logger.fine("notifications received for " + notifications.getSubscriptions().size() + " objects");

        updateCommittedVersion(notifications.getEstimatedLatestKnownClock());
        if (notifications.getStatus() == SubscriptionStatus.ACTIVE) {
            // Process notifications.
            for (final ObjectSubscriptionInfo subscriptionInfo : notifications.getSubscriptions()) {
                if (subscriptionInfo.isDirty() && subscriptionInfo.getUpdates().isEmpty()) {
                    logger.warning("unexpected server notification information without update");
                } else {
                    applyObjectUpdates(subscriptionInfo.getId(), subscriptionInfo.getOldClock(),
                            subscriptionInfo.getUpdates(), subscriptionInfo.getNewClock());
                }
            }
        } else {
            // Renew lost subscriptions.
            synchronized (this) {
                for (final CRDTIdentifier id : objectUpdateSubscriptions.keySet()) {
                    asyncSubscribeObjectUpdates(id);
                }
            }
        }
    }

    /**
     * @return true if subscription should be continued for this object
     */
    private synchronized void applyObjectUpdates(final CRDTIdentifier id, final CausalityClock dependencyClock,
            final List<CRDTObjectOperationsGroup<?>> ops, final CausalityClock outputClock) {
        if (stopFlag) {
            logger.info("Update received after client has been stopped -> ignoring");
            return;
        }

        final UpdateSubscription subscription = objectUpdateSubscriptions.get(id);
        if (subscription == null) {
            removeUpdateSubscriptionAsyncUnsubscribe(id);
        }

        final CRDT crdt = objectsCache.getWithoutTouch(id);
        if (crdt == null) {
            // Ooops, we evicted the object from the cache.
            logger.info("cannot apply received updates on object " + id + " as it has been evicted from the cache");
            if (subscription != null) {
                if (subscription.hasListener()) {
                    if (!ops.isEmpty()) {
                        // There still listener waiting, make some efforts to
                        // fire the notification.
                        asyncSubscribeObjectUpdates(id);
                    }
                } else {
                    // Stop subscription for object evicted from the cache.
                    removeUpdateSubscriptionAsyncUnsubscribe(id);
                }
            }
            return;
        }

        final CMP_CLOCK clkCmp = crdt.getClock().compareTo(dependencyClock);
        if (clkCmp == CMP_CLOCK.CMP_ISDOMINATED || clkCmp == CMP_CLOCK.CMP_CONCURRENT) {
            // Ooops, we missed some update or messages were ordered.
            logger.info("cannot apply received updates on object " + id + " due to unsatisfied dependencies");
            if (subscription != null && !ops.isEmpty()) {
                asyncSubscribeObjectUpdates(id);
            }
            return;
        }

        if (logger.isLoggable(Level.INFO)) {
            logger.info("applying received updates on object " + id);
        }

        for (final CRDTObjectOperationsGroup<?> op : ops) {
            if (!crdt.execute(op, CRDTOperationDependencyPolicy.RECORD_BLINDLY)) {
                if (logger.isLoggable(Level.INFO)) {
                    logger.info("update " + op.getBaseTimestamp() + " was already included in the state of object "
                            + id);
                }
                // Already applied update.
                continue;
            }
            if (subscription != null && subscription.hasListener()) {
                handleObjectUpdatesTryNotify(id, subscription, Collections.singleton(op.getBaseTimestamp()));
            }
        }
        crdt.getClock().merge(outputClock);
    }

    private synchronized void handleObjectUpdatesTryNotify(CRDTIdentifier id, UpdateSubscription subscription,
            Collection<? extends Timestamp> updateTimestamps) {
        if (stopFlag) {
            logger.info("Update received after client has been stopped -> ignoring");
            return;
        }

        Map<Timestamp, CRDTIdentifier> uncommittedUpdates = new HashMap<Timestamp, CRDTIdentifier>();
        for (final Timestamp ts : updateTimestamps) {
            if (!subscription.readVersion.includes(ts)) {
                if (committedVersion.includes(ts)) {
                    notificationsCallbacksExecutor.execute(subscription.generateListenerNotification(id));
                    return;
                }
                uncommittedUpdates.put(ts, id);
            }
        }
        // There was no committed timestamp we could notify about, so put
        // them the queue of updates to notify when they are committed.
        for (final Entry<Timestamp, CRDTIdentifier> entry : uncommittedUpdates.entrySet()) {
            Set<CRDTIdentifier> ids = uncommittedUpdatesObjectsToNotify.get(entry.getKey());
            if (ids == null) {
                ids = new HashSet<CRDTIdentifier>();
                uncommittedUpdatesObjectsToNotify.put(entry.getKey(), ids);
            }
            ids.add(entry.getValue());
            if (logger.isLoggable(Level.INFO)) {
                logger.info("Update on object " + id + " visible, but not committed, delaying notification");
            }
        }
    }

    private synchronized <V extends CRDT<V>> void handleObjectNewVersionTryNotify(CRDTIdentifier id,
            final UpdateSubscription subscription, final V newCrdtVersion) {
        if (stopFlag) {
            logger.info("Update received after client has been stopped -> ignoring");
            return;
        }

        final Set<Timestamp> recentUpdates;
        try {
            recentUpdates = newCrdtVersion.getUpdateTimestampsSince(subscription.readVersion);
        } catch (IllegalArgumentException x) {
            // Object has been pruned since then, look at the values.
            // This is a very bizzare case.
            logger.warning("Object has been pruned since notification was set up, needs to investigate the observable view");
            final TxnLocalCRDT<V> newView = newCrdtVersion.getTxnLocalCopy(committedVersion, subscription.txn);
            if (!newView.equals(subscription.crdtView.getValue())) {
                notificationsCallbacksExecutor.execute(subscription.generateListenerNotification(id));
            }
            return;
        }
        handleObjectUpdatesTryNotify(id, subscription, recentUpdates);
    }

    private synchronized UpdateSubscription addUpdateSubscription(final CRDT<?> crdt, final TxnLocalCRDT<?> localView,
            ObjectUpdatesListener listener) {
        final UpdateSubscription updateSubscription = new UpdateSubscription(pendingTxn, localView, listener);
        // Overwriting old entry and even subscribing again is fine, the
        // interface specifies clearly that the latest get() matters.
        final UpdateSubscription oldSubscription = objectUpdateSubscriptions.put(crdt.getUID(), updateSubscription);
        if (oldSubscription == null && crdt.isRegisteredInStore()) {
            // If object is not in the store yet, wait until
            // applyGloballyCommittedTxn() with actual subscription.
            asyncSubscribeObjectUpdates(crdt.getUID());
        }
        return updateSubscription;
    }

    private void asyncSubscribeObjectUpdates(final CRDTIdentifier id) {
        if (stopFlag) {
            logger.info("Update received after client has been stopped -> ignoring");
            return;
        }

        notificationsSubscriberExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final CausalityClock version;
                synchronized (SwiftImpl.this) {
                    if (!objectUpdateSubscriptions.containsKey(id)) {
                        return;
                    }
                    version = clockWithLocallyCommittedDependencies(committedVersion);
                    version.drop(CLIENT_CLOCK_ID);
                }
                try {
                    fetchObjectVersion(id, false, BaseCRDT.class, version, false, true, false);
                } catch (SwiftException x) {
                    logger.warning("could not fetch the latest version of an object for notifications purposes: "
                            + x.getMessage());
                }
            }
        });
    }

    private synchronized void removeUpdateSubscriptionAsyncUnsubscribe(final CRDTIdentifier id) {
        objectUpdateSubscriptions.remove(id);
        notificationsSubscriberExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (objectUpdateSubscriptions.containsKey(id)) {
                    return;
                }
                if (localEndpoint.send(serverEndpoint, new UnsubscribeUpdatesRequest(clientId, id)).failed()) {
                    logger.info("failed to unsuscribe object updates");
                }
            }
        });
    }

    @Override
    public synchronized void discardTxn(AbstractTxnHandle txn) {
        assertPendingTransaction(txn);
        setPendingTxn(null);
        logger.info("local transaction " + txn.getLocalTimestamp() + " rolled back");
    }

    @Override
    public synchronized void commitTxn(AbstractTxnHandle txn) {
        assertPendingTransaction(txn);
        assertRunning();

        // Big WISHME: write disk log and allow local recovery.
        txn.markLocallyCommitted();
        logger.info("transaction " + txn.getLocalTimestamp() + " commited locally");
        if (txn.isReadOnly()) {
            // Read-only transaction can be immediately discarded.
            txn.markGloballyCommitted();
            logger.info("read-only transaction " + txn.getLocalTimestamp() + " (virtually) commited globally");
        } else {
            for (final AbstractTxnHandle dependeeTxn : pendingTxnLocalDependencies) {
                // Replace timestamps of transactions that globally committed
                // when this transaction was pending.
                if (dependeeTxn.getStatus() == TxnStatus.COMMITTED_GLOBAL) {
                    txn.includeGlobalDependency(dependeeTxn.getLocalTimestamp(), dependeeTxn.getGlobalTimestamp());
                }
            }
            // Update transaction is queued up for global commit.
            addLocallyCommittedTransaction(txn);
        }
        setPendingTxn(null);
        objectsCache.evictOutdated();
    }

    private void addLocallyCommittedTransaction(AbstractTxnHandle txn) {
        locallyCommittedTxnsQueue.addLast(txn);
        // Notify committer thread.
        this.notifyAll();
    }

    /**
     * Stubborn commit procedure, tries to get a global timestamp for a
     * transaction and commit using this timestamp. Repeats until it succeeds.
     * 
     * @param txn
     *            locally committed transaction
     */
    private void commitToStore(final AbstractTxnHandle txn) {
        txn.assertStatus(TxnStatus.COMMITTED_LOCAL);
        if (txn.getUpdatesDependencyClock().hasEventFrom(CLIENT_CLOCK_ID)) {
            throw new IllegalStateException("Trying to commit to data store with client clock");
        }

        CommitUpdatesReply reply;
        do {
            assignGlobalTimestamp(txn);

            final LinkedList<CRDTObjectOperationsGroup<?>> operationsGroups = new LinkedList<CRDTObjectOperationsGroup<?>>(
                    txn.getAllGlobalOperations());
            // Commit at server.
            reply = retryableTaskExecutor.execute(new CallableWithDeadline<CommitUpdatesReply>() {
                @Override
                protected CommitUpdatesReply callOrFailWithNull() {
                    final AtomicReference<CommitUpdatesReply> commitReplyRef = new AtomicReference<CommitUpdatesReply>();
                    localEndpoint.send(serverEndpoint, new CommitUpdatesRequest(clientId, txn.getGlobalTimestamp(),
                            operationsGroups), new CommitUpdatesReplyHandler() {
                        @Override
                        public void onReceive(RpcHandle conn, CommitUpdatesReply reply) {
                            commitReplyRef.set(reply);
                        }
                    }, timeoutMillis);
                    return commitReplyRef.get();
                }
            });
        } while (reply.getStatus() == CommitStatus.INVALID_TIMESTAMP);

        if (reply.getStatus() == CommitStatus.ALREADY_COMMITTED) {
            // FIXME Perhaps we could move this complexity to RequestTimestamp
            // on server side? Otherwise we need to keep track of all global
            // timestamps we previously tried.
            throw new UnsupportedOperationException("transaction committed under another timestamp, unsupported case");
        }
        logger.info("transaction " + txn.getLocalTimestamp() + " commited globally as " + txn.getGlobalTimestamp());
    }

    /**
     * Requests and assigns a new global timestamp for the transaction.
     * 
     * @param txn
     *            locally committed transaction
     */
    private void assignGlobalTimestamp(final AbstractTxnHandle txn) {
        txn.assertStatus(TxnStatus.COMMITTED_LOCAL);

        final GenerateTimestampReply reply = retryableTaskExecutor
                .execute(new CallableWithDeadline<GenerateTimestampReply>() {
                    @Override
                    protected GenerateTimestampReply callOrFailWithNull() {
                        final AtomicReference<GenerateTimestampReply> replyRef = new AtomicReference<GenerateTimestampReply>();
                        localEndpoint.send(
                                serverEndpoint,
                                new GenerateTimestampRequest(clientId, txn.getUpdatesDependencyClock(), txn
                                        .getGlobalTimestamp()), new GenerateTimestampReplyHandler() {
                                    @Override
                                    public void onReceive(RpcHandle conn, GenerateTimestampReply reply) {
                                        replyRef.set(reply);
                                    }
                                }, timeoutMillis);
                        return replyRef.get();
                    }
                });

        // And replace old timestamp in operations with timestamp from server.
        txn.setGlobalTimestamp(reply.getTimestamp());
    }

    /**
     * Applies globally committed transaction locally using a global timestamp.
     * 
     * @param txn
     *            globally committed transaction to apply locally
     */
    private synchronized void applyGloballyCommittedTxn(AbstractTxnHandle txn) {
        txn.assertStatus(TxnStatus.COMMITTED_LOCAL, TxnStatus.COMMITTED_GLOBAL);
        if (txn.getStatus() == TxnStatus.COMMITTED_GLOBAL) {
            return;
        }

        txn.markGloballyCommitted();
        for (final CRDTObjectOperationsGroup opsGroup : txn.getAllGlobalOperations()) {
            // Try to apply changes in a cached copy of an object.
            final CRDT<?> crdt = objectsCache.getWithoutTouch(opsGroup.getTargetUID());
            if (crdt == null) {
                logger.warning("object evicted from the local cache before global commit");
            } else {
                crdt.execute(opsGroup, CRDTOperationDependencyPolicy.IGNORE);
                // Subscribe updates if they were requested.
                final UpdateSubscription subscription = objectUpdateSubscriptions.get(opsGroup.getTargetUID());
                if (subscription != null && opsGroup.hasCreationState()) {
                    asyncSubscribeObjectUpdates(opsGroup.getTargetUID());
                }
            }
        }
        objectsCache.recordOnAll(txn.getGlobalTimestamp());

        final Iterator<AbstractTxnHandle> localTxnIter = locallyCommittedTxnsQueue.iterator();
        if (localTxnIter.next() != txn) {
            throw new IllegalStateException("Internal error: committed transaction was not the first from the queue");
        }
        localTxnIter.remove();
        while (localTxnIter.hasNext()) {
            final AbstractTxnHandle dependingTxn = localTxnIter.next();
            dependingTxn.includeGlobalDependency(txn.getLocalTimestamp(), txn.getGlobalTimestamp());
            // pendingTxn will map timestamp later inside commitToStore().

            // TODO [tricky]: to implement IsolationLevel.READ_COMMITTED we may
            // need to replace timestamps in pending transaction too.
        }
        for (final UpdateSubscription subscription : objectUpdateSubscriptions.values()) {
            subscription.replaceReadVersionTimestamp(txn.getLocalTimestamp(), txn.getGlobalTimestamp());
        }
        committedVersion.record(txn.getGlobalTimestamp());
    }

    private synchronized AbstractTxnHandle getNextLocallyCommittedTxnBlocking() {
        while (locallyCommittedTxnsQueue.isEmpty() && !stopFlag) {
            try {
                this.wait();
            } catch (InterruptedException e) {
            }
        }
        return locallyCommittedTxnsQueue.peekFirst();
    }

    private synchronized void setPendingTxn(final AbstractTxnHandle txn) {
        pendingTxn = txn;
        pendingTxnLocalDependencies.clear();
        if (txn != null) {
            pendingTxnLocalDependencies.addAll(locallyCommittedTxnsQueue);
        }
    }

    private void assertNoPendingTransaction() {
        if (pendingTxn != null) {
            throw new IllegalStateException("Only one transaction can be executing at the time");
        }
    }

    private void assertPendingTransaction(final AbstractTxnHandle expectedTxn) {
        if (!pendingTxn.equals(expectedTxn)) {
            throw new IllegalStateException(
                    "Corrupted state: unexpected transaction is bothering me, not the pending one");
        }
    }

    private void assertRunning() {
        if (stopFlag) {
            throw new IllegalStateException("client is stopped");
        }
    }

    private void assertIsGlobalClock(CausalityClock version) {
        if (version.hasEventFrom(CLIENT_CLOCK_ID)) {
            throw new IllegalArgumentException("transaction requested visibility of local transaction");
        }
    }

    /**
     * Thread continuously committing locally committed transactions. The thread
     * takes the oldest locally committed transaction one by one, tries to
     * commit it to the store and applies to it to local cache and depender
     * transactions.
     */
    private class CommitterThread extends Thread {

        public CommitterThread() {
            super("SwiftTransactionCommitterThread");
        }

        @Override
        public void run() {
            while (true) {
                final AbstractTxnHandle nextToCommit = getNextLocallyCommittedTxnBlocking();
                if (stopFlag && (!stopGracefully || nextToCommit == null)) {
                    return;
                }
                commitToStore(nextToCommit);
                applyGloballyCommittedTxn(nextToCommit);
            }
        }
    }

    private class NotoficationsProcessorThread extends Thread {
        public NotoficationsProcessorThread() {
            super("SwiftNotificationsProcessorThread");
            setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                synchronized (SwiftImpl.this) {
                    if (stopFlag) {
                        return;
                    }
                }
                fetchSubscribedNotifications();
            }
        }
    }

    /**
     * Client representation of updates subscription. When listener is not null,
     * the listener is awaiting for notification on update that occurred after
     * the readVersion.
     * 
     * @author mzawirski
     */
    private static class UpdateSubscription {
        public ObjectUpdatesListener listener;
        private AbstractTxnHandle txn;
        private TxnLocalCRDT<?> crdtView;
        private CausalityClock readVersion;

        public UpdateSubscription(AbstractTxnHandle txn, TxnLocalCRDT<?> crdtView, final ObjectUpdatesListener listener) {
            if (listener != null && !listener.isSubscriptionOnly()) {
                this.txn = txn;
                this.crdtView = crdtView;
                this.listener = listener;
                this.readVersion = crdtView.getClock().clone();
            }
            // else: only subscribe updates, but do not bother with notifying on
            // updates
        }

        public boolean hasListener() {
            return listener != null;
        }

        // FIXME: it seems more reasonable to call it after we make sure the
        // triggering update is included in committedVersion!
        public Runnable generateListenerNotification(final CRDTIdentifier id) {
            if (!hasListener()) {
                throw new IllegalStateException("Trying to notify already notified updates listener");
            }

            final ObjectUpdatesListener listenerRef = this.listener;
            final AbstractTxnHandle txnRef = this.txn;
            final TxnLocalCRDT<?> crdtRef = this.crdtView;
            txn = null;
            crdtView = null;
            listener = null;
            readVersion = null;

            return new Runnable() {
                @Override
                public void run() {
                    logger.info("Notifying on update on object " + id);
                    listenerRef.onObjectUpdate(txnRef, id, crdtRef);
                }
            };
        }

        public void replaceReadVersionTimestamp(Timestamp localTimestamp, Timestamp globalTimestamp) {
            if (!hasListener()) {
                return;
            }

            // Objects in cache always use global timestamp, so we need to map
            // stuff.
            if (readVersion.includes(localTimestamp)) {
                readVersion.drop(localTimestamp);
                readVersion.record(globalTimestamp);
            }
        }
    }
}
