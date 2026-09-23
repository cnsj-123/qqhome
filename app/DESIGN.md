# Closie Android Design System

> A local-first wardrobe companion. Clothes first, chrome second.
>
> **«The wardrobe supplies the color; Closie supplies the frame.»**
>
> The clothes are the richest color on screen. Closie stays restrained — ~90% neutral — so
> it reads like a personal fashion journal, not a Material database app.

## Brand personality

- **minimal** — only what matters, nothing extra
- **warm** — low-saturation fig, human tone
- **personal** — it is *your* wardrobe, not a catalog
- **image-first** — clothing and outfit photos are the heroes
- **calm** — generous whitespace, quiet hierarchy
- **lightweight** — fast to scan, fast to record

Closie should feel like a private wardrobe journal, not a database admin tool.

## Form philosophy

> «Select when possible, type only when necessary.»
>
> «Show essentials first, details on demand.»
>
> «User-entered values become future suggestions.»

Adding a piece of clothing is choosing, not filling a 20-field form.

## Color tokens

| Token            | Hex     | Usage                                      |
|------------------|---------|--------------------------------------------|
| Porcelain        | #F7F6F2 | root background, behind everything         |
| Paper            | #FFFFFF | sheets, cards, input backgrounds           |
| Mist             | #EFEEE9 | flat-image background, search, subtle fills|
| Fog              | #E7E5DF | disabled, dragged placeholders             |
| Ink              | #171717 | primary text, selected chip                |
| Graphite         | #55524E | subtitles, metadata, hints                 |
| Stone            | #96918A | placeholders, disabled, unselected tab     |
| Hairline         | #DDDAD3 | dividers, borders                          |
| Fig              | #713D4B | brand accent: save, +add, indicator, icon  |
| FigPressed       | #5E303D | pressed / darker fig                       |
| FigSoft          | #EFE3E6 | tinted surface accents                     |
| Moss             | #747B61 | subtle success / rare secondary accent     |
| MossSoft         | #EBEDE5 | moss tint surface                          |
| Error            | Material baseline error | errors, destructive actions |

### Fig usage rules

Fig is the brand color, **not** the page color. It is allowed for:
- primary save action
- "+ add"
- a very small selected indicator (dot / underline)
- current state / rating
- app icon
- small brand recognition

Fig is **not** allowed for:
- large fig backgrounds
- every chip fill
- every button
- all titles
- a big fig navigation pill

Moss is used even more sparingly — at most a subtle success or rare secondary accent.
Never place Fig and Moss next to each other as a pair.

## Dark mode

Dark mode is **planned but not implemented yet**. The current C4 theme intentionally
uses the light palette only (`LightColorScheme`) so that screens and shared components
never mix fixed light tokens with a dark scheme's white text/icons.

When dark mode is introduced later, screens/components must be migrated to semantic
`MaterialTheme.colorScheme.*` tokens instead of the raw `ClosieColor.*` constants.

## Typography

Use the Android system font (Roboto / Noto Sans CJK).

- **Display / large titles**: SemiBold
- **Body**: Regular
- **Metadata / hints**: Regular, InkSecondary

Avoid giant extra-bold headings, heavy ALL CAPS, and tiny low-contrast captions.

## Spacing

Base grid: 4 dp.

| Token | Value |
|-------|-------|
| xxs   | 4 dp  |
| xs    | 8 dp  |
| sm    | 12 dp |
| md    | 16 dp |
| lg    | 20 dp |
| xl    | 24 dp |
| xxl   | 32 dp |

- page horizontal padding: 16–20 dp
- major section gap: 24–32 dp
- element gap inside a section: 12–16 dp

## Radius

| Token | Value |
|-------|-------|
| Small  | 8 dp  |
| Medium | 12 dp |
| Large  | 14 dp |
| XL     | 16 dp |
| Pill   | 999 dp|

## Surfaces

- root: Porcelain
- cards/sheets: Paper with 1 dp Hairline stroke, **no elevation shadow**
- image placeholders: Mist
- selection backdrop: FigSoft

Hierarchy comes from whitespace and surface color, not from shadows.

## Image treatment

