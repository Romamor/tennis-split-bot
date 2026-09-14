"""Build an editable ERD from the checked-in SQLite schema, without opening user data."""
import argparse
import html
import json
import sqlite3
import xml.etree.ElementTree as E
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
p = argparse.ArgumentParser()
p.add_argument('--output', type=Path, default=ROOT/'docs/diagrams/DATABASE_ERD.drawio')
p.add_argument('--preview-dir', type=Path, default=ROOT/'build/database-erd')
args = p.parse_args()
c = sqlite3.connect(':memory:')
c.executescript((ROOT/'src/main/resources/db/schema.sql').read_text())
tables = [r[0] for r in c.execute("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY rowid")]
info = {t: list(c.execute(f'PRAGMA table_info({t})')) for t in tables}
fks = {}
unique = {}
for t in tables:
    groups = {}
    for row in c.execute(f'PRAGMA foreign_key_list({t})'):
        groups.setdefault(row[0], []).append(row)
    fks[t] = [sorted(rows, key=lambda r:r[1]) for rows in groups.values()]
    unique[t] = []
    for idx in c.execute(f'PRAGMA index_list({t})'):
        if idx[2] and not idx[4]:
            unique[t].append([r[2] for r in c.execute(f'PRAGMA index_info({idx[1]})')])
    pk = [r[1] for r in sorted(info[t], key=lambda r:r[5]) if r[5]]
    if pk and pk not in unique[t]: unique[t].insert(0,pk)

DESC = {
 'training_polls':'Опрос до создания тренировки', 'poll_signups':'Только записавшиеся: без времени и отказов',
 'users':'Telegram-аккаунты и личные настройки', 'groups':'Чаты и часовой пояс',
 'group_users':'Аккаунт в конкретной группе', 'group_admins':'Назначения администраторов бота',
 'trainings':'Тренировка: детали, статус, версия', 'training_players':'Участие, время, гости и оплата стола',
 'transfers':'Отмеченные переводы между людьми', 'actions':'Команды, история и защита от повторов',
 'balance_entries':'Денежное влияние действия на аккаунт', 'bot_events':'Входящие события и план обработки',
 'bot_sessions':'Текущее меню и ввод пользователя', 'bot_buttons':'Токены кнопок и постоянных ссылок',
 'bot_deliveries':'Доставка, закрепление и удаление сообщений'}
NOTES = {
 'training_polls':['Тренировка создаётся только после закрытия опроса', 'Ссылка на итоговую тренировку — защита от повторов'],
 'poll_signups':['Три поля: группа, опрос, аккаунт', 'После создания тренировки строки удаляются'],
 'users':['UNIQUE is_bot=1 — только одна запись бота', 'Название / время по умолчанию принадлежат аккаунту'],
 'groups':['polls_enabled — разрешить создание опросов', 'guests_enabled / track_time — для новых тренировок'],
 'group_users':['★ is_attending и nickname — задел, интерфейса ещё нет', 'present — известное членство; не участие в тренировке'],
 'group_admins':['Администраторы Telegram проверяются через API', 'В этой таблице — только назначения внутри бота'],
 'trainings':['OPEN / CLOSED / CANCELLED', 'guests_enabled / track_time — сохранённые правила', 'Повторное открытие снимает прежний расчёт'],
 'training_players':['paid — оплата стола, а не перевод человеку', 'Гости принадлежат пригласившему; своих строк нет'],
 'transfers':['REVIEW — в процессе; ACTIVE — выполнен', 'CANCELLED — архив прежнего интерфейса', 'Получатель определяется to_user; это право приёма', 'reviewer / review_party — прежние служебные поля'],
 'actions':['training_id и transfer_id не заполнены одновременно', 'SendPayment: история без денежных проводок', 'ReceivePayment: история и проводки одной транзакцией'],
 'balance_entries':['SUM(amount) по группе и user_id — фактический баланс', 'SUM(amount) внутри действия = 0 — проверяет код'],
 'bot_events':['План завершённого события очищается; update_id остаётся'],
 'bot_sessions':['group_id=NULL — общее меню / личные настройки', 'chat_id и message_id — идентификаторы Telegram, не FK'],
 'bot_buttons':['Актуальные кнопки не истекают через семь дней', 'Заменённые очищаются после короткой задержки'],
 'bot_deliveries':['display_page не используется после отмены страниц', 'delivery_key связывает сообщения логически, без FK к actions']}
