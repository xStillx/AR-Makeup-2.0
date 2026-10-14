# AR Makeup — конечные варианты параметров помады

Этот документ хранит утверждённые конечные наборы параметров помады. Для каждого нового продукта добавляется отдельный раздел с идентификацией оттенка, финишем и полным набором runtime-параметров.

Значения в этом реестре не следует заменять результатами временных экспериментов. Новый или изменённый вариант фиксируется здесь после явного выбора пользователя.

## Масло и тинт IOS_REFERENCE/dev — 2026-10-14

Новая команда пользователя разрешает самостоятельные финиши только в «Как на iOS». GUERLAIN kiss bee glow oil309/775 → «Масло», OK Beauty Valkyrie/Safari → «Тинт»; source HEX/density/texture из каталога сохранены. В UNIFIED для oil/tint по-прежнему не переключается финиш; новые кнопки скрыты. Это заменяет старое правило «для Oil/Tint нет отдельных формул» ниже только в IOS_REFERENCE.

OIL использует общие цветовые defaults дев-режима и включённые optical controls, как GLOSS, но собственную wet формулу: source gain1.40/max0.50, texture response, runtime attenuation×0.50 (base0.70/0.25), mask0.30–0.72, exponent1.05, shine strength0.42, linear white mix2.30. Добавочный блик отключён на верхней губе. Плотность стандартная dev0.57/0.68/1.00 до alpha-множителей.

TINT использует общие dev defaults с выключенными optical controls, как MATTE; runtime gain/cap0 независимо от ручного specular. Coverage=base+(0.88-base)×0.65: LOW0.7715/MEDIUM0.81/HIGH0.922. Не добавляет фактуру/синтетические тени и не делает повторного смешивания с цветом губ. Его source hue/saturation, camera value и финальный linear blend такие же, как у базового dev материала.

Ручной финиш сохраняется по режимам: IOS_REFERENCE_selected_lipstick_finish_v1 и прежний UNIFIED selected_lipstick_finish_v1. Настройки OIL/TINT используют существующий dev namespace; профили MATTE/SATIN/GLOSS не сбрасываются. Визуальный результат новых финишей остаётся кандидатом до оценки пользователя.

## Верхняя губа без добавочных бликов IOS_REFERENCE/dev — 2026-10-14

По просьбе пользователя SATIN/GLOSS больше не добавляют отражающий слой на верхнюю губу: shine умножается на точную per-vertex принадлежность lowerLip (upper0/lower1), не на camera/UV высоту. На нижней губе сохраняются ослабленные силы из раздела ниже. Естественная camera-value светотень, diffuse/wet separation, RGB, плотность и alpha не меняются. Поправка только для «Как на iOS», без reset и без изменений UNIFIED. Изменение не обещает убрать естественно светлые пиксели камеры из самого diffuse материала.

## Ослабление блеска IOS_REFERENCE/dev — 2026-10-14

По просьбе пользователя после визуальной оценки SATIN/GLOSS в «Как на iOS» ослаблены: расчётный reflection gain И peak cap сатина×0.40 (на60% меньше), глянца×0.50 (на50% меньше). Это коэффициенты слоя отражения, не проценты общей яркости пикселя. Новые base gain/max SATIN0.54/0.28 и GLOSS0.70/0.20 затем умножаются на texture response. Расположение/форма блика, diffuse цвет/плотность и MATTE0 прежние. Поправка действует с сохранёнными ручными controls, без reset/смены namespaces. UNIFIED не затронут. Новый визуальный результат пока не принят.

## IOS_REFERENCE/dev — реализационный кандидат, 2026-10-14

По команде пользователя режим «Как на iOS» заменён базой готовой ветки dev42318424. UNIFIED, его три блока ниже и versioned keys не менялись. Новый кандидат ещё не принят визуально на лице.

