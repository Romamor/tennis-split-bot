"""Generate an importable Grafana dashboard; all examples use a neutral instance label."""
import json
from pathlib import Path

DS={'type':'prometheus','uid':'${DS_METRICS}'}
S='{instance="$instance",job="integrations/node_exporter"}'
P=[]

def panel(title,targets,x,y,w,h,unit,kind='timeseries',description=''):
    config={'unit':unit,'color':{'mode':'palette-classic'},'mappings':[],
            'thresholds':{'mode':'absolute','steps':[{'color':'green','value':None}]}}
    if kind=='timeseries':
        config['custom']={'drawStyle':'line','lineInterpolation':'linear','lineWidth':2,'fillOpacity':6,'spanNulls':False,'showPoints':'never','axisCenteredZero':False,'stacking':{'mode':'none','group':'A'}}
    P.append({'id':len(P)+1,'title':title,'description':description,'type':kind,'datasource':DS,
              'gridPos':{'x':x,'y':y,'w':w,'h':h},'fieldConfig':{'defaults':config,'overrides':[]},
              'targets':[{'refId':chr(65+i),'expr':expr,'legendFormat':label,'datasource':DS,'range':True,'instant':kind=='stat'} for i,(label,expr) in enumerate(targets)],
              'options':{'legend':{'displayMode':'table','placement':'bottom','calcs':['lastNotNull']},'tooltip':{'mode':'multi','sort':'none'}} if kind=='timeseries' else {'reduceOptions':{'calcs':['lastNotNull'],'fields':'','values':False},'colorMode':'value','graphMode':'area','textMode':'auto','orientation':'auto'}})

panel('Доступно памяти',[('Доступно','node_memory_MemAvailable_bytes'+S)],0,0,6,4,'bytes','stat')
panel('Удерживает VMware',[('Ballooning','vds_balloon_current_bytes'+S)],6,0,6,4,'bytes','stat')
panel('Занято swap',[('Swap','node_memory_SwapTotal_bytes'+S+' - node_memory_SwapFree_bytes'+S)],12,0,6,4,'bytes','stat')
panel('Состояние бота',[('Работает','vds_bot_running'+S)],18,0,6,4,'none','stat','1 — контейнер работает; это не проверка ответа Telegram.')
panel('Память виртуалки и ballooning',[
 ('Всего','node_memory_MemTotal_bytes'+S),('Доступно','node_memory_MemAvailable_bytes'+S),
 ('Удерживает VMware','vds_balloon_current_bytes'+S),('Запрос VMware','vds_balloon_target_bytes'+S)],0,4,14,9,'bytes',description='Линии не складываются: показатели частично пересекаются.')
panel('Память контейнера бота',[
 ('RAM','vds_bot_memory_bytes'+S),('Swap','vds_bot_swap_bytes'+S)],14,4,10,9,'bytes',description='Малая RAM при большом swap не означает, что процессу нужно мало памяти.')
panel('Задержки из-за памяти (PSI)',[
 ('Часть задач ждёт','100 * rate(node_pressure_memory_waiting_seconds_total'+S+'[5m])'),
 ('Все активные задачи ждут','100 * rate(node_pressure_memory_stalled_seconds_total'+S+'[5m])')],0,13,12,8,'percent',description='Доля времени ожидания памяти за 5 минут. Пропуск метрик не заменяется нулём.')
panel('Обмен со swap',[
 ('Чтение с диска','rate(node_vmstat_pswpin'+S+'[5m])'),
 ('Запись на диск','rate(node_vmstat_pswpout'+S+'[5m])')],12,13,12,8,'ops',description='Страниц в секунду. Занятый swap без обмена с ним сам по себе не означает тормоза.')
panel('Загрузка процессора',[
 ('CPU','100 * (1 - avg by(instance)(rate(node_cpu_seconds_total{instance="$instance",job="integrations/node_exporter",mode="idle"}[5m])))')],0,21,8,7,'percent')
panel('Свободное место на корневом диске',[
 ('Доступно','node_filesystem_avail_bytes{instance="$instance",job="integrations/node_exporter",mountpoint="/"}')],8,21,8,7,'bytes')
panel('Проверка источников метрик',[
 ('Опрос Linux','up'+S),('Чтение VMware','vds_balloon_collector_success'+S),('Чтение контейнера','vds_bot_collector_success'+S)],16,21,8,7,'none',description='1 — успешно, 0 — ошибка; отсутствующая линия — метрика не поступает.')
panel('Возраст дополнительных показателей',[
 ('Возраст','time() - vds_extra_sample_timestamp_seconds'+S)],0,28,12,6,'s',description='Норма около минуты. Рост означает остановку дополнительного сборщика.')
