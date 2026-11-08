package net.sourceforge.solitaire_cg;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.ViewConfiguration;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Stack;

/** Device-side movement checks against the real View, rules, history and save code. */
public class TapMoveReview extends Instrumentation {
  private SolitaireCG activity;
  private SolitaireView view;
  private CardAnchor[] anchors;
  private Rules rules;
  private Stack<Move> history;
  private final StringBuilder passed = new StringBuilder();
  private long downTime;

  @Override public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      Intent intent = new Intent(getTargetContext(), SolitaireCG.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (SolitaireCG) startActivitySync(intent);
      waitForIdleSync();
      view = activity.findViewById(R.id.solitaire);

      check("stationary selection, jitter, cancel and reselect", () -> {
        basic();
        String before = state();
        float[] p = point(6);
        touch(MotionEvent.ACTION_DOWN, p);
        require(state().equals(before), "DOWN changed the deal");
        touch(MotionEvent.ACTION_MOVE, new float[]{p[0] + 1, p[1] + 1});
        touch(MotionEvent.ACTION_UP, new float[]{p[0] + 1, p[1] + 1});
        require(field(view, "mTapCard") == top(6), "jitter did not select");
        require(state().equals(before), "selection changed deal/history");
        tap(6);
        require(field(view, "mTapCard") == null, "source tap did not clear");
        tap(6);
        tap(8);
        require(field(view, "mTapCard") == top(8), "source change failed");
        tapAt(new float[]{view.getWidth() / 2f, view.getHeight() - 5});
        require(field(view, "mTapCard") == null, "table tap did not clear");
        require(state().equals(before), "cancel/reselect changed deal/history");
      });

      check("light finger taps with normal finger movement: 7S to 8H", () -> {
        basic();
        setDeal(new String[]{"", "", "", "", "", "", "7S", "8H", "", "", "", "", ""}, 0);
        String before = state();
        float[] p = point(6);
        lightTouch(MotionEvent.ACTION_DOWN, p);
        lightTouch(MotionEvent.ACTION_MOVE, new float[]{p[0] + 28, p[1] + 12});
        lightTouch(MotionEvent.ACTION_UP, new float[]{p[0] + 28, p[1] + 12});
        require(field(view, "mTapCard") == top(6),
            "light tap was lost to a tiny drag; slop=" + field(view, "mTouchSlop"));
        require(state().equals(before), "light source tap changed deal/history");
        p = point(7);
        lightTouch(MotionEvent.ACTION_DOWN, p);
        lightTouch(MotionEvent.ACTION_MOVE, new float[]{p[0] - 28, p[1] + 12});
        lightTouch(MotionEvent.ACTION_UP, new float[]{p[0] - 28, p[1] + 12});
        require(anchors[6].GetCount() == 0 && anchors[7].GetCount() == 2 && history.size() == 1,
            "light destination tap did not move seven onto eight");
        view.Undo();
        require(state().equals(before), "light-tap Undo mismatch");
      });

      check("selection feedback appears on contact before release", () -> {
        basic();
        String before = state();
        float[] p = point(6);
        lightTouch(MotionEvent.ACTION_DOWN, p);
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        int border = bitmap.getPixel((int) top(6).GetX() + 3, (int) p[1]);
        bitmap.recycle();
        require(border == 0xffffb300, "no immediate gold selection feedback");
        require(state().equals(before), "contact feedback changed deal/history");
        lightTouch(MotionEvent.ACTION_CANCEL, p);
        require(state().equals(before) && field(view, "mTapCard") == null, "canceled preview selected/moved cards");
      });

      check("tableau single, hidden reveal, multi-card run and Undo", () -> {
        basic();
        String before = state();
        tap(6); tap(7);
        require(anchors[6].GetHiddenCount() == 0 && anchors[7].GetCount() == 2,
            "single move/reveal failed");
        require(history.size() == 1 && history.peek().GetUnhide(), "reveal history missing");
        Card first = anchors[7].GetCards()[0];
        tapAt(exposed(7, 0)); tap(9);
        require(anchors[9].GetCount() == 3 && anchors[9].GetCards()[1] == first,
            "run move failed");
        require(history.size() == 2 && history.peek().GetCount() == 2, "run history wrong");
        view.Undo(); view.Undo();
        require(state().equals(before), "Undo did not restore hidden card and run");
      });

      check("waste, King to empty tableau, Ace to foundation and Undo", () -> {
        basic();
        String before = state();
        tap(1); tap(6);
        require(anchors[1].GetCount() == 0 && top(6).GetValue() == 4, "waste move failed");
        view.Undo();
        tap(10); tap(12);
        require(anchors[10].GetCount() == 0 && top(12).GetValue() == 13, "King move failed");
        view.Undo();
        tap(11); tap(3);
        require(anchors[11].GetCount() == 0 && top(3).GetSuit() == Card.CLUBS, "Ace move failed");
        view.Undo();
        require(state().equals(before), "waste/King/Ace Undo changed deal");
      });

      check("illegal rank, color, suit, empty column and foundation run", () -> {
        basic();
        String before = state();
        tap(6); tap(8); // 5C onto 6S
        require(state().equals(before), "wrong color accepted");
        tap(6); tap(9); // 5C onto 7S
        require(state().equals(before), "wrong rank accepted");
        tap(6); tap(12);
        require(state().equals(before) && field(view, "mTapCard") == top(6), "non-King accepted");
        basic();
        setDeal(new String[]{"", "2H", "AD", "", "", "", "3D", "2D AC", "", "", "", "", ""}, 0);
        before = state();
        tap(1); tap(2);
        require(state().equals(before), "wrong foundation suit accepted");
        tap(6); tap(2);
        require(state().equals(before), "wrong foundation rank accepted");
        tapAt(exposed(7, 0)); tap(2);
        require(state().equals(before), "multi-card foundation accepted");
      });

      check("drag single/run/waste/King/Ace, invalid return, selected drag", () -> {
        basic();
        String before = state();
        tap(6);
        drag(point(6), point(7), false);
        require(anchors[7].GetCount() == 2, "drag after selecting failed");
        drag(exposed(7, 0), point(9), false);
        require(anchors[9].GetCount() == 3, "run drag failed");
        view.Undo(); view.Undo();
        drag(point(1), point(6), false); view.Undo();
        drag(point(10), point(12), false); view.Undo();
        drag(point(11), point(3), false); view.Undo();
        require(state().equals(before), "drag Undo mismatch");
        drag(point(6), new float[]{view.getWidth() / 2f, view.getHeight() - 5}, false);
        require(state().equals(before), "invalid drag changed deal");
        drag(point(6), point(7), true);
        require(anchors[7].GetCount() == 2, "UP-only displacement lost move");
        view.Undo();
        require(state().equals(before), "UP-only Undo mismatch");
      });

      check("ACTION_CANCEL, extra pointer and missing UP restore cards", () -> {
        basic();
        String before = state();
        float[] p = point(6), q = point(7);
        tap(6);
        touch(MotionEvent.ACTION_DOWN, p);
        touch(MotionEvent.ACTION_MOVE, q);
        touch(MotionEvent.ACTION_CANCEL, q);
        require(state().equals(before), "CANCEL committed a legal drop");
        tap(6);
        touch(MotionEvent.ACTION_DOWN, p);
        touch(MotionEvent.ACTION_POINTER_DOWN, p);
        touch(MotionEvent.ACTION_UP, q);
        require(state().equals(before) && field(view, "mTapCard") == null, "second pointer moved cards");
        touch(MotionEvent.ACTION_DOWN, p);
        touch(MotionEvent.ACTION_MOVE, q);
        touch(MotionEvent.ACTION_DOWN, p);
        touch(MotionEvent.ACTION_CANCEL, p);
        require(state().equals(before), "missing UP lost cards");
        touch(MotionEvent.ACTION_DOWN, point(0));
        touch(MotionEvent.ACTION_CANCEL, point(0));
        require(state().equals(before), "canceled stock tap dealt cards");
      });

      check("stock draw-three, selection clearing, recycling and Undo", () -> {
        basic();
        String before = state();
        int stock = anchors[0].GetCount();
        tap(6); tap(0);
        require(anchors[0].GetCount() == stock - 3 && field(view, "mTapCard") == null,
            "selected stock tap did not deal exactly three");
        view.Undo();
        require(state().equals(before), "stock Undo mismatch");
        while (anchors[0].GetCount() > 0) tap(0);
        String emptyStock = state();
        tap(0);
        require(anchors[1].GetCount() == 0 && anchors[0].GetCount() == stock + 1, "recycle failed");
        view.Undo();
        require(state().equals(emptyStock), "recycle Undo mismatch");
      });

      check("Undo while selected or dragging; save/load while selected or dragging", () -> {
        basic();
        String before = state();
        tap(6); tap(7); tap(8); view.Undo();
        require(state().equals(before) && field(view, "mTapCard") == null, "selected Undo failed");
        tap(6); tap(7);
        touch(MotionEvent.ACTION_DOWN, point(8));
        touch(MotionEvent.ACTION_MOVE, point(12));
        view.Undo();
        require(state().equals(before), "dragging Undo failed");
        tap(6); tap(7); tap(8);
        String saved = state();
        view.SaveGame();
        require(field(view, "mTapCard") == null && view.LoadSave(), "selected save/load failed");
        view.onResume(); bind();
        require(state().equals(saved), "selected save/load mismatch");
        touch(MotionEvent.ACTION_DOWN, point(8));
        touch(MotionEvent.ACTION_MOVE, point(12));
        view.SaveGame();
        require(view.LoadSave(), "drag save/load failed");
        view.onResume(); bind();
        require(state().equals(saved), "drag save/load lost cards/history");
        view.Undo();
        require(state().equals(before), "save/load Undo failed");
      });

      check("long run direct selection", () -> {
        basic();
        setDeal(new String[]{"", "", "", "", "", "", "KS QH JS TH 9S 8H 7S 6H 5S 4H 3S 2H AS", "TD", "", "", "", "", ""}, 0);
        String before = state();
        tapAt(exposed(6, 4)); tap(7);
        require(anchors[7].GetCount() == 10 && history.peek().GetCount() == 9, "long subrun failed");
        view.Undo();
        require(state().equals(before), "long subrun Undo failed");
      });

      check("expanded stack long-press setup", () -> {
        basic();
        setDeal(new String[]{"", "", "", "", "", "", "7C 6H 5S", "8D", "", "", "", "", ""}, 0);
        touch(MotionEvent.ACTION_DOWN, exposed(6, 0));
      });
      SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150);
      check("expanded stack tap selection and move", () -> {
        require((Integer) field(view, "mViewMode") == 3, "long-press did not expand");
        touch(MotionEvent.ACTION_UP, new float[]{100, 100});
        SelectCard selected = (SelectCard) field(view, "mSelectCard");
        Card[] cards = (Card[]) field(selected, "mCard");
        tapAt(new float[]{cards[0].GetX() + Card.WIDTH / 2f, cards[0].GetY() + Card.HEIGHT / 2f});
        require(anchors[6].GetCount() == 3 && field(view, "mTapCard") == topAt(6, 0),
            "expanded selection left cards detached");
        tap(7);
        require(anchors[7].GetCount() == 4 && history.peek().GetCount() == 3, "expanded tap move failed");
        view.Undo();
      });

