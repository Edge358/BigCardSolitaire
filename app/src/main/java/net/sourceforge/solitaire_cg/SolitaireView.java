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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.PointF;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.AtomicFile;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.StreamCorruptedException;
import java.util.Stack;

// The brains of the operation
public class SolitaireView extends View {

  private static final int MODE_NORMAL      = 1;
  private static final int MODE_MOVE_CARD   = 2;
  private static final int MODE_CARD_SELECT = 3;
  private static final int MODE_TEXT        = 4;
  private static final int MODE_ANIMATE     = 5;
  private static final int MODE_WIN         = 6;
  private static final int MODE_WIN_STOP    = 7;

  private static final String SAVE_FILENAME = "solitairecg_save.bin";
  // This is incremented only when the save system changes.
  private static final String SAVE_VERSION = "solitairecg_save_2";

  private CharSequence mSplashText;
  private CharSequence mWinText;

  private CardAnchor[] mCardAnchor;
  private boolean mIsLandscape;
  private DrawMaster mDrawMaster;
  private Rules mRules;
  private TextView mTextView;
  private AnimateCard mAnimateCard;
  private int mWidthPixels;
  private int mHeightPixels;
  private int mDpi;

  private MoveCard mMoveCard;
  private SelectCard mSelectCard;
  private int mViewMode;
  private boolean mTextViewDown;

  private PointF mLastPoint;
  private PointF mDownPoint;
  private RefreshHandler mRefreshHandler;
  private Stack<Move> mMoveHistory;
  private Replay mReplay;
  private Context mContext;
  private boolean mHasMoved;
  private Speed mSpeed;

  // A stationary selection owns no cards; anchors remain the complete deal.
  private CardAnchor mTapAnchor;
  private Card mTapCard;
  private CardAnchor mTouchAnchor;
  private Card mTouchCard;
  private int mTouchSlop;
  private boolean mTouchActive;
  private boolean mExpandedThisGesture;
  private boolean mExpandedScrolling;
  private final Paint mSelectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mHintEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private View mHintButton;
  private TextView mHintStatus;
  private KlondikeHints.Result mHintResult;
  private KlondikeHints.MoveHint mHintMove;
  private String mHintPosition;
  private int mHintIndex = -1;
  private boolean mHintStock;
  private boolean mCheckStockHint;
  private String mLastAutoNoMoves;
  private final Runnable mExpandStack = new Runnable() {
    @Override public void run() {
      if (mTouchActive && !mHasMoved && mViewMode == MODE_NORMAL &&
          mTouchAnchor instanceof GenericAnchor && mTouchAnchor.GetMovableCount() > 1) {
        ClearTapSelection();
        mSelectCard.InitFromAnchor(mTouchAnchor);
        ChangeViewMode(MODE_CARD_SELECT);
        mExpandedThisGesture = true;
        invalidate();
      }
    }
  };

  private Card[] mUndoStorage;

  private int mElapsed = 0;
  private long mStartTime;
  private boolean mTimePaused;

  private boolean mGameStarted;
  private boolean mPaused;
  private boolean mDisplayTime;

  private int mWinningScore;

  public SolitaireView(Context context, AttributeSet attrs) {
    super(context, attrs);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setLongClickable(false);

    // Get display properties
    mWidthPixels = getResources().getDisplayMetrics().widthPixels;
    mHeightPixels = getResources().getDisplayMetrics().heightPixels;
    mDpi = (int) (getResources().getDisplayMetrics().density * 160f);
    // Some devices report incorrect DPI so fall back to default
    if (mDpi < 60)
      mDpi = 160;

    // Get screen orientation
    mIsLandscape = (   getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE );

    mDrawMaster = new DrawMaster(context, mWidthPixels, mHeightPixels, mDpi);
    mMoveCard = new MoveCard();
    mSelectCard = new SelectCard();
    mViewMode = MODE_NORMAL;
    mLastPoint = new PointF();
    mDownPoint = new PointF();
    mRefreshHandler = new RefreshHandler(this);
    mMoveHistory = new Stack<Move>();
    mUndoStorage = new Card[CardAnchor.MAX_CARDS];
    mAnimateCard = new AnimateCard(this, mWidthPixels);
    mSpeed = new Speed();
    // Large cards need more finger-jitter tolerance than a scrolling widget.
    // No hold or pressure requirement: deliberate movement still starts a drag.
    mTouchSlop = Math.max(ViewConfiguration.get(context).getScaledTouchSlop(),
        Math.round(24 * mDpi / 160f));
    mSelectionPaint.setStyle(Paint.Style.STROKE);
    mSelectionPaint.setStrokeWidth(5 * mDpi / 160f);
    mSelectionPaint.setColor(0xffffb300);
    mHintPaint.setStyle(Paint.Style.STROKE);
    mHintPaint.setStrokeWidth(5 * mDpi / 160f);
    mHintPaint.setColor(0xff40e0ff);
    mHintEdgePaint.setStyle(Paint.Style.STROKE);
    mHintEdgePaint.setStrokeWidth(8 * mDpi / 160f);
    mHintEdgePaint.setColor(0xff003040);
    mReplay = new Replay(this, mAnimateCard);

    mSplashText = context.getResources().getText(R.string.splash_text);
    mWinText = context.getResources().getText(R.string.win_text);
    mContext = context;
    mTextViewDown = false;
    mWinningScore = 0;
  }