panel('Перезапуски и OOM контейнера',[
 ('Перезапуски','vds_bot_restart_count'+S),('Последняя остановка из-за OOM','vds_bot_oom_killed'+S)],12,28,12,6,'none')

P.append({'id':len(P)+1,'title':'VPN · состояние, ресурсы и трафик','type':'row','collapsed':False,'panels':[],'gridPos':{'x':0,'y':34,'w':24,'h':1}})
panel('Состояние VPN',[('{{vpn}}','vds_vpn_running'+S)],0,35,12,4,'none','stat','1 — контейнер запущен; доступность туннеля снаружи этим не проверяется.')
P[-1]['fieldConfig']['defaults']['mappings']=[{'type':'value','options':{'0':{'text':'Остановлен','color':'red'},'1':{'text':'Запущен','color':'green'}}}]
panel('Сбор метрик VPN',[('{{vpn}}','vds_vpn_collector_success'+S)],12,35,12,4,'none','stat','0 — не удалось прочитать показатели; это не нулевое потребление ресурсов.')
P[-1]['fieldConfig']['defaults']['mappings']=[{'type':'value','options':{'0':{'text':'Ошибка чтения','color':'red'},'1':{'text':'ОК','color':'green'}}}]
panel('Память VPN',[
 ('{{vpn}} · RAM','vds_vpn_memory_bytes'+S),('{{vpn}} · swap','vds_vpn_swap_bytes'+S)],0,39,12,8,'bytes')
panel('CPU VPN',[
 ('{{vpn}}','100 * rate(vds_vpn_cpu_seconds_total'+S+'[5m])')],12,39,12,8,'percent',description='100% соответствует одному логическому ядру. Сетевую работу ядра Linux нужно сопоставлять с общей загрузкой CPU.')
panel('Трафик туннелей VPN',[
 ('{{vpn}} · принято','rate(vds_vpn_receive_bytes_total'+S+'[5m])'),
 ('{{vpn}} · передано','rate(vds_vpn_transmit_bytes_total'+S+'[5m])')],0,47,12,8,'Bps',description='Байт/с на одном выбранном туннельном интерфейсе. Внешний зашифрованный трафик повторно не суммируется. Это текущий трафик, не проверка максимальной скорости VPN.')
panel('Сеть виртуалки',[
 ('{{device}} · принято','rate(node_network_receive_bytes_total'+S+'[5m])'),
 ('{{device}} · передано','rate(node_network_transmit_bytes_total'+S+'[5m])')],12,47,12,8,'Bps',description='Трафик внешних интерфейсов хоста. Включает VPN, бота и мониторинг. Его не нужно складывать с графиком туннелей.')
panel('Ошибки и потери на интерфейсах VPN',[
 ('{{vpn}} · ошибки вход','rate(vds_vpn_receive_errors_total'+S+'[5m])'),
 ('{{vpn}} · ошибки выход','rate(vds_vpn_transmit_errors_total'+S+'[5m])'),
 ('{{vpn}} · отброшено вход','rate(vds_vpn_receive_drops_total'+S+'[5m])'),
 ('{{vpn}} · отброшено выход','rate(vds_vpn_transmit_drops_total'+S+'[5m])')],0,55,12,8,'ops',description='Ошибки и отброшенные пакеты интерфейса в секунду. Не измеряет потери на всём пути до клиента.')
panel('Перезапуски и OOM VPN',[
 ('{{vpn}} · счётчик перезапусков Docker','vds_vpn_restarts_total'+S),
 ('{{vpn}} · последняя остановка OOM','vds_vpn_oom_killed'+S),
 ('{{vpn}} · OOM текущей cgroup','vds_vpn_oom_kills_total'+S)],12,55,12,8,'none',description='Счётчики сбрасываются при пересоздании контейнера / cgroup. Короткое событие между опросами может не попасть в снимок.')

# Keep existing panel IDs stable; reserve a new block before the optional container section.
for item in P:
    if item['gridPos']['y']>=34:
        item['gridPos']['y']+=30
    for target in item.get('targets',[]):
        if 'vds_vpn_' in target['expr']:
            target['expr']=target['expr'].replace(S,S[:-1]+',vpn=~"$vpn"}')

P.append({'id':len(P)+1,'title':'Бот · отклик кнопок','type':'row','collapsed':False,'panels':[],
          'gridPos':{'x':0,'y':34,'w':24,'h':1}})

