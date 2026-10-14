package com.hdklinik.isitme;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
  @Override
  public void onCreate(Bundle savedInstanceState) {
    registerPlugin(ForegroundAudioPlugin.class);
    super.onCreate(savedInstanceState);
  }
}
