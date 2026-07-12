package hk.uwu.reareye.funcardcore.wallpaperapi;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

interface IRearWallpaperHostService {
    Bundle getCapabilities();
    Bundle listWallpapers();
    Bundle importWallpaper(in ParcelFileDescriptor packageFd, String displayName);
    Bundle applyWallpaper(int wallpaperId);
    Bundle renameWallpaper(int wallpaperId, String displayName);
    Bundle deleteWallpaper(int wallpaperId);
}
