-- Примеры запросов для локальной копии SQLite. Только чтение.
PRAGMA query_only = ON;

-- Таблицы приложения.
SELECT name FROM sqlite_schema WHERE type = 'table' ORDER BY name;

-- Группы, учтённые ботом.
SELECT group_id, title FROM tg_groups ORDER BY title;

-- Участники и текущие балансы: плюс — участнику должны, минус — должен он.
-- group_id в результате разделяет независимые расчёты разных групп.
SELECT p.group_id, p.display_name AS participant,
       p.active, COALESCE(SUM(e.amount), 0) AS balance_rub
FROM participants AS p
LEFT JOIN balance_entries AS e
  ON e.group_id = p.group_id AND e.participant_id = p.participant_id
GROUP BY p.group_id, p.participant_id, p.display_name, p.active
ORDER BY p.group_id, balance_rub DESC, participant;

-- Состояние тренировок. EDITING: рабочая правка отличается от учтённого варианта.
SELECT group_id, draft_id, occurred_on, status, version, financial_version
FROM training_drafts ORDER BY occurred_on DESC, draft_id LIMIT 50;

-- Два варианта одной тренировки: рабочий и последний учтённый.
-- published_json у отменённой тренировки хранится для истории, но уже не действует.
SELECT draft_id, status, content_json AS working_copy,
       published_json AS last_published_copy
FROM training_drafts ORDER BY occurred_on DESC LIMIT 10;

-- Финансовые операции, включая исправления, отмены и реальные переводы.
SELECT group_id, sequence, kind, entity_id, entity_version, recorded_at
FROM operations ORDER BY recorded_at DESC, sequence DESC LIMIT 50;

-- Все изменения интерфейсных сущностей; эта таблица не равна журналу денег.
SELECT group_id, audit_id, kind, entity_id, actor_user_id, recorded_at
FROM workflow_audit ORDER BY audit_id DESC LIMIT 50;
