package com.hdklinik.isitme;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "ForegroundAudio")
public class ForegroundAudioPlugin extends Plugin {

  private final Handler main = new Handler(Looper.getMainLooper());

  @Override
  public void load() {
    AudioEngine.get().setListener(new AudioEngine.Listener() {
      @Override
      public void onGate(final boolean open) {
        main.post(() -> {
          JSObject o = new JSObject();
          o.put("open", open);
          notifyListeners("gate", o);
        });
      }

      @Override
      public void onLevel(final float level) {
        main.post(() -> {
          JSObject o = new JSObject();
          o.put("level", (double) level);
          notifyListeners("level", o);
        });
      }

      @Override
      public void onInputLevel(final float level) {
        main.post(() -> {
          JSObject o = new JSObject();
          o.put("level", (double) level);
          notifyListeners("inLevel", o);
        });
      }

      @Override
      public void onError(final String message) {
        main.post(() -> {
          JSObject o = new JSObject();
          o.put("message", message);
          notifyListeners("error", o);
        });
      }

      @Override
      public void onStopped(final String reason) {
        main.post(() -> {
          JSObject o = new JSObject();
          o.put("reason", reason);
          notifyListeners("stopped", o);
          try {
            getContext().stopService(new Intent(getContext(), ForegroundAudioService.class));
          } catch (Exception ignored) {}
        });
      }

      @Override
      public void onLatency(final float ms) {
        main.post(() -> {
          JSObject o = new JSObject();
          o.put("ms", (double) ms);
          notifyListeners("latency", o);
        });
      }
    });
  }

  @PluginMethod
  public void start(PluginCall call) {
    if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
      call.reject("mic_permission");
      return;
    }
    try {
      if (Build.VERSION.SDK_INT >= 33 && getActivity() != null
          && ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
             != PackageManager.PERMISSION_GRANTED) {
        ActivityCompat.requestPermissions(getActivity(), new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
      }
      Intent i = new Intent(getContext(), ForegroundAudioService.class);
      i.putExtra("params", call.getData().toString());
      ContextCompat.startForegroundService(getContext(), i);
      call.resolve();
    } catch (Exception e) {
      call.reject(String.valueOf(e.getMessage()));
    }
  }

  @PluginMethod
  public void setParams(PluginCall call) {
    AudioEngine.get().setParams(call.getData().toString());
    call.resolve();
  }

  @PluginMethod
  public void stop(PluginCall call) {
    AudioEngine.get().stop();
    getContext().stopService(new Intent(getContext(), ForegroundAudioService.class));
    call.resolve();
  }

  @PluginMethod
  public void openBatterySettings(PluginCall call) {
    try {
      Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
      i.setData(Uri.parse("package:" + getContext().getPackageName()));
      getActivity().startActivity(i);
    } catch (Exception e) {
      try {
        getActivity().startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
      } catch (Exception ignored) {}
    }
    call.resolve();
  }
}
