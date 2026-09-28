package com.grandis.nova.preorder.payability;

import com.grandis.nova.preorder.preorder.PayabilityBlocker;
import com.grandis.nova.preorder.preorder.PreorderSnapshot;

/** @param blocker 결제할 수 있으면 null */
record Payability(PreorderSnapshot preorder, PayabilityBlocker blocker) {

    public boolean payable() {
        return blocker == null;
    }
}
