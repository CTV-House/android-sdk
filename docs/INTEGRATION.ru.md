# Руководство по интеграции

English version: [INTEGRATION.md](INTEGRATION.md).
Вопросы о влиянии на приложение: [FAQ.ru.md](FAQ.ru.md).

Библиотека показывает VAST-креатив поверх контентного плеера, когда хост сообщает о
возможности: пауза контента (`PauseRollAd`, §3) или действие зрителя в приложении
(`SwitchRollAd`, §10). Поддерживаются два формата креатива, оба через одну точку входа:

| Формат | Креатив | Поведение |
|---|---|---|
| **Video** | VAST `Linear` с progressive `MediaFile` | играет со звуком, по окончании скрывается сам |
| **Banner** | VAST `Companion` со `StaticResource` | висит до skip или возобновления контента |

Какой из них покажется, решает VAST-ответ, а не хост. Companion `StaticResource` показывается
как баннер. Если его нет — пригодный `MediaFile` как видео. PauseRoll-теги с рекламного
сервера часто содержат оба: Linear, оставшийся от шаблона preroll, и полноэкранный companion;
картинка и есть паузный креатив. Audio-only `MediaFile` играет как видеокреатив без картинки.

Хосту не обязательно знать, какой формат на экране: `onOpen()` и `onClose()` ведут себя
одинаково для обоих. Разница видна в колбэках трекинга (§11) и в том, скрывается ли креатив сам.

---

## 1. Требования

| | |
|---|---|
| minSdk | 21 |
| JVM target | 11 |
| Media3 | `1.2+`, предоставляет хост; проверено на 1.3.1 |
| Разрешения | объявляет сам AAR: `INTERNET`, `ACCESS_NETWORK_STATE`, `AD_ID` |
| Язык | Kotlin или Java |

Media3 объявлен `compileOnly`, версию выбирает хост. Во всём приложении должна быть одна
линия Media3.

## 2. Подключение

```kotlin
// settings.gradle.kts
maven("https://jitpack.io")

// app/build.gradle.kts
dependencies {
    implementation("com.github.CTV-House:android-sdk:1.2.0")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
}
```

Правила R8 входят в AAR — добавлять ничего не нужно.

## 3. Встраивание

Один экземпляр обслуживает один экран. Создаётся, когда контейнер уже есть; хост сам говорит,
когда есть возможность, и отпускает экземпляр при уничтожении экрана. Контентный плеер в SDK
не передаётся: слот `onClose` читает ваше поле `player`, поэтому один `PauseRollAd` и один listener
переживают `onStart` / `onStop`, пока вы меняете ExoPlayer. В примере — `PauseRollAd`, запуск
по tag URL. Формат — `TriggerRoll`; хост, у которого разметка уже есть, реализует
`TriggerRoll.Controller`.

```kotlin
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var pauseRoll: PauseRollAd? = null
    private var contentHadPlayback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pauseRoll = PauseRollAd(binding.root)
            .setTagUrl(VAST_TAG_URL)
            .setRequestTimeoutMs(10_000)
            .setSkipOffsetSeconds(5)
            .setSoundEnabled(true)
            .setControlsPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.BOTTOM)
            .setMarkingPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.TOP)
            .setSkipPosition(Overlay.ControlsHorizontal.RIGHT, Overlay.ControlsVertical.TOP)
            .setLogoVisible(true)
            .setInfoVisible(true)
            .setPauseVisible(true)
            .setMuteVisible(true)
            .setListener(adListener)
            .attach()
    }

    override fun onStart() {
        super.onStart()
        val exo = ExoPlayer.Builder(this).build().also { player = it }
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(CONTENT_URL))
        exo.prepare()
        exo.playWhenReady = true

        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) return
                contentHadPlayback = true
                pauseRoll?.close()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && contentHadPlayback) {
                    pauseRoll?.trigger("pause")
                }
            }
        })
    }

    override fun onStop() {
        pauseRoll?.close()
        contentHadPlayback = false
        binding.playerView.player = null
        player?.release()
        player = null
        super.onStop()
    }

    override fun onDestroy() {
        pauseRoll?.detach()
        pauseRoll = null
        super.onDestroy()
    }

    private val adListener = object : TriggerRoll.Listener {
        override fun onOpen() {
            binding.playerView.useController = false
            binding.playerView.hideController()
        }

        override fun onClose() {
            // close() из onStop приходит сюда же — возобновление сыграло бы в фоне.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            binding.playerView.useController = true
            binding.playerView.showController()
            binding.playerView.requestFocus()
            player?.playWhenReady = true
        }

        override fun onNoAd() = Unit
        override fun onError(message: String) = Log.w("TriggerRoll", message)
        override fun onSkip(urls: List<String>) = Log.i("TriggerRoll", "skip")
        override fun onImpression(urls: List<String>) = Log.i("TriggerRoll", "показ засчитан")
        override fun onStart(urls: List<String>) = Log.i("TriggerRoll", "видео началось")
    }
}
```

