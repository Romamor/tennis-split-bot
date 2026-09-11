# Постоянные сценарии текущей сверки

10 сентября 2026. Состояние установки каждого выпуска — в DEPLOYMENT_STATUS.md.
Тесты находятся в `src/test/kotlin/ru/movereon/tennis/`.

Проходят 93 теста. Набор дополнен сценариями задач #1 и #2: новая панель, таблицы,
гости, закрепление и миграция.

| Правило | Сценарий проверки |
| --- | --- |
| У участника два пункта меню | `member menu has two primary items and transfer amount edits preserve review` |
| Названия учёта и исправления | `accounting controls use approved labels and preserve balances while correcting` |
| Восстановление без повторного расчёта и потери переводов | `restoring cancelled training preserves input and transfers until explicitly accounted without duplicates` |
| Восстановление отменённой открытой или исправляемой тренировки, права своей группы | `restore is group scoped admin only and works after cancelling an open or edited training` |
| Восстановление из списка, история и обновление той же карточки | `admin restores cancelled training from history and updates the same public card` |
| Снятые права при нажатии старой кнопки восстановления | `saved restore button checks live administrator rights` |
| Произвольный перевод и правка суммы | Сценарий меню выше и `amount edit preserves review ownership and does not create a second transfer` |
| Дубли за 24 часа, независимо от автора и указанной даты | `duplicates use a rolling 24 hour window regardless of recorder or stated date` |
| Выход из несохранённого выбора | `back warns on unsaved selection and continues or discards without writing history` |
| Выход из персональной панели | `leaving an unsaved admin edit warns and discards only that edit` |
| Выход из текстовой формы | `unchanged details exit directly and changed details require an explicit discard` |
| Назад по цепочке история → карточка → страница списка | `back from history returns to training and original training list page` |
| Назад после правки игрока | `editing a player returns to the same roster page and keeps summary visible` |
| Стабильный выбор и одинаковые имена | `selection order survives attendance changes and names have profile verification` |
| Подтверждение одной правкой | `admin attendance edits stay atomic and unchanged confirmation records nothing` |
| Массовый выбор, страницы, перезапуск | `bulk selection survives pagination and restart with one confirmed history entry` |
| Конкурентное участие и снятые права | `concurrent self registration and revoked administrator cannot be overwritten by bulk selection` |
| Ограниченная история массовых изменений | `a page of bulk additions keeps history below Telegram message limit` |
| История суммы «до → после», местное время | Сценарий меню выше открывает историю конкретного перевода |
| Обновление схемы без потери финансов | `cached attendance migration preserves records and includes open participation in zero balances` |
| Пакет кнопок и подготовленная посещаемость | `render batches button writes and roster reads use prepared attendance` |

Также сохранены прежние сценарии изоляции групп, текущих ролей, повторов Telegram,
положения личного меню, одной персональной панели, расчётов и истории финансов.
Изменение теста при новой реализации требует сохранения его пользовательского смысла.
Предупреждение о несохранённом вводе относится к формам администратора.
Пользовательская панель сохраняет изменения немедленно и закрывается без подтверждения.

## Измерение

Одна новая карточка в сценарии сравнения: отдельная подготовка 8 кнопок требует
8 транзакций записи, подготовка всего экрана — 1. Чтение списка трижды не содержит
запросов к training_players для пересчёта посещаемости. Эти числа не являются
замером задержки Telegram или общим количеством запросов за пользовательский ход.

## Границы

Подставной Telegram и локальный HTTP-сервер не доказывают, что конкретный клиент
Mac показывает персональную панель или открывает профиль при его настройках приватности.
Это остаётся отдельной живой проверкой после разрешённой установки.

## Добавлено при ревью хранения

- `completed event plans are released while incomplete recovery and financial history survive`:
  очищаются только планы завершённых событий, сохраняются номера, незавершённые планы и история.
- Проверки повтора ответа теперь имитируют сбой триггером SQLite **до** отметки завершения,
  а не превращают уже завершённое событие в незавершённое задним числом.
- `balance filter toggles do not accumulate an unbounded return chain`: 25 переключений
  не увеличивают размер команды.
- `DatabaseMaintenanceTest`: исходная версия 1 и байты источника не меняются при
  копировании и проверке; копируются данные из активного WAL, восстановление сохраняет
  историю и балансы, права копии — 0600, перезапись существующего файла запрещена.

Отдельная задача `storageSimulation` не входит в обычный прогон тестов. Модельный год
40/10/156 выполнен до и после очистки планов; финансовая история совпадает по SHA-256.

## Задачи #1 и #2

- Таблица, кликабельные имена, экранирование текста и отдельные строки гостей.
- Вход с 0 ч, несколько гостей, общее время, распределение округления на аккаунт пригласившего.
- Оплата после выхода, изменения без подтверждения, повторы команд без удвоения.
- Обновление уже открытых панелей, сохранение страницы общей таблицы.
- Закрытие панелей при учёте и запрет старых кнопок обычному участнику.
- Закрепление только новой карточки, отказ прав, явный повтор и неизвестный результат.
- HTTP-проверка sendRichMessage, rich_message, адресата и pinChatMessage.
- ParticipationMigrationTest открывает настоящий формат версии 2 и проверяет сохранность
  старого гостевого времени, всей истории и проводок, затем новый повторный учёт.

Результаты искусственной годовой симуляции нового ввода — в STORAGE_REVIEW.md.
Тесты отправляют запросы только подставному API и локальному HTTP-серверу.

Сверка макетов закреплена проверкой `personal controls follow the mockup with direct guests and compact submenu navigation`: порядок строк, прямое добавление/удаление гостей и общая строка навигации подменю.
