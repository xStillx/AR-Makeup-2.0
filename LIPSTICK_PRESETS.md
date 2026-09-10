# AR Makeup — конечные варианты параметров помады

Этот документ хранит утверждённые конечные наборы параметров помады. Для каждого нового продукта добавляется отдельный раздел с идентификацией оттенка, финишем и полным набором runtime-параметров.

Значения в этом реестре не следует заменять результатами временных экспериментов. Новый или изменённый вариант фиксируется здесь после явного выбора пользователя.

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
