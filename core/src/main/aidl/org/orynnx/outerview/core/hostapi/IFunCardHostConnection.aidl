package org.orynnx.outerview.core.hostapi;

import org.orynnx.outerview.core.hostapi.IFunCardHostService;

oneway interface IFunCardHostConnection {
    void onServiceConnected(IFunCardHostService service);
}
