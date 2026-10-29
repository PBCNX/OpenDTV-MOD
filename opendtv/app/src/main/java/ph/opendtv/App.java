package ph.opendtv;

import android.app.Application;

/**
 * The UI uses the fixed dark TV palette from {@code Theme.OpenDtv}. Dynamic
 * colours are intentionally not applied: they pull the toolbar and buttons
 * towards the phone wallpaper and break the 10-foot contrast contract.
 */
public class App extends Application {
}