Из Java достаточно переопределить нужные колбэки:

```java
pauseRoll = new PauseRollAd(rootView)
        .setTagUrl(VAST_TAG_URL)
        .setListener(new TriggerRoll.Listener() {
            @Override public void onOpen() { }
            @Override public void onImpression(@NonNull List<String> urls) { }
        })
        .attach();
pauseRoll.trigger("pause");
```

`binding.root` — контейнер, в который добавляется оверлей, обычно корневой `FrameLayout` с
плеером. Оверлей вставляется скрытым при `attach()` и поднимается наверх непосредственно перед
показом, поэтому добавленные позже view не перекроют креатив. Библиотека не смотрит на
контентный плеер и не пишет в него. Вызовите `trigger`, когда нужен показ
(обычно пауза после того, как контент уже играл), и `close`, когда окно закрылось
(обычно play). Позже ту же пару можно использовать для перемотки; сам SDK перемотку не делает.

### Показ без звука

Звук настраивается на слоте — аргументом конструктора или сеттером:

```kotlin
PauseRollAd(binding.root)
    .setSoundEnabled(false)
```

```java
new PauseRollAd(rootView).setSoundEnabled(false);
```

То же самое вторым аргументом конструктора: `PauseRollAd(binding.root, soundEnabled = false)`.
По умолчанию `true`. При `false` любой
креатив играет с нулевой громкостью, и библиотека не запрашивает аудиофокус — музыка или другое
приложение на фоне остаются нетронутыми. Кнопка mute скрыта. Изначальная тишина не отправляется
как VAST-событие `mute`: зритель ничего не делал. Вызывать до старта креатива.

## 4. Формат Video

`Linear` с progressive `MediaFile` играет отдельный `ExoPlayer` на поверхности поверх контента.
Порядок выбора в `MediaFiles`: progressive video (`mp4` → `webm` → остальные), затем
progressive audio. HLS, DASH и VPAID пропускаются, если есть progressive-вариант. Контейнер
определяется по MIME-типу, а при его отсутствии — по пути URL.

Что видит хост:

- При включённом звуке реклама запрашивает аудиофокус, поэтому чужие приложения приглушаются
  как для контента. При `soundEnabled = false` не происходит ни того, ни другого.
- `onStart`, квартили, `progress@offset`, `onMute` / `onUnmute`, `onPause` / `onResume`.
- По окончании креатив скрывается сам: `complete`, `closeLinear`, VAST `close`, затем
  слот `onClose()`. При `setDismissOnCreativeEnd(false)` после `complete` и `closeLinear` на
  экране остаётся последний кадр, а показ заканчивается по skip или по вашему `close()`.
- Квартили и progress считаются по позиции воспроизведения креатива, а не по системным часам,
  поэтому буферизация не сдвигает события вперёд.

Если воспроизведение сорвалось, придут `onVastError` и `onError`, оверлей скроется.
Контентный плеер остаётся как был; хост, который возобновляет в слотовом `onClose`,
продолжит воспроизведение после этого скрытия.

## 5. Формат Banner

