package com.panpanpan.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class serviceStop extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(android.R.style.Theme_NoDisplay);
        super.onCreate(savedInstanceState);
        stopService(new Intent(this, MidiBridgeService.class));
    }

    @Override
    protected void onResume() {
        finish();
        super.onResume();
    }
}