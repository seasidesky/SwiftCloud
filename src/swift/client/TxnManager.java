package swift.client;

import swift.crdt.CRDTIdentifier;
import swift.crdt.interfaces.CRDT;
import swift.crdt.interfaces.CachePolicy;
import swift.crdt.interfaces.Swift;
import swift.crdt.interfaces.TxnLocalCRDT;
import swift.exceptions.ConsistentSnapshotVersionNotFoundException;
import swift.exceptions.NoSuchObjectException;
import swift.exceptions.WrongTypeException;

public interface TxnManager extends Swift {
    TxnHandleImpl beginTxn(CachePolicy cp, boolean readOnly);

    <V extends CRDT<V>> TxnLocalCRDT<V> getObjectTxnView(TxnHandleImpl txn, CRDTIdentifier id,
            boolean create, Class<V> classOfV) throws WrongTypeException, NoSuchObjectException,
            ConsistentSnapshotVersionNotFoundException;

    void discardTxn(TxnHandleImpl txn);

    void commitTxn(TxnHandleImpl txn);
}