  public void InitGame(int gameType) {
    CancelTouchInteraction();
    // New Game deliberately replaces the old deal, even if its first save fails.
    GetSettings().edit().putBoolean("SolitaireSaveValid", false).commit();
    int oldScore = 0;
    String oldGameType = "None";

    // We really really want focus :)
    setFocusable(true);
    setFocusableInTouchMode(true);
    requestFocus();

    SharedPreferences.Editor editor = GetSettings().edit();
    if (mRules != null) {
      if (mRules.HasScore()) {
        if (mViewMode == MODE_WIN || mViewMode == MODE_WIN_STOP) {
          oldScore = mWinningScore;
        } else {
          oldScore = mRules.GetScore();
        }
        oldGameType = mRules.GetGameTypeString();
        if (oldScore > GetSettings().getInt(mRules.GetGameTypeString() + "Score", -52)) {
          editor.putInt(mRules.GetGameTypeString() + "Score", oldScore);
        }

      }
    }
    ChangeViewMode(MODE_NORMAL);
    mMoveHistory.clear();
    mRules = Rules.CreateRules(gameType, null, this, mMoveHistory, mAnimateCard);
    if (oldGameType == mRules.GetGameTypeString()) {
      mRules.SetCarryOverScore(oldScore);
    }
    Card.SetSize(gameType, mDrawMaster.GetWidth(), mDrawMaster.GetDpi(),
                 mIsLandscape);
    mDrawMaster.DrawCards(GetSettings().getBoolean("DisplayBigCards", false));
    mCardAnchor = mRules.GetAnchorArray();
    if (mDrawMaster.GetWidth() > 1) {
      mRules.Resize(mDrawMaster.GetWidth(), mDrawMaster.GetHeight());
      Refresh();
    }
    SetDisplayTime(GetSettings().getBoolean("DisplayTime", true));
    editor.putInt("LastType", gameType);
    editor.commit();
    mStartTime = SystemClock.uptimeMillis();
    mElapsed = 0;
    mTimePaused = false;
    mPaused = false;
    mGameStarted = false;
    WriteSave();
  }

  public Context GetContext() { return mContext; }
  public SharedPreferences GetSettings() { return ((SolitaireCG)mContext).GetSettings(); }
  public DrawMaster GetDrawMaster() { return mDrawMaster; }
  public Rules GetRules() { return mRules; }
  public void ClearGameStarted() { mGameStarted = false; }
  public void SetDisplayTime(boolean displayTime) { mDisplayTime = displayTime; }

  public void SetTimePassing(boolean timePassing) {
    if (timePassing == true && (mViewMode == MODE_WIN || mViewMode == MODE_WIN_STOP)) {
      return;
    }
    if (timePassing == true && mTimePaused == true) {
      mStartTime = SystemClock.uptimeMillis() - mElapsed;
      mTimePaused = false;
    } else if (timePassing == false) {
      mTimePaused = true;
    }
  }

  public void UpdateTime() {
    if (!mTimePaused) {
      int elapsed = (int)(SystemClock.uptimeMillis() - mStartTime);
      if (elapsed / 1000 > mElapsed / 1000) {
        Refresh();
      }
      mElapsed = elapsed;
    }
  }

  private void ChangeViewMode(int newMode) {
    ClearHint();
    if (newMode != MODE_NORMAL) ClearTapSelection();
    switch (mViewMode) {
      case MODE_NORMAL:
        if (newMode != MODE_NORMAL) {
          DrawBoard();
        }
        mTextView.setVisibility(View.INVISIBLE);
        break;
      case MODE_MOVE_CARD:
        mMoveCard.Release();
        DrawBoard();
        break;
      case MODE_CARD_SELECT:
        mSelectCard.Release();
        DrawBoard();
        break;
      case MODE_TEXT:
        mTextView.setVisibility(View.VISIBLE);
        break;
      case MODE_ANIMATE:
        mRefreshHandler.SetRefresh(RefreshHandler.SINGLE_REFRESH);
        break;
      case MODE_WIN:
      case MODE_WIN_STOP:
        if (newMode != MODE_WIN_STOP) {
          mTextView.setVisibility(View.INVISIBLE);
        }
        DrawBoard();
        mReplay.StopPlaying();
        break;
    }
    mViewMode = newMode;
    switch (newMode) {
      case MODE_WIN:
        SetTimePassing(false);
      case MODE_MOVE_CARD:
      case MODE_CARD_SELECT:
      case MODE_ANIMATE:
        mRefreshHandler.SetRefresh(RefreshHandler.LOCK_REFRESH);
        break;

      case MODE_NORMAL:
        mTextView.setVisibility(View.INVISIBLE);
        mRefreshHandler.SetRefresh(RefreshHandler.SINGLE_REFRESH);
        break;

      case MODE_TEXT:
        mTextView.setVisibility(View.VISIBLE);
      case MODE_WIN_STOP:
        mRefreshHandler.SetRefresh(RefreshHandler.SINGLE_REFRESH);
        break;
    }
    UpdateHintAvailability();
  }

  public void onPause() {
    CancelTouchInteraction();
    if (!mPaused) {
      mPaused = true;
      UpdateHintAvailability();
      mRefreshHandler.SetRunning(false);
      mRules.ClearEvent();
      mRules.SetIgnoreEvents(true);
      mReplay.StopPlaying();
      if (mAnimateCard.GetAnimate()) {
        mAnimateCard.Cancel();
      }
      mRules.FinishDeal();
      if (mViewMode != MODE_WIN && mViewMode != MODE_WIN_STOP) {
        ChangeViewMode(MODE_NORMAL);
      }

      if (mRules != null && mRules.GetScore() > GetSettings().getInt(mRules.GetGameTypeString() + "Score", -52)) {
        SharedPreferences.Editor editor = GetSettings().edit();
        editor.putInt(mRules.GetGameTypeString() + "Score", mRules.GetScore());
        editor.commit();
      }
    }
  }

  private AtomicFile SaveFile() {
    return new AtomicFile(mContext.getFileStreamPath(SAVE_FILENAME));
  }

  public boolean SaveGame() {
    if (mRules == null) return false;
    onPause();
    // Winning deliberately retires the saved deal before replay starts.
    if (mRules.HasWon()) return true;
    return WriteSave();
  }

