# Drill art

Each drill card, the "Recommended for you" cards, History cards and the top of each drill guide show
an illustration of that drill's position. The art is flat and single-colour, matching the skill icons
(`res/drawable/skill_*.png`).

## Files

`app/src/main/res/drawable-nodpi/drill_art_<drill_id>.webp`: 512x512, transparent, WebP quality 86.
They are mapped in `ui/component/DrillArt.kt`. A drill without one falls back to its vector pose icon
(`drill_pose_*.xml`), then to the skill icon.

| Drill id | Name | Status |
|---|---|---|
| serve-standing-float | Serving Stance | done |
| serve-jump-float | Serving Reach | done |
| serve-topspin-jump | Serving Body Alignment | done |
| receive-wall-platform | Passing Platform | done |
| receive-short-court | Passing Ready Stance | done |
| receive-serve-to-target | Passing Body Alignment | done |
| set-wall-reps | Setting Stance | done |
| set-toss-to-target | Setting Extension | done |
| set-back-set | Setting Overhead Alignment | done |
| spike-standing-swing | Spiking Arm Position | done |
| spike-three-step | Spiking Reach | done |
| spike-four-step-lines | Spiking Body Alignment | done |
| block-ready-jumps | Blocking Stance | done |
| block-lateral-shuffle | Blocking Arm Alignment | done |
| block-read-setter | Blocking Body Alignment | done |

## Provenance

The project owner generated these images with ChatGPT image generation (November 2026). The existing
skill icon was attached as a style reference. Under OpenAI's terms, rights in the output belong to the
user who generated it. No third-party photos or other coaches' imagery were used. The only processing
was cropping to the figure, padding to a square, resizing and WebP conversion with Pillow.

### Shared style prompt

> Flat vector illustration. One solid-colour athlete silhouette with a few thin, lighter lines for
> clothing folds and muscle shapes. No face details, no dark outlines. A volleyball in the same colour
> with white seams and short speed lines. Soft semi-transparent swoosh trails in a lighter tint and a
> small halftone dot pattern. Transparent background, square. Right-handed athlete in a short-sleeved
> jersey and shorts. Accurate volleyball technique, readable at a small size. No text or logos.
> Colours: Serving #F26A15, Passing #2563EB, Setting #0FA06B, Spiking #7C3AED, Blocking #F5A524.

Each drill then got a one-paragraph pose description: feet, knees, arms, hands and ball position, as
in the drill's own description in `StaticDrillRepository`.
