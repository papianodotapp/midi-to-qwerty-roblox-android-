package com.panpanpan.app;

import android.content.Intent;
import android.service.quicksettings.TileService;

public class tileService extends TileService {

    @Override
    public void onClick() {
        startActivityAndCollapse(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        super.onClick();
    }

}