  // Also checkpoint a freshly dealt game without pausing its controls.
  private boolean WriteSave() {
    if (mRules != null && mViewMode == MODE_NORMAL) {
      AtomicFile save = SaveFile();
      FileOutputStream fout = null;
      try {
        fout = save.startWrite();
        ObjectOutputStream oout = new ObjectOutputStream(fout);

        int cardCount = mRules.GetCardCount();
        int[] value = new int[cardCount];
        int[] suit = new int[cardCount];
        int[] anchorCardCount = new int[mCardAnchor.length];
        int[] anchorHiddenCount = new int[mCardAnchor.length];
        int historySize = mMoveHistory.size();
        int[] historyFrom = new int[historySize];
        int[] historyToBegin = new int[historySize];
        int[] historyToEnd = new int[historySize];
        int[] historyCount = new int[historySize];
        int[] historyFlags = new int[historySize];
        Card[] card;

        cardCount = 0;
        for (int i = 0; i < mCardAnchor.length; i++) {
          anchorCardCount[i] = mCardAnchor[i].GetCount();
          anchorHiddenCount[i] = mCardAnchor[i].GetHiddenCount();
          card = mCardAnchor[i].GetCards();
          for (int j = 0; j < anchorCardCount[i]; j++, cardCount++) {
            value[cardCount] = card[j].GetValue();
            suit[cardCount] = card[j].GetSuit();
          }
        }

        for (int i = 0; i < historySize; i++) {
          // Saving must not consume Undo, even if Exit and onStop both save.
          Move move = mMoveHistory.get(historySize - 1 - i);
          historyFrom[i] = move.GetFrom();
          historyToBegin[i] = move.GetToBegin();
          historyToEnd[i] = move.GetToEnd();
          historyCount[i] = move.GetCount();
          historyFlags[i] = move.GetFlags();
        }

        oout.writeObject(SAVE_VERSION);
        oout.writeInt(mCardAnchor.length);
        oout.writeInt(cardCount);
        oout.writeInt(mRules.GetType());
        oout.writeObject(anchorCardCount);
        oout.writeObject(anchorHiddenCount);
        oout.writeObject(value);
        oout.writeObject(suit);
        oout.writeInt(mRules.GetRulesExtra());
        oout.writeInt(mRules.GetScore());
        oout.writeInt(mElapsed);
        oout.writeObject(historyFrom);
        oout.writeObject(historyToBegin);
        oout.writeObject(historyToEnd);
        oout.writeObject(historyCount);
        oout.writeObject(historyFlags);
        // AtomicFile owns closing the stream: flush and sync before committing.
        oout.flush();
        fout.getFD().sync();
        save.finishWrite(fout);
        fout = null;

        SharedPreferences.Editor editor = GetSettings().edit();
        editor.putBoolean("SolitaireSaveValid", true);
        return editor.commit();
      } catch (IOException e) {
        Log.e("SolitaireView.java", "Save failed; keeping the previous completed save", e);
      } finally {
        if (fout != null) save.failWrite(fout);
      }
    }
    return false;
  }

  public boolean LoadSave() {
    ClearHint();
    mDrawMaster.DrawCards(GetSettings().getBoolean("DisplayBigCards", false));
    mTimePaused = true;

    try (FileInputStream fin = SaveFile().openRead();
         ObjectInputStream oin = new ObjectInputStream(fin)) {
      
      String version = (String)oin.readObject();
      if (!SAVE_VERSION.equals(version)) {
        Log.e("SolitaireView.java", "Invalid save version");
        return false;
      }
      Bundle map = new Bundle();
        
      map.putInt("cardAnchorCount", oin.readInt());
      map.putInt("cardCount", oin.readInt());
      int type = oin.readInt();
      map.putIntArray("anchorCardCount", (int[])oin.readObject());
      map.putIntArray("anchorHiddenCount", (int[])oin.readObject());
      map.putIntArray("value", (int[])oin.readObject());
      map.putIntArray("suit", (int[])oin.readObject());
      map.putInt("rulesExtra", oin.readInt());
      map.putInt("score", oin.readInt());
      mElapsed = oin.readInt();
      mStartTime = SystemClock.uptimeMillis() - mElapsed;
      int[] historyFrom = (int[])oin.readObject();
      int[] historyToBegin = (int[])oin.readObject();
      int[] historyToEnd = (int[])oin.readObject();
      int[] historyCount = (int[])oin.readObject();
      int[] historyFlags = (int[])oin.readObject();
      // onStart can reload into the same View after Home or screen sleep.
      // Replace the saved undo history instead of appending another copy.
      mMoveHistory.clear();
      for (int i = historyFrom.length - 1; i >= 0; i--) {
        mMoveHistory.push(new Move(historyFrom[i], historyToBegin[i], historyToEnd[i],
                                   historyCount[i], historyFlags[i]));
      }

      mGameStarted = !mMoveHistory.isEmpty();
      mRules = Rules.CreateRules(type, map, this, mMoveHistory, mAnimateCard);
      Card.SetSize(type, mDrawMaster.GetWidth(), mDrawMaster.GetDpi(),
                   mIsLandscape);
      SetDisplayTime(GetSettings().getBoolean("DisplayTime", true));
      mCardAnchor = mRules.GetAnchorArray();
      if (mDrawMaster.GetWidth() > 1) {
        mRules.Resize(mDrawMaster.GetWidth(), mDrawMaster.GetHeight());
        Refresh();
      }
      mTimePaused = false;

      // We really really really want focus :)
      setFocusable(true);
      setFocusableInTouchMode(true);
      requestFocus();

      return true;

    } catch (FileNotFoundException e) {
      Log.e("SolitaireView.java", "LoadSave(): File not found");
    } catch (StreamCorruptedException e) {
      Log.e("SolitaireView.java", "LoadSave(): Stream Corrupted");
    } catch (IOException e) {
      Log.e("SolitaireView.java", "LoadSave(): IOException");
    } catch (ClassNotFoundException e) {
      Log.e("SolitaireView.java", "LoadSave(): Class not found exception");
    }
    mTimePaused = false;
    mPaused = false;
    return false;
  }

  public void onResume() {
    mStartTime = SystemClock.uptimeMillis() - mElapsed;
    mRules.SetIgnoreEvents(false);
    mPaused = false;
    mRefreshHandler.SetRunning(true);
    Refresh();
  }

  public void onStart() {
    // Restore card size
    Card.SetSize(GetSettings().getInt("LastType", Rules.KLONDIKE),
                 mDrawMaster.GetWidth(), mDrawMaster.GetDpi(),
                 mIsLandscape);
  }

  public void Refresh() {
    mRefreshHandler.SingleRefresh();
    UpdateHintAvailability();
  }

  public void SetHintControls(View button, TextView status) {
    mHintButton = button;
    mHintStatus = status;
    UpdateHintAvailability();
  }

  private boolean CanShowHint() {
    return mRules != null && mRules.GetType() == Rules.KLONDIKE && !mRules.HasWon() &&
        !mPaused && !mTouchActive && mViewMode == MODE_NORMAL &&
        !mAnimateCard.GetAnimate() && !mRules.HasPendingEvents();
  }

