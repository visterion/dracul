# DESIGN.md

> Visual identity and brand language for Dracul.
> A reference for banners, logos, README assets, and any future visual work.

## Design Philosophy

Dracul’s visual identity follows a single principle: **gothic substance, modern restraint**.

The project is thematically rooted in vampiric metaphor — Dracul the lord, his Strigoi, the night hunt, the blood moon. But it is also a serious piece of engineering: a Java agent system, an investment research tool, an Apache-2.0 framework. The visual language must hold both truths at once.

This means:

- **No Halloween clichés.** No dripping fonts, no cartoon fangs, no graveyard kitsch.
- **No bland fintech minimalism.** No sterile blue gradients, no generic “AI startup” aesthetic.
- **Gothic themes as undercurrents, not overcurrents.** The vampire is implied through composition, color, and small details — not shouted through obvious imagery.
- **Restraint as confidence.** Dracul does not need to prove its theme. The theme is in the bones of the project; the surface can afford to be quiet.

The visual reference points are: Bloomberg Terminal meets Bram Stoker. Mike Mignola’s *Hellboy* covers, but cleaner. The *Financial Times* if it had a gothic editor. Castlevania concept art if it were laid out by a Swiss typographer.

## Color Palette

### Primary

|Name             |Hex      |Usage                                                                                                             |
|-----------------|---------|------------------------------------------------------------------------------------------------------------------|
|**Crypt Black**  |`#0A0A0F`|Primary background. Near-black with a faint cool tilt.                                                            |
|**Blood Crimson**|`#A11D2C`|Primary accent. The wordmark “DRACUL” itself. Bold, deep, slightly desaturated red — never pure red, never bright.|
|**Bone Ivory**   |`#F5F1E8`|Light text on dark backgrounds. Warm, off-white, never pure white.                                                |

### Secondary

|Name                |Hex      |Usage                                                                                                              |
|--------------------|---------|-------------------------------------------------------------------------------------------------------------------|
|**Cathedral Gold**  |`#B8945C`|Subtle accents, hairlines, decorative elements. Never used for text or large surfaces — purely an ornamental touch.|
|**Ash Gray**        |`#6B6B70`|Tagline text, secondary labels, muted UI elements.                                                                 |
|**Moonlight Silver**|`#C4C4CA`|Very rare. Only for delicate line work or hover states.                                                            |

### Color Rules

- **Never use pure black (`#000000`)** — Crypt Black is the deepest tone.
- **Never use pure white (`#FFFFFF`)** — Bone Ivory is the lightest tone.
- **Never use bright or saturated red** — Blood Crimson is the only red. No alternates.
- **Gold is never dominant** — it appears in lines under 1px equivalent, in small icon details, never in text or fills larger than a small ornament.
- **The palette can shrink, never expand.** A piece using only Crypt Black, Blood Crimson, and Bone Ivory is on-brand. A piece adding any new color is off-brand.

## Typography

### Wordmark — “DRACUL”

The wordmark is set in a **modern serif** with subtle gothic character. Suggested faces:

- **Cormorant Garamond** (free, Google Fonts) — first choice
- **Playfair Display** (free, Google Fonts) — alternate
- **EB Garamond** (free, Google Fonts) — softer alternate

Always set in **uppercase**, with normal letter spacing or +20 tracking maximum. Never stretched, never compressed, never with effects (no shadows, no glows, no outlines beyond a single hairline if needed for contrast).

The “D” may carry a single ornamental flourish — a small swash, a hairline that hints at a bat-wing curve — but no more than one such detail per composition.

### Body & Tagline

Set in **modern sans-serif**. Suggested faces:

- **Inter** (free, Google Fonts) — first choice
- **Source Sans Pro** (free) — alternate
- **IBM Plex Sans** (free) — for technical contexts

Always set in lowercase or sentence case. Never uppercase except for very small labels (e.g. “APACHE 2.0”).

### Hierarchy

