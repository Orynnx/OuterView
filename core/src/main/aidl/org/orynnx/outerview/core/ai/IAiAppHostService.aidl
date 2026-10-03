package org.orynnx.outerview.core.ai;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IAiAppHostService {
    Bundle getCapabilities();
    Bundle listCards();
    Bundle importCard(in ParcelFileDescriptor packageFd, String displayName);
    Bundle removeCard(String cardId);
    Bundle restoreCard(String cardId);
    Bundle openSystemManager();
}
