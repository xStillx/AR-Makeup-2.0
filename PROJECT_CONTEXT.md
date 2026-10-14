# AR Makeup — актуальный контекст проекта

Актуально на 2026-10-14. Этот файл хранит только текущее состояние и долгоживущие решения. Итоги отклонённых экспериментов сохранены кратко; локальные снимки не входят в Git.

## Активно: Oil/Tint из iOS dev только в «Как на iOS» — 2026-10-14

По новой команде пользователя реализованы самостоятельные OIL/TINT в IOS_REFERENCE, вместо прежнего правила «не переключать финиш». Каталоговые GUERLAIN kiss bee glow oil309/775 теперь выбирают «Масло», OK Beauty Valkyrie/Safari — «Тинт»; исходные HEX, density и texture сохранены. Две новые кнопки видны только в IOS_REFERENCE. В UNIFIED Oil/Tint по-прежнему сохраняют текущий финиш, новых формул/профилей там нет; defaultsFor защищён от неподдерживаемой комбинации. Старые enum ordinals не менялись.

Перенесены активные equations dev42318424: OIL использует wet mask0.30–0.72, exponent1.05, shine strength0.42, white linear blend×2.30; source gain1.40/max0.50 умножаются на texture response и согласованное ослабление×0.50. Блик только нижней губы через vLowerLip, как у ранее ослабленного GLOSS. TINT не добавляет shine; coverage=base+(0.88-base)×0.65, то есть LOW0.7715/MEDIUM0.81/HIGH0.922 до opacity/intensity/mesh alpha. Не добавлена повторная примесь natural lip из неиспользуемого profile-поля iOS. Общая dev camera-value/linear blend база прежняя, MATTE/SATIN/GLOSS не перекалиброваны.

Выбранный финиш сохраняется отдельно по режимам: новый IOS_REFERENCE_selected_lipstick_finish_v1, прежний selected_lipstick_finish_v1 остаётся UNIFIED. Первый вход IOS принимает уже выбранный Oil/Tint продукт, если нового ключа ещё нет; последующие ручные overrides сохраняются. Общие RGB/density/texture и per-mode intensity синхронизируются только по поддерживаемым финишам:5 IOS+3 UNIFIED. Старые profiles/settings не удалены и namespace не сброшен. Новых FBO, camera samples, CPU readback, ML/assets/dependencies нет. Temporal shine/SDF iOS всё ещё не перенесены; полного визуального паритета не обещаем.

:app:assembleDebug прошёл без unit-тестов. Локальная диагностика compiled artifact проверила8 defaults,264 palette snapshots, все3 texture responses, Tint coverage/no-shine, mode guards и точную upper/lower membership. Offscreen GPU:210 UNIFIED snapshots error0;252 прежних IOS diffuse и504 Oil/Tint diffuse max error1 RGB;12 shine snapshots error0; upper added shine/lower before-after/alpha error0; финальные composites error0. Визуальное принятие Oil/Tint и live performance ещё требуют устройства. Коммит/push не запрошены.

git diff --check прошёл. Перед установкой Samsung R5CRC3SPVNE отображался в adb devices, но затем отключился: install -r вернул device not found, повторный список устройств пуст. Этот APK НЕ установлен, запуск/live smoke новых Oil/Tint не выполнен. Готовый файл: app/build/outputs/apk/debug/app-debug.apk; установка без очистки данных после восстановления ADB-соединения.

## Активно: блик верхней губы отключён в «Как на iOS» — 2026-10-14

По новой просьбе пользователя IosLipMaterial дополнительно умножает только рассчитанный shine на vLowerLip. Отдельный статический float attribute aLowerLip=0 для первых verticesPerLip верхней губы, =1 для нижних verticesPerLip; принадлежность берётся из порядка tessellator, не выводится из camera/UV высоты. Проверка обнаружила, что исторические material UV не совпадают с семантической ориентацией верх/низ, поэтому UV-threshold не используется и сами UV не переписываются. SATIN/GLOSS не добавляют отражающий слой на всю верхнюю губу, включая уголки; нижняя сохраняет ранее ослабленные coefficients (SATIN×0.40/GLOSS×0.50). Diffuse camera-value/естественная светотень и wet highlight separation не изменены: отключение отражения не возвращает цветной hot spot в глянцевую diffuse base. Alpha/плотность/edge/carrier/Sync/tracker/MATTE/UNIFIED/settings/namespaces не менялись; нет новых samples/FBO/моделей/assets, один статический per-vertex float buffer.

:app:assembleDebug и git diff --check прошли без unit-тестов. Compiled mesh inspection подтвердил mask0 всех upper vertices и mask1 всех lower vertices. Offscreen GPU: upper added shine vs zero-shine reference error0 и нижняя до/после error0 при разных UV с обеих сторон0.5, alpha error0. Прежние210 UNIFIED snapshots побитно одинаковы;252 IOS diffuse max error1;8 lower-shine snapshots error0; final linear composite error0. Окончательный APK установлен через adb install -r на R5CRC3SPVNE и запущен (pid8590); ошибок shader/GL/FATAL в просмотренном process log не найдено. Визуальная оценка отсутствия верхнего блика остаётся за пользователем. Коммит/push не запрошены.

При последнем чтении live журнала после запуска есть ERROR_CAMERA_DISABLED; текущий FACE TRACKING не подтверждён для этого запуска. Это не отменяет успешную локальную shader/GL диагностику, но полноценный live smoke/visual acceptance не объявляются пройденными.

## Активно: ослаблены отражения «Как на iOS» — 2026-10-14

После device-пробы пользователь сообщил, что SATIN/GLOSS слишком блестят и выглядят яркими. Только IosLipMaterial.parameters добавляет finish calibration: SATIN gain и peak cap×0.40, GLOSS×0.50. Это уменьшение расчётного отражающего слоя на60%/50%, не обещание такого же процента изменения итогового RGB. Множитель применяется после прежних texture coefficients для CREAMY/MOUSSE/LIQUID: новые base gain/cap SATIN0.54/0.28, GLOSS0.70/0.20. Diffuse camera-value формула, wet separation, spatial highlight mask/exponent, плотность/alpha, MATTE no-shine, UNIFIED shader/defaults/keys и tracker не меняются.

Настройки/namespace не сбрасываются: поправка встроена в runtime material coefficients и действует также с существующими ручными specular/highlight controls. :app:assembleDebug и git diff --check прошли без unit-тестов. Inspection compiled debug artifact подтвердил gain/cap×0.40/0.50 для всех3 textures, прежние density/defaults и MATTE0. Offscreen GPU:210 UNIFIED snapshots max error0;252 IOS diffuse max error1;8 прежних/ослабленных SATIN/GLOSS shine snapshots max error0; финальная linear-композиция error0. Визуальная величина ослабления — кандидат до новой оценки пользователя.

Обновление успешно установлено через adb install -r на Samsung SM-G990B R5CRC3SPVNE без очистки данных и запущено (pid30259). Телефон разблокирован/awake; own-app preferences подтвердили IOS_REFERENCE и MATTE на момент проверки. Live журнал показал FACE TRACKING/SYNC pair, age0, anchor0 и render58–60 FPS; это краткий smoke, не benchmark и не визуальное принятие SATIN/GLOSS. В просмотренном process log shader/GL/FATAL errors не обнаружены. Пользователь может выбирать SATIN/GLOSS для визуальной оценки. Коммит/push не запрошены.

## Активно: «Как на iOS» заменён dev-кандидатом; UNIFIED сохранён — 2026-10-14

Пользователь явно разрешил заменить старый IOS_REFERENCE готовой iOS dev-formula, НЕ трогая единую формулу. IosLipMaterial независимо реализует diffuse/shine equations dev42318424 в GLSL: hue/saturation source RGB, полный cameraValue×pigmentValue/0.85 per pixel, linear RGB для бликов и финальной композиции. Убраны старые inverse-alpha compensation, low-density-gloss RGB mix, синтетические seam/detail shadows и дополнительный частичный camera transfer. Runtime IOS density получает локальный coverage LOW0.57/MEDIUM0.68/HIGH1; shared LipstickDensity/UNIFIED0.30/0.65/1 и legacy coverage-поля не менялись. Интенсивность IOS теперь является дополнительным alpha-множителем, не sRGB примесью камеры.

В IOS ветке существующие два blur targets хранят unassociated material sRGB + alpha; общий 13-tap separable blur по-прежнему размывает только alpha. Финальный composite применяет linear(camera,material,alpha×outerFeather) ОДИН раз. UNIFIED продолжает прежнюю signed-delta encoding/composite ветку без изменений уравнений. Режим кодировки фиксируется локально на начало raster/blur/composite frame, чтобы UI switch не интерпретировал чужой RGB format. Tracker/exact Sync/mesh/carriers/aperture/outer gates не менялись.

SATIN/GLOSS используют dev camera-derived shine equations, texture gain/max multipliers и удаление wet highlight из diffuse до отдельного отражения. Математически независимый shine вычисляется/накладывается в одном material shader, не отдельными history buffers. Temporal shine stabilization и самостоятельный contour SDF compute pass dev НЕ перенесены; вместо SDF действует UV annulus highlight gate, не ослабляющий внутренний пигмент. MATTE сохраняет нулевые блики по прежнему принятому решению, хотя dev допускает слабый shine. OIL/TINT по-прежнему НЕ переключают финиш. Поэтому не называть результат полным GPU/визуальным паритетом iOS.

Только IOS defaults: opacity0.99, pigmentBrightness1, fixed cameraValueTransfer1, cameraDetail1 (legacy не используется), highlightSize/threshold/concentration1, cameraSampleScale1, toothProtection1; общие source RGB/HIGH/MOUSSE/coverage1.98/edge0.50 прежние. Новый namespace IOS_REFERENCE_MATERIAL_${finish}_DEV_42318424_V1 и intensity key IOS_REFERENCE_shared_pigment_opacity_dev_v1 сбрасывают только старые IOS overrides один раз. Все UNIFIED profile/intensity keys и values сохранены; общий каталог/HEX/density/texture shared selection прежние. Старые profiles не удалены. Неиспользуемые material controls скрыты только в новом IOS режиме, как перечислено в LIPSTICK_PRESETS.md; поддерживаемые colour/opacity/shine/edge controls остаются редактируемыми.

Бюджет: новых FBO/ML/assets/dependencies/camera streams/CPU readback нет. IOS diffuse использует один camera sample; активная оптика ещё4 (раньше material13 с деталями/бликами), не считая прежней общей semantic edge refinement. RGBA8 intermediate — прежний Android бюджет, не iOS rgba16Float chain. При затемнении/мелких градиентах device A/B всё ещё обязателен.

Проверки :app:assembleDebug и git diff --check прошли, unit-тесты не запускались. Offscreen GLES2/SwiftShader: GLSL external/buffered camera валиден;210 UNIFIED before/after snapshots побитно одинаковы, final composite error0;252 IOS diffuse snapshots отличаются от CPU math максимум на1 RGB level, encoded RGB/alpha max error1;4 SATIN/GLOSS shine snapshots max error0, final IOS linear composite error0. Фактически скомпилированные6 defaults и198 palette snapshots проверены; подтверждены новые density/defaults, неизменные UNIFIED coefficients, MATTE no-shine и скрытие устаревших controls. ADB не обнаруживает телефон: APK собран, но новая версия не установлена и live/performance acceptance не выполнен. Коммит/push не выполнялись.

## Исследован актуальный iOS dev: Banuba-подобная база — 2026-10-14

Пользователь исправил ссылку: готовый ориентир — https://github.com/Dzhanaeva/virtual-makeup/tree/dev, НЕ main. Проверен dev commit42318424b4655ecb6f724cb8395b7d72e42942f5 (2026-10-13, fix and debug). Main d810b9d от2026-09-04 совпадает с прежней локальной копией по SHA-256 compositor/types/catalog и не является актуальным эталоном. Выводы о других HEX и отсутствии Banuba-переноса в main НЕ относятся к dev. Ссылка на подробности — верх IOS_REFERENCE_CONTEXT.md.

В dev lip_fragment действительно использует Banuba-подобный cameraValue×pigmentValue/0.85 полностью, per pixel, затем переводит пигмент в linear RGB. В активной diffuse-ветке нет нашего transfer-mix0.20, усреднения valueSource четырьмя reference samples, добавочных seamShadow/detail multipliers и inverse-alpha correctiveColor. Coverage приходит из профиля; отдельные shine-проходы вычисляют/стабилизируют/геометрически ограничивают реальные блики. Для wet finishes яркий camera highlight частично убирается из diffuse value перед отдельным отражением, чтобы не учитывать его дважды. Профильные поля/функция lip_captured_shape_exposure присутствуют, но эта функция в текущем Metal shader НЕ вызывается; не переносить неиспользуемые поля как якобы необходимую коррекцию.