COLORS = {t:('#155e75' if t in tables[:4] else '#047857' if t in tables[4:6] else '#9a5b12' if t in tables[6:9] else '#5b4b8a') for t in tables}
PAGES = [('01 · Обзор',tables,[]),('02 · Аккаунты и группы',tables[:4],[]),
 ('03 · Тренировки',tables[4:6],['users','groups','group_users']),
 ('04 · Финансы',tables[6:9],['users','groups','group_users','trainings']),
 ('05 · Состояние бота',tables[9:13],['users','groups']),
 ('06 · Опросы',tables[13:],['users','groups','group_users','trainings'])]
mx=E.Element('mxfile',host='app.diagrams.net',type='device',compressed='false')
args.preview_dir.mkdir(parents=True,exist_ok=True)
report=[]

class Page:
 def __init__(self,title,width,height):
  self.diagram=E.SubElement(mx,'diagram',id=f'page-{len(mx)}',name=title)
  model=E.SubElement(self.diagram,'mxGraphModel',dx='1600',dy='1000',grid='1',gridSize='10',page='1',pageScale='1',pageWidth=str(width),pageHeight=str(height),background='#f7fafc')
  self.root=E.SubElement(model,'root');E.SubElement(self.root,'mxCell',id='0');E.SubElement(self.root,'mxCell',id='1',parent='0')
  self.svg=[];self.cards={};self.count=0;self.width=width;self.height=height
 def box(self,id,x,y,w,h,text='',fill='#ffffff',stroke='#d7e1ea',size=14,color='#172b3a',bold=False,round=False):
  style=f'rounded={int(round)};whiteSpace=wrap;html=0;fillColor={fill};strokeColor={stroke};fontColor={color};fontSize={size};fontFamily=Arial;align=left;verticalAlign=middle;spacingLeft=12;spacingRight=10;'
  if bold:style+='fontStyle=1;'
  cell=E.SubElement(self.root,'mxCell',id=id,value=text,style=style,vertex='1',parent='1');E.SubElement(cell,'mxGeometry',x=str(x),y=str(y),width=str(w),height=str(h),attrib={'as':'geometry'})
  self.svg.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{8 if round else 0}" fill="{fill}" stroke="{stroke}"/>')
  lines=text.split('\n');top=y+h/2-(len(lines)-1)*size*.65
  self.svg.append(f'<text x="{x+12}" y="{top}" font-size="{size}" fill="{color}" font-weight="{600 if bold else 400}" dominant-baseline="middle">'+''.join(f'<tspan x="{x+12}" dy="{0 if i==0 else size*1.3}">{html.escape(line)}</tspan>' for i,line in enumerate(lines))+'</text>')
  return cell
 def text(self,id,x,y,w,h,text,size=15,color='#52677a',bold=False):
  return self.box(id,x,y,w,h,text,fill='none',stroke='none',size=size,color=color,bold=bold)
 def card(self,t,x,y,w,compact=False,reference=False):
  first_cell=len(self.root)
  fields=info[t];fkcols={r[3] for fk in fks[t] for r in fk}
  pk=[r[1] for r in sorted(fields,key=lambda r:r[5]) if r[5]]
  extras=[] if compact else ['PK ('+', '.join(pk)+')']+['UQ ('+', '.join(u)+')' for u in unique[t] if u!=pk]+NOTES[t]
  height=112 if compact else 78+len(fields)*29+len(extras)*26+12
  self.box(t,x,y,w,height,'',round=True)
  self.box(t+'-header',x,y,w,40,t+('  ·  ссылка' if reference else ''),fill=COLORS[t],stroke=COLORS[t],color='#ffffff',bold=True,size=19)
  self.text(t+'-desc',x,y+40,w,34,DESC[t],13)
  if compact:
   self.text(t+'-key',x,y+75,w,28,'PK '+', '.join(pk),12)
  else:
   for i,col in enumerate(fields):
    _,name,kind,required,default,primary=col
    flags=('PK ' if primary else '')+('FK ' if name in fkcols else '')+('?' if not(required or primary) else '')
    special=(t=='group_users' and name in ['nickname','is_attending']) or (t=='bot_deliveries' and name=='display_page')
    yy=y+78+i*29
    self.box(t+'-field-'+name,x+1,yy,w-2,29,'',fill='#fff3db' if special else '#f0f5f8' if i%2==0 else '#ffffff',stroke='none')
    self.text(t+'-name-'+name,x+4,yy,260,29,name+(' ★' if special else ''),14,color='#172b3a')
    self.text(t+'-type-'+name,x+w-210,yy,95,29,kind,12)
    self.text(t+'-flags-'+name,x+w-105,yy,100,29,flags.strip(),12,color='#9a5b12',bold=True)
   yy=y+78+len(fields)*29
   for j,note in enumerate(extras):self.text(t+'-note-'+str(j),x+2,yy+j*26,w-4,26,note,12)
  # Keep fields and labels inside the table so moving a table in draw.io moves them together.
  for cell in list(self.root)[first_cell+1:]:
   cell.set('parent',t)
   geometry=cell.find('mxGeometry')
   geometry.set('x',str(float(geometry.get('x'))-x))
   geometry.set('y',str(float(geometry.get('y'))-y))
  self.cards[t]=(x,y,w,height)
 def edge(self,source,target,label,lane,top=False):
  x,y,w,h=self.cards[source];a,b,v,k=self.cards[target]
  if top:
   points=[(x+w*.5,y),(x+w*.5,lane),(a+v*.5,lane),(a+v*.5,b+k)]
   ports='exitX=0.5;exitY=0;entryX=0.5;entryY=1;'
  else:
   points=[(x+w*.5,y+h),(x+w*.5,lane),(a+v*.5,lane),(a+v*.5,b+k)]
   ports='exitX=0.5;exitY=1;entryX=0.5;entryY=1;'
  self.count+=1
  cell=E.SubElement(self.root,'mxCell',id='edge-'+str(self.count),value=label,edge='1',parent='1',source=source,target=target,style='edgeStyle=orthogonalEdgeStyle;rounded=1;endArrow=open;endFill=0;strokeColor=#7b8fa3;strokeWidth=1.4;fontSize=11;labelBackgroundColor=#f7fafc;'+ports)
  geo=E.SubElement(cell,'mxGeometry',relative='1',attrib={'as':'geometry'});arr=E.SubElement(geo,'Array',attrib={'as':'points'})
  for px,py in points[1:-1]:E.SubElement(arr,'mxPoint',x=str(px),y=str(py))
  path='M '+' L '.join(f'{px},{py}' for px,py in points)
  self.svg.append(f'<path d="{path}" fill="none" stroke="#7b8fa3" stroke-width="1.4" stroke-linejoin="round" marker-end="url(#arrow)"/>')
  lx=(points[1][0]+points[2][0])/2;ly=lane-8
  self.svg.append(f'<text x="{lx}" y="{ly}" text-anchor="middle" fill="#52677a" font-size="11">{html.escape(label)}</text>')
 def save(self,index):
  svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.width}" height="{self.height}" viewBox="0 0 {self.width} {self.height}"><defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M1,1 L7,4 L1,7" fill="none" stroke="#7b8fa3"/></marker></defs><rect width="100%" height="100%" fill="#f7fafc"/><g font-family="Arial, sans-serif">'+''.join(self.svg)+'</g></svg>'
  (args.preview_dir/f'page-{index}.svg').write_text(svg)
  (args.preview_dir/f'page-{index}.html').write_text('<!doctype html><meta charset="utf-8"><style>body{margin:0;background:#f7fafc}</style>'+svg)

