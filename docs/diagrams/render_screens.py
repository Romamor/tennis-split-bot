import json,xml.etree.ElementTree as E,re,html,math
from pathlib import Path
from html.parser import HTMLParser
import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--screens',required=True)
parser.add_argument('--output',default='docs/diagrams/BOT_USER_FLOWS.drawio')
parser.add_argument('--preview-dir',default='build/ui-diagrams')
parser.add_argument('--heights')
args=parser.parse_args()
preview_dir=Path(args.preview_dir);preview_dir.mkdir(parents=True,exist_ok=True)
source=json.loads(Path(args.screens).read_text())
roles={1:'Участник',2:'Администратор бота',3:'Суперадминистратор группы'}
titles={'groups':'Выбор группы','menu':'Главное меню','mine':'Мои тренировки: открытые','mine_closed':'Мои тренировки после учёта','public':'Карточка в группе','personal_before':'Ещё не присоединился','personal_joined':'Присоединился · 0 ч','personal_time':'Ввод времени','personal_payment':'Ввод оплаты','personal_guest':'Участие с гостем','personal_left':'Вышел · оплату сохранил','personal_owner':'Панель создателя','training_own':'Своя тренировка в личке','training_other':'Чужая тренировка в личке','training_closed':'Учтённая тренировка','preview':'Проверка учёта','history':'История тренировки','new_title':'Создание · название','new_date':'Создание · дата','new_time':'Создание · время','new_ready':'Перед публикацией','debts':'Мои расчёты','balances':'Баланс группы','settled':'Нулевые балансы','transfers':'История переводов','people':'Выбор участника','direction':'Направление перевода','transfer_amount':'Ввод суммы перевода','transfer_ready':'Проверка перевода','transfer_duplicate':'Возможный дубль перевода','transfer_date':'Дата перевода','transfer_note':'Комментарий','transfer':'Перевод учтён','transfer_review':'Уточнение перевода','transfer_cancelled':'Перевод отменён','edit_transfer_amount':'Исправление суммы','edit_transfer_ready':'Проверка исправления','edit_transfer_duplicate':'Совпадение при исправлении','transfer_history':'История одного перевода','profile':'Проверка профиля','exit':'Несохранённый ввод','all':'Все тренировки','roster':'Состав тренировки','player':'Форма выбранного игрока','paid':'Произвольная оплата игрока','player_conflict':'Параллельная правка игрока','add_players':'Массовый выбор игроков','pick_players':'Системный выбор аккаунта','edit_title':'Правка · название','edit_date':'Правка · дата','edit_time':'Правка · начало','edit_ready':'Подтверждение деталей','training_review':'Тренировка с правками','preview_review':'Проверка новых итогов','cancel':'Подтверждение отмены','training_cancelled':'Отменённая тренировка','training_failure':'Проблема доставки карточки','recover':'Повтор публикации','administrators':'Список администраторов','candidates':'Кандидаты в администраторы','role_member':'Участник без роли','role_admin':'Назначенный администратор','role_super':'Суперадминистратор Telegram','role_super_extra':'Суперадмин с назначением бота'}
categories=[('Участие в общем чате',['public','personal_before','personal_joined','personal_payment','personal_owner','personal_left','personal_guest','personal_time']),('Личное меню, создание и учёт',['groups','menu','mine','training_other','new_title','new_date','new_time','new_ready','training_own','preview','training_closed','mine_closed','history']),('Мои расчёты и запись перевода',['debts','people','direction','transfer_amount','transfer_ready','transfer_date','transfer_note','transfer_duplicate','balances','settled','profile']),('История и правка переводов',['transfers','transfer','edit_transfer_amount','edit_transfer_ready','edit_transfer_duplicate','transfer_review','transfer_cancelled','transfer_history','exit']),('Администратор: состав и ввод',['all','roster','player','paid','player_conflict','add_players','pick_players']),('Администратор: детали и состояния',['edit_title','edit_date','edit_time','edit_ready','training_review','preview_review','cancel','training_cancelled','training_failure','recover']),('Суперадминистратор: назначения',['administrators','candidates','role_member','role_admin','role_super','role_super_extra'])]
# Same-screen arrows indicate updating values; data in each screen is an illustrative snapshot.
def resolve(key,b):
 a=b.get('target');label=b['text']
 if not a:return []
 k=a['kind'];opt=a.get('option','');id=a.get('id','')
 if k=='menu':return ['menu']
 if k=='groups':return ['groups']
 if k=='training':return [('training_closed' if key=='preview' else 'training_review' if key=='preview_review' else 'training_own') if id=='own' else 'training_other']
 if k=='trainings':return ['all' if opt=='all' else 'mine_closed' if key=='training_closed' else 'mine']
 if k=='new':return ['new_title']
 if k=='form_next':return [{'new_title':'new_date','new_date':'new_time','new_time':'new_ready','edit_title':'edit_date','edit_date':'edit_time','edit_time':'edit_ready'}[key]]
 if k=='form_restart':return ['edit_title' if key=='edit_ready' else 'new_title']
 if k=='save_training':return ['training_own']
 if k=='participation':return ['personal_owner' if id=='own' else 'personal_joined' if key.startswith('personal_') else 'personal_before']
 if k=='participation_time':return ['personal_time']
 if k=='participation_payment':return ['personal_payment']
 if k=='participation_change':return [{'JOIN':'personal_joined','LEAVE':'personal_left','ADJUST_GUESTS':'personal_guest'}.get(opt,key)]
 if k=='close_panel':return ['public']
 if k=='preview_finish':return ['preview_review' if key=='training_review' else 'preview']
 if k=='finish':return ['training_closed']
 if k in ('history','transfer_history'):return ['history' if k=='history' else 'transfer_history']
 if k=='transfer':return ['transfer']
 if k=='transfer_people':return ['people']
 if k=='transfer_direction':return ['direction']
 if k in ['debts','balances','settled','transfers','administrators','roster']:return [k]
 if k=='suggested_transfer':return ['transfer_ready']
 if k=='transfer_amount':return ['transfer_amount']
 if k=='transfer_date':return ['transfer_date']
 if k=='transfer_note':return ['transfer_note']
 if k=='save_transfer':return ['transfer'] if key=='transfer_duplicate' else ['transfer','transfer_duplicate']
 if k=='edit_transfer_amount':return ['edit_transfer_amount']
 if k=='save_transfer_amount':return ['transfer'] if key=='edit_transfer_duplicate' else ['transfer','edit_transfer_duplicate']
 if k=='review_transfer':return ['transfer_review']
 if k=='confirm_transfer':return ['transfer']
 if k=='cancel_transfer':return ['transfer_cancelled']
 if k=='exit_continue':return ['new_title']
 if k=='exit_discard':return ['menu']
 if k=='edit_details':return ['edit_title']
 if k=='reopen':return ['training_review']
 if k=='cancel_confirm':return ['cancel']
 if k=='cancel':return ['training_cancelled']
 if k in ('restore','recover_card','retry_pin'):return ['training_own']
 if k=='recover_confirm':return ['recover']
 if k=='player':return ['player']
 if k=='change':return ['player']
 if k=='ask_paid':return ['paid']
 if k=='reload_attendance':return ['player']
 if k=='discard_attendance':return ['roster']
 if k=='save_attendance':return ['roster','player_conflict']
 if k=='add_players':return ['add_players']
 if k=='toggle_player':return ['add_players']
 if k=='save_players':return ['roster']
 if k=='pick_players':return ['pick_players']
 if k=='admin_candidates':return ['candidates']
 if k=='admin_person':return ['role_super' if a.get('user')==3 else 'role_admin' if a.get('user')==2 else 'role_member']
 if k=='set_admin':return ['role_admin' if a.get('value') else 'role_super' if key=='role_super_extra' else 'role_member']
 raise ValueError((key,label,k))
