# CardLens detection architecture

The scanner optimizes for **precision before recall**: a card that cannot be
proven is sent to confirmation. It must never be silently assigned to the most
plausible catalogue result.

## Detection pipeline

1. Detect four physical card edges and rectify the image to 63:88.
2. Reject background OCR when no card contour is present.
3. Classify the game from several proprietary/layout signals with a runner-up
   margin. Generic words such as `Leader`, `Stage`, or `Energy` cannot lock a game.
4. OCR the whole card, then a 3× high-resolution footer pass.
5. Parse a game-specific printed key; require the same reading over multiple frames.
6. Resolve only inside that game's catalogue. Never widen a confident game to all games.
7. Verify card name and a multi-region visual signature (art, title, frame, footer,
   and relative RGB palette). A close runner-up forces confirmation.
8. Require the physical finish to be confirmed before committing. A single diffuse
   camera image cannot reliably distinguish non-foil, foil, reverse holo, etched,
   textured, or similar specular treatments.

## Printed keys and variant rules

| Game | Printed evidence | What it proves | Remaining ambiguity |
|---|---|---|---|
| Magic | set code + collector number + language | exact Scryfall printing/language | foil/etched/textured/serialized finish |
| Pokémon | modern set code + number/printed total + language | exact modern card object | physical finish; older sets use a graphical set symbol |
| Yu-Gi-Oh! | set code under artwork | product printing and catalogue rarity | 1st/Unlimited and physical foil treatment; passcode alone is only card identity |
| One Piece | `OP/ST/EB/PRBxx-xxx` | card identity | parallels reuse the same number, so artwork comparison is mandatory |
| Lorcana | collector number + language + set code | exact artwork/rarity, including over-numbered Enchanted cards | normal vs foil where both exist |
| Star Wars: Unlimited | set + collector/variant number | exact normal/Hyperspace/Showcase/Prestige artwork represented by the catalogue | foil treatment |
| Dragon Ball Fusion World | card number + printed star marks | identity and a useful parallel hint | API can return several alt arts; artwork comparison remains mandatory |
| Riftbound | set + collector number/total + optional suffix (`a`, etc.) | exact API variant, including showcase entries | physical finish if shared by the same entry |

Research references: [Magic card anatomy](https://magic.wizards.com/en/news/feature/anatomy-magic-card-2006-10-21),
[Pokémon card/set API model](https://docs.pokemontcg.io/api-reference/cards/card-object/),
[Yu-Gi-Oh! API set and alternate-art model](https://ygoprodeck.com/api-guide/),
[One Piece same-number parallel rules](https://en.onepiece-cardgame.com/topics/013.php),
[Lorcast exact set/collector endpoint](https://lorcast.com/docs/api/cards),
[Star Wars variant definitions](https://starwarsunlimited.com/articles/boosting-ahead-of-release),
[Dragon Ball official alt-art product description](https://www.dbs-cardgame.com/fw/en/products/01_5.html).

## Known hard limits and next accuracy work

- Build a labeled real-camera benchmark before tuning thresholds: multiple phones,
  sleeves, rotations, glare levels, languages, and at least standard plus special
  variants for every game. Accuracy claims without this set are not meaningful.
- Train a small on-device card-image embedding model on catalogue images plus
  synthetic camera augmentation. The current multi-region hash is a conservative
  verifier, not a replacement for a trained retrieval model.
- Add a set-symbol image classifier for pre-Scarlet/Violet Pokémon. Those cards do
  not provide the modern OCR-friendly three-letter set code.
- If automatic finish detection is required, capture a short guided tilt video and
  analyze moving specular highlights. A single still image must continue to abstain.
- Keep catalogue adapters under contract tests. A perfect vision result still maps
  incorrectly when an upstream API collapses printings or omits variants.

## UX contract

- Show what evidence is being sought for the detected game.
- Explain why confirmation is required; never show an unexplained confidence score.
- Put set, collector number, rarity, language, artwork, and finish in the comparison UI.
- Disable collection commit until finish has been confirmed.
- Keep manual game selection available when the card is too stylized for safe auto-classification.