Начальные настройки этого режима отличаются от общей базы ниже: opacity0.99; pigment_opacity1; pigment_brightness1; camera_value_transfer1 (фиксированный полный per-pixel перенос, control скрыт); camera_detail1 (добавочный detail control не используется); highlight_size/threshold/concentration1; camera_sample_scale1; tooth_protection1. Общие RGB158/31/35, HIGH/MOUSSE, coverage1.98 и edge softness/blur0.50 сохраняются до выбора каталогового оттенка. Выбор цвета переносит source RGB/finish/texture/density; исходные HEX не корректируются.

Coverage: LOW0.57/MEDIUM0.68/HIGH1.00. Итоговая alpha=mesh coverage×density coverage×pigment_opacity×opacity. Интенсивность теперь меняет alpha, а не добавляет второй sRGB color mix. RGB пигмента формируется из hue/saturation заданного цвета и camera HSV value×pigmentValue/0.85; камера/материал смешиваются в linear RGB после растушёвки alpha. Inverse-alpha compensation, synthetic seam/detail shadows и дополнительные matte light layers исключены.

SATIN source highlight gain1.35/max0.70; GLOSS1.40/max0.40 с texture multipliers. UI specular/highlight retention/strength/glow остаются множителями; цветовые brightness/contrast/saturation/hue и natural_lip — ручными overrides. MATTE сохраняет выключенные блики. При нейтральных overrides SATIN/GLOSS используют dev equations; temporal shine history и отдельный SDF gate не перенесены, вместо них действует accepted Android annulus highlight gate. Никаких новых GPU targets/ML/assets.

Профили только IOS_REFERENCE: `${rendering_mode}_MATERIAL_${finish}_DEV_42318424_V1`; intensity key `IOS_REFERENCE_shared_pigment_opacity_dev_v1`. Старые профили не удалены, UNIFIED keys прежние. Неиспользуемые controls скрыты только в новом режиме: camera_value_transfer/camera_detail/material_detail/shadow/micro_texture/surface_detail/luminance/seam_shadow/wet_inner_edge, также прежние matte_*.

## Плотность выбранного оттенка — 2026-10-14

По команде пользователя всем33 оттенкам в `LipstickColorCatalog` назначен `density` из исходного списка. В UNIFIED выбор оттенка или изменение «Плотности продукта» устанавливает стартовую «Интенсивность цвета» (`pigment_opacity`):

- `LOW` →0.30 (13 оттенков);
- `MEDIUM` →0.65 (10 оттенков);
- `HIGH` →1.00 (10 оттенков).

Это стартовые коэффициенты смешивания пигмента, не измеренные физические проценты непрозрачности. GUERLAIN775 и309 →LOW; Guerlain131/518/06 и OK Beauty Safari/Valkyrie →MEDIUM; Dior999 →HIGH. Полный список хранится в каталоге, HEX не изменяются. Цвет и density общие для MATTE/SATIN/GLOSS, остальные параметры — принятая база ниже и отдельные ручные настройки материала. Общая opacity остаётся1, coverage1.98; силу бликов коэффициент density не умножает.

В новой IOS_REFERENCE/dev применяется coverage LOW0.57/MEDIUM0.68/HIGH1.00 через alpha (для TINT — отдельная поправка сверху); начальный `pigment_opacity`=1.00 является дополнительным множителем alpha. Прежние iOS-коэффициенты0.54/0.78/0.92 и low-density-gloss compensation больше не используются в этом режиме. Дополнительное уменьшение0.30/0.65 там не накладывается автоматически. В обоих режимах ручная «Интенсивность цвета» может переопределить стартовую величину; она сохраняется между финишами внутри режима. Следующий выбор оттенка заново применяет его density; «Сброс» возвращает стартовую интенсивность текущей density, сохраняя цвет. Выбор оттенка также применяет его texture и поддерживаемый finish из каталога; ручной выбор финиша/текстуры остаётся доступным и сохраняется. Для OIL/TINT в UNIFIED сохраняется текущий финиш; в IOS_REFERENCE теперь выбираются их самостоятельные формулы без соответствия GLOSS/MATTE. В IOS_REFERENCE texture влияет на материал; в UNIFIED пока сохраняется как параметр без отдельного shader response.

