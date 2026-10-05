# Вариант с «Атакой титанов»

Актуальный вариант: [telegram-avatar-aot-v3.png](telegram-avatar-aot-v3.png).
Плащ удалён, эмблема уменьшена и выполнена как плоский рисунок на накладке.
Создан встроенным `image_gen` двумя последовательными правками версии v2.
Мяч, монета и синий фон сохранены; прежняя версия остаётся ниже для сравнения.

## Промпты правок v3

```text
Use case: precise-object-edit. Edit this existing Telegram avatar. Remove ONLY the entire green cloak behind the table-tennis paddle, including every green fabric area, all folds, and its silver clasp. Replace the removed cloak with the same clean vivid blue background, reconstructing any newly exposed paddle edges naturally. Preserve the paddle, the Wings of Freedom emblem printed on its face, white ping-pong ball, gold coin, their exact placement, proportions, colors and illustrated style. Preserve square framing and opaque blue background. Add nothing. No text, no watermark. The final icon must contain only the paddle with emblem, ball and coin on blue, with absolutely no cloak or fabric.
```

```text
Use case: precise-object-edit. Refine ONLY the Wings of Freedom emblem on the blue face of the paddle in the attached avatar. Make it a clean, precisely drawn flat printed decal on the paddle rubber, NOT a raised badge: no bevels, no extrusion, no drop shadow on emblem, no metallic borders. Reduce the emblem size by about 22 percent, center it neatly in the upper-middle of the paddle face with generous consistent blue margins, and align its shield and feather geometry with the paddle's long axis and surface perspective. Preserve the recognizable Attack on Titan Survey Corps Wings of Freedom design: one crisp white feathered wing and one crisp dark navy feathered wing within a fine clean shield outline, carefully spaced feathers of consistent visual weight, smooth exact edges. A tasteful logo printed onto the paddle, integrated into the material with the same surface lighting. Preserve everything else exactly: paddle silhouette, handle, ball, gold coin, square blue background, positions, scale, palette and overall illustration. There is NO cloak; do not add any fabric or other objects. No text or watermark. One finished icon with opaque blue background.
```

## Предыдущий вариант v2

[telegram-avatar-aot-v2.png](telegram-avatar-aot-v2.png) — ракетка с эмблемой
«Крылья свободы» и зелёным плащом разведкорпуса; мяч, монета и синий фон сохранены.
Квадратный PNG 1254 × 1254, исходный аватар v1 не изменён.
Создан 6 октября 2026 года встроенным `image_gen` редактированием v1.
В профиль Telegram автоматически не устанавливался.

## Промпт

```text
Use case: precise-object-edit. Edit the supplied Telegram avatar for a friendly table-tennis expense-splitting bot, adding a recognizable Attack on Titan Survey Corps theme. Preserve the existing square composition, vivid blue background, large diagonal table-tennis paddle, white ping-pong ball and gold coin, clean illustrated finish and strong small-size readability. Add a bold simplified Survey Corps Wings of Freedom emblem centered on the blue paddle face, with the distinctive white and dark blue feathered wings. Add a small flowing forest-green Scout cloak draped behind the paddle, as if the paddle were wearing it, subordinate to the main silhouette. Keep the emblem large enough to read, cloak simple with only a couple of folds. Retain the coin and ball unobscured. No people, no faces, no titans, no gore, no additional props, no lettering or watermark. Keep all essential elements fully within the central circular safe area for Telegram cropping, with ample margin. One polished finished avatar, not a mockup or alternatives sheet. Opaque background.
```