def quantile(phase,q,group='le'):
    return f'histogram_quantile({q}, sum by ({group}) (rate(tennis_ui_{phase}_seconds_bucket'+S+'[1h])))'

panel('Отклик кнопок · p50 / p95',[
    ('p50',quantile('response',.5)),('p95',quantile('response',.95))],0,35,12,4,'s','stat',
    'От получения нажатия ботом до принятия панели Telegram, включая очередь и повторы. Не измеряет отрисовку на телефоне. Процентили приблизительны; при редких нажатиях смотри число ответов.')
panel('Ответов за час',[
    ('Ответы','sum(increase(tennis_ui_response_seconds_count'+S+'[1h]))')],12,35,6,4,'short','stat')
panel('Возраст метрик бота',[
    ('Возраст','time() - tennis_ui_sample_timestamp_seconds'+S)],18,35,6,4,'s','stat',
    'Обычно до двух минут. При остановке бота старые цифры не доказывают работоспособность; сверяй возраст и состояние контейнера.')
panel('Отклик по разделам · p95',[
    ('{{category}}',quantile('response',.95,'le,category'))],0,39,12,8,'s',
    description='Категории фиксированы; имён, групп и текстов кнопок в метриках нет. Нет нажатий — нет оценки задержки.')
panel('Очередь и обработка · p95',[
    ('Ожидание первой обработки',quantile('queue',.95)),('Попытка обработки',quantile('processing',.95))],12,39,12,8,'s',
    description='Обработка включает сетевые запросы. Эти линии и Telegram API не нужно складывать. Повторные попытки считаются отдельно; полное время с повторами видно в отклике.')
panel('Telegram API · p95',[
    ('{{method}}',quantile('api',.95,'le,method'))],0,47,12,8,'s',
    description='Все обращения, включая фоновые, с ошибками и ожиданием тайм-аута. getUpdates исключён: ожидание новых событий не является задержкой кнопки.')
panel('Исходы обработки за час',[
    ('{{outcome}}','sum by(outcome) (increase(tennis_ui_attempts_total{instance="$instance",job="integrations/node_exporter",outcome!~"api_.*"}[1h]))')],12,47,12,8,'short',
    description='success — принят ответ; retry — событие отложено; uncertain — результат доставки неизвестен; rejected/error — отказ/ошибка; ignored — нет новой панели. Попыток может быть больше нажатий.')
panel('Особые ответы API за час',[
    ('{{outcome}}','sum by(outcome) (increase(tennis_ui_attempts_total{instance="$instance",job="integrations/node_exporter",outcome=~"api_.*"}[1h]))')],0,55,12,8,'short',description='Включает ожидаемые NOT_MODIFIED, MESSAGE_MISSING и истечение callback. Это причины исключений API, не число неработающих кнопок.')
panel('Проверка метрик приложения',[
    ('Чтение','vds_ui_metrics_collector_success'+S)],12,55,12,8,'none',
    description='1 — прочитан разрешённый числовой формат; 0 — файл отсутствует или не прошёл проверку. Данные приложения обновляются в цикле бота, без отдельного веб-сервера.')
P.sort(key=lambda item:(item['gridPos']['y'],item['gridPos']['x']))

dashboard={'__inputs':[{'name':'DS_METRICS','label':'Grafana Cloud Metrics','description':'Выбери Prometheus-источник метрик своего облака.','type':'datasource','pluginId':'prometheus','pluginName':'Prometheus'}],
           'id':None,'uid':'vds-memory','title':'VDS · память и отклик бота','tags':['vds','memory','bot'],'timezone':'browser','schemaVersion':39,'version':3,'editable':True,'refresh':'1m',
           'time':{'from':'now-6h','to':'now'},'panels':P,'templating':{'list':[{'name':'instance','label':'Виртуалка','type':'query','datasource':DS,'query':{'query':'label_values(node_memory_MemTotal_bytes{job="integrations/node_exporter"}, instance)','refId':'instance'},'refresh':1,'multi':False,'includeAll':False,'current':{'selected':True,'text':'vds-1','value':'vds-1'}},
            {'name':'vpn','label':'Контейнер','type':'query','datasource':DS,
             'query':{'query':'query_result(vds_vpn_collector_success'+S+')','refId':'vpn'},
             'regex':'/vpn="([^"]+)"/','refresh':2,'multi':True,'includeAll':True,'allValue':None,
             'current':{'selected':True,'text':'All','value':'$__all'}}]}}
Path(__file__).with_name('vds-memory.dashboard.json').write_text(json.dumps(dashboard,ensure_ascii=False,indent=2)+'\n')
print('Dashboard panels:',len(P))
