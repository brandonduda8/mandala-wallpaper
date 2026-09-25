package com.apexforge.mandalawallpaper;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.util.Log;

/**
 * Debug-only helper (never merged into release builds) so CI can bind the
 * live wallpaper without the system picker: `cmd wallpaper set-component`
 * does not exist on API 34, but the public WallpaperManager API works.
 */
public class DebugSetWallpaperActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            WallpaperManager wm = WallpaperManager.getInstance(this);
            wm.setWallpaperComponent(new ComponentName(this, MandalaWallpaperService.class));
            Log.i("MandalaDebug", "wallpaper component set OK");
        } catch (Exception e) {
            Log.e("MandalaDebug", "setWallpaperComponent failed", e);
        }
        finish();
    }
}
