package swift.crdt.interfaces;

/**
 * Shared Swift scout that allows a single or multiple open concurrent sessions.
 * 
 * @author mzawirski
 */
public interface SwiftScout {
    /**
     * Creates a new session with the shared Swift scout. Note that session do
     * not introduce extra overhead and do not need to be explicitly closed.
     * 
     * @param sessionId
     * @return a new session client, associated with this shared instance
     * @throws UnsupportedOperationException
     *             when scout does not support more sessions
     */
    SwiftSession newSession(final String sessionId);

    /**
     * Stops the scout, which renders it unusable after this call returns.
     * 
     * @param waitForCommit
     *            when true, this call blocks until all locally committed
     *            transactions commit globally in the store
     */
    void stop(boolean waitForCommit);

    /**
     * Prints and resets caching statistics.
     */
    void printAndResetCacheStats();
}