  private void UpdateHintAvailability() {
    boolean available = CanShowHint();
    if (mHintButton != null) mHintButton.setEnabled(available);
    if (available && mCheckStockHint) {
      mCheckStockHint = false;
      KlondikeHints.Result result = KlondikeHints.inspect(mCardAnchor);
      String position = KlondikeHints.positionKey(mCardAnchor);
      if (result.moves.isEmpty() && !result.drawStock && !position.equals(mLastAutoNoMoves)) {
        mHintResult = result;
        mHintPosition = position;
        mLastAutoNoMoves = position;
        SetHintStatus(R.string.dad_no_moves);
        invalidate();
      }
    }
  }

  // Called after the real stock action completes, never while inspecting a hint.
  public void StockChanged() {
    ClearHint();
    mCheckStockHint = true;
    UpdateHintAvailability();
  }

  public void ShowHint() {
    if (!CanShowHint()) return;
    ClearTapSelection();
    String position = KlondikeHints.positionKey(mCardAnchor);
    if (!position.equals(mHintPosition)) {
      mHintResult = KlondikeHints.inspect(mCardAnchor);
      mHintPosition = position;
      mHintIndex = -1;
    }
    mHintMove = null;
    mHintStock = false;
    if (!mHintResult.moves.isEmpty()) {
      mHintIndex = (mHintIndex + 1) % mHintResult.moves.size();
      mHintMove = mHintResult.moves.get(mHintIndex);
      SetHintStatus(R.string.dad_hint_move);
    } else if (mHintResult.drawStock) {
      mHintStock = true;
      SetHintStatus(R.string.dad_hint_stock);
    } else {
      SetHintStatus(R.string.dad_no_moves);
    }
    invalidate();
  }

  private void SetHintStatus(int text) {
    if (mHintStatus != null) mHintStatus.setText(text);
  }

  private void ClearHint() {
    mHintResult = null;
    mHintMove = null;
    mHintPosition = null;
    mHintIndex = -1;
    mHintStock = false;
    mCheckStockHint = false;
    if (mHintStatus != null && mHintStatus.length() > 0) mHintStatus.setText("");
    invalidate();
  }

  private void DrawHint(Canvas canvas) {
    if (mHintPosition == null) return;
    if (!CanShowHint() || !mHintPosition.equals(KlondikeHints.positionKey(mCardAnchor))) {
      ClearHint();
      return;
    }
    if (mHintStock) {
      DrawHintOutline(canvas, mCardAnchor[0], null);
    } else if (mHintMove != null) {
      DrawHintOutline(canvas, mHintMove.source, mHintMove.card);
      CardAnchor destination = mHintMove.destination;
      DrawHintOutline(canvas, destination, destination.GetCount() == 0 ? null :
          destination.GetCards()[destination.GetCount() - 1]);
    }
  }

  private void DrawHintOutline(Canvas canvas, CardAnchor anchor, Card first) {
    float x = first == null ? anchor.GetX() : first.GetX();
    float y = first == null ? anchor.GetNewY() : first.GetY();
    float bottom = first == null ? y + Card.HEIGHT :
        anchor.GetCards()[anchor.GetCount() - 1].GetY() + Card.HEIGHT;
    float inset = mHintEdgePaint.getStrokeWidth() / 2;
    canvas.drawRoundRect(x + inset, y + inset, x + Card.WIDTH - inset, bottom - inset,
        8, 8, mHintEdgePaint);
    canvas.drawRoundRect(x + inset, y + inset, x + Card.WIDTH - inset, bottom - inset,
        8, 8, mHintPaint);
  }

  public void SetTextView(TextView textView) {
    mTextView = textView;
    // Initialise text color to white
    mTextView.setTextColor(0xAAFFFFFF);
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    if (w <= 0 || h <= 0) return;
    CancelTouchInteraction();
    mWidthPixels = w;
    mHeightPixels = h;
    mDrawMaster.SetScreenSize(w, h);
    if (mRules != null) {
      Card.SetSize(mRules.GetType(), w, mDpi, mIsLandscape);
      mDrawMaster.DrawCards(GetSettings().getBoolean("DisplayBigCards", false));
      mRules.Resize(w, h);
      // Resizing replaces the cached bitmap, including during a text/win overlay.
      DrawBoard();
    }
    invalidate();
  }

  public void DisplaySplash() {
    if (mRules.HasWon()) {
      Toast.makeText(mContext, R.string.toast_use_start_menu, Toast.LENGTH_SHORT).show();
      return;
    }
    mTextView.setTextSize(18);
    mTextView.setGravity(Gravity.LEFT);
    DisplayText(mSplashText);
  }

  public void DisplayWin() {
    mTextView.setTextSize(24);
    mTextView.setGravity(Gravity.CENTER_HORIZONTAL);
    DisplayText(mWinText);
    ChangeViewMode(MODE_WIN);
    mRules.SetIgnoreEvents(true);
    mReplay.StartReplay(mMoveHistory, mCardAnchor);
  }

  public void RestartGame() {
    if (mRules.HasWon()) {
      Toast.makeText(mContext, R.string.toast_restart_invalid, Toast.LENGTH_SHORT).show();
      return;
    }
    if (mViewMode == MODE_CARD_SELECT) {
      ChangeViewMode(MODE_NORMAL);
    }
    mRules.SetIgnoreEvents(true);
    while (!mMoveHistory.empty()) {
      Undo();
    }
    mRules.SetIgnoreEvents(false);
    Refresh();
  }

  public void Deal() {
    ClearHint();
    if (mViewMode == MODE_TEXT) {
      ChangeViewMode(MODE_NORMAL);
    } else if (mViewMode == MODE_NORMAL) {
      mRules.EventAlert(Rules.EVENT_DEAL, mCardAnchor[0]);
      Refresh();
    }
  }

  public void DisplayText(CharSequence text) {
    mTextView.setText(text);
    ChangeViewMode(MODE_TEXT);
    Refresh();
  }

  public void DrawBoard() {
    if (mCardAnchor == null) return;
    Canvas boardCanvas = mDrawMaster.GetBoardCanvas();
    mDrawMaster.DrawBackground(boardCanvas);
    for (int i = 0; i < mCardAnchor.length; i++) {
      mCardAnchor[i].Draw(mDrawMaster, boardCanvas);
    }
  }

