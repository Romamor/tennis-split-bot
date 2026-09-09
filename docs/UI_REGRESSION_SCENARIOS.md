# Постоянные сценарии текущей сверки

10 сентября 2026. Локальный пакет, без разрешения на выкладку.
Тесты находятся в `src/test/kotlin/ru/movereon/tennis/`.

| Правило | Сценарий проверки |
| --- | --- |
| У участника два пункта меню | `member menu has two primary items and transfer amount edits preserve review` |
| Названия учёта и исправления | `accounting controls use approved labels and preserve balances while correcting` |
| Произвольный перевод и правка суммы | Сценарий меню выше и `amount edit preserves review ownership and does not create a second transfer` |
| Дубли за 24 часа, независимо от автора и указанной даты | `duplicates use a rolling 24 hour window regardless of recorder or stated date` |
| Выход из несохранённого выбора | `back warns on unsaved selection and continues or discards without writing history` |
| Выход из персональной панели | `back from dirty attendance warns in group and discards only that input` |
| Выход из текстовой формы | `unchanged details exit directly and changed details require an explicit discard` |
| Назад по цепочке история → карточка → страница списка | `back from history returns to training and original training list page` |
| Назад после правки игрока | `editing a player returns to the same roster page and keeps summary visible` |
| Стабильный выбор и одинаковые имена | `selection order survives attendance changes and names have profile verification` |
| Подтверждение одной правкой | `only confirmation records one change and unchanged confirmation records nothing` |
| Массовый выбор, страницы, перезапуск | `bulk selection survives pagination and restart with one confirmed history entry` |
| Конкурентное участие и снятые права | `concurrent self registration and revoked administrator cannot be overwritten by bulk selection` |
| Ограниченная история массовых изменений | `a page of bulk additions keeps history below Telegram message limit` |
| История суммы «до → после», местное время | Сценарий меню выше открывает историю конкретного перевода |
| Обновление схемы без потери финансов | `cached attendance migration preserves records and includes open participation in zero balances` |
| Пакет кнопок и подготовленная посещаемость | `render batches button writes and roster reads use prepared attendance` |

Также сохранены прежние сценарии изоляции групп, текущих ролей, повторов Telegram,
положения личного меню, одной персональной панели, расчётов и истории финансов.
Изменение теста при новой реализации требует сохранения его пользовательского смысла.
Старый сценарий перехода в личку теперь начинается с подтверждённого участия:
неподтверждённый ввод по новому решению пользователя вызывает предупреждение.

## Измерение

Одна новая карточка в сценарии сравнения: отдельная подготовка 8 кнопок требует
8 транзакций записи, подготовка всего экрана — 1. Чтение списка трижды не содержит
запросов к training_players для пересчёта посещаемости. Эти числа не являются
замером задержки Telegram или общим количеством запросов за пользовательский ход.

## Границы

Подставной Telegram и локальный HTTP-сервер не доказывают, что конкретный клиент
Mac показывает персональную панель или открывает профиль при его настройках приватности.
Это остаётся отдельной живой проверкой после разрешённой установки.