|Level       |Use                                             |Style                                            |
|------------|------------------------------------------------|-------------------------------------------------|
|**Wordmark**|“DRACUL”                                        |Cormorant Garamond, uppercase, Blood Crimson     |
|**Tagline** |“structural market anomalies. eternal patience.”|Inter, lowercase, Ash Gray, ~40% size of wordmark|
|**Body**    |Documentation prose                             |Inter, sentence case, Bone Ivory on dark         |
|**Caption** |Micro-labels, attribution                       |Inter, lowercase, Ash Gray, very small           |

## Iconography

### Core Icons

Three icons form the visual vocabulary of Dracul:

- **🌙 The Moon** — A crescent or full circle, always thin-line, never filled. Represents the time of work, the patient cycle.
- **🦇 The Bat** — A simple silhouette, geometric, never cute. Always small, always understated. Used singly or in counts of six (for the six Strigoi).
- **🪙 The Coin** — A circle with a single stylized mark inside. Represents the hunt’s prize. Used sparingly — never as the dominant visual.

### Icon Rules

- All icons are **line-based**, single weight (~1.5pt at 24×24px reference size).
- Icons live in **Cathedral Gold** for accents, or **Bone Ivory** on dark backgrounds.
- **No filled icons** in the primary brand language. Filled icons read as cartoonish; line icons read as engraved.
- A composition may show **at most three icons together**. Four or more becomes decoration, which is off-brand.

### What is NOT in the icon vocabulary

- Skulls, coffins, crosses, gravestones — too literal, too kitsch.
- Dollar signs (`$`) as primary symbol — too on-the-nose for a tool that respects its theme.
- Stock chart arrows — used only as integrated parts of compositions, never as standalone icons.
- Any explicit “AI” iconography (neural networks, robot heads, glowing brains) — Dracul is about agents, not about marketing AI.

## Banner Specification

The official Dracul banner. Use this for the GitHub repo social preview, the README header, and any external presentation.

### Format

- **Aspect ratio:** 2:1 (1280×640 px standard, 1200×600 acceptable, 1500×750 for high-DPI)
- **File:** PNG with transparency where possible, or SVG for vector usage
- **Variant set:** dark mode primary, light mode secondary (rare)

### Composition

```
┌──────────────────────────────────────────────────────────────────────┐
│                                                                      │
│                                                                      │
│      DRACUL                                  🦇 🦇 🦇 🦇 🦇 🦇       │  ← upper third:
│      structural market anomalies.            ──────/\─/\─\──         │  wordmark left,
│      eternal patience.                          🌙                   │  illustration right
│                                                                      │
│                                                                      │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
```

### Detailed Spec

- **Background:** Crypt Black (`#0A0A0F`), with optional subtle gradient toward slightly darker in the corners for vignette effect. Gradient must be invisible at first glance.
- **Wordmark “DRACUL”:** Cormorant Garamond, uppercase, Blood Crimson (`#A11D2C`). Positioned at left third, vertically centered. Font size approximately 1/6 of banner height.
- **Tagline:** “structural market anomalies. eternal patience.” in Inter, lowercase, Ash Gray (`#6B6B70`). Positioned directly below wordmark, font size approximately 1/4 of wordmark size.
- **Right-side illustration:** A horizontal stripe in the right two-thirds of the banner. Six small bat silhouettes flying in formation, left to right, slightly varying in vertical position to suggest motion. Beneath them, a single thin Cathedral Gold (`#B8945C`) line that rises and falls in the rhythm of a candlestick chart — small, almost subliminal. Above the bats, a single thin crescent moon icon in Bone Ivory.
- **Negative space:** At least 1/4 of the banner is empty space. The composition breathes.
- **Optional micro-detail:** Bottom right corner, very small Ash Gray text: “powered by Vistierie · apache 2.0”. This is optional and only included on the GitHub social preview, not on internal banners.

### What the banner must NOT have

- A castle silhouette (too literal)
- A blood moon as a large central element (too on-the-nose)
- Cape-wearing figures or visible vampire characters
- Stock tickers or numerical data
- Any text in red except the wordmark itself
- More than two type sizes
- Drop shadows, glows, or any layer effects

## Image-Generation Prompt

If using Nano Banana, Imagen, or another image-generation tool to produce the banner:

