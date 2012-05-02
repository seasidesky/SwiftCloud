package swift.dc.proto;

import swift.client.proto.SubscriptionType;
import swift.client.proto.FastRecentUpdatesReply.ObjectSubscriptionInfo;
import swift.clocks.CausalityClock;
import swift.crdt.CRDTIdentifier;
import swift.dc.*;
import sys.dht.api.DHT;
import sys.dht.api.DHT.Connection;
import sys.dht.api.DHT.ReplyHandler;

/**
 * Object for sending a notification of an update to a surrogate
 * 
 * @author preguica
 */
public class DHTSendNotification implements DHT.Reply {
    ObjectSubscriptionInfo info;
    CausalityClock estimatedDCVersion;
    
    /**
     * Needed for Kryo serialization
     */
    public DHTSendNotification() {
    }

    public DHTSendNotification(ObjectSubscriptionInfo info, CausalityClock estimatedDCVersion) {
        super();
        this.info = info;
        this.estimatedDCVersion = estimatedDCVersion;
    }


    public ObjectSubscriptionInfo getInfo() {
        return info;
    }

    @Override
    public void deliverTo(Connection conn, ReplyHandler handler) {
        ((DHTDataNode.ReplyHandler) handler).onReceive(conn, this);
        
    }

    public CausalityClock getEstimatedDCVersion() {
        return estimatedDCVersion;
    }

}
