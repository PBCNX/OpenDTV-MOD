package com.pbcnx.opendtvmod.ui.watch;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Handler;

import androidx.annotation.Nullable;

import com.pbcnx.opendtvmod.tuner.ChannelEntry;
import com.pbcnx.opendtvmod.tuner.TunerHub;

/**
 * The accessors every collaborator seam repeats. One typed contract instead of each Host
 * redeclaring the screen/queue/prefs/current-channel accessors under slightly different names
 * and nullability promises.
 */
public interface ScreenSeam {
    Activity activity();

    Handler ui();

    TunerHub hub();

    TunerHub.Owner owner();

    @Nullable ChannelEntry currentChannel();

    void setCurrentChannel(ChannelEntry entry);

    boolean canTune();

    void setCanTune(boolean ready);

    SharedPreferences prefs();
}
