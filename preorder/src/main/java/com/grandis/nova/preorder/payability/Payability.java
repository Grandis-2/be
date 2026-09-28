package com.grandis.nova.preorder.payability;

import com.grandis.nova.preorder.preorder.PayabilityBlocker;
import com.grandis.nova.preorder.preorder.Preorder;

/** @param blocker 결제할 수 있으면 null */
record Payability(Preorder preorder, PayabilityBlocker blocker) {

    public boolean payable() {
        return blocker == null;
    }
}
