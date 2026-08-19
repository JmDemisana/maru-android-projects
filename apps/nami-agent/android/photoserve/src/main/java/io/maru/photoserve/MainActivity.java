package io.maru.photoserve;

import io.maru.applets.AppletBridgeActivity;

public class MainActivity extends AppletBridgeActivity {
    @Override
    protected String getAppletDisplayName() {
        return "PhotoServe";
    }

    @Override
    protected boolean isAppletSupportedOnDevice() {
        return false;
    }

    @Override
    protected String getUnsupportedTitle() {
        return "PhotoServe is desktop-first";
    }

    @Override
    protected String getUnsupportedMessage() {
        return "PhotoServe stays on desktop for the roomy crop, layout, export, and print workflow. This phone build only keeps the app listed alongside the rest of your Maru tools.";
    }

    @Override
    protected String getUnsupportedNote() {
        return "Use the website or desktop app when you want the full print workstation.";
    }
}