Companion `StaticResource` показывается на весь экран, масштабируется `FIT_CENTER`. Если в том же
VAST есть ещё и Linear `MediaFile`, побеждает companion: это и есть креатив паузы. Видео берётся
только когда companion нет, либо когда картинка не скачалась.

Альфа-канал картинки сохраняется, и там, где креатива нет, мы ничего не рисуем — поэтому сквозь
прозрачный PNG виден контент хоста. То же и с полями вокруг креатива с другими пропорциями.
`setBackdropVisible(true)` возвращает под креатив чёрную подложку — для площадок, где реклама
должна перекрывать контент. Элементы управления в обоих случаях остаются на своей полупрозрачной
плашке.

Если в Linear есть отдельный audio `MediaFile` (`audio/*` или известное аудиорасширение), он
играет под картинкой, без видеоповерхности. Звук из video `MediaFile` не берётся: нет
аудиофайла — баннер без звука. Пока играет саундтрек, видны mute и pause. Когда он кончился,
уходят `complete` и `closeLinear`, mute/pause скрываются. Если аудио сорвалось, картинка
остаётся, хост получает `onError`.

У картинки нет воспроизведения, которое могло бы закончиться, поэтому её показ заканчивает
время на экране:

- саундтрек, если он есть в ответе, — его конец и есть конец креатива;
- иначе `Duration` из `Linear` в том же ответе;
- иначе `setBannerDurationSeconds(n)` — по умолчанию `0`, то есть без таймера.

Именно это по умолчанию закрывает картинку. При `setDismissOnCreativeEnd(false)` ничего из
перечисленного её не снимает: она ждёт skip или ваш `close()`.

Это стоит решать под площадку: на экране паузы слотовый `onClose()`, который возобновляет контент,
возобновит его, пока зритель ещё стоит на паузе. Там передайте `setDismissOnCreativeEnd(false)`,
как это сделано в демо, и пусть показ заканчивает зритель.

Что видит хост:

- `onImpression`, `creativeView`, `loaded`. `start`, квартили, `progress` и `complete` — только
  пока играет саундтрек.
- Когда время выше вышло, показ заканчивается сам: VAST `close`, затем слот `onClose()`.
  Картинку, которую нечем отмерить, снимает зритель. Skip — это VAST `onSkip(urls)` и затем
  слот `onClose()`; возобновление контента — VAST `close` и затем слот `onClose()`.
- Тело изображения ограничено 8 МБ, декодирование — с даунсэмплингом до 4 МПx.
- Если картинка не загрузилась, а Linear `MediaFile` есть, библиотека переходит на видео.
  Если видео тоже нет, не показывается ничего: ни оверлея, ни impression, только `onNoAd()`.

## 6. Skip

Кнопка skip видна сразу и становится активной по истечении оффсета, отсчёт идёт в её тексте.
Оффсет берётся в таком порядке:

1. `setSkipOffsetSeconds(n)`, если хост его задал;
2. абсолютный `skipoffset` из VAST `Linear` (процентный игнорируется);
3. 5 секунд.

Skip отправляет VAST-трекеры `skip` и `onSkip(urls)`. Библиотека контент **не** возобновляет.
Сделайте это в слотовом `onClose()`: он же срабатывает, когда видеокреатив доиграл и когда
хост вызывает `close()`. У `onNoAd` парного `onClose` нет — возобновите и там, если промах
не должен оставлять поток на паузе.

## 7. Хром

Три независимых куска, у каждого свой угол (горизонталь × вертикаль):

```kotlin
.setControlsPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.BOTTOM)
.setMarkingPosition(Overlay.ControlsHorizontal.LEFT, Overlay.ControlsVertical.TOP)
.setSkipPosition(Overlay.ControlsHorizontal.RIGHT, Overlay.ControlsVertical.TOP)
```

| Кусок | По умолчанию | Содержимое |
|---|---|---|
| группа воспроизведения | слева × снизу | info, mute, pause |
| маркировка | слева × сверху | логотип + чип «РЕКЛАМА» |
| skip | справа × сверху | отсчёт / пропуск |

