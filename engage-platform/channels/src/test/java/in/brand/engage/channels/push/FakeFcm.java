package in.brand.engage.channels.push;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Records every batch and answers each token from a script. */
class FakeFcm implements FcmClient {

    final List<Batch> calls = new ArrayList<>();
    private final Function<String, TokenResult> perToken;
    private int failCall = -1;

    FakeFcm(Function<String, TokenResult> perToken) {
        this.perToken = perToken;
    }

    /** The n-th call (0-based) fails as a whole. */
    FakeFcm failingCall(int n) {
        this.failCall = n;
        return this;
    }

    @Override
    public List<TokenResult> send(Batch batch) throws FcmCallException {
        int n = calls.size();
        calls.add(batch);
        if (n == failCall) throw new FcmCallException("UNAVAILABLE: backend down", null);
        return batch.tokens().stream().map(perToken).toList();
    }
}
