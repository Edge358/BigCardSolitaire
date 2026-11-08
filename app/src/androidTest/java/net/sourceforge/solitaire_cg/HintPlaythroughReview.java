package net.sourceforge.solitaire_cg;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Drives Hint and real touch events; a separate rules oracle checks every no-moves claim. */
public class HintPlaythroughReview extends Instrumentation {
  private SolitaireCG activity;
  private SolitaireView view;
  private CardAnchor[] piles;
  private final StringBuilder trace = new StringBuilder();
  private int randomActions;
  private int randomBlocked;
  private int randomWon;
  private int randomLimited;

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      Intent intent = new Intent(getTargetContext(), SolitaireCG.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (SolitaireCG) startActivitySync(intent);
      waitForIdleSync(); view = activity.findViewById(R.id.solitaire);
      onMain(() -> {
        fixture(new String[]{"7C 8C 3C 4H 5C", "6C", "AC 2C", "AD 2D", "AS 2S", "AH 2H",
            "9C", "9S", "TC", "TS", "JC", "JS", "QC"});
        hint(); require((Boolean) field("mHintStock"), "initial Hint must guide stock");
        trace.append("Controlled play: Hint recommends stock; ");
        tapPile(0);
        require(top(1).GetValue() == 3 && top(1).GetSuit() == Card.CLUBS, "draw-three order wrong");
        hint(); KlondikeHints.MoveHint move = current();
        require(move != null && move.source == piles[1] && move.destination == piles[2], "Hint must offer 3C to foundation");
        execute(move); trace.append("draw three; Hint offers 3C to clubs; player taps that move; ");
        hint(); require(noMoves(), "Hint must report terminal blocked position");
        require(!oracleHasMove(), "independent oracle found a move in claimed blocked position");
        tapPile(0); require(noMoves(), "automatic feedback missing after drawing last two");
        trace.append("Hint reports no moves; last-two draw reports no moves automatically; ");
        Set<String> seen = new HashSet<>(); int stockActions = 0;
        while (seen.add(KlondikeHints.positionKey(piles))) {
          tapPile(0); require(noMoves() && !oracleHasMove(), "recycling changed blocked conclusion incorrectly");
          stockActions++;
        }
        trace.append(stockActions).append(" more stock actions through a repeated ordered state remain blocked; ");
        activity.findViewById(R.id.undo).performClick();
        require(status().length() == 0, "Undo failed to clear feedback");
        require((Integer)field("mViewMode") == 1, "feedback blocked gameplay");
        activity.findViewById(R.id.new_game).performClick(); piles = view.GetRules().GetAnchorArray();
        require(status().length() == 0, "New Game failed to clear feedback");
        trace.append("Undo and New Game work.\n");
      });
      onMain(() -> {
        String[] won = {"", "", "", "", "", "", "KC", "KD", "KS", "KH", "", "", ""};
        for (int suit = 0; suit < 4; suit++) {
          StringBuilder foundation = new StringBuilder();
          for (int rank = 1; rank <= 12; rank++) {
            if (rank > 1) foundation.append(' ');
            foundation.append("A23456789TJQK".charAt(rank-1)).append("CDSH".charAt(suit));
          }
          won[suit+2] = foundation.toString();
        }
        fixture(won);
        for (int i = 0; i < 4; i++) {
          KlondikeHints.MoveHint selected = null;
          int count = KlondikeHints.inspect(piles).moves.size();
          for (int j = 0; j < count; j++) {
            hint(); KlondikeHints.MoveHint h = current();
            if (h.card.GetValue() == 13 && h.destination.GetNumber() < 6) { selected = h; break; }
          }
          require(selected != null, "winning King missing from Hint cycle"); execute(selected);
        }
        require(view.GetRules().HasWon(), "four hinted Kings did not win");
        view.ShowHint(); require(!noMoves(), "win incorrectly reported no moves");
        trace.append("Win play: four hinted King moves reach the real win/replay state; no false no-moves message.\n");
        fresh();
      });

      // Ordinary shuffled games provide broader interaction coverage. The driver
      // avoids repeating the same card move from the same position, but is not a solver.
      for (int game = 0; game < 12; game++) {
        onMain(this::fresh);
        Set<String> attempted = new HashSet<>();
        boolean ended = false;
        for (int step = 0; step < 120; step++) {
          int[] outcome = {0};
          onMain(() -> {
            if (view.GetRules().HasWon()) { outcome[0] = 1; return; }
            unique();
            hint();
            boolean available = oracleHasMove();
            require(noMoves() == !available, "Hint disagrees with independent full-cycle oracle");
            if (!available) { outcome[0] = 2; return; }
            String position = KlondikeHints.positionKey(piles);
            KlondikeHints.MoveHint chosen = null;
            int count = KlondikeHints.inspect(piles).moves.size();
            for (int i = 0; i < count; i++) {
              KlondikeHints.MoveHint h = current();
              String action = position+":"+h.source.GetNumber()+":"+id(h.card)+":"+h.count+":"+h.destination.GetNumber();
              if (attempted.add(action)) { chosen = h; break; }
              hint();
            }
            if (chosen != null) execute(chosen); else tapPile(0);
            randomActions++;
            if (!view.GetRules().HasWon()) {
              unique();
              if (noMoves()) require(!oracleHasMove(), "automatic false no-moves claim");
            }
          });
          if (outcome[0] != 0) {
            if (outcome[0] == 1) randomWon++; else randomBlocked++;
            ended = true; break;
          }
        }
        if (!ended) randomLimited++;
      }
      onMain(() -> { fresh(); view.SaveGame(); view.onResume(); });
      trace.append("12 ordinary shuffled games: ").append(randomActions).append(" player actions; ")
          .append(randomBlocked).append(" verified no-moves endings; ").append(randomWon)
          .append(" wins; ").append(randomLimited).append(" reached the 120-action review limit without a recorded terminal result; the final post-limit board was not separately classified.\n")
          .append("No card loss/duplication or false no-moves claims. Random review limits are not claims of unwinnability.");
      result.putString("passed", trace.toString()); finish(Activity.RESULT_OK, result);
    } catch (Throwable error) {
      result.putString("passed", trace.toString());
      result.putString("error", android.util.Log.getStackTraceString(error));
      finish(Activity.RESULT_CANCELED, result);
    }
  }

  private void fresh() { view.InitGame(Rules.KLONDIKE); view.onPause(); view.onResume(); piles = view.GetRules().GetAnchorArray(); }

  private void fixture(String[] source) {
    fresh(); Rules rules = view.GetRules(); rules.ClearEvent(); rules.SetIgnoreEvents(true);
    for (CardAnchor p : piles) { p.SetHiddenCount(0); while (p.GetCount() > 0) p.PopCard(); }
    List<List<Card>> parsed = new ArrayList<>(); Set<Integer> used = new HashSet<>();
    for (String pile : source) {
      List<Card> cards = new ArrayList<>(); parsed.add(cards);
      if (!pile.isEmpty()) for (String name : pile.split(" ")) {
        Card card = new Card("A23456789TJQK".indexOf(name.charAt(0))+1, "CDSH".indexOf(name.charAt(1)));
        require(used.add(id(card)), "duplicate fixture card"); cards.add(card);
      }
    }
    for (int suit = 0; suit < 4; suit++) for (int rank = 1; rank <= 13; rank++) {
      Card card = new Card(rank, suit); if (!used.contains(id(card))) piles[12].AddCard(card);
    }
    piles[12].SetHiddenCount(piles[12].GetCount());
    for (int i = 0; i < 13; i++) for (Card card : parsed.get(i)) piles[i].AddCard(card);
    rules.Resize(view.getWidth(), view.getHeight()); rules.SetIgnoreEvents(false); view.Refresh(); view.DrawBoard(); unique();
  }

  private void hint() {
    String before = KlondikeHints.positionKey(piles);
    activity.findViewById(R.id.hint).performClick();
    require(before.equals(KlondikeHints.positionKey(piles)), "Hint changed the deal");
  }

  private void execute(KlondikeHints.MoveHint move) {
    Card first = move.card; CardAnchor source = move.source;
    int index = 0; while (source.GetCards()[index] != first) index++;
    float bottom = index+1 < source.GetCount() ? source.GetCards()[index+1].GetY() : first.GetY()+Card.HEIGHT;
    tap(first.GetX()+Card.WIDTH/2f, (first.GetY()+bottom)/2f);
    tapPile(move.destination.GetNumber());
    require(view.GetRules().HasWon() || move.destination.GetCards()[move.destination.GetCount()-move.count] == first, "hinted touch move failed");
  }

  private void tapPile(int index) {
    CardAnchor p = piles[index]; Card top = p.GetCount() == 0 ? null : top(index);
    tap((top == null ? p.GetX() : top.GetX())+Card.WIDTH/2f, (top == null ? p.GetNewY() : top.GetY())+Card.HEIGHT/2f);
  }

  private void tap(float x, float y) {
    long now = SystemClock.uptimeMillis();
    for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
      require(view.dispatchTouchEvent(event), "touch rejected"); event.recycle();
    }
  }

  // Independent oracle: no KlondikeHints methods or CanBuildCards predicates.
  private boolean oracleHasMove() {
    for (int source = 1; source < 13; source++) {
      CardAnchor p = piles[source];
      for (int first = p.GetCount()-1; first >= p.GetHiddenCount(); first--) {
        if (source < 6 && first != p.GetCount()-1) break;
        if (first+1 < p.GetCount()) {
          Card a = p.GetCards()[first], b = p.GetCards()[first+1];
          if (a.GetValue() != b.GetValue()+1 || a.GetSuit()%2 == b.GetSuit()%2) break;
        }
        for (int dest = 2; dest < 13; dest++) if (dest != source && legal(p.GetCards()[first], p.GetCount()-first, dest)) return true;
      }
    }
    List<Card> stock = copy(0), waste = copy(1); Set<String> seen = new HashSet<>();
    if (stock.isEmpty() && waste.isEmpty()) return false;
    while (seen.add(key(stock)+"/"+key(waste))) {
      if (stock.isEmpty()) while (!waste.isEmpty()) stock.add(waste.remove(waste.size()-1));
      else for (int n = 0; n < 3 && !stock.isEmpty(); n++) waste.add(stock.remove(stock.size()-1));
      if (!waste.isEmpty()) for (int dest = 2; dest < 13; dest++) if (legal(waste.get(waste.size()-1), 1, dest)) return true;
    }
    return false;
  }

  private boolean legal(Card card, int count, int dest) {
    Card target = piles[dest].GetCount() == 0 ? null : top(dest);
    if (dest < 6) return count == 1 && (target == null ? card.GetValue() == 1 : card.GetSuit() == target.GetSuit() && card.GetValue() == target.GetValue()+1);
    return target == null ? card.GetValue() == 13 : card.GetSuit()%2 != target.GetSuit()%2 && card.GetValue() == target.GetValue()-1;
  }
  private List<Card> copy(int index) { List<Card> cards = new ArrayList<>(); for (int i = 0; i < piles[index].GetCount(); i++) cards.add(piles[index].GetCards()[i]); return cards; }
  private String key(List<Card> cards) { StringBuilder key = new StringBuilder(); for (Card c : cards) key.append(id(c)).append(','); return key.toString(); }
  private Card top(int index) { return piles[index].GetCards()[piles[index].GetCount()-1]; }
  private static int id(Card card) { return card.GetSuit()*13+card.GetValue(); }
  private TextView status() { return activity.findViewById(R.id.hint_status); }
  private boolean noMoves() { return status().getText().toString().equals(activity.getString(R.string.dad_no_moves)); }
  private KlondikeHints.MoveHint current() { return (KlondikeHints.MoveHint)field("mHintMove"); }
  private Object field(String name) { try { Field f = SolitaireView.class.getDeclaredField(name); f.setAccessible(true); return f.get(view); } catch (Exception e) { throw new IllegalStateException(e); } }
  private void unique() { Set<Integer> cards = new HashSet<>(); for (CardAnchor p : piles) for (int i = 0; i < p.GetCount(); i++) require(cards.add(id(p.GetCards()[i])), "duplicate card"); require(cards.size() == 52, "missing cards"); }
  private void onMain(Runnable work) { Throwable[] failed = new Throwable[1]; runOnMainSync(() -> { try { work.run(); } catch (Throwable e) { failed[0] = e; } }); if (failed[0] != null) throw new AssertionError(failed[0]); }
  private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