input_next={'new_title':'new_date','new_date':'new_time','new_time':'new_ready','edit_title':'edit_date','edit_date':'edit_time','edit_time':'edit_ready','transfer_amount':'transfer_ready','transfer_date':'transfer_ready','transfer_note':'transfer_ready','edit_transfer_amount':'edit_transfer_ready','paid':'player','pick_players':'add_players'}
class Plain(HTMLParser):
 def __init__(self):super().__init__();self.parts=[]
 def handle_data(self,s):self.parts.append(s)
def body_html(s):
 if not s.get('html'):return html.escape(s['text']).replace('\n','<br>')
 raw=s['html'];raw=re.sub(r'<h3>(.*?)</h3>',r'<div style="font-size:19px;margin-bottom:9px">\1</div>',raw)
 raw=re.sub(r'<table[^>]*>','<table style="border-collapse:collapse;width:100%;font-size:12px">',raw)
 raw=raw.replace('<th>','<th style="font-weight:normal;border-bottom:1px solid #52606c;padding:5px">').replace('<td>','<td style="border-bottom:1px solid #52606c;padding:5px">').replace('<td align="right">','<td style="text-align:right;border-bottom:1px solid #52606c;padding:5px">')
 raw=re.sub(r'<a href="[^"]+">', '<span style="color:#a6d8fc">',raw).replace('</a>','</span>');return raw

