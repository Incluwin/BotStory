# Cash Shop window

A port of the community release "cashshop-window" (no licence or author line): the Cash Shop as a window over the
field, with categories, search, a live avatar preview and a cart of up to 24 items bought at once.

User decisions (2026-10-25): a curated catalogue with real durations; the Cash Shop button opens the window and the
window's own button enters the stock Cash Shop (locker, gifts, wish list); NX Credit or Maple Points.

## Opening it

The client's Cash Shop button sends the stock ENTER_CASHSHOP. `EnterCashShopHandler` checks the stock refusals first
(an event, a mini-dungeon, a map that refuses the Cash Shop); then a client with the `cash_shop_window` feature
active, while the `cash_shop_window` server mod is on, gets the window instead of the stage
(`CashShopWindowHandler.open`: enableActions, open, the category index, the balances). The window's Cash Shop button
sends action 4 and enters the stock stage behind the same refusals. Any other client keeps the stock Cash Shop.

## What it sells (`server.cashshop.CashShopWindowCatalog`)

The stock shop's own on-sale commodities (Etc.wz Commodity.img via `CashItemFactory`), built once on first use and
logged ("Cash Shop window catalogue: N items in M categories"):

- one row per item; where the shop offers an item twice, the lower price per item per day;
- each row's tab and category from the item id (the release's mapping), only categories the window shows;
- left out: off sale or free offers, packages, the ETC family (quest items), Maple Life, and rings (the stock shop
  sells those through its couple, friendship and wedding flows with a partner).

The release's own catalogue file made every item permanent, sold quest items for 1 NX and a companion tool priced
everything at 0; none of it is used.

## Buying (`CashShopWindowPurchase`)

- A cart is 1-24 different items paid in one currency: NX Credit or Maple Points (NX Prepaid is the stock gift
  currency and is refused).
- The stock gates hold: the entry refusals above, level 16 for store permits, `USE_SUPPLY_RATE_COUPONS` for rate
  coupons.
- Price, then space for the whole cart at once (stacking, one-of-a-kind items, a fresh slot per pet), then every
  item is built with the commodity's own `CashItem.toItem()`: the stock serial number and duration (the WZ's 0 is
  90 days, 1 is 4 or 2 hours on the rate coupons), with the server's `ITEMS_NEVER_EXPIRE` and `PETS_NEVER_EXPIRE`
  applying as in the stock shop.
- Delivery and payment run in one economy transaction (`CASH_SHOP_WINDOW`): an item that does not go in rolls the
  inventory back and nothing is charged. The release charged nothing but kept what had already gone in.
- Items go straight to the inventory, as the stock shop's meso purchases do; the locker is for stage purchases.

## Packets

C->S 0x11A CASHSHOP_WINDOW, S->C 0x180 CASHSHOP_WINDOW_SYNC (`CashShopWindowPackets` has the layout). Requests are in
the ECONOMY rate family. The index is sent on open; a category's rows come when the player opens it, 400 to a packet.

## Bots

Companions and citizens never press the Cash Shop button and never report client features, so none of this reaches
them.
