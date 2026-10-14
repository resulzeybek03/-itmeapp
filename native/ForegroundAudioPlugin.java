package com.hdklinik.isitme;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ForegroundAudio")
public class ForegroundAudioPlugin extends Plugin {

  @PluginMethod
  public void start(PluginCall call) {
    try {
      if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
          && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
             != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
      }
      ContextCompat.startForegroundService(getContext(), new Intent(getContext(), ForegroundAudioService.class));
      call.resolve();
    } catch (Exception e) {
      call.reject(String.valueOf(e.getMessage()));
    }
  }

  @PluginMethod
  public void stop(PluginCall call) {
    getContext().stopService(new Intent(getContext(), ForegroundAudioService.class));
    call.resolve();
  }
}