      expandThree();
      check("expanded stack horizontal drag and Undo", () -> {
        SelectCard selected = (SelectCard) field(view, "mSelectCard");
        Card[] cards = (Card[]) field(selected, "mCard");
        drag(new float[]{cards[0].GetX() + Card.WIDTH / 2f, cards[0].GetY() + Card.HEIGHT / 2f}, point(7), false);
        require(anchors[7].GetCount() == 4 && history.peek().GetCount() == 3, "expanded drag failed");
        view.Undo();
        require(anchors[6].GetCount() == 3 && anchors[7].GetCount() == 1, "expanded drag Undo failed");
      });

      expandThree();
      check("expanded stack scrolling and cancellation", () -> {
        SelectCard selected = (SelectCard) field(view, "mSelectCard");
        Card[] cards = (Card[]) field(selected, "mCard");
        float startY = cards[0].GetY();
        float[] p = new float[]{cards[0].GetX() + Card.WIDTH / 2f, startY + Card.HEIGHT / 2f};
        float[] q = new float[]{p[0], p[1] - Card.HEIGHT / 2f};
        drag(p, q, false);
        require((Integer) field(view, "mViewMode") == 3 && cards[0].GetY() < startY,
            "expanded vertical scroll became a drag");
        touch(MotionEvent.ACTION_CANCEL, q);
        require(anchors[6].GetCount() == 3 && history.empty(), "expanded cancellation lost cards");
      });