  @Override
  public void onDraw(Canvas canvas) {
    if (mRules == null) return;

    // Only draw the stagnant stuff if it may have changed
    if (mViewMode == MODE_NORMAL) {
      // SanityCheck is for debug use only.
      SanityCheck();
      DrawBoard();
    }
    mDrawMaster.DrawLastBoard(canvas);
    if (mDisplayTime) {
      mDrawMaster.DrawTime(canvas, mElapsed);
    }
    if (mRules.HasString()) {
      mDrawMaster.DrawRulesString(canvas, mRules.GetString());
    }

    switch (mViewMode) {
      case MODE_NORMAL:
        DrawTapSelection(canvas);
        DrawHint(canvas);
        break;
      case MODE_MOVE_CARD:
        mMoveCard.Draw(mDrawMaster, canvas);
        break;
      case MODE_CARD_SELECT:
        mSelectCard.Draw(mDrawMaster, canvas);
        break;
      case MODE_WIN:
        if (mReplay.IsPlaying()) {
          mAnimateCard.Draw(mDrawMaster, canvas);
        }
      case MODE_WIN_STOP:
      case MODE_TEXT:
        mDrawMaster.DrawShade(canvas);
        break;
      case MODE_ANIMATE:
        mAnimateCard.Draw(mDrawMaster, canvas);
    }

    mRules.HandleEvents();
    UpdateHintAvailability();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent msg) {
    switch (keyCode) {
      case KeyEvent.KEYCODE_DPAD_CENTER:
      case KeyEvent.KEYCODE_SEARCH:
        Deal();
        return true;
      case KeyEvent.KEYCODE_BACK:
        Undo();
        return true;
    }
    mRules.HandleEvents();
    return super.onKeyDown(keyCode, msg);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    boolean ret = false;

    // Yes you can get touch events while in the "paused" state.
    if (mPaused) {
      return false;
    }

    if (mRules.GetType() == Rules.KLONDIKE && mViewMode != MODE_TEXT) {
      return HandleTapAndDrag(event);
    }

    // Text mode only handles clickys
    if (mViewMode == MODE_TEXT) {
      if (event.getAction() == MotionEvent.ACTION_UP && mTextViewDown) {
        SharedPreferences.Editor editor = mContext.getSharedPreferences("SolitairePreferences", 0).edit();
        editor.putBoolean("PlayedBefore", true);
        editor.commit();
        mTextViewDown = false;
        ChangeViewMode(MODE_NORMAL);
      } if (event.getAction() == MotionEvent.ACTION_DOWN) {
        mTextViewDown = true;
      }
      return true;
    }

    switch (event.getAction()) {
      case MotionEvent.ACTION_DOWN:
        mHasMoved = false;
        mSpeed.Reset();
        ret = onDown(event.getX(), event.getY());
        mDownPoint.set(event.getX(), event.getY());
        break;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        ret = onRelease(event.getX(), event.getY());
        break;
      case MotionEvent.ACTION_MOVE:
        if (!mHasMoved) {
          CheckMoved(event.getX(), event.getY());
        }
        ret = onMove(mLastPoint.x - event.getX(), mLastPoint.y - event.getY(),
                     event.getX(), event.getY());
        break;
    }
    mLastPoint.set(event.getX(), event.getY());

    if (!mGameStarted && !mMoveHistory.empty()) {
      mGameStarted = true;
      MarkAttempt();
    }

    mRules.HandleEvents();

    return ret;
  }

  private void ClearTapSelection() {
    mTapAnchor = null;
    mTapCard = null;
    invalidate();
  }

  private int CardIndex(CardAnchor anchor, Card card) {
    if (anchor != null && card != null) {
      for (int i = 0; i < anchor.GetCount(); i++) {
        if (anchor.GetCards()[i] == card) return i;
      }
    }
    return -1;
  }

  private CardAnchor HitAnchor(float x, float y) {
    for (CardAnchor anchor : mCardAnchor) {
      if (anchor instanceof GenericAnchor ? anchor.IsOverDeck(x, y) : anchor.IsOverCard(x, y)) {
        return anchor;
      }
    }
    return null;
  }

  private Card HitMovableCard(CardAnchor anchor, float x, float y) {
    if (anchor == null || anchor == mCardAnchor[0]) return null;
    int first = Math.max(anchor.GetHiddenCount(), anchor.GetCount() - anchor.GetMovableCount());
    for (int i = anchor.GetCount() - 1; i >= first; i--) {
      Card card = anchor.GetCards()[i];
      float left = anchor.GetLeftEdge() == -1 ? card.GetX() : anchor.GetLeftEdge();
      float right = anchor.GetRightEdge() == -1 ? card.GetX() + Card.WIDTH : anchor.GetRightEdge();
      if (x >= left && x <= right &&
          y >= card.GetY() && y <= card.GetY() + Card.HEIGHT) return card;
    }
    return null;
  }

  private void DrawTapSelection(Canvas canvas) {
    CardAnchor anchor = mTapAnchor;
    Card card = mTapCard;
    // Preview the first selection on contact, without waiting for finger-up.
    // The pending touch still owns no cards and cancellation remains harmless.
    if (anchor == null && mTouchActive && !mHasMoved) {
      anchor = mTouchAnchor;
      card = mTouchCard;
    }
    int first = CardIndex(anchor, card);
    if (first < 0) return;
    float inset = mSelectionPaint.getStrokeWidth() / 2;
    Card last = anchor.GetCards()[anchor.GetCount() - 1];
    canvas.drawRoundRect(card.GetX() + inset, card.GetY() + inset,
        card.GetX() + Card.WIDTH - inset, last.GetY() + Card.HEIGHT - inset,
        8, 8, mSelectionPaint);
  }

  // Also used by New Game, Undo, resizing, pause and Exit's synchronous save.
  private void CancelTouchInteraction() {
    ClearHint();
    mLastAutoNoMoves = null;
    removeCallbacks(mExpandStack);
    mTouchActive = false;
    mTouchAnchor = null;
    mTouchCard = null;
    ClearTapSelection();
    if (mViewMode == MODE_MOVE_CARD || mViewMode == MODE_CARD_SELECT) {
      boolean ignored = mRules.GetIgnoreEvents();
      mRules.SetIgnoreEvents(true);
      ChangeViewMode(MODE_NORMAL);
      mRules.SetIgnoreEvents(ignored);
    }
    UpdateHintAvailability();
  }