## Основные профили финишей

- Статус: общая база финишей по команде пользователя от 2026-10-14, на основе последнего ручного MATTE-блока.
- Профили хранения: `${rendering_mode}_MATERIAL_${finish}_COMMON_9E1F23_V1`; прежние сохранённые профили не удалены, но больше не перекрывают новую базу.
- Правило: MATTE, SATIN и GLOSS используют одинаковые цвет `#9E1F23` (RGB158/31/35), HIGH/MOUSSE, покрытие, цветопередачу, фактуру, настройки формы бликов и края. Различаются только оптические силы: у MATTE отражения/блики/свечение выключены; в UNIFIED более слабый SATIN и более сильный GLOSS сохраняются существующим finish response рендера. IOS_REFERENCE теперь использует отдельный dev-кандидат, описанный выше.
- Блоки ниже относятся к `UNIFIED`: `matte_light_transfer`/`matte_soft_light`=1 работают у всех трёх финишей. Исторические ключи `matte_*` сохранены; в UI это «Плавная светотень» и «Мягкое высветление».
- Блоки ниже остаются действующими UNIFIED defaults. IOS_REFERENCE заменён dev-кандидатом сверху; его defaults уже НЕ равны этим блокам. Исторические matte_* там0 и скрыты. Формулы двух режимов не объединены.
- Исторические конечные Dior/Brown Espresso ниже остаются без изменений и не становятся красными продуктами из-за новой общей базы.

### GLOSS

```text
rendering_mode=UNIFIED
finish=GLOSS
product_density=HIGH
product_texture=MOUSSE
opacity=1.00
pigment_opacity=1.00
coverage=1.98
natural_lip=0.00
pigment_red=158
pigment_green=31
pigment_blue=35
pigment_brightness=0.98
brightness=1.00
contrast=1.00
saturation=1.00
hue=0.0
luminance=1.00
camera_value_transfer=0.20
camera_detail=0.20
matte_light_transfer=1.00
matte_soft_light=1.00
material_detail=1.00
shadow=1.00
micro_texture=1.00
surface_detail=1.00
roughness=1.00
specular=1.00
highlight_retention=1.00
highlight_strength=1.00
highlight_size=1.20
highlight_threshold=1.15
highlight_concentration=1.25
satin_glow=1.00
wet_inner_edge=1.00
edge_refinement=1.00
edge_softness=0.50
edge_blur=0.50
inner_coverage=1.00
corner_fade=1.00
seam_shadow=1.00
tooth_protection=0.00
camera_sample_scale=1.15
```

### SATIN

```text
rendering_mode=UNIFIED
finish=SATIN
product_density=HIGH
product_texture=MOUSSE
opacity=1.00
pigment_opacity=1.00
coverage=1.98
natural_lip=0.00
pigment_red=158
pigment_green=31
pigment_blue=35
pigment_brightness=0.98
brightness=1.00
contrast=1.00
saturation=1.00
hue=0.0
luminance=1.00
camera_value_transfer=0.20
camera_detail=0.20
matte_light_transfer=1.00
matte_soft_light=1.00
material_detail=1.00
shadow=1.00
micro_texture=1.00
surface_detail=1.00
roughness=1.00
specular=1.00
highlight_retention=1.00
highlight_strength=1.00
highlight_size=1.20
highlight_threshold=1.15
highlight_concentration=1.25
satin_glow=1.00
wet_inner_edge=1.00
edge_refinement=1.00
edge_softness=0.50
edge_blur=0.50
inner_coverage=1.00
corner_fade=1.00
seam_shadow=1.00
tooth_protection=0.00
camera_sample_scale=1.15
```

### MATTE