for idx,(title,owned,refs) in enumerate(PAGES,1):
 if idx==1:
  page=Page(title,1960,1850)
  page.text('title',48,25,1860,50,'База бота · 15 таблиц · схема 8',30,'#172b3a',True)
  page.text('intro',48,85,1860,42,'Обзор хранения. Стрелки показывают главные зависимости; все внешние ключи и поля — на вкладках 02–06.',17)
  lanes=[('Аккаунты и группы',tables[:4],170),('Тренировки',tables[4:6],460),('Финансы и история',tables[6:9],750),('Состояние общения с Telegram',tables[9:13],1110),('Опросы до создания тренировки',tables[13:],1370)]
  for label,names,yy in lanes:
   page.text('lane-'+str(yy),48,yy-40,1800,30,label,20,'#172b3a',True)
   for j,t in enumerate(names):page.card(t,60+j*470,yy,420,compact=True)
  # The overview intentionally omits cross-lane FK lines; the detail pages list every constraint.
  for n,(a,b) in enumerate([('group_users','users'),('group_users','groups'),('group_admins','group_users')]):page.edge(a,b,'FK',320+n*25)
  page.edge('training_players','trainings','строки участия → тренировка',610)
  page.edge('actions','transfers','действие → платёж (если применимо)',920)
  page.edge('balance_entries','actions','проводка → действие',955)
  page.box('financial-note',60,1580,1840,150,'Баланс = сумма balance_entries по группе и аккаунту. Отдельной таблицы балансов нет.\nДоступные платежи вычисляются: фактический баланс + резерв ожидающих transfers (REVIEW).\nSendPayment создаёт transfers + actions. ReceivePayment обновляет transfers и добавляет actions + balance_entries.\nУчастие и оплата стола хранятся в training_players; перевод между людьми — в transfers.',size=18,round=True)
 else:
  width=max(2040,len(owned)*620+80,len(refs)*500+80)
  page=Page(title,width,2200)
  page.text('title',48,20,width-90,55,title.split(' · ',1)[1]+' · таблицы и внешние ключи',28,'#172b3a',True)
  page.text('legend',48,80,width-90,50,'PK — первичный ключ · FK — внешний ключ · UQ — уникальность · ? — допускается NULL · ★ — задел / не используется',16)
  if refs:
   for j,t in enumerate(refs):page.card(t,60+j*500,155,460,compact=True,reference=True)
  yy=430 if refs else 180
  for j,t in enumerate(owned):page.card(t,60+j*620,yy,570)
  bottom=max(y+h for x,y,w,h in page.cards.values())
  pairs={};fkrows=[];n=0
  for t in owned:
   for fk in fks[t]:
    n+=1;parent=fk[0][2];sourcecols=[r[3] for r in fk];targetcols=[r[4] for r in fk]
    assert parent in page.cards,(t,parent)
    required=all(any(col[1]==s and (col[3] or col[5]) for col in info[t]) for s in sourcecols)
    max_children='0..1' if any(set(u)<=set(sourcecols) for u in unique[t]) else '0..N'
    card=max_children+' → '+('1' if required else '0..1')
    fkrows.append((f'R{n} · {t} ({", ".join(sourcecols)})',f'→ {parent} ({", ".join(targetcols)})   [{card}]'))
    pairs.setdefault((t,parent),[]).append(n)
  lower=0;upper=0
  for (child,parent),ids in pairs.items():
   ref=parent in refs
   if ref: lane=300+upper*13;upper+=1
   else:lane=bottom+45+lower*33;lower+=1
   page.edge(child,parent,', '.join('R'+str(n) for n in ids),lane,top=ref)
  y=bottom+95+lower*33
  page.text('fk-title',48,y,width-90,35,'Точные связи · строка слева ссылается на строку справа',20,'#172b3a',True);y+=44
  page.text('fk-hint',48,y,width-90,34,'Одна линия может объединять несколько FK. В скобках: сколько дочерних записей → сколько родительских записей.',15);y+=40
  for i,(line1,line2) in enumerate(fkrows):
   col=i%2;row=i//2
   page.box('fk-row-'+str(i),60+col*(width//2),y+row*67,width//2-85,61,line1+'\n'+line2,fill='#ffffff',size=13,round=True)
  end=y+((len(fkrows)+1)//2)*67+40
  page.text('source-note',48,end,width-90,70,'Источник: src/main/resources/db/schema.sql. Показаны все столбцы и объявленные FK; CHECK и DEFAULT полностью описаны в SQL.\nПунктирные / предполагаемые FK не добавляются: chat_id, message_id, JSON и delivery_key сами по себе не ссылки базы.',14)
  page.height=end+110
  page.diagram.find('mxGraphModel').set('pageHeight',str(page.height))
 page.save(idx)
 report.append({'page':title,'owned':owned,'references':refs,'edges':page.count,'width':page.width,'height':page.height})
assert set(t for _,owned,_ in PAGES[1:] for t in owned)==set(tables)
E.indent(mx);args.output.parent.mkdir(parents=True,exist_ok=True);E.ElementTree(mx).write(args.output,encoding='utf-8',xml_declaration=True)
(args.preview_dir/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print(json.dumps({'tables':len(tables),'fields':sum(map(len,info.values())),'foreign_keys':sum(map(len,fks.values())),'pages':len(PAGES)},ensure_ascii=False))