      expandThree();
      check("expanded stack save/load", () -> {
        view.SaveGame();
        require(view.LoadSave(), "expanded save failed");
        view.onResume(); bind();
        require(anchors[6].GetCount() == 3 && history.empty(), "expanded save lost cards/history");
      });

      expandThree();
      check("expanded stack New Game", () -> {
        view.InitGame(Rules.KLONDIKE); settle();
        require(history.empty(), "expanded New Game retained history");
      });

      check("New Game while selected or dragging", () -> {
        basic(); tap(6); view.InitGame(Rules.KLONDIKE); settle();
        require(history.empty() && field(view, "mTapCard") == null, "selected New Game failed");
        basic();
        touch(MotionEvent.ACTION_DOWN, point(6));
        touch(MotionEvent.ACTION_MOVE, point(7));
        view.InitGame(Rules.KLONDIKE); settle();
        require(history.empty(), "dragging New Game retained history");
      });

      check("selection screenshot setup", () -> { basic(); tap(6); });
      waitForIdleSync();
      SystemClock.sleep(200);
      Bitmap screenshot = getUiAutomation().takeScreenshot();
      if (screenshot != null) {
        try (FileOutputStream output = new FileOutputStream(new File(getTargetContext().getCacheDir(), "tap-selection.png"))) {
          screenshot.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
        screenshot.recycle();
      }
      check("final untouched random deal", () -> { view.InitGame(Rules.KLONDIKE); settle(); view.SaveGame(); view.onResume(); });
      result.putString("passed", passed.toString());
      finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("passed", passed.toString());
      result.putString("error", android.util.Log.getStackTraceString(error));
      // Leave an ordinary deal even on a test failure.
      if (view != null) runOnMainSync(() -> { view.InitGame(Rules.KLONDIKE); view.SaveGame(); });
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void check(String name, Runnable test) {
    final Throwable[] failure = new Throwable[1];
    runOnMainSync(() -> {
      try {
        test.run();
        if ((Integer) field(view, "mViewMode") == 1) unique();
      } catch (Throwable error) { failure[0] = error; }
    });
    if (failure[0] != null) throw new AssertionError(name, failure[0]);
    passed.append(name).append("; ");
  }

  private void expandThree() {
    check("expanded gesture setup", () -> {
      basic();
      setDeal(new String[]{"", "", "", "", "", "", "7C 6H 5S", "8D", "", "", "", "", ""}, 0);
      touch(MotionEvent.ACTION_DOWN, exposed(6, 0));
    });
    SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150);
    check("expanded gesture ready", () -> {
      require((Integer) field(view, "mViewMode") == 3, "stack did not expand");
      touch(MotionEvent.ACTION_UP, new float[]{100, 100});
    });
  }