Звук и пауза появляются у linear-креатива и пока под баннером играет саундтрек. Info — когда
у креатива есть http(s) `ClickThrough`. У баннера остаются маркировка (с логотипом), skip и info,
если есть посадочный URL.
Контролы появляются вместе с креативом, а не пока медиа ещё буферизуется.
Нажатие mute или pause отправляет VAST-трекеры `mute` / `unmute` / `pause` / `resume` так же,
как событие плеера. Нажатие info открывает QR код `ClickThrough` и пингует `ClickTracking`.

Хром рассчитан на пульт: сам оверлей фокус не забирает, skip остаётся в цепочке во время
отсчёта, сфокусированный контрол становится белым. С pause пульт дотягивается до skip, даже
если тот в другом углу.

Логотип стоит в чипе маркировки слева от текста. По умолчанию виден;
`setLogoVisible(false)` убирает иконку, надпись остаётся. Info, pause и mute скрываются так же:
`setInfoVisible` / `setPauseVisible` / `setMuteVisible`.

## 8. Конфигурация

Всё — цепочка сеттеров, вызываемых до `attach()`; набор одинаков у обоих лончеров. Звук можно
также передать в конструктор.

| Метод | По умолчанию | Описание |
|---|---|---|
| конструктор `soundEnabled` / `setSoundEnabled` | `true` | `false` — без звука и без аудиофокуса |
| `setTagUrl` | — | обязателен до первой возможности; макросы подставляются при запросе (§9) |
| `setIfa` | ID устройства | переопределяет рекламный ID, который сообщает устройство; пустая строка возвращает его |
| `setIp` | пусто | IP клиента для `${IP}`; изнутри приложения адрес не виден |
| `trigger` | — | хост открывает возможность (`reason` — для лога) |
| `close` | — | хост закрывает её; сбрасывает запрос в полёте |
| `setRequestTimeoutMs` | `10000` | тег и переходы по Wrapper |
| `setSkipOffsetSeconds` | VAST `skipoffset`, иначе `5` | |
| `setDismissOnCreativeEnd` | `true` | `false` держит креатив до skip или `close` |
| `setBannerDurationSeconds` | `0` | время на экране для картинки, которую нечем отмерить |
| `setControlsPosition` | слева × снизу | группа: info / mute / pause |
| `setMarkingPosition` | слева × сверху | чип маркировки |
| `setSkipPosition` | справа × сверху | чип skip |
| `setLogoVisible` | `true` | логотип в чипе маркировки |
| `setBackdropVisible` | `false` | `true` рисует под креативом чёрную подложку |
| `setInfoVisible` | `true` | QR код ClickThrough; без посадочного URL всё равно скрыт |
| `setPauseVisible` | `true` | пауза linear-креатива; у баннера всё равно скрыта |
| `setMuteVisible` | `true` | mute linear-креатива; скрыт у баннера и при выключенном звуке |
| `setMarkingTemplate` | `РЕКЛАМА ${ERID}` | текст маркировки |
| `setSkipCountdownTemplate` | `Пропустить через ${SECONDS}` | обратный отсчёт |
| `setSkipTemplate` | `Пропустить` | skip доступен |
| `setDebugLogging` | `false` | флаг уровня процесса, только тестовые сборки |
| `setListener` | `null` | окно слота и VAST-трекинг |

Тексты по умолчанию соответствуют российским правилам маркировки. Плейсхолдеры: `${ERID}`,
`${SECONDS}`.

## 9. Устройство и макросы

Библиотека собирает один снимок устройства на экран и подставляет из него макросы: в tag URL, в
каждый пиксель трекинга, в URL креатива и в `ClickThrough` за QR-кодом. Пиксель больше не уходит
с сырым `[IFA]` — он описывает то же устройство, что и запрос, который его заработал.

Вместе с AAR приходят три разрешения установочного уровня, ни одно не спрашивает зрителя:
`INTERNET`, `ACCESS_NETWORK_STATE` (тип соединения) и
`com.google.android.gms.permission.AD_ID` (чтение рекламного ID на Android 13+).

