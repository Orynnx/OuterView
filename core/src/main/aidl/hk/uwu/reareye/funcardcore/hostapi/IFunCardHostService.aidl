package hk.uwu.reareye.funcardcore.hostapi;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IFunCardHostService {
    Bundle getCapabilities();
    Bundle listSystemTemplates();
    Bundle listHostCards();
    Bundle installCard(in Bundle request, in ParcelFileDescriptor zipFd);
    Bundle activateCard(in Bundle request);
    Bundle deactivateCard(in Bundle request);
    Bundle uninstallCard(in Bundle request);
    Bundle deleteAllCards(in Bundle request);
    Bundle getCardDiagnostics(String cardId, String business, int notificationId);
}
