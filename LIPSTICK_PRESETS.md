# AR Makeup — конечные варианты параметров помады

Этот документ хранит утверждённые конечные наборы параметров помады. Для каждого нового продукта добавляется отдельный раздел с идентификацией оттенка, финишем и полным набором runtime-параметров.

Значения в этом реестре не следует заменять результатами временных экспериментов. Новый или изменённый вариант фиксируется здесь после явного выбора пользователя.

## Плотность выбранного оттенка — 2026-10-14

По команде пользователя всем33 оттенкам в `LipstickColorCatalog` назначен `density` из исходного списка. В UNIFIED выбор оттенка или изменение «Плотности продукта» устанавливает стартовую «Интенсивность цвета» (`pigment_opacity`):

- `LOW` →0.30 (13 оттенков);
- `MEDIUM` →0.65 (10 оттенков);
- `HIGH` →1.00 (10 оттенков).

Это стартовые коэффициенты смешивания пигмента, не измеренные физические проценты непрозрачности. GUERLAIN775 и309 →LOW; Guerlain131/518/06 и OK Beauty Safari/Valkyrie →MEDIUM; Dior999 →HIGH. Полный список хранится в каталоге, HEX не изменяются. Цвет и density общие для MATTE/SATIN/GLOSS, остальные параметры — принятая база ниже и отдельные ручные настройки материала. Общая opacity остаётся1, coverage1.98; силу бликов коэффициент density не умножает.

В IOS_REFERENCE применяется тот же product density, но начальный `pigment_opacity`=1.00: действующие iOS-коэффициенты0.54/0.78/0.92 и low-density-gloss обработка уже находятся в этой формуле. Дополнительное уменьшение0.30/0.65 там не накладывается автоматически. В обоих режимах ручная «Интенсивность цвета» может переопределить стартовую величину; она сохраняется между финишами внутри режима. Следующий выбор оттенка заново применяет его density; «Сброс» возвращает стартовую интенсивность текущей density, сохраняя цвет. Теперь выбор оттенка также применяет его texture и поддерживаемый finish из каталога; ручной выбор финиша/текстуры остаётся доступным и сохраняется. По явному решению пользователя для OIL/TINT сохраняется текущий финиш; автоматического соответствия GLOSS/MATTE и отдельных формул нет. В IOS_REFERENCE texture уже влияет на материал; в UNIFIED пока сохраняется как параметр без отдельного shader response.

## Основные профили финишей

- Статус: общая база финишей по команде пользователя от 2026-10-14, на основе последнего ручного MATTE-блока.
- Профили хранения: `${rendering_mode}_MATERIAL_${finish}_COMMON_9E1F23_V1`; прежние сохранённые профили не удалены, но больше не перекрывают новую базу.
- Правило: MATTE, SATIN и GLOSS используют одинаковые цвет `#9E1F23` (RGB158/31/35), HIGH/MOUSSE, покрытие, цветопередачу, фактуру, настройки формы бликов и края. Различаются только оптические силы: у MATTE отражения/блики/свечение выключены; в UNIFIED более слабый SATIN и более сильный GLOSS сохраняются существующим finish response рендера. IOS_REFERENCE сохраняет собственные оптические формулы.
- Блоки ниже относятся к `UNIFIED`: `matte_light_transfer`/`matte_soft_light`=1 работают у всех трёх финишей. Исторические ключи `matte_*` сохранены; в UI это «Плавная светотень» и «Мягкое высветление».
- В `IOS_REFERENCE` общая база и цвет те же, но `rendering_mode=IOS_REFERENCE`, `matte_light_transfer=0.00`, `matte_soft_light=0.00`: эти Android-световые добавки в отдельную iOS-формулу не перенесены и controls скрыты. Остальные настройки совпадают с соответствующим блоком ниже. Формулы двух режимов не объединены.
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