Рекламный ID читается из сервиса идентификатора Play Services либо из пары `Settings.Secure` на
устройствах Amazon. Зависимости на Play Services в артефакте нет и в рантайме не требуется:
если ни одного источника нет, значит нет и ID. Запрос выполняется в фоновом потоке при
`attach()`, поэтому ни один вызов хоста его не ждёт.

Отказ зрителя от трекинга, устройство без ID и хост, снявший разрешение `AD_ID`, дают один и тот
же результат: `${IFA}` пустой, `${LIMITADTRACKING}` равен `1`. `setIfa` переопределяет ID
устройства, если хост управляет согласием сам.

| Макрос | Синонимы | Значение |
|---|---|---|
| `${IFA}` | | рекламный ID, пусто если недоступен |
| `${IFATYPE}` | | `gaid`, `afai` или `host`, если задан хостом |
| `${LIMITADTRACKING}` | `${LMT}`, `${DNT}` | `1` при отказе зрителя или при отсутствии ID |
| `${USER_AGENT}` | `${DEVICEUA}`, `${CLIENTUA}` | user agent, который отправляет библиотека |
| `${IP}` | `${DEVICEIP}` | из `setIp`, иначе пусто |
| `${RANDOM}` | `${CACHEBUSTING}`, `${CACHEBUSTER}` | 8-значный cache buster, свой на каждый URL |
| `${TIMESTAMP}` | | миллисекунды epoch |
| `${APPBUNDLE}`, `${APPNAME}`, `${APPVERSION}` | | идентификация приложения хоста |
| `${DEVICEMAKE}`, `${DEVICEMODEL}` | | производитель и модель |
| `${OS}`, `${OSVERSION}` | | `android` и версия релиза |
| `${DEVICETYPE}` | | код OpenRTB: `3` ТВ, `4` телефон, `5` планшет |
| `${CONNECTIONTYPE}` | | код OpenRTB: `0` неизвестно, `1` ethernet, `2` wifi, `3` cellular |
| `${LANGUAGE}` | | двухбуквенный язык локали устройства |
| `${PLAYERSIZE}`, `${DEVICEW}`, `${DEVICEH}` | | размер экрана в пикселях |

Для каждого имени принимаются оба написания — `${IFA}` и VAST-вариант `[IFA]`, регистр не важен.
Значения URL-кодируются. Известный макрос без значения превращается в пустоту, поэтому сырой
токен на сервер не уходит. Имя, которое библиотеке не принадлежит, остаётся как было — именно так
сохраняются собственные параметры рекламного сервера вида `${PUID30}`.

## 10. Реклама по действию в приложении

`SwitchRollAd` — тот же формат, но открывается действием зрителя: выбором карточки, сменой
канала, переходом в раздел, — а не паузой. Конфигурация совпадает с `PauseRollAd`; отличаются
пара вызовов и то, что за оверлеем обычно нечего возобновлять.

```kotlin
switchRoll = SwitchRollAd(binding.root)
    .setTagUrl(VAST_TAG_URL)
    .setListener(adListener)
    .attach()

// там, где происходит действие
switchRoll?.show("card:${card.id}")
```

| Вызов | Смысл |
|---|---|
| `show(action)` | действие произошло; `action` называет его в логе и для `Controller` |
| `dismiss()` | хост убирает оверлей (уход с экрана или переход дальше) |
| `attach()` / `detach()` | как у `PauseRollAd` |

`show`, вызванный пока на экране оверлей от предыдущего действия, игнорируется: зритель,
щёлкающий по ряду карточек, получит одну рекламу, а не очередь. Как только реклама ушла — skip,
доигравший креатив или `dismiss()` — следующее действие открывает новую возможность.

Фокус оверлей навсегда не забирает: за ним ничего не играет, поэтому в `onOpen` обычно достаточно
запретить ввод в лежащий под ним лайаут, а в `onClose` — вернуть фокус.

## 11. События

Окно слота (`TriggerRoll.Listener`): `onOpen`, `onClose`, `onNoAd`, `onError(message)`.
`onOpen` парный ровно с одним `onClose`. Нет fill и ошибки до показа — не вызывается ни то,
ни другое.

