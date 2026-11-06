package com.pbcnx.opendtvmod;

import android.app.Application;

/**
 * The UI uses the fixed dark TV palette from {@code Theme.OpenDtv}. Dynamic colours are
 * intentionally not applied: they pull the toolbar and buttons towards the phone wallpaper and break
 * the 10-foot contrast contract.
 *
 * <p>It also owns the one piece of process-wide state the app has, the developer surface: the hidden
 * Developers screen, its door and the handlers that can act live there, typed and owned in one place
 * instead of in static fields another class reaches into.</p>
 */
public class App extends Application {

    /** The one SharedPreferences file for the app (screens, analyzer and players read it). */
    public static final String PREFS = "opendtv_prefs";

    @Override
    public void onCreate() {
        super.onCreate();
        // The one-time decoder-prefs migration runs before any player or session reads a mode:
        // it used to sit inside PlayerFactory.build, after the modes had already been resolved.
        com.pbcnx.opendtvmod.player.DecoderPrefs.migrate(this);
        DIAGNOSTICS.reset();
        // The process owns the channel list too: a TV-input process can start with no activity, so
        // hydration cannot be the watch screen's job.
        channels.loadAll(com.pbcnx.opendtvmod.tuner.ChannelStore.prefs(this));
    }

    /** The developer surface's state, created once with the process. */
    private final com.pbcnx.opendtvmod.dev.DeveloperSurface developer = new com.pbcnx.opendtvmod.dev.DeveloperSurface();

    /** Process-wide diagnostics state; the developers screen reads it through App.diagnostics(). */
    private static final Diagnostics DIAGNOSTICS = new Diagnostics();

    /** The one diagnostics object for this process. */
    public static Diagnostics diagnostics() {
        return DIAGNOSTICS;
    }

    /** The channel list and favourites: process-wide, like the tuner hub. */
    private final com.pbcnx.opendtvmod.tuner.ChannelStore channels = new com.pbcnx.opendtvmod.tuner.ChannelStore();

    /** The surface for this process; the one way callers reach it. */
    public static com.pbcnx.opendtvmod.dev.DeveloperSurface developer(android.content.Context context) {
        return ((App) context.getApplicationContext()).developer;
    }

    /** The tuner hub for this process: the one dongle owner. */
    public static com.pbcnx.opendtvmod.tuner.TunerHub hub(android.content.Context context) {
        return com.pbcnx.opendtvmod.tuner.TunerHub.get(context);
    }

    /** The channel list for this process: one list, whatever screen is showing it. */
    public static com.pbcnx.opendtvmod.tuner.ChannelStore channels(android.content.Context context) {
        return ((App) context.getApplicationContext()).channels;
    }
}