Dev density.pigmentCoverage LOW0.57/MEDIUM0.68/HIGH1.00, против наших UNIFIED initial intensity0.30/0.65/1.00 и исторических IOS_REFERENCE coverage0.54/0.78/0.92. Семантика различается: dev отдаёт coverage в alpha для финального compositing, у нас UNIFIED pigmentOpacity смешивает camera/pigment в sRGB внутри непрозрачной поверхности. Простая замена трёх чисел не воспроизводит dev. Dev Guerlain131=#D07266, Nrav05=#D68B79, Shik Balm01=#F7C8BE совпадают с нашим исходным каталогом. Новые OIL/TINT в dev не меняют наше принятое правило: пока НЕ переключать их финиш автоматически.

Исследование — read-only source inspection по engineering:debug, не запуск iOS/Metal и не визуальное доказательство паритета. Готовый iOS источник уточняет следующего кандидата: сначала отдельный A/B-перенос активной diffuse базы и корректной linear density-композиции; не добавлять ещё одну придуманную модель освещения поверх неё. Затем независимо оценивать shine. Сохранить Android exact Sync/tracker/coverage и принятые настройки выключенных бликов MATTE; полный перенос multi-pass shine/новых финишей требует отдельной задачи и performance-проверки. По текущему запросу Android/iOS runtime-код, defaults, модели/assets, зависимости и APK НЕ менялись; сборки и unit-тесты не запускались. Идея глобальной коррекции ниже остаётся отложенной.

## Отложено: адаптация яркости светлого пигмента; ориентир Banuba — 2026-10-14

Пользователь уточнил симптом: Guerlain Rouge G Satin131, Shik Total repair Balm (конкретный shade не назван) и Nrav Lipstick05 бежевый выглядят чрезмерно яркими, «как будто светятся». Категории density у этих продуктов разные, поэтому уменьшение одной только intensity не считается достаточным решением. По явной просьбе пользователя идею пока СОХРАНИТЬ, НЕ реализовывать: отдельно адаптировать яркость пигмента к общему освещению камеры, сохраняя source HEX, плотность, слабую фактуру и независимые блики; ограничить дополнительное затемнение тёмных оттенков. Это кандидат, а не утверждённая новая формула/калибровка. Runtime, defaults, namespaces, APK не менялись в рамках сохранения идеи.

Диагностика текущего UNIFIED: applyMatteSurfaceLight использует отношение surface/reference, поэтому равномерная тень не даёт дополнительного затемнения; cameraValueTransfer0.20 слабо связывает плотный пигмент с общей яркостью камеры. Изолированная offscreen GLES/SwiftShader проверка MATTE без бликов, #D07266, pigmentBrightness0.98: при camera RGB180/120/110→90/60/55 и pigmentOpacity1 итог197/108/97→180/99/88; surface controls1/1 против0/0 на равномерном поле дают delta0. Это синтетическое подтверждение ограничения, не измерение актуального кадра пользователя. При intensity0.65 итог191/112/101→149/85/77 меняется сильнее из-за примеси камеры. Полный прежний диагностический скрипт прервался на сравнении near-zero с HEAD; изолированный прогон использовал только его GL setup/render и завершился успешно. Не объявлять весь regression suite пройденным в этой диагностике. Unit-тесты не запускались.

Пользователь предоставил https://github.com/Banuba/beauty-ios и сообщил, что iOS-разработчик использовал эту реализацию с хорошим результатом. Проверены открытые master sources: beauty-ios/effects/Makeup/modules/lips/matt.frag, shiny.frag, index.js и README. В matt.frag hue/saturation берутся из заданного цвета, HSV value из текущего camera pixel с масштабом pigmentValue/0.85 и brightness; смешивание с камерой управляется mask×color alpha. В shiny.frag также есть camera-value base и отдельная shine mask. index.js запрашивает SDK segmentation masks LIPS/LIPS_SHINING; README требует Banuba SDK client token. Это открытый эффект поверх SDK, а не полностью автономный pipeline. Нельзя обещать тот же результат только переносом коэффициентов или считать модели/SDK свободными из-за MIT-маркировки sample. Никакие внешние модели/assets/SDK/зависимости не добавлены.

Часть этой идеи УЖЕ была исследована и самостоятельно реализована у нас 2026-09-08: cameraValuePigment с делением на0.85 (см. историческую секцию). Сейчас она смешивается с исходным пигментом через cameraValueTransfer0.20; при включённых soft surface controls valueSource также частично заменяется средним четырёх reference samples. Поэтому следующий сравнительный A/B должен сначала проверить фактический camera-value path/смешивание относительно Banuba, не предполагая необходимость ещё одной новой модели освещения. Пользователь уточнил: iOS-разработчик перенёс ТОЛЬКО шейдеры, Banuba SDK не подключал. Точный fork/commit, изменения shader/цветового пространства и источники входных масок/shine parameters в его собственном pipeline пока неизвестны; запросить его реально используемые lips shader/config и подготовку входных данных. Не приписывать хороший результат обязательному использованию Banuba SDK. Переход на Banuba SDK, перенос кода или новая формула пока не разрешены этим запросом.

## Активно: финиш и текстура при выборе оттенка — 2026-10-14

По новой команде пользователя палитра переключает также финиш и productTexture из исходного каталога. Все21 продукта имеют обязательные source finish/texture: MATTE/SATIN/GLOSS соответствуют существующим renderer finishes; CREAMY/MOUSSE/LIQUID переносятся точно. Отдельный LipProductFinish сохраняет OIL/TINT без потери исходных данных. Для них независимых формул нет: по явному решению пользователя oil/tint НЕ сопоставляются с GLOSS/MATTE, renderingFinish=null и текущий финиш НЕ переключается; RGB/density/intensity/texture применяются. Новые формулы/режимы не добавлены.

Клик сначала сохраняет исходный профиль, затем обновляет общую selection, выбирает целевой finish и восстанавливает его собственный профиль с нужными оптическими настройками. Assign selectedFinish перед check кнопки исключает повторный restore из listener. Поддерживаемый продукт выводит из TRACKING_TEST в свой production finish. Ручной выбор другого финиша остаётся доступным; повторный клик продукта возвращает каталоговый финиш. Исходные ключи оттенков/HEX не меняются.

shared_product_texture_v1 сохраняет общую texture во всех шести material profiles, selected_lipstick_finish_v1 — текущий финиш. При первом запуске без новых ключей существующий выбранный каталоговый shade задаёт texture/поддерживаемый finish; дальнейшие ручные texture/finish overrides сохраняются при mode switch/relaunch. Custom RGB без каталожного выбора не получает угаданных metadata. Reset сохраняет общую texture наряду с цветом/density. Несвязанные ручные настройки, namespaces и прежние profiles не стираются.

Важно: texture влияет на существующие material coefficients в IOS_REFERENCE, но UNIFIED сейчас не использует productTexture в shader; в UNIFIED меняется и сохраняется параметр, отдельные визуальные модели текстур НЕ реализованы. Все shaders/GPU budgets/камера/трекер остаются прежними.

Проверка :app:assembleDebug и git diff --check прошла. Inspection debug-compiled каталога подтвердил точные finish/density/texture всех21 продуктов и33 unique shades;198 snapshots меняют только RGB/density/intensity/texture, не оптические силы/края. Unit-тесты не запускались. ADB не обнаружил подключённый телефон; новая сборка не установлена, live click/mode/relaunch acceptance не выполнен. Коммит не выполнялся.

## Плотность каталога: реализация до автопереключения финиша — 2026-10-14

По явной команде пользователя всем21 продуктам/33 оттенкам добавлен обязательный `LipColorProduct.density` из исходного Swift-списка. Распределение оттенков: LOW13, MEDIUM10, HIGH10. `LipstickDensity` сохраняет прежний iOS pigmentCoverage0.54/0.78/0.92 и отдельно задаёт UNIFIED initialPigmentOpacity0.30/0.65/1.00. Это стартовые коэффициенты «Интенсивности цвета», не физические измерения/не принятая окончательная визуальная калибровка каждого бренда. Например GUERLAIN775/309 —LOW0.30; Guerlain131/518/06 и OK Beauty Safari/Valkyrie —MEDIUM0.65; Dior999 —HIGH1.00. Точные source-sRGB HEX и unique selection keys не меняются. Общая база соответствует последнему блоку пользователя: opacity1, coverage1.98, naturalLip0, pigmentBrightness0.98, cameraValueTransfer/cameraDetail0.20, мягкий свет1/1 в UNIFIED, остальные принятые параметры/finish optics прежние; RGB, density и стартовая интенсивность выбираются из каталога.

`withProductShade` меняет только RGB, productDensity и pigmentOpacity, сохраняя блики, края и остальные ручные настройки материала. Выбор/ручной density slider сбрасывает intensity к начальному значению; ручной pigmentOpacity может дальше переопределить его без второго скрытого множителя. Цвет/density общие для всех финишей обоих режимов; pigmentOpacity отдельно общий между тремя финишами каждого режима. IOS_REFERENCE получает исходную категорию density и initialPigmentOpacity1, поскольку уже применяет собственные density/low-gloss guards; автоматически добавлять поверх них0.30/0.65 запрещено этим решением. Формулы shader не менялись. Finish/texture исходного каталога (включая oil/tint) по-прежнему не переключаются автоматически; текущий material mode/finish и MOUSSE base сохраняются.

SharedPreferences хранят shared_product_density_v1 и `${mode}_shared_pigment_opacity_v1`; обновляются только RGB/density/intensity ключи существующих шести production profiles. Старые profiles/другие ручные параметры не стираются. При первом обновлении уже выбранный каталоговый shade получает его density и начальную intensity, если новых shared keys ещё нет. Custom RGB без каталожной привязки не получает угаданную density; ручные density/intensity сохраняются. Reset возвращает начальную intensity текущей density в активном режиме и базовый материал, сохраняя общий цвет/density. TRACKING_TEST исключён. Нет новых GPU targets/samples, моделей/assets, зависимостей, camera streams или CPU readback.

`:app:assembleDebug` и `git diff --check` прошли. Проверка фактического debug-compiled каталога/`withProductShade` прошла для198 snapshots (33×6): точные RGB, исходная density, UNIFIED0.30/0.65/1.00 и IOS_REFERENCE intensity1; другие параметры, включая силу бликов/edges, не изменились. Подтверждены21 продукт/33 unique keys и распределение LOW13/MEDIUM10/HIGH10. Прежняя offscreen GPU-проверка общих баз/rollback/alpha/inner guards также прошла, shaders не менялись. APK успешно установлен на Samsung SM-G990B R5CRC3SPVNE и запущен (pid31132), журнал подтвердил UNIFIED/MATTE. Read-only inspection own app preferences подтвердил миграцию уже выбранного GUERLAIN775: shared densityLOW, pigmentOpacity0.30 во всех трёх UNIFIED profiles, densityLOW во всех шести profiles, IOS_REFERENCE pigmentOpacity1.00. Сохранённый shade key/HEX не изменился. Процесс жив, FATAL/GL/shader errors в просмотренном журнале не найдены; камера вернула CAMERA_DISABLED при keyguard showing/INTERACTIVE_STATE_SLEEP. Визуальное сравнение на лице, ручная правка intensity/reset и переключения после разблокировки остаются за пользователем. Unit-тесты не запускались; коммит не выполнялся.

Отдельное наблюдение по скринам пользователя: яркие775/131/Safari при pigmentOpacity0.65 в контровом свете всё ещё выглядят слабо связанными с освещением лица. Проверка действующего shader на синтетической равномерно освещённой/затенённой поверхности подтвердила ограничение относительного surface/reference lighting: при равномерной тени отношение1, добавка нейтральна; глобальный camera-value transfer остаётся0.20. Новая автоматическая density не объявляется решением всего эффекта «наклейки». Доработка общего освещения яркого пигмента — отдельная отложенная задача, в этой реализации не выполнялась.

## Общая горизонтальная палитра: первоначальная реализация — 2026-10-14

По команде пользователя добавлен локальный `LipstickColorCatalog`:21 продукт и33 оттенка из предоставленного Swift-списка, в исходном порядке и с исходными name/HEX/productID. Это палитра цвета и density (добавлена в секции выше), а не переключатель финиша/текстуры продукта: finish/texture исходного каталога, в том числе oil/tint, намеренно не применяются к материалу. Каждый source-sRGB HEX точно разбирается в8-bit RGB через сдвиги/маски, затем нормализуется /255; без коррекции оттенка, gamma-конверсии или изменения brightness. Повторяющиеся source productID сохранены; уникальный ключ selection строится из product.id + shade.productID + HEX, поэтому MAC-варианты «ЗЯ» и одноимённые shades разных брендов не объединяются.

