package com.pbcnx.opendtvmod.dev.screen;


import android.app.Activity;
import android.content.Intent;

import com.pbcnx.opendtvmod.bml.BmlController;

/** The hidden Developers screen's door: diagnostics flush first, then the activity. */
public final class DeveloperDoor {

    private DeveloperDoor() {
    }

    public static void open(Activity activity, BmlController bml) {
        if (bml != null && bml.isActive()) {
            bml.diagnostics(json -> { /* the controller stashes what it gets; nothing to do here */ });
        }
        activity.startActivity(new Intent(activity, DeveloperOptionsActivity.class));
    }
}
