package org.telegram.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restore a launcher entry after removing Bebragram's old icon aliases. */
public class LauncherIconMigrationReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        LauncherIconController.tryFixLauncherIconIfNeeded();
    }
}