В верхней части нижней phase card, над заголовком «Параметры», размещён независимый HorizontalScrollView. Айтем128dp: название продукта сверху, круг цвета50×50dp, имя оттенка снизу; выбранный круг имеет внешнюю рамку58dp. Палитра остаётся видимой при GONE tuningScroll; новые зависимости/assets не нужны. Accessibility item сообщает полное название продукта/оттенка и radio-style selected state. При первом запуске без сохранённого выбора ни один новый оттенок автоматически не применяется.

Выбор меняет pigment RGB, density и стартовую intensity, как описано выше, у всех MATTE/SATIN/GLOSS и обновляет активный immutable tuning renderer. Общий HEX/selection key сохраняется отдельно;18 RGB preference values всех шести material profiles синхронизируются, несвязанные finish-specific параметры не переписываются. Restore/mode/finish switching накладывает общую selection поверх сохранённых настроек. Reset сбрасывает текущий материал и возвращает стартовую intensity, но сохраняет общий выбранный цвет/density. Ручные RGB sliders также задают общий цвет и снимают привязку к каталожному shade; persistence происходит после завершения drag/при pause. TRACKING_TEST остаётся диагностическим magenta и не входит в общую палитру пигментов. Tracker/exact Sync/mesh/blur/shaders и основные profiles не меняются.

При первой реализации палитры `:app:assembleDebug` и `git diff --check` прошли. Локальная проверка debug-compiled каталога подтвердила21 продукт/33 уникальных composite keys, точный HEX→RGB→HEX roundtrip и неизменность всех не-RGB параметров в198 material snapshots до добавления density. Например `#D07266`=208/114/102, `#B8202D`=184/32/45, `#2E0201`=46/2/1. Палитра по XML находится вне сворачиваемого tuningScroll. Позднее пользователь прислал скрины с выбранными131/Safari/775 и свёрнутой панелью, подтверждающие наличие палитры и выбор оттенков; полное switching/relaunch acceptance не проводилось. Результаты новой проверки density/установки фиксируются в верхней секции. Unit-тесты не запускались; коммит не выполнялся.

## Активно: одна калибровочная база всех финишей — 2026-10-14

По явной команде пользователя последний ручной MATTE-блок применён к MATTE/SATIN/GLOSS, включая основной RGB158/31/35 (`#9E1F23`) и pigmentBrightness0.98. Общие HIGH/MOUSSE, opacity/pigmentOpacity1, coverage1.98, naturalLip0, brightness/contrast/saturation/luminance1, hue0, cameraValueTransfer/cameraDetail0.20, нейтральная фактура и roughness1, size1.20/threshold1.15/concentration1.25, edge refinement1/softness0.50/blur0.50, innerCoverage1, corner/seam1, toothProtection0, cameraSampleScale1.15. `finishDefaults` строит один общий набор; только MATTE выключает specular/highlightRetention/highlightStrength/satinGlow/wetInnerEdge, у SATIN/GLOSS они1. Существующий shader finish response сохраняет более слабый SATIN и более сильный GLOSS. Product texture больше не подменяется на CREAMY/LIQUID при переключении финиша.

В UNIFIED перенос плавного света и мягкое высветление теперь общие для всех трёх finishes: `matteLightTransfer`/`matteSoftLight` defaults1/1, убрано ограничение ветки только MATTE. UI показывает controls для всех production finishes в UNIFIED; исторические поля/экспортные ключи `matte_*` не переименовывались. При одинаковых параметрах и выключенных оптических силах база всех трёх finishes должна совпадать. Дополнительные6 camera samples теперь расходуются также активными SATIN/GLOSS; новых FBO, inference, camera streams или readback нет. Расчёт и интенсивность освещения прежние; отложенная идея удаления геометрических теней не реализована.

Общая числовая база/цвет применены и к IOS_REFERENCE; формула этого режима не меняется. Два UNIFIED-only световых controls там по-прежнему0 и скрыты, поскольку не поддерживаются iOS-reference formula. Эти режимные отличия явно указаны в `LIPSTICK_PRESETS.md`; нет утверждения, что оба режима визуально одинаковы. Обновлены все шесть production preference namespaces до `${rendering_mode}_MATERIAL_${finish}_COMMON_9E1F23_V1`, чтобы прежние ручные RGB/параметры не перекрывали новую явно заказанную базу. Старые profiles сохранены, ручное дальнейшее редактирование остаётся per-mode/per-finish. TRACKING_TEST не изменён. Исторические продуктовые presets Dior/Brown Espresso в реестре не переписаны. Предыдущая коррекция уголков/inner carrier, трекер и exact Sync сохранены.

`:app:assembleDebug` и `git diff --check` прошли. Локальная сверка фактически скомпилированных шести defaults с полными блоками реестра подтвердила RGB158/31/35, brightness0.98, HIGH/MOUSSE и равенство всех не-оптических параметров внутри каждого режима. Offscreen GLES2/SwiftShader: UNIFIED MATTE/SATIN/GLOSS при одинаковых strengths0 дают побитно одинаковый RGB на flat/broad-light/gradient/noise inputs; оба новых controls0 сохраняют прежнюю формулу каждого finish, IOS_REFERENCE не меняется при тех же входных uniforms, центральная alpha255, guards и непрерывность закрытого шва сохранены. GLSL external/buffered camera прошёл проверку. APK успешно установлен поверх текущего на Samsung SM-G990B R5CRC3SPVNE и запущен (pid24010), журнал подтвердил UNIFIED/MATTE. Процесс жив, shader/GL/FATAL ошибок в просмотренном process log не обнаружено; камера вернула CAMERA_DISABLED при showing keyguard/INTERACTIVE_STATE_SLEEP. Поэтому live visual/performance acceptance и переключение финишей на лице не подтверждены; пользователю нужно разблокировать телефон и проверить. Unit-тесты не запускались; коммит не выполнялся.

## Отложено: только камерная светотень MATTE — 2026-10-14

По просьбе пользователя сохранить идею, но пока не реализовывать: следующий A/B-кандидат убирает дополнительное геометрическое затемнение шва и коррекцию уголков в MATTE, оставляя ограниченный относительный перенос плавной яркости из camera frame и слабые реальные мелкие складки. Плотность пигмента сохраняется; исходный RGB губ не подмешивается ради объёма. По одному кадру отделение освещения от естественной пигментации приблизительное, поэтому необходимы нормализация и ограничения переноса, без обещания точной реконструкции света. Новые matte controls уже используют камеру, а не заранее нарисованные тени; сохранённая идея касается оставшихся поправок и следующего визуального сравнения. Текущая задача ниже меняет только покрытие уголков, не светотень/цвет.

## Активно: покрытие кончиков уголков губ — 2026-10-14

Пользователь сообщил о маленьких незакрашенных участках именно в уголках. В текущем thickness-based carrier расширение равно нулю на концах дуг, а общая внешняя растушёвка продолжает ослаблять эти тонкие участки. Кандидат: ARCore mesh дополнительно расширяет только outer arcs вдоль измеренного вектора уголок–уголок на1.2% ширины рта с плавным спадом в крайних12% каждой дуги. Inner arcs, включая их endpoints, остаются прежними: полость рта не сужается и не заполняется. Размеры vertex/index buffers и topology не меняются; новые параметры tessellator по умолчанию0 оставляют legacy callers и canonical material UV без изменений.

Общий финальный composite локально сужает outer feather до0.10 outer-to-inner coordinate только в той же corner-band; на остальных участках действует прежний edgeBlur-dependent feather. Пространственное размытие остаётся прежним. Scissor включает фактические carrier vertices, но шаг blur по-прежнему рассчитывается по исходному контуру. Изменение общее для MATTE/SATIN/GLOSS в UNIFIED и IOS_REFERENCE; tracker, exact Sync, temporal refiner, RGB, material shading/highlights, ручные параметры и namespaces не меняются. Нет новых GPU targets, camera samples, ML/assets или зависимостей. Это ограниченный геометрический candidate, не доказанное уточнение истинного контура по изображению. Визуальная проверка на улыбке, открытом рте, поворотах и разных лицах обязательна.

`:app:assembleDebug` и `git diff --check` прошли. Локальный offscreen GLES2/SwiftShader capture использует фактический debug-compiled tessellator, material shader, оба alpha blur passes и composite. На синтетических закрытых/открытых губах шириной256px максимальное outer-only смещение3.072px; геометрия inner arcs сохранена с float-погрешностью <1e-7, позиции/coverage/UV тела дуг вне corner band неизменны, normals конечны и нормализованы, инверсий triangles нет, rotation/mirror fixtures прошли. В UNIFIED и IOS_REFERENCE для всех трёх finishes alpha выросла локально в уголках; центральная область маски совпадает побитно, центральная открытая aperture остаётся нулевой. Проверка material GLSL для external/buffered camera и прежних lighting/rollback endpoints также прошла. Это synthetic verification, не device visual acceptance. Samsung R5CRC3SPVNE кратко появился в `adb devices`, но к установке отключился: `adb install -r` вернул device not found, повторный список пуст. Новая сборка не установлена; APK `app/build/outputs/apk/debug/app-debug.apk`. Unit-тесты не запускались; коммит не выполнялся.

## Исправление: непрерывное освещение закрытого шва MATTE — 2026-10-14

A/B пользователя (первый кадр — оба новых controls0, второй —1/1) подтвердил полезное мягкое высветление при плотном пигменте: светлый участок пальца примерно RGB156/31/35 без добавки и165/46/49 с ней, Chrome-эталон171/49/51. Одновременно появилась искусственная горизонтальная полоса, продолжающая линию смыкания губ на пальце. Причина воспроизведена offscreen: прежний `matteSurfaceWeight` гасил новое освещение у inner border независимо от состояния рта; на гладком световом пятне это давало перепад16 единиц RGB между телом губы и закрытым швом.

По команде пользователя inner light protection теперь равна inner-band weight * существующая aperture visibility * признаки полости в исходном camera pixel. При закрытом рте ослабления нет: общая экспозиционная опора и оба новых световых компонента непрерывны до внутреннего края. При открытом рте тёмная полость и яркие малонасыщенные зубы сохраняют прежнее ограничение. Camera evidence также позволяет коже пальца сохранять освещение, если перекрытие вызвало ложную оценку открытия. Это эвристика ограничения света, а не новая ML/occlusion mask. Внешний guard, coverage, geometry, tracker, исходный seamShadow и остальные финиши/режимы не изменены; дополнительных camera samples/FBO нет.

Параметры визуальной проверки сохраняются: UNIFIED/MATTE, RGB158/31/35, pigmentBrightness0.98, opacity/pigmentOpacity1, cameraValueTransfer/cameraDetail0.20, matteLightTransfer/matteSoftLight1, остальные значения из блока пользователя 2026-10-13. Defaults и preference namespaces не менялись. На GPU synthetic capture перепад закрытого шва уменьшился16→0; при ложном открытии на коже перепад также0, а реальные dark-cavity/neutral-teeth inner pixels совпадают с защищённым прежним результатом. Проверка изолирует добавленное освещение при seamShadow0, чтобы не убирать намеренно заданную пользователем старую тень между губами. GLSL external/buffered camera, центральная alpha255, flat-field, pigmentOpacity0, rollback0/0 и неизменность IOS_REFERENCE/SATIN/GLOSS прошли проверку. `:app:assembleDebug` и `git diff --check` прошли. Устройство отсутствует в adb, визуальная оценка новой сборки остаётся впереди. Unit-тесты не запускались; коммит не выполнялся.

## Активно: мягкое освещение UNIFIED/MATTE — 2026-10-13

Пользователь сравнивает Android с предоставленными Chrome-скриншотами, в том числе перекрывая губы пальцем для оценки сохранения объёма. Исходный UNIFIED/MATTE RGB158/38/32, pigmentBrightness0.85, cameraValueTransfer/cameraDetail0.20 выглядел темнее и ровнее эталона. Проба RGB158/31/35 приблизила оттенок. Усиление cameraDetail до0.80 пользователь отверг как неестественное; вернулся к0.20. Последний ручной блок: RGB158/31/35, pigmentBrightness0.98, pigmentOpacity0.95, opacity1, остальные прежние параметры. Эти значения не объявлены конечным пресетом и не записаны в defaults. Подмешивание5% натуральной основы почти не восстановило мягкое высветление на пальце; для проверки новой формулы рекомендован pigmentOpacity1.