Трекинг (`TriggerRoll.Listener`): каталог VAST 2–4.2, каждый колбэк получает URL, которые
библиотека пингует сама. Колбэк трекинга срабатывает только если в ответе действительно есть URL
для этого события — пустая группа не сообщается, поэтому определять по колбэкам трекинга
состояние показа не стоит. Таблица ниже говорит, какие события формат может дать в принципе.

| Событие | Video | Banner |
|---|---|---|
| `impression`, `creativeView`, `loaded` | да | да |
| `start`, квартили, `progress@offset` | да | пока играет саундтрек |
| `mute` / `unmute`, `pause` / `resume` | да | пока играет саундтрек |
| `viewable` после 2 с на экране, иначе `notViewable` | да | да |
| `complete` + `closeLinear` | только если доиграл | если саундтрек доиграл |
| `close`, `overlayViewDuration` | да | да |
| `skip` | да | да |

Сбои:

| Ситуация | Результат |
|---|---|
| нет `tagUrl` или тег не загрузился | `onError` |
| нет пригодного креатива | `onNoAd`, плюс `onVastError`, если в ответе были Error URL |
| ошибка воспроизведения креатива | `onVastError` + `onError`, затем слот `onClose` |
| саундтрек под баннером сорвался | `onVastError` + `onError`; картинка остаётся |
| контент возобновлён во время загрузки | хост `close()` — ответ отбрасывается молча |
| контент возобновлён во время показа | хост `close()` — VAST `close`, затем слот `onClose` |
| зритель нажал skip | VAST `skip` / `onSkip`, затем слот `onClose` |

Каждый квартиль и каждый `progress@offset` отправляются один раз за показ.

Цепочка Wrapper разворачивается до пяти переходов, Impression, Error и TrackingEvents всех
уровней объединяются.

## 12. Жизненный цикл

| Вызов | Когда | Поток |
|---|---|---|
| `attach()` | после готовности контейнера (`onCreate`) | main |
| `trigger()` | хост: пауза (позже — перемотка) | main |
| `close()` | хост: play / возможность закрыта (`onStop`) | main |
| `detach()` | уничтожение экрана (`onDestroy`) | main |

`attach()` идемпотентен. `trigger` / `close` можно вызывать много раз на том же экземпляре.
`detach()` терминален: останавливает потоки библиотеки, снимает оверлей, колбэки хоста и
рекламный плеер. Повторный `attach()` на том же объекте игнорируется с записью в лог — для
следующего экрана создайте новый экземпляр. Контентный плеер в SDK не передаётся: listener
читает ваше поле `player`, поэтому `onClose` всегда видит тот ExoPlayer, который сейчас жив.

Все конфигурационные методы — main thread. Все колбэки listener приходят в main thread.

## 13. Android TV и пульты

При появлении хрома фокус на skip. Дальше библиотека его не двигает: пульт сам ходит по info,
mute, pause и skip. Хром контентного плеера спрячьте в `onOpen()`, чтобы пульт не управлял им
под оверлеем. После скрытия оверлея библиотека фокус не возвращает — контроллер и фокус
восстановите в `onClose()`, как в примере выше.

`BACK` и другие клавиши не перехватываются.

## 14. Чего формат не делает

- Не открывает браузер. `ClickThrough` показывается QR-кодом с контрола info; `ClickTracking`
  пингуется при нажатии. `CustomClick` разбирается, но не обрабатывается.
- Не исполняет интерактивные креативы: VPAID, SIMID, HTML- и iframe-ресурсы пропускаются.
- Не показывает рекламные блоки из нескольких креативов: на одну возможность приходится один показ.

## 15. Версии и лицензия

Версия артефакта — SemVer, доступна в рантайме как `com.ctvhouse.sdk.SdkVersion.NAME`.
История изменений — [CHANGELOG.ru.md](../CHANGELOG.ru.md).

Библиотека проприетарная: некоммерческое использование разрешено, коммерческое право
принадлежит правообладателю. Полный текст — в [LICENSE](../LICENSE).
