/* Copyright (C) 2026 jpi59. SPDX-License-Identifier: GPL-3.0-or-later */
package org.jpi59.ethichandoff;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

public class AppFont {

    private static Typeface regular;
    private static Typeface medium;
    private static Typeface semibold;
    private static Typeface bold;

    public static void init(Context context) {
        if (regular != null) return;
        try {
            Typeface base = context.getResources().getFont(R.font.plus_jakarta_sans);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                regular = Typeface.create(base, 400, false);
                medium = Typeface.create(base, 500, false);
                semibold = Typeface.create(base, 600, false);
                bold = Typeface.create(base, 700, false);
            } else {
                regular = Typeface.create(base, Typeface.NORMAL);
                medium = Typeface.create(base, Typeface.NORMAL);
                semibold = Typeface.create(base, Typeface.BOLD);
                bold = Typeface.create(base, Typeface.BOLD);
            }
        } catch (Exception e) {
            Log.e("EthicOneCall", "Failed to load Plus Jakarta Sans font, falling back", e);
            regular = Typeface.create("sans-serif", Typeface.NORMAL);
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            semibold = Typeface.create("sans-serif-medium", Typeface.BOLD);
            bold = Typeface.create("sans-serif", Typeface.BOLD);
        }
    }

    public static Typeface regular() {
        return regular != null ? regular : Typeface.DEFAULT;
    }

    public static Typeface medium() {
        return medium != null ? medium : Typeface.DEFAULT_BOLD;
    }

    public static Typeface semibold() {
        return semibold != null ? semibold : Typeface.DEFAULT_BOLD;
    }

    public static Typeface bold() {
        return bold != null ? bold : Typeface.DEFAULT_BOLD;
    }
}