По явной команде пользователя в UNIFIED/MATTE добавлен отдельный перенос плавного освещения и нейтральное мягкое высветление при плотном покрытии. Четыре mid-band UV (индексы контуров3/7/13/17, середина между outer/inner до carrier expansion) берутся из той же отображаемой observation. GPU усредняет camera RGB в этих точках для общей экспозиционной опоры. Локальный свет сглаживается существующей9-tap выборкой и двумя дополнительными samples вдоль направления уголков рта, с шагом3.5% ширины * cameraSampleScale. Относительный logarithmic luminance signal вычисляется в linear sRGB; ограниченный multiplicative response передаёт объём, малая нейтральная добавка высветляет только более освещённые области. Её cap зависит от энергии пигмента, чтобы не создавать серую вуаль у тёмных оттенков. При недостаточном освещении новый signal ослабляется.

Новые controls в группе «ФАКТУРА»: `matte_light_transfer` («Плавная светотень») и `matte_soft_light` («Мягкое высветление»), диапазон0–2. Defaults1/1 действуют только для UNIFIED/MATTE; в других finish/mode defaults0, controls скрыты. Оба значения0 полностью возвращают предыдущий UNIFIED/MATTE. Плавный переход около0 также плавно меняет экспозиционную опору. При включении «Яркость из камеры (доп.)» использует общую опору внутри губ, а прежний cameraDetail независимо задаёт мелкую фактуру. Geometry guard ослабляет новую добавку у outer border и уголков, включая изменение экспозиционной опоры; inner guard уточнён 2026-10-14 в секции выше. PigmentOpacity0 сохраняет прежнюю натуральную основу; новая светотень применяется к пигменту до этого смешивания. Specular/gloss остаются выключены у MATTE, coverage/alpha не уменьшаются.

IOS_REFERENCE, SATIN/GLOSS и утверждённые продуктовые presets не менялись. Старые preference namespaces сохранены: ручные RGB/интенсивности/edge-настройки переживают обновление, отсутствующие новые ключи получают defaults1/1. Экспорт и persistence новых параметров используют существующий enum-путь. Добавлено6 camera samples только в активной MATTE-ветке (15 material samples вместо9; semantic coverage samples отдельно). Новых FBO, CPU readback, temporal filters, ML/assets, зависимостей или camera streams нет. Требуется device performance/visual acceptance.

Debug build и проверка GLSL для external/buffered camera прошли. Offscreen GLES2/SwiftShader подтвердил отсутствие RGB-регрессии при обоих новых controls0, неизменность IOS_REFERENCE/SATIN/GLOSS, flat-field и pigmentOpacity0, полную центральную alpha255 и отсутствие добавленного lighting halo на обеих границах. На искусственном мягком световом пятне прежний центр RGB155/30/34 сменяется на161/45/48 при сохранении базового145/28/32; это проверка поведения, не доказательство совпадения с Chrome. Устройство отсутствует в `adb devices`; визуальная проверка на лице/пальце и на разных оттенках впереди. Unit-тесты не запускались, коммит не выполнялся.

## История: SATIN как общая база MATTE/SATIN/GLOSS — 2026-09-16

По команде пользователя основные настройки финишей синхронизированы вокруг принятого SATIN-профиля в обоих режимах `UNIFIED` и `IOS_REFERENCE`. Один `finishDefaults` задаёт coverage1.98, naturalLip0, pigmentBrightness0.85, luminance1, cameraValueTransfer0.20, cameraDetail0.20, нейтральную детализацию материала, edge refinement1, softness/blur0.50, innerCoverage1, corner/seam1, toothProtection0 и cameraSampleScale1.15. Формулы двух режимов не объединялись; общими стали их входные tuning defaults.

Цвет не копируется между продуктами: MATTE сохраняет RGB158/38/32 (`#9E2620`), SATIN — RGB194/5/14 (`#C2050E`), GLOSS — RGB100/50/41 (`#643229`). MATTE сохраняет metadata `MOUSSE` и полностью выключает оптическую добавку через specular/highlightRetention/highlightStrength/satinGlow/wetInnerEdge0; неактивные size/threshold/concentration наследуются от SATIN. GLOSS сохраняет `LIQUID` и прежний оптический блок: specular/retention/strength1, size/threshold/concentration1, satinGlow/wetInnerEdge1, cameraSampleScale1. SATIN остаётся без изменений.

Для принудительной загрузки новых defaults MATTE/GLOSS получили новые namespaces `*_MATTE_SATIN_BASE_9E2620_V1` и `*_GLOSS_SATIN_BASE_643229_V1`; SATIN namespaces не менялись. Полные блоки обновлены в `LIPSTICK_PRESETS.md`. `:app:assembleDebug` и `git diff --check` прошли. Установить APK для runtime smoke не удалось: Samsung `R5CRC3SPVNE` отключился и отсутствует в `adb devices`. Unit-тесты не запускались.
## Активно: внешний carrier и адаптивный внутренний край — 2026-09-16

После исправления внешнего контура и закрытого шва пользователь показал отдельный дефект открытого рта: вдоль верхнего и нижнего inner edge оставалась видимая полоса натуральной губы. Активный ARCore mesh уже использовал полное coverage до внутренней границы (`featherInnerBoundary=false`), поэтому дефект локализован в геометрии: прежний `innerSeamExpansion` плавно становился равен нулю при `mouthOpenness` 0.035–0.12, после чего mesh снова заканчивался на немного заниженных inner landmarks MediaPipe.

Текущий `LipMeshTessellator` сохраняет внешний carrier0.18. Внутреннее расширение теперь равно0.14 при закрытом рте и плавно переходит к ненулевым0.10 локальной толщины при открытом. Одновременно `innerCarrierFeather` меняется от0 до1: закрытый шов остаётся плотным, а в открытом состоянии первые40% добавленного mouth-side carrier сохраняют полное coverage, после чего alpha плавно спадает к новой границе. Смещение по-прежнему умножается на arc weight и равно нулю в уголках. Полость рта не заполняется отдельными triangles; параметры MediaPipe, topology, mapping, exact Sync и `LipContourTemporalRefiner` не менялись. Изменение находится до выбора материала и действует на MATTE/SATIN/GLOSS в `UNIFIED` и `IOS_REFERENCE`; остальные tessellator callers сохраняют default expansion/feather0.

`:app:assembleDebug` и `git diff --check` прошли. APK установлен и запущен на Samsung SM-G990B (`R5CRC3SPVNE`) в `UNIFIED / MATTE`; процесс активен, ошибок `ARMakeupArCore`, `AndroidRuntime` или OpenGL в отфильтрованном журнале нет. Требуется визуальная проверка открытого рта, особенно верхних зубов, крайних положений артикуляции и разных лиц. Unit-тесты не запускались.
## Активно: переключаемые iOS и единая формула — 2026-09-16

**Исправлена нейтральная цветопередача UNIFIED.** Удалено скрытое фиксированное просвечивание камеры из центра материала (`outputCoverage=0.92` и дополнительный множитель opacity0.99): итоговая alpha теперь `clamp(coverage * cornerOpacity, 0, 1)`, поэтому при полном coverage центр воспроизводит рассчитанный цвет материала без подмешивания 8–9% камеры, а растушёвка сохраняется в coverage mask и corner opacity. Camera-derived reflection теперь накладывается после коррекции luminance базового пигмента, чтобы настройка цветопередачи не осветляла и не затемняла блик. Утверждённые числовые профили не менялись; требуется визуальная перепроверка на устройстве. После изменения `:app:assembleDebug` и `git diff --check` прошли; устройство в `adb` отсутствовало, поэтому runtime-компиляция GLSL и визуальная оценка ещё не выполнены. Unit-тесты и коммит не выполнялись.

**Обновлён основной UNIFIED/SATIN (V5).** После исправления нейтральной цветопередачи пользователь утвердил конечный SATIN block: HIGH/CREAMY, opacity/pigmentOpacity1, coverage1.98, naturalLip0, RGB194/5/14 (`#C2050E`), pigmentBrightness0.85, luminance1, cameraValueTransfer0.20, cameraDetail0.20; specular/highlightRetention/highlightStrength1, highlightSize1.20, threshold1.15, concentration1.25, satinGlow/wetInnerEdge1; edge refinement1, softness/blur0.50, innerCoverage1, toothProtection0, cameraSampleScale1.15, остальные параметры1. Набор сохранён в `unifiedDefaultsFor(SATIN)` и `LIPSTICK_PRESETS.md`. Для обоих активных SATIN-режимов используются общие RGB-константы C2050E; прежние B8202D-константы сохранены для legacy/reference-кода. SATIN namespace поднят до `UNIFIED_MATERIAL_SATIN_V5`; последующая синхронизация MATTE/GLOSS с сатиновой базой описана в верхней активной секции.

После обновления обоих SATIN-профилей `:app:assembleDebug` и `git diff --check` прошли. APK установлен и запущен на Samsung SM-G990B (R5CRC3SPVNE); процесс остался жив, ошибок shader compile/link, GL_INVALID, AndroidRuntime/FATAL и ARCore render не найдено. Сохранённый режим при smoke-проверке был UNIFIED/MATTE, поэтому визуальная оценка SATIN V5/V3 остаётся за пользователем. Unit-тесты и коммит не выполнялись.
**IOS_REFERENCE/SATIN синхронизирован с V5.** Для `defaultsFor(SATIN)` и `unifiedDefaultsFor(SATIN)` сохранён один конечный набор RGB194/5/14, coverage1.98 и material/highlight/edge-параметров; в обоих режимах дополнительно установлены cameraValueTransfer0.20, innerCoverage1 и toothProtection0. Формулы `IosLipMaterial` и `UnifiedLipMaterial` не объединялись. Preference namespaces обновлены до `IOS_MATERIAL_SATIN_C2050E_V3` и `UNIFIED_MATERIAL_SATIN_V5`, чтобы старые сохранённые значения не перекрывали defaults. Оба полных блока обновлены в `LIPSTICK_PRESETS.md`; результаты сборки и smoke-проверки зафиксированы выше.
**Добавлена независимая интенсивность цвета.** `pigment_opacity` (`LipstickTuning.pigmentOpacity`, UI «Интенсивность цвета», 0–1, default1) смешивает сохранённую camera/natural-lip фактуру с рассчитанным пигментом до reflection layer. Поэтому значение0 оставляет натуральную основу, но сохраняет блик GLOSS/SATIN; `opacity` по-прежнему управляет всем эффектом целиком. Параметр работает в UNIFIED и как optional Android override в IOS_REFERENCE, при default1 визуально не меняет утверждённые профили. Он автоматически участвует в per-mode/per-finish persistence и экспорте. Для прозрачного блеска следует оставлять общую «Интенсивность» и coverage на базовых значениях, уменьшая только «Интенсивность цвета».

После добавления параметра `:app:assembleDebug` и `git diff --check` прошли. APK установлен и запущен на Samsung SM-G990B (R5CRC3SPVNE), runtime подтвердил `UNIFIED`; shader compile/link, GL_INVALID, AndroidRuntime/FATAL и ARCore render failure не обнаружены. Визуальная оценка диапазона прозрачного блеска остаётся за пользователем. Unit-тесты и коммит не выполнялись.
**История: предыдущие основные профили UNIFIED (заменены общей сатиновой базой 2026-09-16).** Пользователь утвердил переданные GLOSS/SATIN/MATTE blocks как базовые параметры финишей. Они сохранены в `LipstickTuning.unifiedDefaultsFor`, полном виде — в `LIPSTICK_PRESETS.md`, а preference namespace поднят до `UNIFIED_MATERIAL_*_V2`, чтобы старые ручные значения не перекрывали defaults. Для следующих помад выбирается готовый finish profile и меняются только pigment RGB и, для прозрачного/тонирующего продукта, `pigment_opacity`; остальные параметры финиша считаются зафиксированными до отдельной явной команды пользователя.

GLOSS: HIGH/LIQUID, coverage2, naturalLip0.10, RGB100/50/41, pigmentBrightness1, cameraValueTransfer0, нейтральные detail/highlight/edge multipliers. SATIN: актуальный V5 описан выше; прежние V2/V3/V4 заменены. MATTE: HIGH/MOUSSE, coverage2, naturalLip0.20, RGB158/38/32, pigmentBrightness0.85, cameraValueTransfer0.50, highlights/specular/satin/wet0, edge refinement/softness/blur2/1/1, cornerFade0, seamShadow2, cameraSampleScale1.15.

После фиксации V2-профилей `:app:assembleDebug` и `git diff --check` прошли. Установить этот APK не удалось: во время установки Samsung отключился, `adb devices` после ошибки не показал устройств. Unit-тесты и коммит не выполнялись.
**Контекст решения.** Пользователь запросил A/B-проверку двух вариантов цветоотображения: текущего переноса iOS и упрощённой формулы, где MATTE/SATIN/GLOSS используют одну пигментную основу. Отдельные renderer/tracking pipelines не создаются: это увеличило бы риск расхождения Sync, topology, temporal filters и edge blur.

