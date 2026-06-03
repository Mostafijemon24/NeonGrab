package com.neongrab.downloader;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import com.neongrab.downloader.ytdlp.YtDlpEngineHelper;
import com.neongrab.downloader.ytdlp.YtDlpPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(YtDlpPlugin.class);
        super.onCreate(savedInstanceState);
        YtDlpEngineHelper.initAsync(this);
    }
}