```
A minimalist GitHub banner, 1280x640, predominantly dark with subtle
gradients. Centered slightly left: the word "DRACUL" in large, elegant
serif typography (Cormorant Garamond or similar), deep crimson on near-
black background. Beneath in smaller, muted gray sans-serif: "structural
market anomalies. eternal patience." On the right side of the banner, a
clean line-art illustration in thin gold strokes: six small bat silhouettes
flying in formation across a stylized horizontal line that rises and falls
like a candlestick chart. A small crescent moon icon sits above them.
Negative space is generous. The whole composition feels like a high-end
financial publication or a serious open-source project — nothing flashy,
but immediately recognizable. Color palette: pure black, deep crimson,
muted gold, ash gray. Style: modern editorial design, vector-clean.
```

After generation, expect to refine via edits:

- “Less castle, more open space”
- “Make bats smaller and more uniform”
- “Reduce gold line opacity by half”
- “Remove background texture”

The goal of generation is a starting point; the design language above is the destination.

## Voice & Tone

The visual language is paired with a written voice. Both must align.

### Tone Principles

- **Confident, not boastful.** Dracul is a research tool, not a market oracle.
- **Patient, not slow.** The voice never apologizes for taking time, but never indulges in lengthy throat-clearing either.
- **Atmospheric where useful, technical where required.** Marketing copy can lean gothic. API documentation never does.
- **Romanian-rooted, English-spoken.** Names (Dracul, Vistierie, Strigoi) are Romanian; everything else is plain English.

### Phrases & Mottos

- **Primary motto:** *“The immortal know no impatience.”* Used sparingly, as a closing line or a banner tagline alternate.
- **Secondary tagline:** *“Structural market anomalies. Eternal patience.”* The default subtitle.
- **Functional descriptor:** *“Agentic investment research.”* For technical contexts where the gothic flavor is inappropriate.

### What the voice avoids

- Exclamation marks (always)
- Emoji in core documentation (allowed in supplementary chat or social media only)
- Self-aggrandizing claims about returns or accuracy
- Trading-floor slang
- Halloween-style adjectives (“spooky”, “creepy”, “ghoulish”)

## Asset Inventory

Maintained list of canonical visual assets. Each lives in `assets/branding/` in the repo.

|Asset                 |File              |Purpose                             |
|----------------------|------------------|------------------------------------|
|Banner (dark, default)|`banner-dark.png` |GitHub social preview, README header|
|Banner (light, rare)  |`banner-light.png`|Light-themed contexts, presentations|
|Banner SVG (vector)   |`banner.svg`      |Scaling, modification               |
|Wordmark only         |`wordmark.svg`    |Inline usage, small contexts        |
|Bat icon              |`bat.svg`         |UI elements, favicons               |
|Moon icon             |`moon.svg`        |UI accents                          |
|Coin icon             |`coin.svg`        |UI accents                          |
|Color palette swatch  |`palette.png`     |Reference for designers             |

## Accessibility

The brand language must respect accessibility, even at the cost of some atmospheric intensity:

- **Wordmark “DRACUL” in Blood Crimson on Crypt Black** has a contrast ratio of approximately 4.8:1, passing WCAG AA for large text. It must always be large; never use crimson on black for body copy.
- **Body text** uses Bone Ivory on Crypt Black, contrast ~14:1, passing WCAG AAA.
- **Tagline in Ash Gray on Crypt Black** has contrast ~4.5:1, passing WCAG AA only for large text. Never use Ash Gray for small body copy.
- **All visual content has text alternatives.** Banner alt text: “Dracul — structural market anomalies, eternal patience. Six bat silhouettes flying across a financial chart line beneath a crescent moon.”

## Evolution

This design language is a snapshot, not a contract. As Dracul grows, certain elements will be tested and refined:

- The six-bat motif may need adjustment if Dracul ever has more or fewer Strigoi.
- The Cathedral Gold accent may be reduced further, or eliminated entirely if it begins to feel decorative.
- The Cormorant Garamond wordmark may be replaced if a custom typographic mark is ever commissioned.

Any change to this document should be discussed and recorded in the commit message. The brand’s strength comes from consistency, not from spontaneity.

-----

*Last updated: defined at project inception.*
*Maintained by: the lord of the crypt.*
