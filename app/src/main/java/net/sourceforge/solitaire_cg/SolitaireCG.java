/*
  Copyright 2008 Google Inc.
  
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  
       http://www.apache.org/licenses/LICENSE-2.0
  
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

  Modified by Curtis Gedak 2015, 2016, 2017, 2024, 2025
*/
// Modified for Big Card Solitaire tablet edition, September 2026.
package net.sourceforge.solitaire_cg;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Menu;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.TextView;
import android.widget.Toast;

// A single Klondike table with visible controls; the existing rules and input remain.
public class SolitaireCG extends Activity {
  private View mMainView;
  private SolitaireView mSolitaireView;
  private SharedPreferences mSettings;
  private boolean mExiting;
  private boolean mInitialized;

  public SharedPreferences GetSettings() { return mSettings; }

  @Override
  protected void attachBaseContext(Context base) {
    Configuration config = new Configuration(base.getResources().getConfiguration());
    config.fontScale = 1.0f;
    if (Build.VERSION.SDK_INT >= 24) {
      config.densityDpi = DisplayMetrics.DENSITY_DEVICE_STABLE;
    }
    config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
        | Configuration.UI_MODE_NIGHT_NO;
    super.attachBaseContext(base.createConfigurationContext(config));
  }

  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    mSettings = getSharedPreferences("SolitairePreferences", MODE_PRIVATE);
    SharedPreferences.Editor editor = mSettings.edit();
    // Do not try to load another variant into this edition's Klondike table.
    if (mSettings.getInt("LastType", Rules.KLONDIKE) != Rules.KLONDIKE) {
      editor.putBoolean("SolitaireSaveValid", false);
    }
    editor.putBoolean("LockLandscape", true)
        .putBoolean("KlondikeDealThree", true)
        .putBoolean("DisplayBigCards", false)
        .putBoolean("KlondikeStyleNormal", true)
        .putBoolean("DisplayTime", false)
        .putInt("LastType", Rules.KLONDIKE).commit();

    setContentView(R.layout.main);
    getWindow().setBackgroundDrawableResource(R.color.table_green);
    applyImmersiveMode();
    mMainView = findViewById(R.id.main_view);
    mSolitaireView = (SolitaireView) findViewById(R.id.solitaire);
    mSolitaireView.SetTextView((TextView) findViewById(R.id.text));
    mSolitaireView.SetHintControls(findViewById(R.id.hint), (TextView) findViewById(R.id.hint_status));
    findViewById(R.id.hint).setOnClickListener(v -> {
      mSolitaireView.ShowHint();
      mSolitaireView.requestFocus();
    });
    findViewById(R.id.new_game).setOnClickListener(v -> {
      mSolitaireView.InitGame(Rules.KLONDIKE);
      mSolitaireView.requestFocus();
    });
    findViewById(R.id.undo).setOnClickListener(v -> {
      mSolitaireView.Undo();
      mSolitaireView.requestFocus();
    });
    findViewById(R.id.exit).setOnClickListener(v -> {
      // Save synchronously before finishing so Exit can safely end this process.
      if (!mSolitaireView.SaveGame()) {
        mSolitaireView.onResume();
        Toast.makeText(this, R.string.dad_save_failed, Toast.LENGTH_LONG).show();
        return;
      }
      mExiting = true;
      finishAndRemoveTask();
    });
  }

  @Override
  public void onStart() {
    super.onStart();
    mSolitaireView.onStart();
    // A stopped Activity still owns its current deal, even if saving failed.
    if (mInitialized) return;
    mInitialized = true;
    if (mSettings.getBoolean("SolitaireSaveValid", false)) {
      if (mSolitaireView.LoadSave()) {
        return;
      }
    }
    // No first-play splash or hidden-menu instructions in this edition.
    mSolitaireView.InitGame(Rules.KLONDIKE);
  }

  @Override
  protected void onResume() {
    super.onResume();
    mSolitaireView.onResume();
    applyImmersiveMode();
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      applyImmersiveMode();
    }
  }

  // Immersive full-screen mode, using Android's native Java APIs.
  private void applyImmersiveMode() {
    if (Build.VERSION.SDK_INT >= 30) {
      getWindow().setDecorFitsSystemWindows(false);
      WindowInsetsController controller = getWindow().getInsetsController();
      if (controller != null) {
        controller.setSystemBarsBehavior(
            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsets.Type.systemBars());
      }
    } else {
      getWindow().getDecorView().setSystemUiVisibility(
          View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
          | View.SYSTEM_UI_FLAG_FULLSCREEN
          | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
          | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
          | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
          | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
  }

  @Override
  protected void onPause() {
    mSolitaireView.onPause();
    super.onPause();
  }

  @Override
  protected void onStop() {
    mSolitaireView.SaveGame();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (mExiting) {
      android.os.Process.killProcess(android.os.Process.myPid());
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) { return false; }

  // Retained for the upstream Options/Stats source, which is not exposed here.
  public void CancelOptions() {
    setContentView(mMainView);
    mSolitaireView.requestFocus();
    mSolitaireView.SetTimePassing(true);
  }

  public void RefreshOptions() {
    setContentView(mMainView);
    mSolitaireView.RefreshOptions();
  }
}
