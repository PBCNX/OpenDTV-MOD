package com.pbcnx.opendtvmod.analyzer;

import androidx.annotation.Nullable;

import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import com.pbcnx.opendtvmod.ts.ServiceInfo;

/** Small statics every tab needs: context-free formatting and lookup. */
public final class AnalyzerUtil {

    private AnalyzerUtil() {
    }

    /** Board views only exist where the board is in the layout (portrait). */
    public static void setText(TextView v, CharSequence s) {
        if (v != null) v.setText(s);
    }

    @Nullable public static ServiceInfo findService(List<ServiceInfo> list, int sid) {
        for (ServiceInfo s : list) if (s.serviceId == sid) return s;
        return null;
    }

    public static String safe(String s) {
        return s == null ? "" : s;
    }

    public static String hex4(int v) {
        return String.format(Locale.US, "%04X", Math.max(v, 0));
    }

}
