package io.github.edrock200.appdownloader.install;

import android.os.ParcelFileDescriptor;
import android.content.IntentSender;

interface IShizukuInstaller {
    void install(in ParcelFileDescriptor[] apks, in String[] names, in long[] sizes,
                 in IntentSender result) = 1;
    int getUid() = 2;
    void destroy() = 16777114;
}
