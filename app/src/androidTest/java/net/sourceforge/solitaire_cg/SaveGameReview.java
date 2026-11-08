package net.sourceforge.solitaire_cg;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Stack;

/** Run only in the separate .savereview app, never against a player's game. */
public class SaveGameReview extends Instrumentation {
  private SolitaireCG activity;
  private SolitaireView view;
  private boolean interrupt;
  private final StringBuilder passed = new StringBuilder();

  @Override public void onCreate(Bundle args) {
    super.onCreate(args);
    interrupt = args != null && "true".equals(args.getString("interrupt"));
    start();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      require(getTargetContext().getPackageName().endsWith(".savereview"), "Use isolated review application ID");
      if (interrupt) {
        // Simulate process death after only part of a replacement has been written.
        FileOutputStream out = save().startWrite();
        out.write(new byte[]{1, 2, 3});
        out.flush();
        out.getFD().sync();
        android.os.Process.killProcess(android.os.Process.myPid());
        return;
      }
      Intent intent = new Intent(getTargetContext(), SolitaireCG.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (SolitaireCG) startActivitySync(intent);
      waitForIdleSync();
      view = activity.findViewById(R.id.solitaire);
      check("opening retains a valid recovery save", () -> require(valid(), "Opening invalidated save"));
      check("repeated save/load retains board and Undo", () -> {
        view.InitGame(Rules.KLONDIKE);
        String original = state();
        deal();
        String moved = state();
        require(view.SaveGame() && view.SaveGame(), "save failed");
        require(view.LoadSave() && view.LoadSave(), "load failed");
        view.onResume();
        require(state().equals(moved), "board/history changed");
        view.Undo();
        require(state().equals(original), "Undo no longer reverses the move");
      });
      check("aborted replacement preserves committed bytes and Undo", () -> {
        deal();
        require(view.SaveGame(), "save failed");
        byte[] committed = save().readFully();
        String before = state();
        try {
          FileOutputStream out = save().startWrite();
          out.write(new byte[]{1, 2, 3});
          save().failWrite(out);
        } catch (Exception e) { throw new AssertionError(e); }
        require(Arrays.equals(committed, save().readFully()), "abort damaged committed bytes");
        require(view.LoadSave(), "recovery failed");
        view.onResume();
        require(state().equals(before), "recovery changed board/history");
      });
      check("failed background save keeps current deal and last good disk save", () -> {
        require(view.SaveGame(), "save failed");
        byte[] committed = save().readFully();
        view.onResume();
        deal();
        String unsaved = state();
        File blocked = blockWrite();
        try {
          require(!view.SaveGame(), "injected write failure not reported");
          require(valid(), "failed save invalidated last good save");
          activity.onPause(); activity.onStop(); activity.onStart(); activity.onResume();
          require(state().equals(unsaved), "returning reloaded an older deal");
          activity.findViewById(R.id.exit).performClick();
          require(!activity.isFinishing(), "failed Exit discarded the live game");
          require(!(Boolean) field(view, "mPaused"), "failed Exit left game paused");
          require(Arrays.equals(committed, readBase()), "failed save damaged prior bytes");
        } finally { unblockWrite(blocked); }
        require(view.SaveGame(), "retry failed");
        require(view.LoadSave(), "retry load failed");
        view.onResume();
        require(state().equals(unsaved), "retry changed board/history");
      });
      check("New Game checkpoints immediately; failed first save retires old deal", () -> {
        view.InitGame(Rules.KLONDIKE);
        String fresh = state();
        require(valid() && view.LoadSave(), "new deal has no checkpoint");
        require(state().equals(fresh), "new deal checkpoint differs");
        view.onResume();
        File blocked = blockWrite();
        try {
          view.InitGame(Rules.KLONDIKE);
          require(!valid(), "failed New Game can resurrect abandoned deal");
        } finally { unblockWrite(blocked); }
        require(view.SaveGame(), "new deal retry failed");
        view.onResume();
      });
      check("winning retires prior save before replay", () -> {
        require(valid(), "missing starting checkpoint");
        finishGame();
        require(view.GetRules().HasWon(), "winning move did not trigger win");
        require(!valid(), "win can resurrect unfinished deal");
        require(view.SaveGame() && !valid(), "saving during replay revived old deal");
        activity.onStart(); activity.onResume();
        require(view.GetRules().HasWon() && !valid(), "returning from win revived old deal");
        view.InitGame(Rules.KLONDIKE);
        deal();
        require(view.SaveGame(), "final fixture save failed");
        view.onResume();
      });
      result.putString("passed", passed.toString());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("failure", android.util.Log.getStackTraceString(error));
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private interface Check { void run() throws Exception; }
  private void check(String name, Check test) {
    Throwable[] failure = new Throwable[1];
    runOnMainSync(() -> {
      try { test.run(); } catch (Throwable error) { failure[0] = error; }
    });
    if (failure[0] != null) throw new AssertionError(name, failure[0]);
    passed.append(name).append("; ");
  }
  private AtomicFile save() { return new AtomicFile(getTargetContext().getFileStreamPath("solitairecg_save.bin")); }
  private boolean valid() { return activity.GetSettings().getBoolean("SolitaireSaveValid", false); }
  private byte[] readBase() throws Exception {
    try (java.io.FileInputStream in = new java.io.FileInputStream(save().getBaseFile())) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[4096]; int count;
      while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
      return out.toByteArray();
    }
  }
  private File blockWrite() throws Exception {
    // Android 11+ AtomicFile stages writes in .new; a nonempty directory forces IOException.
    require(android.os.Build.VERSION.SDK_INT >= 30, "failure injection requires API 30+");
    File dir = new File(save().getBaseFile() + ".new");
    require(dir.mkdir(), "cannot inject failure");
    require(new File(dir, "blocker").createNewFile(), "cannot block staging file");
    return dir;
  }
  private void unblockWrite(File dir) {
    require(new File(dir, "blocker").delete() && dir.delete(), "failure cleanup failed");
  }
  @SuppressWarnings("unchecked") private void finishGame() {
    Rules rules = view.GetRules();
    rules.ClearEvent(); rules.SetIgnoreEvents(true);
    CardAnchor[] anchors = rules.GetAnchorArray();
    for (CardAnchor anchor : anchors) {
      anchor.SetHiddenCount(0);
      while (anchor.GetCount() > 0) anchor.PopCard();
    }
    for (int suit = 0; suit < 4; suit++) {
      for (int value = 1; value <= 13; value++) {
        anchors[suit == 0 && value == 13 ? 6 : suit + 2].AddCard(new Card(value, suit));
      }
    }
    Stack<Move> history = (Stack<Move>) field(view, "mMoveHistory");
    history.clear();
    history.push(new Move(6, 2, 1, false, false));
    rules.SetIgnoreEvents(false);
    anchors[2].AddCard(anchors[6].PopCard());
    rules.HandleEvents();
  }
  private void deal() {
    view.GetRules().EventAlert(Rules.EVENT_DEAL, view.GetRules().GetAnchorArray()[0]);
    view.GetRules().HandleEvents();
  }
  @SuppressWarnings("unchecked") private String state() {
    StringBuilder state = new StringBuilder();
    boolean[] seen = new boolean[52]; int count = 0;
    for (CardAnchor anchor : view.GetRules().GetAnchorArray()) {
      state.append('/').append(anchor.GetHiddenCount()).append(':');
      for (int i = 0; i < anchor.GetCount(); i++) {
        Card card = anchor.GetCards()[i];
        int id = card.GetSuit() * 13 + card.GetValue() - 1;
        require(!seen[id], "duplicate card"); seen[id] = true; count++;
        state.append(id).append(',');
      }
    }
    require(count == 52, "missing cards");
    for (Move move : (Stack<Move>) field(view, "mMoveHistory")) {
      state.append('|').append(move.GetFrom()).append(',').append(move.GetToBegin())
          .append(',').append(move.GetToEnd()).append(',').append(move.GetCount()).append(',').append(move.GetFlags());
    }
    return state.toString();
  }
  private static Object field(Object object, String name) {
    try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
    catch (Exception e) { throw new AssertionError(e); }
  }
  private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
