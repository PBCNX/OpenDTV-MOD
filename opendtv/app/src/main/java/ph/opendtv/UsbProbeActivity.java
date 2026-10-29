package ph.opendtv;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** The USB probe, in a tree that carries no tuner driver: it can only report that. */
public class UsbProbeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        TextView t = new TextView(this);
        t.setPadding(48, 96, 48, 48);
        t.setText("No tuner driver is built into this tree.\n\n"
                + "The seam is here (TunerSource, TunerProvider, TunerRegistry); the drivers are in "
                + "their own repositories. See DRIVERS.md.");
        setContentView(t);
    }
}