package net.sourceforge.solitaire_cg;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/** Deterministic checks against the actual rules, controls, rendering, and save file. */
public class HintReview extends Instrumentation {
  private SolitaireCG activity;
  private SolitaireView view;
  private CardAnchor[] anchors;
  private Rules rules;
  private Stack<Move> history;
  private long downTime;
  private final StringBuilder passed = new StringBuilder();

  @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

  @Override public void onStart() {
    Bundle result = new Bundle();
    try {
      Intent intent = new Intent(getTargetContext(), SolitaireCG.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity = (SolitaireCG) startActivitySync(intent);
      waitForIdleSync();
      view = activity.findViewById(R.id.solitaire);

      check("all source types, suffixes, empty destinations and illegal exclusions", () -> {
        fixture(new String[]{"", "2H 3C AD", "AC 2C", "AS", "", "", "9D 7C 6H 5S", "8D", "KS QH", "", "3H", "2D", "9S"}, 1);
        KlondikeHints.Result hints = inspectUnchanged();
        require(has(hints,1,"AD",1,4), "waste Ace missing");
        require(has(hints,6,"7C",3,7), "tableau run missing");
        require(has(hints,6,"6H",2,6) == false, "same pile suggested");
        require(has(hints,8,"KS",2,9), "King run to empty missing");
        require(has(hints,2,"2C",1,10), "foundation to tableau missing");
        require(has(hints,3,"AS",1,4), "foundation Ace relocation missing");
        require(has(hints,3,"AS",1,11), "foundation Ace to tableau missing");
        for (KlondikeHints.MoveHint hint : hints.moves) {
          require(hint.source != hint.destination && hint.destination.GetNumber() >= 2, "invalid destination");
          require(hint.card != anchors[6].GetCards()[0], "hidden card suggested");
          if (hint.source == anchors[1]) require(hint.card == top(1), "covered waste suggested");
          if (hint.destination.GetNumber() < 6) require(hint.count == 1, "foundation run suggested");
          int rank = hint.card.GetValue(), suit = hint.card.GetSuit();
          Card target = hint.destination.GetCount() == 0 ? null :
              hint.destination.GetCards()[hint.destination.GetCount()-1];
          boolean legal = hint.destination.GetNumber() < 6 ?
              (target == null ? rank == 1 : suit == target.GetSuit() && rank == target.GetValue()+1) :
              (target == null ? rank == 13 : (suit % 2 != target.GetSuit() % 2) && rank == target.GetValue()-1);
          require(legal, "illegal rank/color/suit suggested");
        }
        require(!has(hints,6,"6H",2,7), "wrong rank accepted");
        require(!has(hints,6,"7C",3,4), "foundation run accepted");
      });

      check("button cycles every pair, wraps, preserves save/history/time and clears selection", () -> {
        simple();
        tap(6);
        require(field(view,"mTapCard") != null, "selection setup failed");
        view.SaveGame(); view.onResume();
        tap(6);
        byte[] saved = saveBytes();
        String before = state();
        Object elapsed = field(view,"mElapsed"), start = field(view,"mStartTime");
        List<KlondikeHints.MoveHint> moves = KlondikeHints.inspect(anchors).moves;
        require(moves.size() > 1, "multiple-move fixture required");
        for (int i = 0; i <= moves.size(); i++) {
          activity.findViewById(R.id.hint).performClick();
          KlondikeHints.MoveHint actual = current();
          KlondikeHints.MoveHint expected = moves.get(i % moves.size());
          require(same(actual, expected), "cycling mismatch at "+i);
          require(field(view,"mTapCard") == null, "hint became tap selection");
          require(state().equals(before), "hint changed board/history/coordinates");
          require(Arrays.equals(saved,saveBytes()), "hint wrote the save");
          require(elapsed.equals(field(view,"mElapsed")) && start.equals(field(view,"mStartTime")), "hint changed timer");
        }
      });

      check("one move remains stable; hinted tap/drag and Undo recompute", () -> {
        fixture(new String[]{"", "", "", "", "", "", "7C", "8H", "TC", "TS", "JC", "JS", "QC"},0);
        require(inspectUnchanged().moves.size()==1, "expected one move");
        view.ShowHint(); KlondikeHints.MoveHint first=current(); view.ShowHint();
        require(same(first,current()),"single move did not wrap");
        String before=state();
        tap(6); tap(7);
        require(current()==null && status().length()==0,"tap retained hint");
        view.ShowHint(); noMoves();
        view.Undo(); require(state().equals(before),"tap Undo mismatch");
        view.ShowHint(); require(same(first,current()),"Undo did not reset cycle");
        drag(6,7);
        require(current()==null && history.size()==1,"drag retained hint or wrong history");
        view.Undo(); require(state().equals(before),"drag Undo mismatch");
      });

      check("stock cue: final one/two, empty stock, and opportunity only after recycling", () -> {
        blocked("AC 2C 3C 4C", ""); stockExpected(true);
        blocked("AC 2C 3C 4C 5C", ""); stockExpected(true);
        blocked("", "4C 3C AC 2C"); stockExpected(true);
        blocked("5C", "4C 3C AC 2C"); stockExpected(true);
        view.ShowHint();
        require((Boolean)field(view,"mHintStock") && current()==null,"stock cue exposed future card");
        require(status().getText().toString().equals(activity.getString(R.string.dad_hint_stock)),"stock guidance missing");
        draw();
        require(!(Boolean)field(view,"mHintStock") && status().length()==0,"drawing retained stock hint or false no-moves");
      });

      check("complete exhausted cycle, empty stock/waste, and automatic nonmodal feedback", () -> {
        blocked("2C 3C 4C 5C", "6C"); stockExpected(false);
        draw();
        noMoves();
        String afterDraw=state(); view.ShowHint(); require(state().equals(afterDraw),"no-moves Hint mutated board");
        require(activity.findViewById(R.id.undo).isEnabled() && activity.findViewById(R.id.new_game).isEnabled(),"controls blocked");
        view.Undo(); require(status().length()==0,"Undo retained message");
        blocked("", "2C 3C 4C"); draw(); noMoves();
        blocked("", ""); stockExpected(false); view.ShowHint(); noMoves();
        activity.findViewById(R.id.new_game).performClick(); bind();
        require(status().length()==0 && history.empty(),"New Game retained message/history");
      });

      check("reversible legal moves prevent exhausted-stock claims", () -> {
        blocked("2C 3C", "");
        rules.SetIgnoreEvents(true);
        Card ace = removeHidden("AC"); anchors[2].AddCard(ace);
        rules.SetIgnoreEvents(false);
        require(!inspectUnchanged().moves.isEmpty(),"Ace relocation was filtered");
        draw(); require(status().length()==0,"automatic false no-moves on reversible move");
        view.ShowHint(); require(current()!=null,"reversible move missing from UI");
      });

      check("waste removal changes draw-three access and Undo restores it", () -> {
        fixture(new String[]{"5C", "4C 3C AC 2H", "", "", "", "", "3S", "9C", "9S", "TC", "TS", "JC", "QC"},0);
        String before=state(); view.ShowHint();
        require(has(inspectUnchanged(),1,"2H",1,6),"waste removal fixture failed");
        tap(1); tap(6);
        view.ShowHint(); require(current()!=null && current().card.GetValue()==1,"new waste Ace not found");
        view.Undo(); require(state().equals(before),"waste Undo mismatch");
        require(has(inspectUnchanged(),1,"2H",1,6),"old waste availability not restored");
        // Compare actual future exposed waste cards before/after the removal.
        String priorCycle=realWasteCycle();
        fixture(new String[]{"5C", "4C 3C AC 2H", "", "", "", "", "3S", "9C", "9S", "TC", "TS", "JC", "QC"},0);
        tap(1); tap(6);
        require(!priorCycle.equals(realWasteCycle()),"removal did not change draw access");
      });

      check("active touch/drag/expanded stack/animation/pending events reject inspection", () -> {
        simple(); String before=state();
        touch(MotionEvent.ACTION_DOWN,point(6)); view.ShowHint();
        require(current()==null && !activity.findViewById(R.id.hint).isEnabled(),"active touch accepted Hint");
        touch(MotionEvent.ACTION_MOVE,point(7));
        view.ShowHint(); require(current()==null,"drag accepted Hint");
        touch(MotionEvent.ACTION_CANCEL,point(7)); require(state().equals(before),"canceled drag lost cards");
        touch(MotionEvent.ACTION_DOWN,point(6));
        ((Runnable)field(view,"mExpandStack")).run();
        touch(MotionEvent.ACTION_UP,point(6));
        require((Integer)field(view,"mViewMode")==3,"expanded setup failed");
        view.ShowHint(); require(current()==null,"expanded stack accepted Hint");
        view.onPause(); view.onResume(); require(state().equals(before),"expanded cancel changed deal");
        rules.EventAlert(Rules.EVENT_DEAL,anchors[0]);
        view.ShowHint(); require(current()==null,"pending event accepted Hint"); rules.ClearEvent();
        setField(view,"mViewMode",5); view.ShowHint(); require(current()==null,"animation accepted Hint");
        setField(view,"mViewMode",1); view.Refresh();
      });

      check("pause/save/load/resize/win clear transient hints", () -> {
        simple(); view.ShowHint(); String before=state();
        view.onPause(); require(current()==null,"pause retained hint");
        view.ShowHint(); require(current()==null,"paused Hint accepted"); view.onResume();
        view.ShowHint(); require(view.SaveGame(),"save failed"); require(current()==null,"save retained hint");
        view.onResume(); view.ShowHint(); require(view.LoadSave(),"load failed"); bind();
        require(current()==null && state().equals(before),"load changed deal or retained hint");
        view.ShowHint(); view.onSizeChanged(view.getWidth(),view.getHeight(),view.getWidth()-1,view.getHeight());
        require(current()==null && state().equals(before),"resize retained hint or changed cards");
        view.ShowHint(); rules.mWon=true; view.DisplayWin();
        require(current()==null,"win retained hint"); view.ShowHint(); require(status().length()==0,"win showed no-moves");
        view.InitGame(Rules.KLONDIKE); bind();
      });

      check("cyan source/destination outlines and unchanged board/control geometry", () -> {
        simple(); view.ShowHint(); KlondikeHints.MoveHint hint=current();
        Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap));
        int inset=Math.round(4*activity.getResources().getDisplayMetrics().density);
        require(bitmap.getPixel((int)hint.card.GetX()+inset,(int)hint.card.GetY()+Card.HEIGHT/2)==0xff40e0ff,"source outline absent");
        CardAnchor dest=hint.destination;
        float x=dest.GetCount()==0?dest.GetX():top(dest.GetNumber()).GetX();
        float y=dest.GetCount()==0?dest.GetNewY():top(dest.GetNumber()).GetY();
        require(bitmap.getPixel((int)x+inset,(int)y+Card.HEIGHT/2)==0xff40e0ff,"destination outline absent");
        bitmap.recycle();
        View button=activity.findViewById(R.id.hint), undo=activity.findViewById(R.id.undo);
        require(button.getWidth()==undo.getWidth() && button.getHeight()==undo.getHeight(),"Hint differs from existing control size");
        require(button.getLeft()>undo.getRight(),"Hint overlaps Undo");
        require(anchors[0].GetNewY()==30 && anchors[6].GetCards()[0].GetY()==30+Card.HEIGHT+40,"card spacing changed");
      });
      screenshot("hint-pair.png");
      check("no-moves screenshot setup", () -> { blocked("2C 3C 4C",""); draw(); noMoves(); });
      screenshot("hint-no-moves.png");
      check("stock screenshot setup", () -> { blocked("AC 2C 3C 4C",""); view.ShowHint(); });
      screenshot("hint-stock.png");
      check("finish with ordinary playable deal", () -> { view.InitGame(Rules.KLONDIKE); bind(); view.SaveGame(); view.onResume(); });
      result.putString("passed",passed.toString()); finish(Activity.RESULT_OK,result);
    } catch (Throwable error) {
      result.putString("passed",passed.toString());
      result.putString("error",android.util.Log.getStackTraceString(error));
      finish(Activity.RESULT_CANCELED,result);
    }
  }

  private void check(String name,Runnable work) {
    Throwable[] failure=new Throwable[1];
    runOnMainSync(() -> { try { work.run(); unique(); } catch(Throwable e) { failure[0]=e; } });
    if(failure[0]!=null) throw new AssertionError(name,failure[0]);
    passed.append(name).append("; ");
  }

  @SuppressWarnings("unchecked") private void bind() {
    rules=view.GetRules(); anchors=rules.GetAnchorArray(); history=(Stack<Move>)field(view,"mMoveHistory");
  }

  private void fixture(String[] piles,int hiddenSix) {
    view.InitGame(Rules.KLONDIKE); view.onPause(); view.onResume(); bind();
    rules.ClearEvent(); rules.SetIgnoreEvents(true);
    for(CardAnchor a:anchors) { a.SetHiddenCount(0); while(a.GetCount()>0) a.PopCard(); }
    List<List<Card>> parsed=new ArrayList<>(); Set<Integer> used=new HashSet<>();
    for(String pile:piles) {
      List<Card> cards=new ArrayList<>(); parsed.add(cards);
      if(!pile.isEmpty()) for(String name:pile.split(" ")) {
        Card card=card(name); require(used.add(id(card)),"duplicate fixture card "+name); cards.add(card);
      }
    }
    for(int suit=0;suit<4;suit++) for(int value=1;value<=13;value++) {
      Card card=new Card(value,suit); if(!used.contains(id(card))) anchors[12].AddCard(card);
    }
    anchors[12].SetHiddenCount(anchors[12].GetCount());
    for(int i=0;i<13;i++) for(Card card:parsed.get(i)) anchors[i].AddCard(card);
    anchors[6].SetHiddenCount(hiddenSix); history.clear();
    rules.Resize(view.getWidth(),view.getHeight()); rules.SetIgnoreEvents(false); view.Refresh(); view.DrawBoard();
  }

  private void simple() { fixture(new String[]{"", "AD", "", "", "", "", "7C 6H 5S", "8D", "KS", "", "9C", "9S", "TC"},0); }
  private void blocked(String stock,String waste) { fixture(new String[]{stock,waste,"","","","","9C","9S","TC","TS","JC","JS","QC"},0); }
  private KlondikeHints.Result inspectUnchanged() { String before=state(); KlondikeHints.Result result=KlondikeHints.inspect(anchors); require(before.equals(state()),"inspection changed live state"); return result; }
  private void stockExpected(boolean expected) {
    KlondikeHints.Result hints=inspectUnchanged(); require(hints.moves.isEmpty(),"unexpected immediate move");
    require(hints.drawStock==expected,"stock-cycle result mismatch");
    // Independent oracle: let the real rules draw/recycle and inspect the actual waste top.
    Set<String> seen=new HashSet<>(); boolean found=false;
    while(seen.add(KlondikeHints.positionKey(anchors))) {
      rules.EventProcess(Rules.EVENT_DEAL,anchors[0]);
      if(anchors[1].GetCount()>0) for(int i=2;i<13;i++) if(anchors[i].CanBuildCards(top(1),1)) found=true;
    }
    require(found==expected,"actual stock events disagree with snapshot scan");
    while(!history.empty()) view.Undo();
  }
  private String realWasteCycle() {
    StringBuilder tops=new StringBuilder(); Set<String> seen=new HashSet<>();
    while(seen.add(KlondikeHints.positionKey(anchors))) { rules.EventProcess(Rules.EVENT_DEAL,anchors[0]); tops.append(anchors[1].GetCount()==0?0:id(top(1))).append(','); }
    return tops.toString();
  }
  private Card removeHidden(String name) {
    Card wanted=card(name); CardAnchor pile=anchors[12]; List<Card> cards=new ArrayList<>(); Card result=null;
    int hidden=pile.GetHiddenCount();
    for(int i=0;i<pile.GetCount();i++) { Card c=pile.GetCards()[i]; if(id(c)==id(wanted)) result=c; else cards.add(c); }
    require(result!=null,"hidden fixture card absent"); while(pile.GetCount()>0) pile.PopCard();
    for(Card c:cards) pile.AddCard(c); pile.SetHiddenCount(hidden-1); return result;
  }
  private void draw() { tap(0); }
  private void noMoves() { require(status().getText().toString().equals(activity.getString(R.string.dad_no_moves)),"no-moves feedback absent"); require((Integer)field(view,"mViewMode")==1,"message is modal"); }
  private TextView status() { return activity.findViewById(R.id.hint_status); }
  private KlondikeHints.MoveHint current() { return (KlondikeHints.MoveHint)field(view,"mHintMove"); }
  private static boolean same(KlondikeHints.MoveHint a,KlondikeHints.MoveHint b) { return a!=null && b!=null && a.source==b.source && a.card==b.card && a.count==b.count && a.destination==b.destination; }
  private static boolean has(KlondikeHints.Result result,int source,String name,int count,int destination) {
    Card wanted=card(name);
    for(KlondikeHints.MoveHint h:result.moves) if(h.source.GetNumber()==source && id(h.card)==id(wanted) && h.count==count && h.destination.GetNumber()==destination) return true;
    return false;
  }
  private static Card card(String name) { return new Card("A23456789TJQK".indexOf(name.charAt(0))+1,"CDSH".indexOf(name.charAt(1))); }
  private static int id(Card card) { return card.GetSuit()*13+card.GetValue(); }
  private Card top(int pile) { return anchors[pile].GetCards()[anchors[pile].GetCount()-1]; }
  private float[] point(int pile) {
    CardAnchor a=anchors[pile]; Card top=a.GetCount()==0?null:top(pile);
    return new float[]{(top==null?a.GetX():top.GetX())+Card.WIDTH/2f,(top==null?a.GetNewY():top.GetY())+Card.HEIGHT/2f};
  }
  private void tap(int pile) { float[] p=point(pile); touch(MotionEvent.ACTION_DOWN,p); touch(MotionEvent.ACTION_UP,p); }
  private void drag(int from,int to) { touch(MotionEvent.ACTION_DOWN,point(from)); touch(MotionEvent.ACTION_MOVE,point(to)); touch(MotionEvent.ACTION_UP,point(to)); }
  private void touch(int action,float[] p) { long now=SystemClock.uptimeMillis(); if(action==MotionEvent.ACTION_DOWN) downTime=now; MotionEvent event=MotionEvent.obtain(downTime,now,action,p[0],p[1],0); require(view.dispatchTouchEvent(event),"touch rejected"); event.recycle(); }
  private String state() {
    unique(); StringBuilder state=new StringBuilder(KlondikeHints.positionKey(anchors));
    for(CardAnchor a:anchors) for(int i=0;i<a.GetCount();i++) { Card c=a.GetCards()[i]; state.append('/').append(c.GetX()).append(',').append(c.GetY()); }
    for(Move m:history) state.append('/').append(m.GetFrom()).append(',').append(m.GetToBegin()).append(',').append(m.GetToEnd()).append(',').append(m.GetCount()).append(',').append(m.GetFlags());
    return state.append('/').append(rules.GetRulesExtra()).append('/').append(rules.HasPendingEvents()).toString();
  }
  private void unique() { Set<Integer> cards=new HashSet<>(); for(CardAnchor a:anchors) { require(a.GetHiddenCount()>=0 && a.GetHiddenCount()<=a.GetCount(),"bad hidden count"); for(int i=0;i<a.GetCount();i++) require(cards.add(id(a.GetCards()[i])),"duplicate card"); } require(cards.size()==52,"expected 52 anchored cards, got "+cards.size()); }
  private byte[] saveBytes() { try(FileInputStream input=new FileInputStream(getTargetContext().getFileStreamPath("solitairecg_save.bin"))) { byte[] bytes=new byte[(int)input.getChannel().size()]; int offset=0,n; while(offset<bytes.length && (n=input.read(bytes,offset,bytes.length-offset))>0) offset+=n; require(offset==bytes.length,"short save read"); return bytes; } catch(Exception e) { throw new IllegalStateException(e); } }
  private void screenshot(String name) throws Exception { waitForIdleSync(); SystemClock.sleep(200); Bitmap bitmap=getUiAutomation().takeScreenshot(); require(bitmap!=null,"screenshot failed"); try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getCacheDir(),name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,out); } bitmap.recycle(); }
  private static Object field(Object target,String name) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target); } catch(Exception e) { throw new IllegalStateException(e); } }
  private static void setField(Object target,String name,Object value) { try { Field f=target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target,value); } catch(Exception e) { throw new IllegalStateException(e); } }
  private static void require(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
