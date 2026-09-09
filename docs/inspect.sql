-- Run with sqlite3 -readonly <copy.sqlite>. New normalized schema only.
.headers on
.mode column
PRAGMA query_only=ON;
SELECT id, title FROM groups;
-- Replace -100123 with the group's actual Telegram chat ID.
.parameter set @group_id -100123

SELECT u.id,u.first_name,u.last_name,u.username,gu.present,gu.is_attending,gu.nickname
FROM group_users gu JOIN users u ON u.id=gu.user_id
WHERE gu.group_id=@group_id;

SELECT id,title,played_on,starts_at,status,version,applied_version
FROM trainings WHERE group_id=@group_id
ORDER BY played_on DESC,id LIMIT 20 OFFSET 0;

SELECT p.training_id,u.first_name,p.playing,p.minutes,p.guest_minutes,p.paid
FROM training_players p JOIN users u ON u.id=p.user_id
WHERE p.group_id=@group_id ORDER BY p.training_id,p.ordinal LIMIT 20 OFFSET 0;

SELECT u.id,u.first_name,SUM(e.amount) AS balance_rub
FROM balance_entries e JOIN users u ON u.id=e.user_id
WHERE e.group_id=@group_id GROUP BY u.id ORDER BY balance_rub DESC;

SELECT id,from_user,to_user,amount,occurred_on,status,reviewer,created_by
FROM transfers WHERE group_id=@group_id ORDER BY occurred_on DESC,id LIMIT 20 OFFSET 0;

SELECT id,actor_id,kind,training_id,transfer_id,occurred_at
FROM actions WHERE group_id=@group_id ORDER BY id DESC LIMIT 20 OFFSET 0;

SELECT active,permanent,COUNT(*) AS buttons FROM bot_buttons GROUP BY active,permanent;
PRAGMA integrity_check;
PRAGMA foreign_key_check;