```text
rendering_mode=UNIFIED
finish=MATTE
product_density=HIGH
product_texture=MOUSSE
opacity=1.00
pigment_opacity=1.00
coverage=1.98
natural_lip=0.00
pigment_red=158
pigment_green=31
pigment_blue=35
pigment_brightness=0.98
brightness=1.00
contrast=1.00
saturation=1.00
hue=0.0
luminance=1.00
camera_value_transfer=0.20
camera_detail=0.20
matte_light_transfer=1.00
matte_soft_light=1.00
material_detail=1.00
shadow=1.00
micro_texture=1.00
surface_detail=1.00
roughness=1.00
specular=0.00
highlight_retention=0.00
highlight_strength=0.00
highlight_size=1.20
highlight_threshold=1.15
highlight_concentration=1.25
satin_glow=0.00
wet_inner_edge=0.00
edge_refinement=1.00
edge_softness=0.50
edge_blur=0.50
inner_coverage=1.00
corner_fade=1.00
seam_shadow=1.00
tooth_protection=0.00
camera_sample_scale=1.15
```
## Dior Rouge Dior Satin 999

- Статус: конечный вариант
- Финиш: `SATIN`
- Оттенок: Dior Rouge Dior Satin 999
- Базовый цвет: `#B8202D` (`RGB 184, 32, 45`)
- Зафиксировано в checkpoint `[Makeup] Finalize Dior 999 satin preset`; заменяет вариант из `4eca18a`

```text
finish=SATIN
opacity=0.69
coverage=1.00
natural_lip=0.00
pigment_red=184
pigment_green=32
pigment_blue=45
pigment_brightness=0.90
brightness=1.00
contrast=1.00
saturation=2.00
hue=-0.9
luminance=0.90
camera_value_transfer=1.00
camera_detail=0.00
material_detail=0.00
shadow=0.00
micro_texture=0.00
surface_detail=0.00
roughness=0.35
specular=0.00
highlight_retention=0.00
highlight_strength=0.00
highlight_size=0.40
highlight_threshold=0.50
highlight_concentration=0.50
satin_glow=0.00
wet_inner_edge=0.00
edge_refinement=2.00
edge_softness=1.00
edge_blur=1.00
inner_coverage=1.00
corner_fade=2.00
seam_shadow=1.95
tooth_protection=2.00
camera_sample_scale=0.35
```

`pigment_brightness` изменяет исходный цвет до camera-value transfer, фактуры и бликов. `brightness` — это «Яркость материала», применяемая к уже рассчитанному материалу.

## L'Oréal Paris Infaillible Laque Resistance 515 Brown Espresso

- Статус: конечный вариант
- Финиш: `GLOSS`
- Оттенок: L'Oréal Paris Infaillible Laque Resistance 515 Brown Espresso
- Основной цвет: `#643229` (`RGB 100, 50, 41`)
- Источник основного цвета: замер предоставленного пользователем свотча на руке
- Checkpoint: ещё не создан

```text
finish=GLOSS
opacity=1.00
coverage=1.00
natural_lip=0.00
pigment_red=100
pigment_green=50
pigment_blue=41
pigment_brightness=1.00
brightness=1.00
contrast=1.05
saturation=1.00
hue=0.6
luminance=1.00
camera_value_transfer=0.82
camera_detail=1.00
material_detail=1.00
shadow=1.00
micro_texture=1.00
surface_detail=1.00
roughness=1.00
specular=0.15
highlight_retention=1.00
highlight_strength=1.00
highlight_size=1.00
highlight_threshold=1.00
highlight_concentration=1.00
satin_glow=1.00
wet_inner_edge=1.00
edge_refinement=1.00
edge_softness=0.00
edge_blur=0.00
inner_coverage=1.00
corner_fade=1.00
seam_shadow=1.00
tooth_protection=1.00
camera_sample_scale=1.00
```

## Формат следующего варианта

Для каждой следующей помады указывать:

- бренд, линейку и название/номер оттенка;
- статус варианта;
- финиш и базовый цвет в HEX/RGB;
- checkpoint, в котором набор стал дефолтом, если он существует;
- полный блок параметров в формате `key=value`.