**Решение.** Над выбором материала в ARCore tuning panel добавлен single-select переключатель «Как на iOS» / «Единая формула». `LipColorRenderingMode` хранит выбранный режим, `ArCoreFaceAnchorRenderer` передаёт один uniform в существующий lip shader, а обе material-функции работают в прежнем GPU pass и используют ту же exact Sync camera texture. Выбранный режим сохраняется между запусками; tuning хранится в независимых namespaces для каждой пары mode+finish. Экспорт параметров теперь включает `rendering_mode`.

В `UNIFIED` один и тот же dense pigment/detail/corner/seam/tooth base применяется к MATTE, SATIN и GLOSS; при полном coverage центр непрозрачен, а снижение alpha остаётся только в маске края и corner opacity. Finish влияет только на camera-derived reflection: MATTE — 0; SATIN — тот же reflection signal с gain 0.46, меньшим sample scale, большей concentration и cap 0.12; GLOSS — полный signal с cap 0.26. Искусственный 2D highlight не добавлялся. RGB, общая интенсивность и остальные runtime tuning остаются регулируемыми; density/texture metadata в этом режиме намеренно не меняют формулу, но остаются доступными для сравнения и iOS режима. Neutral defaults разделены: iOS сохраняет high/creamy или high/liquid reference metadata, единая формула начинает со стандартной общей базы при сохранении прежних reference RGB каждого finish.

**Последствия.** A/B можно делать в одном кадре без перезапуска камеры и без изменения geometry/tracking. Оба варианта используют существующие два blur FBO и не добавляют camera streams, CPU readback, модели, assets или зависимости. Это candidate для визуальной проверки, а не утверждённая новая основа. Unit-тесты и коммит без отдельной команды не выполнять.

`:app:assembleDebug` и `git diff --check` прошли. APK установлен и запущен на Samsung SM-G990B (R5CRC3SPVNE). Runtime log подтвердил запуск `IOS_REFERENCE`, переключение на `UNIFIED` и продолжение Sync-rendering; shader compile/link, GL_INVALID, AndroidRuntime/FATAL и ARCore render failure не обнаружены. Визуальная оценка различий остаётся за пользователем.

## Активно: общий материал iOS для MATTE/SATIN/GLOSS — 2026-09-15

По команде пользователя реализован основной материал предоставленного iOS Metal compositor, а не предложенное упрощение. `IosLipMaterial` заменяет satin-only module и прежние отдельные Android matte/gloss формулы. Общая база — исходный pigment RGB, logarithmic camera relief, density compensation, corner/seam/tooth guards; matte отдельно ослабляет светлые детали, satin усиливает реальные отражения, gloss выделяет их наиболее яркое ядро. Искусственные UV полосы/круги/верхний овал удалены из active path. Исходник iOS не изменялся; источник shader SHA-256 D07A655DFD47417658C5564FA64E900071224B4B83BBD9059E4F7CF185053D05.

`LipstickDensity` (LOW/MEDIUM/HIGH → 0.54/0.78/0.92) и `LipstickTexture` (CREAMY/MOUSSE/LIQUID с iOS detail/highlight/limit/concentration coefficients) — независимые material metadata без SDK/ML типов. Для ручной проверки добавлены дискретные controls в панели, per-finish persistence и экспорт `product_density`/`product_texture`. Low-density GLOSS смешивает пигмент с naturalLipColor на factor до 0.60 и outputCoverage с 0.45 как в Metal; это не общий opacity reset.

Новые `IOS_MATERIAL_*_V1` preference profiles и нейтральные defaults исключают влияние прежних финальных тюнингов. MATTE RGB158/38/32 high/creamy; SATIN RGB184/32/45 high/creamy; GLOSS RGB100/50/41 high/liquid. Все runtime множители =1, opacity=1; naturalLip/edgeSoftness/edgeBlur/hue=0. Legacy cameraValueTransfer выключен (0), но оставлен как явный optional Android override; iOS база не использует HSV value transfer. Финальные Dior/Brown Espresso blocks в `LIPSTICK_PRESETS.md` и прежние SharedPreferences namespaces сохранены без изменений, их параметры относятся к прежней формуле и больше не являются defaults active режима.

На все product finish действует одна mesh coverage (multiplier=2); physical density задаётся отдельно output alpha. Blur получает фактическую alpha материала с density/corner/global opacity, чтобы не переносить продуктовую прозрачность в маску рта. Существующие два GPU FBO, 13-tap alpha blur, scissor ROI, outer feather и fix центрального RGB сохранены; новых GPU targets, camera streams, inference или CPU readback нет. TRACKING_TEST остаётся flat magenta direct path без blur. Координаты/индексы/topology/temporal filters и exact-pair Sync не меняются.

Ориентированные source-texel vectors масштабируются к эквиваленту iOS BGRA с длинной стороной512; радиусы camera detail5 и highlight12x8 теперь имеют сопоставимый пространственный масштаб и сохраняются с exact Sync-парой. Это не буквальная предварительная downsample/filter512: Android продолжает читать существующую GPU camera/paired texture. Для iOS lightingFactor используется neutral fallback1, не camera luma вместо lux. Pigment arithmetic — source-sRGB, final blend — принятые IEC sRGB transfer functions как в предыдущем satin port. Маска/расширенный SceneKit carrier/inner fill triangles iOS не переносятся. Camera color management, input filtering, ARKit ambient и topology остаются ограничениями визуального паритета.

Перенос — candidate для device visual acceptance, не доказанное совпадение с iOS. Unit-тесты и коммит без отдельной команды не выполнять. Исторические секции ниже описывают предыдущие режимы.

`:app:assembleDebug` и `git diff --check` прошли. APK установлен и открыт на Samsung SM-G990B (R5CRC3SPVNE); новый процесс13571 и экран launcher подтверждены. В полном просмотренном process log нет shader compile/link errors, GL_INVALID или FATAL EXCEPTION. UI hierarchy подтвердил выбранный SATIN; автоматическая проверка дальнейших переключений не подтверждена — панель прокрутили, прежние finish bounds перестали быть актуальны, и тестовые taps могли затронуть coverage slider. Пользователю рекомендован ручной reset каждого finish перед чистым сравнением. Базу/namespaces автоматически не сбрасывали после этого, чтобы не уничтожить текущие ручные настройки. Расширенный visual/performance acceptance не проводился, unit-тесты/коммит не выполнялись.

## Настройка: компактный блик верхней губы GLOSS — 2026-09-15

По следующей команде пользователя верхний блик перенесён на противоположную (правую в отображаемой примерке) часть губы: базовый centerArc 0.56 → 0.44. Асимметричный oval contour зеркально отражён по arc-оси вокруг своего центра. Движение от света сохраняет прежнее направление, согласованное с нижним GLOSS: текущий centerArc = 0.44 + clamp(highlightArcCenter - 0.5, -0.08, 0.08), диапазон 0.36–0.52. Размер/feather/яркость, нижний блик и camera mapping не меняются. Ниже сохранена история исходного положения.

Сборка отражённого варианта и diff check прошли; APK установлен на Samsung SM-G990B для визуальной проверки. Unit-тесты и коммит не выполнялись.

Верхнему овалу добавлено горизонтальное движение от того же camera illumination gradient, что у нижнего GLOSS: centerArc = 0.56 + clamp(highlightArcCenter - 0.5, -0.08, 0.08). Используется существующий нижний highlightArcCenter с gain 0.38; диапазон верхнего центра ограничен 0.48–0.64. Базовое смещение сохранено, ring center остаётся 0.50; размер, асимметрия, яркостная формула и нижний блик не меняются. Дополнительных camera samples, temporal state или GPU-проходов нет; расчёт читает ту же exact Sync-пару.

Сборка после добавления движения и diff check прошли. APK установлен на Samsung SM-G990B; визуальная оценка движения верхнего блика остаётся за пользователем. Unit-тесты и коммит не выполнялись.

По команде пользователя широкая верхняя полоса GLOSS заменена одним небольшим мягким неправильным овалом. Центр в lip-local arc/ring UV — (0.56, 0.50), полурадиусы при highlightSize=1 — (0.070, 0.23): около 14% ширины губ и 46% локальной толщины до feather. Небольшой shear и стабильный sinusoidal warp дают асимметричный контур без временного noise. Размер/концентрация/сила управляются существующими highlightSize/highlightConcentration/highlightStrength; освещение камеры и normal lobe продолжают определять яркость/цвет. Верхняя губа обозначается отдельным неизменным material attribute по существующему порядку vertices, без изменения coordinates/indices/topology. Нижний GLOSS, MATTE/SATIN, пигмент, defaults, blur и exact Sync не меняются. Это candidate для визуальной проверки, не утверждённая финальная форма.

`:app:assembleDebug` и `git diff --check` прошли, unit-тесты не запускались. APK с исправлением blur и новой верхней формой установлен на Samsung SM-G990B и открыт; процесс приложения подтверждён. Пользователю нужно выбрать GLOSS для визуальной оценки. Коммит не выполнялся.

## Исправление: сохранение бликов при GPU alpha blur — 2026-09-15

Пользователь сообщил о вертикально выглядящих бликах после добавления blur и поручил исправить. Найден дефект RGB propagation: оба separable-прохода выбирали цвет соседнего texel с максимальной alpha даже внутри губ, смещая уже рассчитанные specular/detail. Теперь каждый проход сохраняет центральный RGB при исходной alpha >= 0.002; соседний цвет используется только для заполнения прозрачных texels. Центральная выборка переиспользуется в Gaussian kernel: остаются 13 samples, два прежних FBO и прежние веса/radius/outer feather. Формула GLOSS, UV, цветопередача, tuning defaults, tracking/mesh и exact Sync не меняются. Исправление применяется к общему MATTE/SATIN/GLOSS path; визуальное подтверждение устранения вертикальных артефактов ещё требуется.

`:app:assembleDebug` и `git diff --check` прошли; unit-тесты не запускались. Установка этой версии не выполнена: `adb devices` не обнаружил подключённых устройств. APK: `app/build/outputs/apk/debug/app-debug.apk`. Коммит не выполнялся.

## Активно: нейтральный GLOSS для проверки бликов — 2026-09-15