  @SuppressWarnings("unchecked") private void bind() {
    anchors = (CardAnchor[]) field(view, "mCardAnchor");
    rules = (Rules) field(view, "mRules");
    history = (Stack<Move>) field(view, "mMoveHistory");
  }

  private void settle() { view.onPause(); view.onResume(); bind(); unique(); }

  private void basic() {
    view.InitGame(Rules.KLONDIKE); settle();
    setDeal(new String[]{"", "4D", "AD", "", "", "", "9D 5C", "6H", "6S", "7S", "KS", "AC", ""}, 1);
  }

  private void setDeal(String[] piles, int hiddenInSix) {
    rules.ClearEvent(); rules.SetIgnoreEvents(true);
    for (CardAnchor anchor : anchors) {
      anchor.SetHiddenCount(0);
      while (anchor.GetCount() > 0) anchor.PopCard();
    }
    HashSet<Integer> used = new HashSet<>();
    for (int i = 0; i < piles.length; i++) {
      if (piles[i].isEmpty()) continue;
      for (String text : piles[i].split(" ")) {
        int value = "A23456789TJQK".indexOf(text.charAt(0)) + 1;
        int suit = "CDSH".indexOf(text.charAt(1));
        require(value > 0 && suit >= 0 && used.add(suit * 13 + value), "bad fixture " + text);
        anchors[i].AddCard(new Card(value, suit));
      }
    }
    for (int suit = 0; suit < 4; suit++) for (int value = 1; value <= 13; value++) {
      if (!used.contains(suit * 13 + value)) anchors[0].AddCard(new Card(value, suit));
    }
    anchors[6].SetHiddenCount(hiddenInSix);
    history.clear();
    rules.Resize(view.getWidth(), view.getHeight());
    rules.SetIgnoreEvents(false);
    view.DrawBoard(); view.invalidate();
    unique();
  }

