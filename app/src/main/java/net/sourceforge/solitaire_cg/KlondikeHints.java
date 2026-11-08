package net.sourceforge.solitaire_cg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Read-only legal moves for this edition's draw-three, unlimited-recycle Klondike. */
final class KlondikeHints {
  // Waste, foundations left-to-right, then tableau left-to-right. No strategy ranking.
  private static final int[] SOURCES = {1, 5, 4, 3, 2, 6, 7, 8, 9, 10, 11, 12};
  private static final int[] DESTINATIONS = {5, 4, 3, 2, 6, 7, 8, 9, 10, 11, 12};

  static final class MoveHint {
    final CardAnchor source;
    final Card card;
    final int count;
    final CardAnchor destination;

    MoveHint(CardAnchor source, Card card, int count, CardAnchor destination) {
      this.source = source;
      this.card = card;
      this.count = count;
      this.destination = destination;
    }
  }

  static final class Result {
    final List<MoveHint> moves;
    final boolean drawStock;

    Result(List<MoveHint> moves, boolean drawStock) {
      this.moves = moves;
      this.drawStock = drawStock;
    }
  }

  static Result inspect(CardAnchor[] anchors) {
    List<MoveHint> moves = new ArrayList<>();
    for (int sourceIndex : SOURCES) {
      CardAnchor source = anchors[sourceIndex];
      int first = Math.max(source.GetHiddenCount(), source.GetCount() - source.GetMovableCount());
      for (int i = first; i < source.GetCount(); i++) {
        Card card = source.GetCards()[i];
        int count = source.GetCount() - i;
        for (int destinationIndex : DESTINATIONS) {
          if (destinationIndex != sourceIndex && anchors[destinationIndex].CanBuildCards(card, count)) {
            moves.add(new MoveHint(source, card, count, anchors[destinationIndex]));
          }
        }
      }
    }
    return new Result(moves, moves.isEmpty() && stockHasMove(anchors));
  }

  private static boolean stockHasMove(CardAnchor[] anchors) {
    // Copy ordered references only. Never pop, reposition, or add a live card.
    List<Card> stock = new ArrayList<>(Arrays.asList(
        Arrays.copyOf(anchors[0].GetCards(), anchors[0].GetCount())));
    List<Card> waste = new ArrayList<>(Arrays.asList(
        Arrays.copyOf(anchors[1].GetCards(), anchors[1].GetCount())));
    if (stock.isEmpty() && waste.isEmpty()) return false;
    Set<String> seen = new HashSet<>();
    while (seen.add(stockKey(stock, waste))) {
      if (stock.isEmpty()) {
        // Recycling is its own stock action, just like EVENT_DEAL.
        while (!waste.isEmpty()) stock.add(waste.remove(waste.size() - 1));
      } else {
        for (int i = 0; i < 3 && !stock.isEmpty(); i++) {
          waste.add(stock.remove(stock.size() - 1));
        }
      }
      if (!waste.isEmpty()) {
        Card card = waste.get(waste.size() - 1);
        for (int destination : DESTINATIONS) {
          if (anchors[destination].CanBuildCards(card, 1)) return true;
        }
      }
    }
    return false;
  }

  private static String stockKey(List<Card> stock, List<Card> waste) {
    StringBuilder key = new StringBuilder();
    for (Card card : stock) key.append(card.GetSuit() * 13 + card.GetValue()).append(',');
    key.append('/');
    for (Card card : waste) key.append(card.GetSuit() * 13 + card.GetValue()).append(',');
    return key.toString();
  }

  /** Includes order and hidden counts so even Undo with the same pile sizes invalidates hints. */
  static String positionKey(CardAnchor[] anchors) {
    StringBuilder key = new StringBuilder();
    for (CardAnchor anchor : anchors) {
      key.append('[').append(anchor.GetHiddenCount()).append(':');
      for (int i = 0; i < anchor.GetCount(); i++) {
        Card card = anchor.GetCards()[i];
        key.append(card.GetSuit() * 13 + card.GetValue()).append(',');
      }
      key.append(']');
    }
    return key.toString();
  }

  private KlondikeHints() { }
}