measured=json.loads(Path(args.heights).read_text()) if args.heights else {}
def body_height(s):
 if str(s['role'])+':'+s['key'] in measured:return max(100,measured[str(s['role'])+':'+s['key']]+25)
 # Conservative wrapping estimate; exact overflow is checked in browser previews.
 if s.get('html'):
  n=s['html'].count('<tr>');return max(220,150+n*47)
 return max(115,28+sum(max(1,math.ceil(len(l)/34)) for l in s['text'].split('\n'))*20)
def smooth(points,r=22):
 result=f'M {points[0][0]} {points[0][1]}'
 for i in range(1,len(points)-1):
  p,c,n=points[i-1],points[i],points[i+1]
  a=math.dist(p,c);b=math.dist(c,n)
  if not a or not b:continue
  z=min(r,a/2,b/2);before=(c[0]+(p[0]-c[0])*z/a,c[1]+(p[1]-c[1])*z/a);after=(c[0]+(n[0]-c[0])*z/b,c[1]+(n[1]-c[1])*z/b)
  result+=f' L {before[0]} {before[1]} Q {c[0]} {c[1]} {after[0]} {after[1]}'
 return result+f' L {points[-1][0]} {points[-1][1]}'
mx=E.Element('mxfile',host='app.diagrams.net',type='device',compressed='false')
report=[]
for role,name in roles.items():
 screens={s['key']:s for s in source if s['role']==role};positions={};category_heads=[];section_for={};row_bottom={};W=430;GAP=160;COLS=4;y=260
 for category,keys in categories:
  keys=[k for k in keys if k in screens]
  if not keys:continue
  category_heads.append((category,y));y+=70
  for offset in range(0,len(keys),COLS):
   line=keys[offset:offset+COLS];maxheight=0
   for k in line:section_for[k]=category
   for i,k in enumerate(line):
    s=screens[k];bh=body_height(s);buttons=sum(48+(20 if max((len(b['text']) for b in row),default=0)>37 else 0) for row in s['rows']);height=bh+buttons+85+(65 if k in input_next else 0)
    positions[k]=(60+i*(W+GAP),y,W,height,bh);maxheight=max(maxheight,height)
   row_bottom[y]=y+maxheight
   y+=maxheight+200
  y+=80
 PW=60+COLS*(W+GAP);PH=y+100
 dia=E.SubElement(mx,'diagram',id='screens-'+str(role),name=name)
 model=E.SubElement(dia,'mxGraphModel',grid='1',gridSize='10',guides='1',tooltips='1',connect='1',arrows='1',page='1',pageScale='1',pageWidth=str(PW),pageHeight=str(PH))
 root=E.SubElement(model,'root');E.SubElement(root,'mxCell',id='0');E.SubElement(root,'mxCell',id='1',parent='0',value='Экраны');E.SubElement(root,'mxCell',id='forward',parent='0',value='Основные переходы');E.SubElement(root,'mxCell',id='returns',parent='0',value='Возвраты и меню',visible='0');E.SubElement(root,'mxCell',id='cross',parent='0',value='Между сценариями',visible='0');E.SubElement(root,'mxCell',id='loops',parent='0',value='Изменение значений',visible='0')
 preview=[];buttons_geo={};allrects=[]
 def vertex(cid,value,x,y,w,h,parent='1',style='',html_mode=True):
  c=E.SubElement(root,'mxCell',id=cid,value=value,style=style+('html=1;' if html_mode else 'html=0;')+'whiteSpace=wrap;fontFamily=Arial;',vertex='1',parent=parent);E.SubElement(c,'mxGeometry',x=str(x),y=str(y),width=str(w),height=str(h),attrib={'as':'geometry'});return c
 vertex('title',name+' · экраны и переходы от кнопок',40,25,PW-80,65,style='fontSize=30;fontColor=#1e344a;align=left;strokeColor=none;fillColor=none;',html_mode=False)
 vertex('help','Экраны выгружены из Screens.render кода 9ab806d, установленного 12.09.2026. Стрелка начинается у кнопки или поля ответа. Петля меняет данные на этом же экране. Примерные значения не описывают все возможные суммы.<br>По умолчанию видны основные маршруты внутри сценария. Слои «Возвраты и меню», «Между сценариями», «Изменение значений» включаются через Вид → Слои. Отмена с несохранённым вводом ведёт к подтверждению.',40,105,PW-80,90,style='fontSize=16;align=left;strokeColor=none;fillColor=none;fontColor=#486071;')
 for i,(title,cy) in enumerate(category_heads):vertex('section'+str(i),title,40,cy,PW-80,48,style='fontSize=25;align=left;fillColor=#e5eef5;strokeColor=none;fontColor=#253f58;',html_mode=False)
 for key,s in screens.items():
  x,sy,w,h,bh=positions[key];gid='screen-'+key
  vertex(gid,'',x,sy,w,h,style='group;fillColor=none;strokeColor=none;')
  vertex(gid+'-label',titles.get(key,key),0,0,w,36,parent=gid,style='fontSize=17;fontStyle=1;align=left;fontColor=#314b65;strokeColor=none;fillColor=none;',html_mode=False)
  vertex(gid+'-context','В группе · видно всем' if key=='public' else 'В группе · личная панель' if s['group'] else 'Личный чат с ботом',0,38,w,24,parent=gid,style='fontSize=12;align=left;fontColor=#637d93;fillColor=none;strokeColor=none;',html_mode=False)
  bhvalue=body_html(s);vertex(gid+'-body',bhvalue,0,66,w,bh,parent=gid,style='rounded=1;arcSize=8;fillColor=#2d3339;strokeColor=#2d3339;fontColor=#f2f6fb;fontSize=14;align=left;verticalAlign=top;spacing=14;overflow=hidden;')
  preview.append(f'<div class="screen" style="left:{x}px;top:{sy}px;width:{w}px;height:{h}px"><h3>{html.escape(titles.get(key,key))}</h3><div class="context">'+('Общий чат' if key=='public' else 'Персональная панель' if s['group'] else 'Личный чат')+f'</div><div class="bubble" data-key="{key}" style="height:{bh}px">{bhvalue}</div>')
  ry=66+bh+5
  for ri,row in enumerate(s['rows']):
   rh=48+(20 if max((len(b['text']) for b in row),default=0)>37 else 0);bw=(w-5*(len(row)-1))/len(row)
   for bi,b in enumerate(row):
    bid=f'{gid}-button{ri}-{bi}';bx=bi*(bw+5)
    vertex(bid,b['text'],bx,ry,bw,rh-5,parent=gid,style='rounded=1;arcSize=10;fillColor=#414b55;strokeColor=#414b55;fontColor=#ecf5ff;fontSize=14;align=center;verticalAlign=middle;spacing=6;',html_mode=False)
    buttons_geo[bid]=(x+bx,sy+ry,bw,rh-5,key,b)
    preview.append(f'<div class="button" style="left:{bx}px;top:{ry}px;width:{bw}px;height:{rh-5}px">{html.escape(b["text"])}</div>')
   ry+=rh
  if key in input_next:
   bid=gid+'-input';label='Выбрать аккаунт Telegram →' if key=='pick_players' else 'Написать ответ · Отправить →'
   vertex(bid,label,0,ry+7,w,48,parent=gid,style='rounded=1;fillColor=#f2f5f8;strokeColor=#9aafc2;fontColor=#436178;fontSize=14;align=left;spacing=10;',html_mode=False)
   buttons_geo[bid]=(x,sy+ry+7,w,48,key,{'text':label,'target':{'kind':'input'}});preview.append(f'<div class="input" style="top:{ry+7}px">{label}</div>')
  preview.append('</div>');allrects.append((x,sy,w,h))
 # Orthogonal routes travel in the empty vertical lanes and horizontal row gutters.
 edges=[];unknown=[]
 for ei,(bid,(bx,by,bw,bh,key,b)) in enumerate(buttons_geo.items()):
  dests=[input_next[key]] if b['target'] and b['target']['kind']=='input' else resolve(key,b)
  if not dests:continue
  for di,dest in enumerate(dests):
   if dest not in positions:unknown.append((key,b['text'],dest));continue
   dx,dy,dw,dh,_=positions[dest];sx,sy,sw,sh,_=positions[key]
   back=(key in ('personal_guest','personal_left','personal_owner') and dest in ('personal_payment','personal_time','personal_joined')) or bool(re.search(r'Назад|Отмена|В меню|Меню группы|Закрыть|Нулевой баланс|Номер',b['text'])) or b['target']['kind'] in ('menu','close_panel','exit_continue','exit_discard')
   kind='returns' if back else 'loops' if dest==key else 'cross' if section_for[key]!=section_for[dest] else 'forward';lane=sx+sw+25+(ei%9)*12;destlane=dx-25-(ei%7)*12
   start=(bx+bw,by+bh/2);end=(dx,dy+80)
   if dest==key:
    points=[start,(lane,start[1]),(lane,sy+45),(sx+sw,sy+45)]
    end=(sx+sw,sy+45)
   elif sy==dy and dx==sx+W+GAP:
    points=[start,(lane,start[1]),(lane,dy+80),end]
   else:
    gutter=(row_bottom[sy]+45 if dy>sy else dy-45)-(ei%6)*5
    points=[start,(lane,start[1]),(lane,gutter),(destlane,gutter),(destlane,dy+80),end]
   style=f'edgeStyle=none;rounded=1;curved=1;arcSize=25;html=0;endArrow=block;endFill=1;strokeWidth=1.3;strokeColor={"#a5adb5" if back else "#557eaa"};'+('dashed=1;' if back else '')+'exitX=1;exitY=0.5;exitPerimeter=1;entryX='+('1;entryY=0.05;' if dest==key else '0;entryY=0.1;')
   label=('Есть совпадение за сутки' if 'duplicate' in dest else 'Без совпадения' if len(dests)>1 and dest=='transfer' else 'Если данные изменились' if dest=='player_conflict' else '')
   ec=E.SubElement(root,'mxCell',id=f'edge{ei}-{di}',value=label,style=style,edge='1',parent=kind,source=bid,target='screen-'+dest)
   geom=E.SubElement(ec,'mxGeometry',relative='1',attrib={'as':'geometry'});arr=E.SubElement(geom,'Array',attrib={'as':'points'})
   for px,py in points[1:-1]:E.SubElement(arr,'mxPoint',x=str(px),y=str(py))
   if kind=='forward':edges.append('<path class="route" d="'+smooth(points)+'"/>')
 # All editable forms share the same unsaved-exit screen. Draw conditional forward routes.
 for key in [k for k in screens if k.startswith(('new_','edit_')) or k in ('add_players','player','transfer_ready','transfer_duplicate')]:
  for bid,(_,_,_,_,bk,b) in buttons_geo.items():
   if bk==key and ('Отмена' in b['text'] or b['text']=='К составу'):
    ec=E.SubElement(root,'mxCell',id='guard-'+bid,value='Есть несохранённый ввод',style='edgeStyle=orthogonalEdgeStyle;rounded=1;endArrow=block;strokeColor=#c29048;fontSize=12;',edge='1',parent='returns',source=bid,target='screen-exit');E.SubElement(ec,'mxGeometry',relative='1',attrib={'as':'geometry'})
 assert not unknown,unknown
 for i,(title,cy) in enumerate(category_heads):preview.append(f'<div style="position:absolute;left:40px;top:{cy}px;font-size:24px;color:#304b65">{i+1:02d} · {html.escape(title)}</div>')
 previewhtml='<!doctype html><meta charset="utf-8"><style>body{margin:0;background:#fff;color:#253f58;font-family:Arial}.board{position:relative}.screen{position:absolute}h3{margin:0;height:36px;font-size:17px}.context{height:30px;font-size:12px;color:#637d93}.bubble{box-sizing:border-box;background:#2d3339;color:#f2f6fb;padding:14px;border-radius:12px;font-size:14px;line-height:20px;overflow:hidden}.bubble p{margin:7px 0}.button{position:absolute;background:#414b55;color:#ecf5ff;display:flex;align-items:center;justify-content:center;text-align:center;border-radius:5px;box-sizing:border-box;padding:6px;font-size:14px}.input{position:absolute;box-sizing:border-box;width:100%;height:48px;background:#f2f5f8;padding:14px;font-size:14px}svg{position:absolute;inset:0;pointer-events:none}path.route{fill:none;stroke:#557eaa;stroke-width:1.3;marker-end:url(#arrow)}</style>'+f'<div class="board" style="width:{PW}px;height:{PH}px"><svg width="{PW}" height="{PH}"><defs><marker id="arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto"><path d="M0 0L8 4L0 8z" fill="#557eaa"/></marker></defs>'+''.join(edges)+'</svg>'+''.join(preview)+'</div>'
 (preview_dir/('screen-preview-'+str(role)+'.html')).write_text(previewhtml)
 report.append({'role':name,'screens':len(screens),'buttons':len(buttons_geo),'edges':len(root.findall("mxCell[@edge='1']")),'width':PW,'height':PH})
E.indent(mx,space='  ');E.ElementTree(mx).write(args.output,encoding='UTF-8',xml_declaration=True)
(preview_dir/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(report)