По команде пользователя текущие GLOSS defaults временно возвращены к нейтральному LipstickTuning: все множители 1, naturalLip/edgeSoftness/edgeBlur/hue 0. В частности specular поднят с 0.15 до 1, cameraValueTransfer с 0.82 до 1, contrast сброшен с 1.05 до 1 и hue с 0.6 до 0. Основной цвет Brown Espresso RGB 100/50/41 (#643229) сохранён. Отдельный preference profile GLOSS_NEUTRAL_HIGHLIGHT_CHECK_643229_V1 исключает override старыми настройками и сохраняет прежний финальный профиль. Утверждённый Brown Espresso preset в LIPSTICK_PRESETS.md не изменяется; это временный режим проверки. SATIN Dior 999, MATTE, GLSL, blur, tracking и exact Sync не меняются.

## Принято: конечный SATIN Dior 999 — 2026-09-10

Пользователь утвердил конечный вариант Dior Rouge Dior Satin 999 с базовым RGB 184/32/45 (#B8202D), pigmentBrightness 0.90, opacity 0.69, naturalLip 0, saturation 2, luminance 0.90 и максимальными edgeSoftness/edgeBlur. Фактура, camera/material detail, shadow, micro/surface detail, specular/highlights, satin glow и wet inner edge отключены переданными параметрами; полный блок хранится в LIPSTICK_PRESETS.md и LipstickTuning.defaultsFor(SATIN). Отдельный versioned preference profile гарантирует загрузку этого набора вместо прежних сохранённых SATIN candidates. Checkpoint: `[Makeup] Finalize Dior 999 satin preset`.

## Реализовано: общий GPU alpha blur для помад — 2026-09-10

По команде пользователя MATTE/SATIN/GLOSS переведены на общий двухпроходный separable blur маски. Material shader рендерит во временную RGBA-текстуру signed color delta относительно камеры и исходную alpha; два 13-tap прохода используют сохранённые iOS Gaussian weights и масштабируют шаг относительно canonical 224×112. Alpha усредняется Gaussian kernel. После исправления 2026-09-15 RGB непустого центрального texel сохраняется; цвет texel с максимальной alpha переносится только в прозрачные texels для продолжения цвета на кромке. Прежний перенос RGB по всему телу губ мог деформировать блики и больше не используется.

Финальный композит снова рисуется существующей lip mesh. Поэтому реальное отверстие рта остаётся геометрически исключённым, а blur не окрашивает зубы/рот. Первый 10% outer gate пользователь оценил как визуально незаметный: он действовал главным образом там, где исходная coverage уже почти нулевая. Текущая реализация использует базовый gate на внешних 25% толщины губы и canonical blur radius 1.5×. Внутренний край этим gate не ослабляется. С 2026-09-16 активный ARCore path использует ограниченный внешний carrier за MediaPipe outer geometry; расширенный iOS SDF gate по пикселям по-прежнему не переносился.

Используются два переиспользуемых full-resolution RGBA8 FBO без CPU readback; fragment work blur-проходов ограничен scissor-областью губ с запасом в шесть sample steps. TRACKING_TEST остаётся прямым диагностическим render path без blur. Non-blocking exact-pair Sync, camera timestamps, landmarks, mesh topology и temporal filters не менялись. edge_softness продолжает формировать coverage до spatial blur. Ползунок edge_blur теперь действует по названию: увеличивает spatial radius от 1.5× до 2.5×, расширяет outer feather с 25% до 45% толщины и усиливает спад исходной coverage; прежняя power-функция, повышавшая слабую alpha и делавшая край плотнее, инвертирована.

:app:assembleDebug проходит, unit-тесты не добавлялись и не запускались. Последний APK установлен и открыт на Samsung SM-G990B. Пользователь выполнил визуальную настройку SATIN и объявил переданный набор конечным. Отдельный performance benchmark общего blur и visual acceptance MATTE/GLOSS не проводились.

## Принято: адаптивные стартовые правила для всех помад — 2026-09-10

По команде пользователя стартовое правило распространено на все product finish. `LipstickTuning` вычисляет исходный `camera_value_transfer` из HSV value основного source-sRGB pigment: value ≤0.45 → 0.82; 0.45–0.65 → 0.90; выше 0.65 → 1.00. Для текущих вариантов это даёт MATTE `#9E2620` → 0.90, SATIN Dior 999 `#B8202D` → 1.00, GLOSS Brown Espresso 515 `#643229` → 0.82.

Правило меняет только стартовую передачу яркости; утверждённые индивидуальные opacity, naturalLip, contrast, saturation, hue, optics и edge-параметры Dior/Brown Espresso сохраняются. Общая нейтральная база новых продуктов остаётся pigmentBrightness/brightness/contrast/saturation=1, hue=0; плотность и finish optics задаются продуктом. TRACKING_TEST исключён. Формула GLSL, tracking, exact-pair Sync, geometry, topology и temporal filters не меняются.

Launcher пока стартует с MATTE и его отдельным preference profile, чтобы текущую базу можно было видеть без старых сохранённых настроек. MATTE `#9E2620` остаётся finish baseline, а не отдельным брендовым продуктом для `LIPSTICK_PRESETS.md`.

`:app:assembleDebug` после общего правила прошёл без unit-тестов. Debug APK установлен и запущен на Samsung SM-G990B (`R5CRC3SPVNE`); пакет обновлён 2026-09-10 12:34:20, процесс приложения запущен. Визуальная оценка остаётся за пользователем.

## Активно: L'Oréal Paris Infaillible Laque Resistance 515 Brown Espresso — 2026-09-09

Завершена настройка плотной глянцевой помады L'Oréal Paris Infaillible Laque Resistance 515 Brown Espresso. Пользователь утвердил основным цветом замер свотча на руке RGB 100/50/41 (`#643229`) и передал конечный набор runtime-параметров.

Конечный GLOSS preset использует RGB 100/50/41 (`#643229`). Предыдущий результат на губах при pigment RGB 105/26/7 (`#691A07`) давал замер RGB 84/35/30 (`#54231E`); это сохранено только как контрольная история, а не текущая база.

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

Launcher стартует с GLOSS для этого продукта. Финальный preset `#643229` использует отдельный versioned preference profile, поэтому прежние GLOSS-значения и рабочие candidates не перекрывают утверждённую базу после `adb install -r`; сохранённый SATIN profile не затрагивается. Tracking, exact-pair Sync, mesh, topology и temporal filters не меняются.

Полный утверждённый набор и основной цвет внесены в `LIPSTICK_PRESETS.md`. Исходные фотографии не копируются в Git.

### Принятое правило первого отображения новой помады

Для нового продукта сначала сохранять точный основной RGB без компенсации через `pigment_brightness` или общую `brightness`: оба параметра стартуют с 1.00, как и `contrast`/`saturation`; `hue=0`. Главная стартовая поправка — `camera_value_transfer`, потому что полный перенос при тёмном пигменте умножает camera value на малый `pigmentValue/0.85` и может чрезмерно затемнять результат. Ориентир для первого candidate: pigment HSV value до 0.45 → transfer 0.80–0.82; 0.45–0.65 → около 0.90; выше 0.65 → 1.00. Это стартовая эвристика для device A/B, а не финальная формула или замена пользовательского принятия.

Для плотного лака стартовать с `opacity=1`, `natural_lip=0`; для средней плотности и тинтов отдельно увеличивать примесь натуральных губ и/или снижать opacity. Для нового GLOSS стартовый `specular=0.15`, `highlight_retention=1`, `highlight_strength=1`; остальное нейтрально. `hue=0.6` у Brown Espresso — индивидуальная коррекция, не общий default. Порядок настройки: camera-value transfer → opacity/natural lip → contrast → specular/highlight → только затем brightness/hue.

Адаптивный выбор этих стартовых значений в коде пока не автоматизирован. Текущие defaults остаются явными per-product наборами; не менять принятую цветопередачу или её формулу без отдельной задачи и device evidence.

## Активно: интерактивная настройка материала — 2026-09-08

По команде пользователя верхняя информационная карточка launcher удалена. Ошибки/запрос CAMERA остаются отдельной центральной панелью. Внизу добавлена компактная прокручиваемая панель `Параметры`, которую можно свернуть; высота раскрытой области ограничена 180 dp, чтобы на SM-G990B не перекрывать губы.

`LipstickTuning` хранит runtime-множители раздельно для каждого finish. Доступно 35 параметров в группах: общее покрытие; цвет; фактура; блики; края и рот; выборка камеры. Finish и Async/Sync также находятся в панели. Изменения применяются к renderer через immutable volatile snapshot без остановки GL thread, сохраняются в SharedPreferences, сбрасываются к дефолту текущего finish и копируются как `key=value`. SATIN и GLOSS хранят утверждённые product defaults; MATTE временно содержит candidate проверки стартового правила.

Пользователь выбрал новый основной SATIN default после усиления растушёвки: opacity 0.69; coverage 1.00; naturalLip 0.00; pigmentBrightness 0.90; brightness/contrast 1.00; saturation 2.00; hue -0.9; luminance 0.90; cameraValueTransfer 1.00; cameraDetail/materialDetail/shadow/microTexture/surfaceDetail 0.00; roughness 0.35; specular/highlightRetention/highlightStrength/satinGlow/wetInnerEdge 0.00; highlightSize 0.40; highlightThreshold/highlightConcentration 0.50; edgeRefinement 2.00; edgeSoftness/edgeBlur 1.00; innerCoverage 1.00; cornerFade 2.00; seamShadow 1.95; toothProtection 2.00; cameraSampleScale 0.35. Базовый цвет остаётся #B8202D (RGB 184/32/45). Для варианта создан новый versioned SATIN preference profile, чтобы сохранённые ранее значения не перекрывали default.

Реализован общий camera-value color transfer для MATTE, SATIN и GLOSS. В собственном GLSL пиксель камеры из той же exact Sync-пары переводится в HSV; пигмент задаёт hue/saturation, а итоговый value равен camera value × (pigment value / 0.85) с ограничением 0–1. Коэффициент 0.85 взят из исследованного Banuba beauty-ios sample и не подбирался вслепую. Ползунок Яркость из камеры (0–1, default 1 для каждого finish) даёт точный A/B: 0 сохраняет прежний пигмент, 1 включает полный перенос. После этого сохраняются существующие material detail, light/highlight и общие runtime-настройки конкретного finish. Banuba SDK, LIPS/LIPS_SHINING masks, модели и assets не подключены; RGB↔HSV и смешивание написаны самостоятельно поверх существующих Sync-frame и MediaPipe mesh/coverage.

Добавлены отдельные runtime-параметры базового цвета для MATTE, SATIN и GLOSS: RGB-каналы 0–255 и Яркость пигмента 0.50–1.20. RGB меняет сам исходный оттенок, а яркость пигмента пропорционально затемняет или осветляет его до camera-value transfer, фактуры и бликов. Прежний параметр Яркость переименован в Яркость материала и продолжает действовать на уже рассчитанный материал. TRACKING_TEST сохраняет диагностический magenta.

`Мягкость края` управляет coverage-функцией mesh до отдельного spatial alpha blur. `Растушёвка края` одновременно усиливает спад исходной coverage, увеличивает реальный spatial radius и расширяет outer feather gate. Точная граница MediaPipe, tessellation, non-blocking exact-pair Sync и temporal filters не изменены. Новых моделей/assets/readback нет.

:app:assembleDebug после color transfer, GPU blur и нового SATIN default прошёл без unit-тестов. APK установлен на Samsung; пользователь завершил визуальный подбор Dior 999. LipFrameTrace держать выключенным.
Связанные документы:

- `AGENTS.md` — обязательные правила работы в репозитории;
- `LIPSTICK_PRESETS.md` — реестр утверждённых конечных вариантов параметров помады;
- `FULL_FACE_ROADMAP.md` — только будущий план;
- `CHAT_HANDOFF.md` — короткая оперативная передача контекста;
- `IOS_REFERENCE_CONTEXT.md` — исследование iOS-reference, различия материалов/масок и новые требования;
- `LICENSE_COMPLIANCE.md` — единый реестр лицензий и release gates;
- `app/src/main/assets/MODEL_LICENSES.md` — происхождение и hash bundled ML-модели.

## Активно: устранение полоски между губами — 2026-09-04

Пользователь разрешил начать исправление полоски после checkpoint 3700c9f. Первый candidate устраняет подтверждённое двойное ослабление покрытия на стороне губы. После установки пользователь поручил «Оставь пока так и закоммить». Это сохранённый рабочий вариант, а не подтверждение полного исчезновения полоски и проверки всех mouth/teeth случаев.

- ARCore launcher создаёт LipMeshTessellator(featherInnerBoundary=false). Внутренняя половина колец сохраняет effectiveCoreCoverage до самого inner contour вместо [core, mid, edge, 0]. Внешняя половина coverage неизменна. Значение по умолчанию true оставляет legacy Filament/Vulkan без изменений.
- В общем ARCore shader удалён дополнительный innerGuard, снижавший alpha до нуля на внутреннем краю. Его mouthTeethExclusionConfidence был containment score landmarks, а не независимой пиксельной маской зубов. Неиспользуемые uniforms удалены; tracking-контракт сохранён.
- Координаты, индексы, normals, UV, точные Sync-пары и temporal filters в этом candidate не изменены. Позднее, 2026-09-10, поверх общей цветовой формулы добавлен отдельный GPU alpha blur; моделей, assets и camera readback нет.
- Первый candidate не добавлял fill triangles или расширение внутрь отверстия. После повторного сообщения пользователя 2026-09-16 добавлено только ограниченное lip-side overlap при почти закрытой aperture; оно отключается при открытии рта. Безусловного заполнения рта нет. Проверка зубов и открытого рта остаётся обязательной частью device acceptance.
- Усиленный blur/outer feather принят как часть конечного SATIN Dior 999 от 2026-09-10. Visual acceptance MATTE/GLOSS, performance benchmark и возможный expanded outer carrier остаются отдельными задачами.
- :app:assembleDebug прошёл; unit-тесты не добавлялись и не запускались. Debug установлен и запущен на Samsung SM-G990B (R5CRC3SPVNE); runtime подтвердил FACE TRACKING, SYNC pair, age=0 и anchor=0.0 px. LipFrameTrace=S. В начале запуска в логе есть CAMERA_DISABLED, затем камера восстановилась и получен FACE TRACKING; ошибок shader/link и FATAL EXCEPTION в проверенном логе не обнаружено. В одном status-окне render 49 FPS / MP 15.2 FPS, camera lag 118 ms — это smoke-наблюдение, не performance acceptance. На запрос проверки полоски и зубов при смыкании/открытии/повороте на сатине и глянце пользователь ответил «Оставь пока так и закоммить». Текущие изменения фиксируются отдельным checkpoint после 3700c9f; актуальный hash см. git log. Дополнительных изменений и проверок не выполнять без новой задачи; push не запрошен.

## Предыдущий этап: изучение iOS и фиксация требований — 2026-09-04

Полный разбор предоставленного iOS-reference сохранён в [IOS_REFERENCE_CONTEXT.md](IOS_REFERENCE_CONTEXT.md): карта всех 12 Swift-компонентов, камера/трекинг, matte/satin/gloss, каталог, цветовая цепочка, маски/blur, смыкание, fallback, румяна, build/assets и отдельный неиспользуемый ML-прототип.

На этом историческом этапе после linear compositing сатин стал чуть ближе к iOS, но ещё не был принят. Эта оценка заменена конечным выбором пользователя от 2026-09-10, сохранённым в начале файла.

Требования следующей реализации:

- Достичь сходства сатина, опираясь на полный путь камеры/материала, без подбора яркости вслепую.
- Добавить небольшое размытие краёв помады, сохраняя фактуру и плотность внутри губ, без мерцания и широкого ореола.
- Исправить незакрашенную полоску между верхней и нижней губой; реальное отверстие рта и зубы должны оставаться без помады.

**Исследование завершено; пользователь поручил закоммитить весь текущий результат.** Checkpoint включает перенос сатина с linear compositing и документацию iOS, но не означает visual acceptance. На момент checkpoint мягкий край и исправление полоски не были реализованы; последующая разрешённая работа описана в активном разделе выше. База перед этим checkpoint — 2042ee6, ветка codex/lipstick-material-v2; принятое поведение трекинга основано на ca80814. Актуальный hash checkpoint смотреть в git log. Для фиксации checkpoint повторная сборка/установка и unit-тесты не выполнялись; push не запрошен.

Ключевые уточнения исследования:

- Основной iOS материал — MetalLipColorCompositor, а не только LipMeshRenderer. Цвет берётся из ориентированного BGRA-входа MediaPipe с длинной стороной 512, затем материал растеризуется в canonical 224×112. Android сейчас семплирует GPU camera texels другого масштаба: одинаковые радиусы 5/12/8 ещё не доказывают одинаковый рельеф/блик.
- SceneKit multiply 0.90–1.0 относится **только к CPU fallback**. При доступном Metal Coordinator передаёт factor=1; прежнее объяснение разницы отсутствующим общим затемнением уточнено. ARKit ambient всё же участвует в Metal detail adaptation. Android ARCore light estimation остаётся DISABLED.
- iOS размывает alpha двумя 13-tap GPU-проходами, сохраняя RGB, и ограничивает её внешней маской и aperture gate. Для непрерывности внутреннего края используются плотная alpha на стороне губы, aperture-only переход, восстановление цветовых texels и 18 fill triangles SceneKit.
- Android снижает coverage к нулю у внутреннего края каждой губы и дополнительно применяет innerGuard; это кандидат причины полоски, пока без доказанной локализации по снимку. Нельзя исправлять её безусловным закрашиванием рта.
- iOS live pose/expression prediction не переносить в принятый exact-pair Android Sync. Новые модели/обучение остаются вне scope; прежние отклонённые contour/anchor эксперименты не возобновлять автоматически.

## Цель и обязательные требования

Нативное Android-приложение виртуальной примерки макияжа через фронтальную камеру. Целевые эффекты: помада и контур губ, румяна, тени и подводка.

Главный приоритет — реалистичность в реальном времени: точная привязка к лицу, отсутствие заметных lag/jitter, корректные окклюзии, сохранение фактуры кожи и физически правдоподобные материалы. Скорость разработки и простота кода вторичны.

Обязательные ограничения:

- не заменять GPU/ML-пайплайн простым 2D alpha overlay;
- один camera owner и одна согласованная sensor/render timeline;
- один model-independent timestamped full-face state для всех эффектов;
- ARCore/MediaPipe-типы не должны попадать в material API;
- inference и камера работают on-device, realtime-очереди — latest-only;
- Новые ML-модели исключены из текущего плана по решению пользователя от 2026-09-04: не искать и не подключать готовые модели, не обучать и не дообучать собственные. Существующий MediaPipe Face Landmarker сохраняется. Вернуться к этому направлению можно только по новой явной команде пользователя.
- Для новых SDK/assets и отдельно разрешённых будущих моделей/датасетов/weights сохраняется license gate; прохождение gate само по себе не разрешает добавление модели.

## Репозиторий и текущий checkpoint

- Проект: `C:\Users\User\AndroidStudioProjects\ARMakeup`.
- Ветка: `codex/lipstick-material-v2`.
- Принятый базовый checkpoint трекинга и материалов до переноса сатина: `ca80814` (`[Tracking] Make non-blocking same-frame Sync the default`).
- Текущее устройство: Samsung SM-G990B.
- Launcher: `ArCoreFaceAnchorActivity`; `MainActivity` сохраняет legacy CameraX/Filament/Vulkan rollback и не экспортируется.
- В ca80814 блокирующий Sync заменён на неблокирующие GPU-пары. По команде пользователя Sync — основной режим по умолчанию; Async сохранён для ручного сравнения/отката.

## Активная архитектура

1. ARCore `1.54.0` владеет фронтальной камерой, camera timeline и текущей глобальной позой лица.
2. MediaPipe Tasks Vision `1.0.0` асинхронно получает latest-only `YUV_420_888` image той же ARCore session и выдаёт внешний/внутренний 2D-контур губ.
3. `MediaPipeFaceObservationAdapter` формирует model-independent `FaceObservation`: sensor timestamp, source transform, нормализованные контуры, geometry confidence, mouth openness и analytic `LipSemanticState`.
4. В Async `TimestampedLipAnchorTransport` переносит контур translation-only между measurement/render timestamps. В Sync сохраняется GPU-пара camera image + exact MediaPipe observation, поэтому cross-frame correction равна нулю.
5. `LipContourTemporalRefiner` обрабатывает только lip-local deformation относительно текущих mouth center/width. Global translation остаётся ответственностью ARCore transport.
6. `LipMeshTessellator` строит динамическую mesh; OpenGL ES compositor семплирует ARCore camera texture внутри неё и применяет coverage, reconstructed normals, camera luminance/gradient и lip-local UV.
7. `HeadDownLipVisibilityGate` скрывает помаду при устойчивом сильном наклоне головы вниз.

Rotation, front-camera mirror и `FILL_CENTER` обязаны быть общими для camera image, landmarks и mesh. Вторую CameraX session создавать нельзя.

## Коротко: что уже сделано

- Рабочий ARCore + MediaPipe launcher с переключением `Матовая / Сатин / Глянец / Трек`.
- Model-independent lip observation и изоляция MediaPipe topology в adapter-слое.
- Timestamped ARCore translation transport и фиксированный face-local carrier вместо expression-sensitive mouth vertices.
- Динамическая outer/inner lip mesh с защитой рта/зубов и мягкими границами.
- Material foundation для matte, satin и gloss; рабочая база принята, но финальная optical/HDR/lighting калибровка не завершена.
- Текущие pigments: matte/gloss `#9E2620`, satin `#B8202D`. Сатин переносится из актуального iOS-эталона; визуальное принятие Android-варианта ещё требуется.
- Верхняя губа визуально принята при открытии/закрытии рта.
- Debug `LipFrameTrace` разделяет MediaPipe-only, ARCore-corrected, local-refined и tessellated координаты.
- `MonotonicSensorTimestampGate` устраняет повторный inference одного camera image: same-sensor изменения снижены с `140` до `0`.
- Evidence-based lower-band candidate пропускает общий синхронный center outer/inner к текущему measurement во время подтверждённого mouth transition, сохраняя сглаживание толщины и мелкой формы.

## Текущий визуальный статус

Пользователь принял Sync из `fe8ffe2`: рот, медленные/быстрые движения головы и движения глаз — всё отлично, прежнего дрожания не видно. После перехода на неблокирующие GPU-пары пользователь сообщил «вроде всё норм», затем подтвердил нормальную работу сворачивания/возврата и потери/повторного обнаружения лица. Новый Sync выбран основным; малый lag нижней губы не является текущей визуальной претензией. Расширенный device/performance acceptance остаётся открытым.

Покадровый trace нельзя держать включённым во время visual acceptance: форматирование большой строки на каждом render frame снижало MediaPipe примерно до `15 FPS`. После `adb shell setprop log.tag.LipFrameTrace S` наблюдались ARCore `58–59 FPS`, MediaPipe `24–27 FPS`, YUV `2–3 ms`, ML `22–32 ms`, age около `41 ms`.

Материалы остаются рабочей базой, а не финальным продуктовым качеством. Нужны проверки при разном освещении, защита от shimmer/auto-exposure, BRDF/HDR/edge refinement и multi-device acceptance.

## Основной Sync: неблокирующие same-frame GPU-пары (ca80814)

Принятый `fe8ffe2` ожидал callback до 100 ms на GL thread. Его визуальный результат сохраняем как эталон, механизм ожидания заменяем:

- `Session.update()` продолжает обновляться; tracker отдаёт completion через atomic reference без `await`/condition.
- `PairedCameraFrameStore`: две viewport-sized RGBA8 GPU texture/FBO — одна pending, другая presented. Один inference in flight, без очереди и CPU readback.
- CPU image допускается в Sync только при точном равенстве image/frame timestamp. До следующего update камера копируется GPU-проходом с rotation/mirror/crop; pitch/visibility/point сохраняются значениями, не live ARCore объектами.
- Только exact completion продвигает пару. No-face/error completion даёт изображение без помады; отсутствие completion удерживает готовую пару.
- Фон и материал читают одну сохранённую текстуру. В shader меняется только samplerExternalOES на sampler2D; BRDF/coverage/pigment, refiner и tessellator не изменены.
- Повторы пары используют её прежние sensor/render timestamps. Live ARCore pose и локальный render transition не двигают геометрию на удерживаемом изображении.
- Mode/session/tracker/pause/GL context и фактические rotation/viewport changes сбрасывают пары. Максимальное удержание 500 ms; затем live camera без помады. Это failure bound, не коэффициент фильтра.
- Status `SYNC pair · camera lag N ms` — разница latest camera и presented timestamp. `age 0 ms` означает совпадение геометрии и изображения, не нулевой motion-to-photon. Sync counters — promoted/reused/no-ready-pair.
- Цена: `2 × width × height × 4` байт GPU memory и один camera-copy pass на submit. Число уникальных пар ограничено MP throughput; свободный GL thread не гарантирует снижения задержки.

Сборка `:app:assembleDebug` прошла без unit-тестов, включая изменение стартового режима на Sync. После несовпадения debug-подписей пользователь самостоятельно переустановил APK. Runtime `SYNC pair` на SM-G990B подтверждён 2026-09-04: в просмотренных status-окнах 23–26 новых пар, no-ready-pair=0, MP 23–28 FPS, render 42–58 FPS, age=0 ms, anchor=0.0 px; camera lag преимущественно 41 ms, иногда 81 ms. В просмотренном runtime/crash log ошибок не найдено, trace выключен (`S`). Пользователь предварительно принял визуальный результат и подтвердил pause/resume и reacquisition. В последующих диагностических debug-сборках запуск с Sync по умолчанию подтверждён. Ручное переключение режимов и расширенные failure/performance checks остаются открытыми. Зависимости, модели и лицензируемые assets не добавлены.

Успех Sync поддерживает temporal mismatch/rebase гипотезу, но не выделяет единственную причину: одновременно обнуляется anchor correction и меняется cadence. Прежние варианты опорных точек/live mesh не дали принятого результата; bypass уменьшал jitter ценой lag. Повторять эти эксперименты без новых данных не планируется.


## Граница губ: исследование завершено без принятого улучшения

При сильном сжатии/подвороте и некоторых поворотах помада может выходить на кожу. Это известное ограничение, visual acceptance точной границы не пройден. По решению пользователя от 2026-09-04 дальнейшие эксперименты приостановлены до новой явной задачи.

- GPU-уточнение по цвету не дало уверенного улучшения и добавляло мерцание. Временная стабилизация уменьшила мерцание, но не решила сильное сжатие. Офлайн-поиск видимого контура также отклонён: линии неверны.
- Общее ослабление/выключение помады при сжатии отклонено как замена точной границе. Принятый HeadDownLipVisibilityGate к этому эксперименту не относится и сохранён.
- Scores существующего Blendshape V2 реагировали на трубочку и смыкание, но не выделили ключевой сильный подворот. Они выводятся из landmarks, а не независимо из пикселей; оснований деформировать контур по ним не получено. Источник: https://storage.googleapis.com/mediapipe-assets/Model%20Card%20Blendshape%20V2.pdf .
- Финальная проверка на SM-G990B: в семи снимках exact CPU RGBA input + GPU background timestamps совпали; наше display mapping и независимое ARCore IMAGE_NORMALIZED -> VIEW_NORMALIZED различались менее чем на 0.000220 px. Вход 640x480, rotation=270. При сильном подвороте сырые MediaPipe points уже охватывают кожу на CPU-изображении до display mapping, refiner, tessellation и материала. Дополнительное смещение при отображении не обнаружено в проверенных позах; это не гарантия для всех устройств/ориентаций.
- По команде пользователя экспериментальный GPU-проход, переключатель, захват по громкости вниз, CPU/GPU readback-диагностика, blendshape output/контракт и tools/diagnostics удалены. После очистки код приложения соответствовал ca80814; Sync, материалы, topology и принятые фильтры сохранены. Blendshape output выключен, новых моделей/assets нет.
- Локальные снимки и результаты сохранены только в gitignored captures/lip-boundary (32 снимка; итог CPU mapping — cpu-mapping/report.json и series.jpg). В Git фотографии не добавлять. Не повторять отклонённые варианты и не подбирать коэффициенты без новых данных и задачи пользователя.

## Открытые tracking-дефекты

### 1. Global jitter при движении головы и глаз

В Async дрожание воспроизводилось при yaw и движении глаз. В принятом Sync оно отсутствует визуально. Точная доля шума позы и rebase не доказана; текущий gate — сохранить результат без GL wait и расширить device matrix.

Диагностика локализовала дефект в global anchor/fusion path:

- статический p95 шага MediaPipe mouth center: `0.75 px`;
- статический p95 изменения ARCore correction: `3.53 px`;
- статический p95 corrected center: `3.46 px`;
- при движении только глаз local lower-lip shape почти неизменна: p95 `0.0004` lip width;
- при том же сценарии MediaPipe center давал выброс до `5.3 px`, ARCore correction — до `8.3 px`, corrected center — до `9.2 px`;
- `anchorResidualPx` практически равен нулю, поэтому translation применяется арифметически правильно;
- `tessResidualPx=0`, тесселяция скачок не создаёт.

Фиксированная 3D-точка carrier уже не зависит от деформации губ. Наиболее вероятный источник — шум `face.centerPose` и cross-tracker rebase в выражении `MediaPipe center(M) + anchor(R) - anchor(M)` при поступлении нового sensor timestamp.

Следующий шаг — расширенная проверка основного exact-pair Sync, без изменения anchor/filter coefficients. Стартовое значение renderer и checkedButton интерфейса установлены в Sync; Async включается вручную.

### 2. Малый residual lag нижней губы

Основное отставание устранено переносом shared lower-band center; в принятом Sync пользователь доволен ртом. Локальные фильтры не менять профилактически. Delivery/presentation cadence исследовать только при воспроизводимой регрессии.

## Действующие архитектурные решения

- Целевое направление — 2D hybrid: ARCore даёт вспомогательный global pose/anchor, существующий MediaPipe backend — локальные 2D-контуры, геометрия и анализ изображения — уточнение продуктовых границ и окклюзий без нового ML inference.
- Видимый canonical 3D face/lip renderer отклонён: он ухудшал контуры при мимике и поворотах.
- Predictor, gyro, optical-flow correction, face-wide affine/projective warp и residual smoothing не возвращать без нового controlled A/B, который показывает преимущество над текущим launcher.
- ARCore mesh не является продуктовой геометрией; он используется только внутренним pose/carrier backend.
- MediaPipe остаётся изолированным за model-independent контрактами; замена landmark model и разработка собственной модели исключены из текущего плана.
- Маски видимых областей строить из существующих landmarks, геометрических ограничений и изображения exact pair. Новые pretrained models, training, fine-tuning и сбор обучающих датасетов не входят в scope: пользователь не выделяет на это время и ресурсы. Это продуктовое ограничение, а не утверждение о запрете коммерческого использования всех готовых моделей.
- iOS-проект не изменять; отдельный общий SDK/JSON contract сейчас не нужен. Сравнивается поведение, а не буквальная реализация.

## Ближайший порядок работы

0. Текущий приоритет — автоматическая density всех33 оттенков (UNIFIED0.30/0.65/1.00), сохранение общего цвета/density/intensity между MATTE/SATIN/GLOSS и визуальная проверка. Общая адаптация освещения ярких пигментов и локальное покрытие уголков также требуют acceptance; tracker/exact Sync не менять. Идея только камерной светотени сохранена и отложена; дополнительные tracking задачи ниже не выполняются.
1. Проверить ручное переключение Async/Sync на устройстве; старт с Sync по умолчанию подтверждён в диагностических сборках; runtime exact pairing уже подтверждён, pause/resume и reacquisition приняты пользователем.
2. Оценить latency, GPU memory/copy cost и thermal основного Sync. Локальные фильтры и материал без конкретной регрессии не менять.
3. Расширить acceptance на другие устройства и движение телефона. Не считать render FPS числом уникальных camera/MP пар.
4. Закончить model-independent `FullFaceRenderState` для общего pose, локальных областей, confidence, visibility и occlusion.
5. Исследование точной границы губ приостановлено до новой задачи. Дальнейшие masks для mouth-teeth/глаз/век/кожи планировать в пределах подтверждённого качества и без новых ML-моделей.
6. Перенести принятый hybrid/material state в production GPU/Vulkan path, сохранив OpenGL launcher как rollback до visual parity.
7. Добавлять lip liner, blush, eyeshadow и eyeliner только поверх общего full-face state.
8. Завершить device matrix, thermal/fallback, privacy/license и release acceptance.

Подробный forward-only план находится в `FULL_FACE_ROADMAP.md`.

## Performance budgets

- Render: целевые `60 FPS`; минимум стабильные `30 FPS` на поддерживаемом классе устройств.
- GPU makeup/compositor: желательно `6–8 ms` p95 в пределах общего `16.6 ms` кадра.
- Face landmarks: стремиться к `30–60 results/s`; inference реже render допустим только без заметного lag.
- Уточнение масок по геометрии/изображению: без нового ML inference, внутри существующего GPU budget и без блокировки камеры/renderer. Прежний ориентир отдельного face parsing inference `15–30 results/s` больше не является активным требованием.
- Очереди realtime-веток: глубина `1`, latest-only, без накопления устаревших кадров.
- Любой FPS считается недостаточным при заметном плавании маски, jitter, motion lag или мерцании границ.

## Acceptance gates

- Нет заметного jitter на статичном лице, при движении глаз, yaw/pitch, движении телефона и после dropout/reacquisition.
- Нет заметного отставания при разговоре, улыбке и открытии/закрытии рта.
- Нет окрашивания зубов, рта, белков глаз, волос и фона.
- Проверены разные лица, оттенки кожи, освещение, мимика и поддерживаемые устройства.
- Материал сохраняет текстуру и светотень камеры; gloss не превращается в статичную белую полосу.
- Выполнены functional device runs, visual recordings, thermal soak и fallback checks.
- До релиза закрыты требования `LICENSE_COMPLIANCE.md`.

## Правила текущей работы

- Перед изменениями читать этот файл и `AGENTS.md`.
- Покадровый trace включать только на короткий controlled capture и выключать перед visual acceptance.
- Unit-тесты запускать только по явной команде пользователя; обычный debug build/device check разрешён.
- Не коммитить без явной команды пользователя.

## Активная задача: перенос принятого iOS-сатина — 2026-09-04

- Пользователь предоставил C:\Users\User\Desktop\ios makeup\virtual-makeup-main как эталон; iOS-проект только прочитан. Git-метаданных в этой копии нет. Проверены MetalLipColorCompositor.swift (основной путь), LipTextureRenderer.swift (CPU fallback), LipMeshRenderer.swift (SceneKit), LipstickTypes.swift и продуктовые presets ContentView.swift. Матовая, сатиновая и глянцевая формулы отличаются от старого Android-переноса; в текущем scope обновляется сатин.
- Выбран соответствующий текущему Android-оттенку iOS Dior Rouge Dior Satin: B8202D, high density, creamy. Не добавлены каталог, бренды/текстуры в UI или новые модели. Матовая и глянцевая отрисовка сохраняют прежнее поведение.
- IosSatinLipMaterial переносит Metal sRGB-формулу: выбранный RGB без прежнего смешивания pigmentStrength=0.42/экспозиционного затемнения; раздельная передача теневого/светлого рельефа, detailStrength=1.7*0.92; реальные отражения из камеры с highlightStrength=1.16*1.32, maximum=0.48, concentration=1.15; tooth/seam/corner guards и компенсация низкочастотного цвета при coverage=0.92, opacity=0.99. Искусственные дуги/круги прежнего сатина больше не используются.
- 40 reference UV knots взяты из iOS CanonicalLipGeometry.swift, нормализованы с его padding и интерполируются существующим tessellator один раз для атрибута материала. Геометрические координаты, topology, temporal filters и принятая coverage mask не меняются. iOS маска/feather compute и SceneKit carrier не переносятся.
- Радиусы 5 px для рельефа и 12x8 px для отражения задаются в исходных GPU camera texels; inverse camera-UV transform учитывает rotation/mirror/crop. Эти два вектора сохраняются с exact Sync-парой. CPU readback, новые FBO и новый inference не добавлены; материал остаётся одним GPU draw, 13 camera samples внутри сатиновой формулы плюс существующий coverage stage.
- Ограничения паритета: ARKit ambientIntensity не имеет подключённого эквивалента в этом Android path; используется нейтральный fallback=1 как в iOS при отсутствии оценки. Уточнено полным исследованием: дополнительное SceneKit затемнение 0.90–1.0 применяется только к CPU fallback, при доступном Metal множитель нейтрален. Цветопередача камер, разрешения, SceneKit color management/фильтрация и разные mesh/mask могут давать визуальные различия. Не объявлять полное совпадение до device A/B.
- Открытие для material guards вычисляется по существующему inner contour в display pixels относительно ширины рта с iOS smoothstep 0.018–0.055. ARKit closure veto не переносится; трекинг и видимость всей помады не меняются.
- Источник MetalLipColorCompositor.swift SHA-256 D07A655DFD47417658C5564FA64E900071224B4B83BBD9059E4F7CF185053D05; LipstickTypes.swift A5D5DE84184914DF0055107783BB2B08F3723BEF28E870A280AE226FDA012E4B. Перенос основан на предоставленном пользователем локальном коде, не на стороннем скачанном asset.
- Обычный :app:assembleDebug прошёл, unit-тесты не добавлялись/не запускались. Финальная сборка после удаления старой ветки сатина установлена на SM-G990B. Первый запуск вернул CAMERA_DISABLED; после разблокировки пользователем камера восстановилась. Снимок экрана подтвердил выбранный SATIN, видимую помаду и FACE TRACKING. В просмотренном runtime-окне render 48–59 FPS, MP 24–29 FPS, age=0, anchor=0, camera lag 41–81 ms; ошибок GL/шейдера/камеры после перезапуска не найдено. Это smoke-проверка, не benchmark и не подтверждение визуального паритета. У пользователя запрошена оценка оттенка, плотности, рельефа и блика относительно iOS. LipFrameTrace=S. Коммит/push в этой задаче не выполнялись.


### Проверка избыточной яркости сатина

Пользователь сообщил, что Android-сатин слишком яркий, и подтвердил сравнение с тем же B8202D; скриншот iOS предоставить не может. В iOS-копии сохранённых изображений-эталонов нет. Причина субъективной разницы пока не доказана; первый перенос не принят.

Обнаружено отличие финального композитинга: iOS формирует sRGB-tagged CGImage для SceneKit, Android смешивал correctiveColor и camera RGB непосредственно в sRGB. В IosSatinLipMaterial добавлены отдельные кусочные sRGB transfer functions (12.92 / 0.04045 / 2.4 / 0.0031308), финальный alpha blend выполняется в linear light с обратным кодированием. Формула самого Metal-пигмента остаётся в исходном sRGB; B8202D, плотность 0.92, opacity 0.99, блики и geometry/coverage не менялись. Это исправление этапа переноса, не произвольное затемнение и не доказанное решение жалобы. Linear blending может повышать светлоту отдельных каналов; нужно оценить насыщенность/общий вид на устройстве, не обещать уменьшение численной яркости.

Оценка ARCore lighting в текущей Activity явно DISABLED. Полное исследование уточнило: iOS multiply 0.90–1.0 используется только в CPU fallback; в основном Metal-пути SceneKit factor=1. Открытым остаётся соответствие ambient-dependent detail и цветовой цепочки, а не обязательный перенос ×0.90. Подставлять затемнение без измерений или выдавать camera luma за lux нельзя.

Debug собран без unit-тестов, версия с linear compositing установлена и запущена на SM-G990B. В просмотренном логе нет ошибок шейдера/камеры; на момент проверки SEARCHING FOR FACE. Последующая оценка пользователя: «стал чуть ближе, но всё ещё не тот результат». Визуальный результат не принят; дальнейший подбор остановлен для полного исследования iOS (см. текущую задачу в начале файла). На этом этапе переноса коммит/push ещё не выполнялись; последующая команда на checkpoint указана в начале файла.
