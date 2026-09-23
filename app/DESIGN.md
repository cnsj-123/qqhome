# Closie Android Design System

> A local-first wardrobe companion. Clothes first, chrome second.

## Brand personality

- **minimal** — only what matters, nothing extra
- **warm** — low-saturation rose, human tone
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
| Canvas           | #FAF9F7 | root background, behind everything         |
| Surface          | #FFFFFF | cards, sheets, input backgrounds           |
| SurfaceSoft      | #F4F2EF | flat-image background, subtle rows         |
| SurfaceMuted     | #EFEBE7 | disabled, dragged placeholders             |
| Ink              | #1C1C1E | primary text, icons                        |
| InkSecondary     | #74716D | subtitles, metadata, hints                 |
| InkTertiary      | #A39F9A | placeholders, disabled text                |
| Hairline         | #E8E4DF | dividers, borders, chip outlines           |
| HairlineStrong   | #DDD8D2 | stronger separators                        |
| Rose             | #C9828C | primary action, active state, selected chip|
| RosePressed      | #B66F7A | pressed / darker rose                      |
| RoseSoft         | #F5E7E9 | tinted surface accents                     |
| Error            | Material baseline error | errors, destructive actions |

### Rose usage rules

Rose is allowed for:
- primary action
- active state
- selected chip
- small emphasis
- current navigation indicator

Rose is **not** allowed for:
- large pink backgrounds
- every card
- every button
- all titles
- gradients

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
| Large  | 16 dp |
| XL     | 20 dp |
| Pill   | 999 dp|

## Surfaces

- root: Canvas
- cards/sheets: Surface with 1 dp Hairline stroke, **no elevation shadow**
- image placeholders: SurfaceSoft
- selection backdrop: RoseSoft

Hierarchy comes from whitespace and surface color, not from shadows.

## Image treatment

### Flat images
- SurfaceSoft background
- ContentScale.Fit
- padding so transparent PNGs feel like a catalog
- 16 dp radius

### Product / model / me photos
- ContentScale.Crop allowed
- 16 dp radius
- in grids, crop to uniform aspect ratio

Closet grid prioritizes: FLAT → PRODUCT → other.

## Buttons

- Primary: Rose fill, Surface text
- Secondary: Surface fill, Ink text, Hairline stroke
- Tertiary: plain text, Rose only when primary action
- Avoid filling every action with Rose

## Search

- pill-shaped soft surface
- Hairline stroke
- clear action on the trailing side
- hint in InkTertiary

## Chips

- unselected: Surface, Hairline stroke, Ink text
- selected: Rose fill, Surface text
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