  private Card top(int anchor) { return topAt(anchor, anchors[anchor].GetCount() - 1); }
  private Card topAt(int anchor, int index) { return anchors[anchor].GetCards()[index]; }

  private float[] point(int anchor) {
    CardAnchor pile = anchors[anchor];
    return new float[]{pile.GetCount() == 0 ? pile.GetX() + Card.WIDTH / 2f : top(anchor).GetX() + Card.WIDTH / 2f,
        pile.GetCount() == 0 ? pile.GetNewY() + Card.HEIGHT / 2f : top(anchor).GetY() + Card.HEIGHT / 2f};
  }

  private float[] exposed(int anchor, int index) {
    Card card = topAt(anchor, index);
    float bottom = index + 1 < anchors[anchor].GetCount() ? topAt(anchor, index + 1).GetY() : card.GetY() + Card.HEIGHT;
    return new float[]{card.GetX() + Card.WIDTH / 2f, (card.GetY() + bottom) / 2f};
  }

  private void tap(int anchor) { tapAt(point(anchor)); }
  private void tapAt(float[] p) { touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); }

  private void drag(float[] p, float[] q, boolean upOnly) {
    touch(MotionEvent.ACTION_DOWN, p);
    if (!upOnly) for (int i = 1; i <= 20; i++) {
      touch(MotionEvent.ACTION_MOVE, new float[]{p[0] + (q[0] - p[0]) * i / 20, p[1] + (q[1] - p[1]) * i / 20});
    }
    touch(MotionEvent.ACTION_UP, q);
  }

  private void touch(int action, float[] p) {
    long now = SystemClock.uptimeMillis();
    if (action == MotionEvent.ACTION_DOWN) downTime = now;
    MotionEvent event = MotionEvent.obtain(downTime, now, action, p[0], p[1], 0);
    require(view.dispatchTouchEvent(event), "touch not consumed");
    event.recycle();
  }

  private void lightTouch(int action, float[] p) {
    long now = SystemClock.uptimeMillis();
    if (action == MotionEvent.ACTION_DOWN) downTime = now;
    MotionEvent.PointerProperties properties = new MotionEvent.PointerProperties();
    properties.id = 0;
    properties.toolType = MotionEvent.TOOL_TYPE_FINGER;
    MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
    coords.x = p[0]; coords.y = p[1];
    coords.pressure = action == MotionEvent.ACTION_UP ? 0 : 0.03f;
    coords.size = 0.08f;
    MotionEvent event = MotionEvent.obtain(downTime, now, action, 1,
        new MotionEvent.PointerProperties[]{properties}, new MotionEvent.PointerCoords[]{coords},
        0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    require(view.dispatchTouchEvent(event), "light touch not consumed");
    event.recycle();
  }

  private String state() {
    unique();
    StringBuilder result = new StringBuilder();
    for (CardAnchor pile : anchors) {
      result.append('[').append(pile.GetHiddenCount()).append(':');
      for (int i = 0; i < pile.GetCount(); i++) {
        Card card = pile.GetCards()[i];
        result.append(card.GetSuit() * 13 + card.GetValue()).append(',');
      }
      result.append(']');
    }
    for (Move move : history) result.append('/').append(move.GetFrom()).append(',').append(move.GetToBegin())
        .append(',').append(move.GetToEnd()).append(',').append(move.GetCount()).append(',').append(move.GetFlags());
    return result.toString();
  }

  private void unique() {
    HashSet<Integer> cards = new HashSet<>();
    for (CardAnchor pile : anchors) {
      require(pile.GetHiddenCount() >= 0 && pile.GetHiddenCount() <= pile.GetCount(), "invalid hidden count");
      for (int i = 0; i < pile.GetCount(); i++) {
        Card card = pile.GetCards()[i];
        require(card != null && cards.add(card.GetSuit() * 13 + card.GetValue()), "duplicate/null card");
      }
    }
    require(cards.size() == 52, "expected 52 unique anchored cards, got " + cards.size());
  }

  private static Object field(Object target, String name) {
    try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }

  private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
