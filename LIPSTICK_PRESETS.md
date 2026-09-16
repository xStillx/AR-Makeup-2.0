# AR Makeup — конечные варианты параметров помады

Этот документ хранит утверждённые конечные наборы параметров помады. Для каждого нового продукта добавляется отдельный раздел с идентификацией оттенка, финишем и полным набором runtime-параметров.

Значения в этом реестре не следует заменять результатами временных экспериментов. Новый или изменённый вариант фиксируется здесь после явного выбора пользователя.

## Основные профили финишей

- Статус: утверждённые основные параметры для `UNIFIED` и `IOS_REFERENCE`.
- Профили хранения: `*_SATIN_C2050E_V3`/`UNIFIED_MATERIAL_SATIN_V5`, `*_MATTE_SATIN_BASE_9E2620_V1`, `*_GLOSS_SATIN_BASE_643229_V1`.
- Правило: MATTE, SATIN и GLOSS используют одну принятую сатиновую базу покрытия, цветопередачи, детализации и краёв. Цвет остаётся отдельным для продукта. MATTE отключает отражение; GLOSS сохраняет прежний liquid-блик.
- Числовые блоки ниже одинаковы для обоих режимов; для iOS-варианта меняется только `rendering_mode=IOS_REFERENCE`, сама формула рендера остаётся отдельной.

### GLOSS

```text
rendering_mode=UNIFIED
finish=GLOSS
product_density=HIGH
product_texture=LIQUID
opacity=1.00
pigment_opacity=1.00
coverage=1.98
natural_lip=0.00
pigment_red=100
pigment_green=50
pigment_blue=41
pigment_brightness=0.85
brightness=1.00
contrast=1.00
saturation=1.00
hue=0.0
luminance=1.00
camera_value_transfer=0.20
camera_detail=0.20
material_detail=1.00
shadow=1.00
micro_texture=1.00
surface_detail=1.00
roughness=1.00
specular=1.00
highlight_retention=1.00
highlight_strength=1.00
highlight_size=1.00
highlight_threshold=1.00
highlight_concentration=1.00
satin_glow=1.00
wet_inner_edge=1.00
edge_refinement=1.00
edge_softness=0.50
edge_blur=0.50
inner_coverage=1.00
corner_fade=1.00
seam_shadow=1.00
tooth_protection=0.00
camera_sample_scale=1.00
```

### SATIN

```text
rendering_mode=UNIFIED
finish=SATIN
product_density=HIGH
product_texture=CREAMY
opacity=1.00
pigment_opacity=1.00
coverage=1.98
natural_lip=0.00
pigment_red=194
pigment_green=5
pigment_blue=14
pigment_brightness=0.85
brightness=1.00
contrast=1.00
saturation=1.00
hue=0.0
luminance=1.00
camera_value_transfer=0.20
camera_detail=0.20
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
pigment_green=38
pigment_blue=32
pigment_brightness=0.85
brightness=1.00
contrast=1.00
saturation=1.00
hue=0.0
luminance=1.00
camera_value_transfer=0.20
camera_detail=0.20
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
