package swift.crdt.operations;

import swift.clocks.Timestamp;
import swift.clocks.TripleTimestamp;
import swift.crdt.IntegerVersioned;

public class IntegerUpdate extends BaseOperation<IntegerVersioned> {
    private int val;

    // required for kryo
    public IntegerUpdate() {
    }

    public IntegerUpdate(TripleTimestamp ts, int val) {
        super(ts);
        this.val = val;
    }

    public int getVal() {
        return this.val;
    }

    @Override
    public void replaceDependentOpTimestamp(Timestamp oldTs, Timestamp newTs) {
        // Integer operation does not rely on any timestamp dependency.
    }

    @Override
    public void applyTo(IntegerVersioned crdt) {
        crdt.applyUpdate(val, getTimestamp());
    }

}