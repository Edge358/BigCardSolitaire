package net.sourceforge.solitaire_cg;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;

/** Visual review using the real card renderer; never loads or changes a saved game. */
public class CardArtworkReview extends Instrumentation {
  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    try {
      // Match the tablet's resource selection, including the app's fixed density.
      Configuration config = new Configuration(getTargetContext().getResources().getConfiguration());
      config.densityDpi = 320;
      config.fontScale = 1.0f;
      config.orientation = Configuration.ORIENTATION_LANDSCAPE;
      config.screenLayout = (config.screenLayout & ~Configuration.SCREENLAYOUT_SIZE_MASK)
          | Configuration.SCREENLAYOUT_SIZE_LARGE;
      config.smallestScreenWidthDp = 670;
      config.screenWidthDp = 1072;
      config.screenHeightDp = 670;
      config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
          | Configuration.UI_MODE_NIGHT_NO;
      Context context = getTargetContext().createConfigurationContext(config);
      Card.SetSize(Rules.KLONDIKE, 2560, 320, true);
      DrawMaster renderer = new DrawMaster(context, 2560, 1424, 320);
      renderer.DrawCards(false);

      int rowHeight = Card.HEIGHT + 58;
      Bitmap sheet = Bitmap.createBitmap(4 * (Card.WIDTH + 32) + 32,
          100 + 3 * rowHeight, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(sheet);
      canvas.drawColor(0xff008000);
      Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
      label.setColor(Color.WHITE);
      label.setTextSize(26);
      canvas.drawText("Actual game renderer - tablet card dimensions", 32, 36, label);
      String[] suits = {"clubs", "diamonds", "spades", "hearts"};
      for (int row = 0; row < 3; row++) {
        for (int column = 0; column < 4; column++) {
          int value = row == 0 ? 9 : row == 1 ? 10 : column < 3 ? 11 + column : 12;
          int suit = row < 2 ? column : column < 3 ? Card.DIAMONDS : Card.HEARTS;
          int x = 32 + column * (Card.WIDTH + 32);
          int y = 100 + row * rowHeight;
          Card card = new Card(value, suit);
          card.SetPosition(x, y);
          canvas.drawText(Card.TEXT[value - 1] + " of " + suits[suit], x, y - 12, label);
          renderer.DrawCard(canvas, card);
        }
      }
      File output = new File(getTargetContext().getCacheDir(), "card-review.png");
      try (FileOutputStream stream = new FileOutputStream(output)) {
        if (!sheet.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
          throw new IllegalStateException("Could not write review sheet");
        }
      }
      result.putString("review", output.getAbsolutePath());
      result.putString("card_size", Card.WIDTH + " x " + Card.HEIGHT);
      finish(Activity.RESULT_OK, result);
    } catch (Exception error) {
      result.putString("error", error.toString());
      finish(Activity.RESULT_CANCELED, result);
    }
  }
}