  private boolean BeginCardMove(CardAnchor anchor, Card card) {
    int first = CardIndex(anchor, card);
    if (first < 0 || first < anchor.GetHiddenCount() ||
        first < anchor.GetCount() - anchor.GetMovableCount()) return false;
    int count = anchor.GetCount() - first;
    Card[] cards = new Card[count];
    float[] xs = new float[count];
    float[] ys = new float[count];
    for (int i = 0; i < count; i++) {
      cards[i] = anchor.GetCards()[first + i];
      xs[i] = cards[i].GetX();
      ys[i] = cards[i].GetY();
    }
    for (int i = 0; i < count; i++) anchor.PopCard();
    mMoveCard.SetAnchor(anchor);
    for (int i = 0; i < count; i++) {
      cards[i].SetPosition(xs[i], ys[i]);
      mMoveCard.AddCard(cards[i]);
    }
    ChangeViewMode(MODE_MOVE_CARD);
    return true;
  }

  // Both gestures use exactly the same history, reveal and placement operation.
  private void CompleteCardMove(CardAnchor destination) {
    CardAnchor source = mMoveCard.GetAnchor();
    boolean unhide = source.GetVisibleCount() == 0 && source.GetCount() > 0;
    mMoveHistory.push(new Move(source.GetNumber(), destination.GetNumber(),
        mMoveCard.GetCount(), false, unhide));
    destination.AddMoveCard(mMoveCard);
    if (mViewMode == MODE_MOVE_CARD) ChangeViewMode(MODE_NORMAL);
  }

  private void TapAt(float x, float y) {
    CardAnchor target = HitAnchor(x, y);
    if (target == mCardAnchor[0]) {
      ClearTapSelection();
      target.TapCard(x, y);
      return;
    }
    int first = CardIndex(mTapAnchor, mTapCard);
    if (first >= 0 && target != null && target != mTapAnchor &&
        target.CanBuildCards(mTapCard, mTapAnchor.GetCount() - first)) {
      if (BeginCardMove(mTapAnchor, mTapCard)) CompleteCardMove(target);
      return;
    }
    Card card = HitMovableCard(target, x, y);
    if (target == null || (target == mTapAnchor && card != null &&
        CardIndex(target, card) >= first)) {
      ClearTapSelection();
    } else if (card != null) {
      mTapAnchor = target;
      mTapCard = card;
    }
    // An illegal empty/blocked destination leaves the selection and deal alone.
    invalidate();
  }

