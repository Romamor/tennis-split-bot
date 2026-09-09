# Как посмотреть новую базу

Новый формат описан в [таблицах и связях](DATABASE_V2_TABLES.md).
Файл по умолчанию — `data/settlements.sqlite`; серверный путь —
`/opt/tennis-split-bot/data/settlements.sqlite` после установки новой версии.
Старая `bot.sqlite` — отдельная тестовая база прежнего формата.

Для согласованной копии работающей базы используй команду `backup` приложения,
а не копирование одного файла: свежие изменения могут находиться в соседнем WAL.
Имя копии должно быть новым. Пример для контейнера:

```sh
docker --context default compose -f /opt/tennis-split-bot/compose.yaml exec -T bot \
  /app/bin/tennis-settlements-bot backup /data/settlements.sqlite /data/inspection-copy.sqlite
```

Скачанную копию можно открыть в обозревателе SQLite или через терминал:

```sh
sqlite3 -readonly data/inspection-copy.sqlite
```

Внутри SQLite:

```sql
.headers on
.mode column
.tables
.schema group_users
SELECT id, title FROM groups;
```

[inspect.sql](inspect.sql) содержит примеры просмотра участников, тренировок,
переводов, балансов и журнала. Подставь ID группы из первого запроса.
Соединяй записи по группе вместе с идентификатором сущности.

Для ознакомления открывай копию только для чтения. Прямая правка финансовых
таблиц обходит проверки ролей, целостность проводок и журнал приложения.