### Flat images
- Mist background
- ContentScale.Fit
- padding so transparent PNGs feel like a catalog
- 14 dp radius

### Product / model / me photos
- ContentScale.Crop allowed
- 14 dp radius
- in grids, crop to a ~4:5 aspect ratio

Closet grid prioritizes: FLAT → PRODUCT → other.

## Buttons

- Primary: Fig fill, Paper text
- Secondary: Paper fill, Ink text, Hairline stroke
- Tertiary: plain text, Fig only when primary action
- Avoid filling every action with Fig

## Search

- Mist surface, no outline
- clear action on the trailing side
- hint in Stone
- 44–46 dp tall

## Chips

- unselected: Paper, Hairline stroke, Graphite text
- selected: Ink fill, Paper text
- minimum touch target 44 dp

## Picker

- tap opens a ModalBottomSheet
- sheet has search, recent/existing section, full list
- custom value shown as "+ use “X”"
- dismiss by swipe down or scrim tap

Do **not** use DropdownMenu for long lists.

## Smart entry

Adding/editing clothes is *choosing*, not filling a 20-field form.

- **Select when possible, type only when necessary** — category, subcategory, brand, store,
  platform, size, safety, return reason, material name, measurement name and unit are all
  searchable pickers.
- **Show essentials first, details on demand** — only image, name, category, subcategory,
  brand, size and purchase price are visible up front. Purchase info, garment details and
  personal notes stay collapsed behind progressive sections.
- **User-entered values become future suggestions** — suggestions are aggregated live from
  `repo.items` (frequency-ordered, case-insensitively deduped, original spelling kept),
  with static presets appended after. A brand typed once appears next time automatically.
- **Custom values** — any picker accepts a free value via `＋ 使用"X"`; saved values are
  trimmed and become future suggestions. No extra suggestion database.
- **Prefer short rows over text fields** — materials/measurements are compact
  `name · value · unit · ×` rows, not stacked OutlinedTextFields.

## Navigation

Single primary bottom navigation:

- 首页
- 衣橱
- OOTD
- 搭配

Settings opens from the home top-right icon.
Returned lives as a segment inside Closet, not as a top-level tab.

Only one navigation enum / structure exists at a time.

## Cards

- one Card per logical surface is fine; do not nest cards inside cards
- prefer whitespace over card borders
- no shadow unless the platform forces one

## Empty state

Truly empty wardrobe:

> 衣橱还是空的
> 添加第一件真正喜欢的衣服。
> [+ 添加衣服]

Filtered empty:

> 没有符合当前条件的衣服
> [清除筛选]

Never mix the two messages.

## App Icon

- **Mark** — a geometric open "C" with a 45° folded lower arm ("Closie C / wardrobe fold").
  It reads as a letter, a folded garment and an opening closet door at once. No hanger, no
  shopping bag, no text.
- **Palette** — Fig `#713D4B` background, Porcelain `#F7F6F2` mark. Distinct on the launcher,
  quiet once inside the app.
- **Monochrome** — outline-only layer (single-color) so Android 13+ themed icons work; it must
  not rely on Fig.
- **Safe area** — the mark is centered inside the 66 dp safe zone so circular / squircle /
  rounded-square masks (incl. OriginOS) never crop it.
- Implemented as an adaptive icon (`mipmap-anydpi-v26`), with foreground / background /
  monochrome layers, plus `roundIcon`.

## Do / Don't

| Do                              | Don't                            |
|---------------------------------|----------------------------------|
| Put the photo first             | Bury photos inside heavy cards   |
| Use whitespace                  | Fill every pixel with controls   |
| One accent color (Rose)         | Pink everything                  |
| Group advanced fields behind sections | Show 20 fields at once     |
| Select before typing            | Force typing for every field     |
| Hide empty labels               | Show "店铺：" when empty         |
| Use Chinese labels in the UI    | Show enum names like OWNED/FLAT  |
| Keep touch targets ≥ 44 dp      | Crowd small tappable areas       |

## Accessibility

- touch targets at least 44–48 dp
- text contrast ≥ 4.5:1 for body text
- contentDescription on images and icon-only buttons
- selected state communicated by more than color alone