  private boolean HandleTapAndDrag(MotionEvent event) {
    int action = event.getActionMasked();
    float x = event.getX(), y = event.getY();
    if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
      CancelTouchInteraction();
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN) {
      ClearHint();
      // Recover from a missing UP/CANCEL, but retain a completed tap selection.
      if (mTouchActive) CancelTouchInteraction();
      if (mViewMode != MODE_NORMAL && mViewMode != MODE_CARD_SELECT) return true;
      mTouchActive = true;
      UpdateHintAvailability();
      mHasMoved = false;
      mExpandedThisGesture = false;
      mExpandedScrolling = false;
      mSpeed.Reset();
      mDownPoint.set(x, y);
      mLastPoint.set(x, y);
      if (mViewMode == MODE_CARD_SELECT) {
        mSelectCard.Tap(x, y);
      } else {
        mTouchAnchor = HitAnchor(x, y);
        mTouchCard = HitMovableCard(mTouchAnchor, x, y);
        if (mTouchCard != null && mTouchAnchor == mTapAnchor &&
            CardIndex(mTapAnchor, mTouchCard) >= CardIndex(mTapAnchor, mTapCard)) {
          mTouchCard = mTapCard;
        }
        if (mTouchCard != null) postDelayed(mExpandStack, ViewConfiguration.getLongPressTimeout());
      }
      invalidate();
      return true;
    }
    if (!mTouchActive) return true;
    if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_UP) return true;

    float totalX = x - mDownPoint.x, totalY = y - mDownPoint.y;
    boolean previouslyMoved = mHasMoved;
    if (totalX * totalX + totalY * totalY > mTouchSlop * mTouchSlop) {
      mHasMoved = true;
      removeCallbacks(mExpandStack);
    }
    // Account for a final displacement even if Android sends no last MOVE.
    if (mViewMode == MODE_NORMAL && mHasMoved && !mExpandedThisGesture) {
      if (BeginCardMove(mTouchAnchor, mTouchCard)) {
        mMoveCard.MovePosition(-totalX, -totalY);
      }
    } else if (mViewMode == MODE_MOVE_CARD) {
      mMoveCard.MovePosition(mLastPoint.x - x, mLastPoint.y - y);
    } else if (mViewMode == MODE_CARD_SELECT && mHasMoved && !mExpandedThisGesture) {
      if (!mExpandedScrolling && mSelectCard.IsOnCard() && Math.abs(totalX) > Math.abs(totalY)) {
        mMoveCard.InitFromSelectCard(mSelectCard, x, y);
        ChangeViewMode(MODE_MOVE_CARD);
      } else {
        mExpandedScrolling = true;
        mSelectCard.Scroll(previouslyMoved ? mLastPoint.y - y : -totalY,
            0, mDrawMaster.GetHeight());
      }
    }
    if (action == MotionEvent.ACTION_MOVE) {
      mSpeed.AddSpeed(mLastPoint.x - x, mLastPoint.y - y);
    } else {
      removeCallbacks(mExpandStack);
      mTouchActive = false;
      if (mViewMode == MODE_MOVE_CARD) {
        onRelease(x, y);
      } else if (mViewMode == MODE_NORMAL && !mHasMoved && !mExpandedThisGesture) {
        TapAt(x, y);
      } else if (mViewMode == MODE_CARD_SELECT && !mHasMoved && !mExpandedThisGesture) {
        CardAnchor anchor = mSelectCard.GetAnchor();
        Card card = mSelectCard.GetSelectedCard();
        CancelTouchInteraction();
        if (card != null) {
          mTapAnchor = anchor;
          mTapCard = card;
        }
      }
      mTouchAnchor = null;
      mTouchCard = null;
    }
    mLastPoint.set(x, y);
    if (!mGameStarted && !mMoveHistory.empty()) {
      mGameStarted = true;
      MarkAttempt();
    }
    mRules.HandleEvents();
    UpdateHintAvailability();
    invalidate();
    return true;
  }

  private boolean onRelease(float x, float y) {
    switch (mViewMode) {
      case MODE_NORMAL:
        if (!mHasMoved) {
          for (int i = 0; i < mCardAnchor.length; i++) {
            if (mCardAnchor[i].ExpandStack(x, y)) {
              mSelectCard.InitFromAnchor(mCardAnchor[i]);
              ChangeViewMode(MODE_CARD_SELECT);
              return true;
            } else if (mCardAnchor[i].TapCard(x, y)) {
              Refresh();
              return true;
            }
          }
        }
        break;
      case MODE_MOVE_CARD:
        for (int close = 0; close < 2; close++) {
          CardAnchor prevAnchor = mMoveCard.GetAnchor();
          for (int i = 0; i < mCardAnchor.length; i++) {
            if (mCardAnchor[i] != prevAnchor) {
              if (mCardAnchor[i].CanDropCard(mMoveCard, close)) {
                CompleteCardMove(mCardAnchor[i]);
                return true;
              }
            }
          }
        }
        if (!mMoveCard.HasMoved()) {
          CardAnchor anchor = mMoveCard.GetAnchor();
          mMoveCard.Release();
          if (anchor.ExpandStack(x, y)) {
            mSelectCard.InitFromAnchor(anchor);
            ChangeViewMode(MODE_CARD_SELECT);
          } else {
            ChangeViewMode(MODE_NORMAL);
            // For Golf type game: Try TapCard because card hasn't
            // moved and ExpandStack() is false
            anchor.TapCard(x, y);
          }
        } else if (mSpeed.IsFast() && mMoveCard.GetCount() == 1) {
          if (!mRules.Fling(mMoveCard)) {
            ChangeViewMode(MODE_NORMAL);
          }
        } else {
          mMoveCard.Release();
          ChangeViewMode(MODE_NORMAL);
        }
        return true;
      case MODE_CARD_SELECT:
        if (!mSelectCard.IsOnCard() && !mHasMoved) {
          mSelectCard.Release();
          ChangeViewMode(MODE_NORMAL);
          return true;
        }
        break;
    }

    return false;
  }

  public boolean onDown(float x, float y) {
    switch (mViewMode) {
      case MODE_NORMAL:
        Card card = null;
        for (int i = 0; i < mCardAnchor.length; i++) {
          card = mCardAnchor[i].GrabCard(x, y);
          if (card != null) {
            if (y < card.GetY() + Card.HEIGHT/4) {
              boolean lastIgnore = mRules.GetIgnoreEvents();
              mRules.SetIgnoreEvents(true);
              mCardAnchor[i].AddCard(card);
              mRules.SetIgnoreEvents(lastIgnore);
              if (mCardAnchor[i].ExpandStack(x, y)) {
                mMoveCard.InitFromAnchor(mCardAnchor[i], x-Card.WIDTH/2, y-Card.HEIGHT/2);
                ChangeViewMode(MODE_MOVE_CARD);
                break;
              }
              card = mCardAnchor[i].PopCard();
            }
            mMoveCard.SetAnchor(mCardAnchor[i]);
            mMoveCard.AddCard(card);
            ChangeViewMode(MODE_MOVE_CARD);
            break;
          }
        }
        break;
      case MODE_CARD_SELECT:
        mSelectCard.Tap(x, y);
        break;
    }
    return true;
  }

  public boolean onMove(float dx, float dy, float x, float y) {
    mSpeed.AddSpeed(dx, dy);
    switch (mViewMode) {
      case MODE_NORMAL:
        if (Math.abs(mDownPoint.x - x) > (15 * mDpi/160) || Math.abs(mDownPoint.y - y) > (15 * mDpi/160)) {
          for (int i = 0; i < mCardAnchor.length; i++) {
            if (mCardAnchor[i].CanMoveStack(mDownPoint.x, mDownPoint.y)) {
              mMoveCard.InitFromAnchor(mCardAnchor[i], x-Card.WIDTH/2, y-Card.HEIGHT/2);
              ChangeViewMode(MODE_MOVE_CARD);
              return true;
            }
          }
        }
        break;
      case MODE_MOVE_CARD:
        mMoveCard.MovePosition(dx, dy);
        return true;
      case MODE_CARD_SELECT:
        if (mSelectCard.IsOnCard() && Math.abs(mDownPoint.x - x) > (30 * mDpi/160)) {
          mMoveCard.InitFromSelectCard(mSelectCard, x, y);
          ChangeViewMode(MODE_MOVE_CARD);
        } else {
          mSelectCard.Scroll(dy, 0, mDrawMaster.GetHeight());
          if (!mSelectCard.IsOnCard()) {
            mSelectCard.Tap(x, y);
          }
        }
        return true;
    }

    return false;
  }

  private void CheckMoved(float x, float y) {
    if (x >= mDownPoint.x - (30 * mDpi/160) && x <= mDownPoint.x + (30 * mDpi/160) &&
        y >= mDownPoint.y - (30 * mDpi/160) && y <= mDownPoint.y + (30 * mDpi/160)) {
      mHasMoved = false;
    } else {
      mHasMoved = true;
    }    
  }

  public void StartAnimating() {
    DrawBoard();
    if (mViewMode != MODE_WIN && mViewMode != MODE_ANIMATE) {
      ChangeViewMode(MODE_ANIMATE);
    }
  }

  public void StopAnimating() {
    if (mViewMode == MODE_ANIMATE) {
      ChangeViewMode(MODE_NORMAL);
    } else if (mViewMode == MODE_WIN) {
      ChangeViewMode(MODE_WIN_STOP);
    }
  }

  public void Undo() {
    CancelTouchInteraction();
    if (mViewMode != MODE_NORMAL && mViewMode != MODE_WIN) {
      return;
    }
    boolean oldIgnore = mRules.GetIgnoreEvents();
    mRules.SetIgnoreEvents(true);

    mMoveCard.Release();
    mSelectCard.Release();

    if (mMoveHistory.empty()) {
      Toast.makeText(mContext, R.string.toast_no_undo_left, Toast.LENGTH_SHORT).show();
    } else {
      Move move = mMoveHistory.pop();
      int count = 0;
      int from = move.GetFrom();
      if (move.GetToBegin() != move.GetToEnd()) {
        for (int i = move.GetToBegin(); i <= move.GetToEnd(); i++) {
          for (int j = 0; j < move.GetCount(); j++) {
            mUndoStorage[count++] = mCardAnchor[i].PopCard();
          }
        }
      } else {
        for (int i = 0; i < move.GetCount(); i++) {
          mUndoStorage[count++] = mCardAnchor[move.GetToBegin()].PopCard();
        }
      }
      if (move.GetUnhide()) {
        mCardAnchor[from].SetHiddenCount(mCardAnchor[from].GetHiddenCount() + 1);
      }
      if (move.GetInvert()) {
        for (int i = 0; i < count; i++) {
          mCardAnchor[from].AddCard(mUndoStorage[i]);
        }
      } else {
        for (int i = count-1; i >= 0; i--) {
          mCardAnchor[from].AddCard(mUndoStorage[i]);
        }
      }
      if (move.GetAddDealCount()) {
        mRules.AddDealCount();
      }
      Refresh();
    }
    mRules.MarkBlockedCards();  //E.g. TriPeaks
    mRules.SetIgnoreEvents(oldIgnore);
  }

  private void MarkAttempt() {
    String gameAttemptString = mRules.GetGameTypeString() + "Attempts";
    int attempts = GetSettings().getInt(gameAttemptString, 0);
    SharedPreferences.Editor editor = GetSettings().edit();
    editor.putInt(gameAttemptString, attempts + 1);
    editor.commit();
  }      

  public void MarkWin() {
    String gameWinString = mRules.GetGameTypeString() + "Wins";
    String gameTimeString = mRules.GetGameTypeString() + "Time";
    int wins = GetSettings().getInt(gameWinString, 0);
    int bestTime = GetSettings().getInt(gameTimeString, -1);
    SharedPreferences.Editor editor = GetSettings().edit();

    if (bestTime == -1 || mElapsed < bestTime) {
      editor.putInt(gameTimeString, mElapsed);
    }

    editor.putBoolean("SolitaireSaveValid", false);
    editor.putInt(gameWinString, wins + 1);
    editor.commit();
    if (mRules.HasScore()) {
      mWinningScore = mRules.GetScore();
      if (mWinningScore > GetSettings().getInt(mRules.GetGameTypeString() + "Score", -52)) {
        editor.putInt(mRules.GetGameTypeString() + "Score", mWinningScore);
      }
    }
  }      

  // Simple function to check for a consistent state in Solitaire.
  private void SanityCheck() {
    int cardCount;
    int matchCount;
    String type = mRules.GetGameTypeString();
    if (type == "Spider1Suit") {
      cardCount = 13;
      matchCount = 8;
    } else if (type == "Spider2Suit") {
      cardCount = 26;
      matchCount = 4;
    } else if (type == "Spider4Suit") {
      cardCount = 52;
      matchCount = 2;
    } else if ( (type == "Forty Thieves") ||
                (type == "DoubleFreecellBuildByAlternateColor") ) {
      cardCount = 52;
      matchCount = 2;
    } else {
      cardCount = 52;
      matchCount = 1;
    }
    
    int[] cards = new int[cardCount];
    for (int i = 0; i < cardCount; i++) {
      cards[i] = 0;
    }
    for (int i = 0; i < mCardAnchor.length; i++) {
      for (int j = 0; j < mCardAnchor[i].GetCount(); j++) {
        Card card = mCardAnchor[i].GetCards()[j];
        int idx = card.GetSuit() * 13 + card.GetValue() - 1;
        if (cards[idx] >= matchCount) {
          mTextView.setTextSize(20);
          mTextView.setGravity(Gravity.CENTER);
          DisplayText("Sanity Check Failed\nExtra: " + card.GetValue() + " " +card.GetSuit());
          return;
        }
        cards[idx]++;
      }
    }
    for (int i = 0; i < cardCount; i++) {
      if (cards[i] != matchCount) {
        mTextView.setTextSize(20);
        mTextView.setGravity(Gravity.CENTER);
        DisplayText("Sanity Check Failed\nMissing: " + (i %13 + 1) + " " + i / 13);
        return;
      }
    }
  }

  public void RefreshOptions() {
    mRules.RefreshOptions();
    SetDisplayTime(GetSettings().getBoolean("DisplayTime", true));
  }
}

