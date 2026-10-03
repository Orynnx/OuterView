package org.orynnx.outerview.core.ai;

import org.orynnx.outerview.core.ai.IAiAppHostService;

oneway interface IAiAppHostConnection {
    void onServiceConnected(IAiAppHostService service);
}