// All animation/rule updates and drawing now share the UI thread.
// Start/stop is idempotent, so an activity can never create two refresh workers.
class RefreshHandler implements Runnable {
  public static final int NO_REFRESH = 1;
  public static final int SINGLE_REFRESH = 2;
  public static final int LOCK_REFRESH = 3;
  private static final int FPS = 30;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final SolitaireView mView;
  private boolean mRun;
  private int mRefresh = NO_REFRESH;

  public RefreshHandler(SolitaireView view) { mView = view; }

  public void SetRefresh(int refresh) { mRefresh = refresh; }

  public void SingleRefresh() {
    if (mRefresh == NO_REFRESH) mRefresh = SINGLE_REFRESH;
  }

  public void SetRunning(boolean run) {
    mHandler.removeCallbacks(this);
    mRun = run;
    if (run) mHandler.post(this);
  }

  @Override
  public void run() {
    if (!mRun) return;
    mView.UpdateTime();
    if (mRefresh != NO_REFRESH) {
      if (mRefresh == SINGLE_REFRESH) mRefresh = NO_REFRESH;
      mView.invalidate();
    }
    mHandler.postDelayed(this, 1000 / FPS);
  }
}

class Speed {
  private static final int SPEED_COUNT = 4;
  private static final float SPEED_THRESHOLD = 10*10;
  private float[] mSpeed;
  private int mIdx;

  public Speed() {
    mSpeed = new float[SPEED_COUNT];
    Reset();
  }
  public void Reset() {
    mIdx = 0;
    for (int i = 0; i < SPEED_COUNT; i++) {
      mSpeed[i] = 0;
    }
  }
  public void AddSpeed(float dx, float dy) {
    mSpeed[mIdx] = dx*dx + dy*dy;
    mIdx = (mIdx + 1) % SPEED_COUNT;
  }
  public boolean IsFast() {
    for (int i = 0; i < SPEED_COUNT; i++) {
      if (mSpeed[i] > SPEED_THRESHOLD) {
        return true;
      }
    }
    return false;
  }
}

