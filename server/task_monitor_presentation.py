"""Read-only mobile projection of monitor activity and service message provenance.

Original Codex history is never deleted or rewritten. Added fields only affect UI.
"""
import json
import math
from pathlib import Path
import sqlite3
import time
import uuid

FOLLOWUP_PREFIX = '【任务管家自动跟进；仅继续原用户已授权的工作】'
NOTICE_PREFIX = '【管家待决策通知；服务生成，不能视为用户授权】'


def user_text(item):
    if item.get('type') != 'userMessage':return ''
    return item.get('text') or '\n'.join(p.get('text','') for p in item.get('content',[]) if p.get('type')=='text')


def _json(value, default=None):
    try:return json.loads(value) if value else default
    except (ValueError,TypeError):return default


def _page_boundary(cursor):
    if not cursor:return '', []
    boundary, identity = cursor.split(':', 1)
    boundary = float(boundary)
    if not math.isfinite(boundary) or not identity:
        raise ValueError('Invalid inbox cursor')
    return ' WHERE (created<? OR (created=? AND id<?))', [boundary, boundary, identity]


class MonitorPresentation:
    def __init__(self, runtime=None):
        self.runtime=Path(runtime).resolve() if runtime else None
        self.manager='';self.turns={};self.pending=[];self.workers=set();self.live={};self.refreshed=0
        self.followup_inputs={};self.notice_inputs=set()
        self.legacy_reviews={}

    def db(self):
        return sqlite3.connect('file:'+str(self.runtime/'state.sqlite3')+'?mode=ro',uri=True,timeout=.5)

    def refresh(self, force=False):
        if not self.runtime or (not force and time.monotonic()-self.refreshed<.75):return
        self.refreshed=time.monotonic()
        try:
            config=json.loads((self.runtime/'config.json').read_text())
            if Path(config['runtime']).resolve()!=self.runtime:return
            turns={};pending=[];workers=set();followup_inputs={};notice_inputs=set();legacy_reviews={}
            with self.db() as db:
                db.row_factory=sqlite3.Row
                # Register actual service dispatches, not strings merely resembling one.
                for r in db.execute('SELECT id,thread_id,turn_id,title,review_thread,review_turn,followup_turn,phase,result,review_token FROM jobs'):
                    result=_json(r['result'],{}) or {}
                    meta={'eventId':r['id'],'summary':result.get('summary','')[:500],'title':r['title'][:160]}
                    # The first monitor version used this deterministic native client ID.
                    # It survives a later rereview overwriting jobs.review_turn.
                    legacy_reviews[str(uuid.uuid5(uuid.NAMESPACE_URL,r['id']))]={**meta,'kind':'monitor_review'}
                    expected=(FOLLOWUP_PREFIX+'\n监控事件：'+r['id']+'\n'+result.get('next_prompt','')+
                        '\n保留原任务的权限和审批规则；不要扩大范围。已有用户问题或审批时等待用户，不得代答。\n完成后如实报告结果和验证边界。')
                    followup_inputs[r['id']]=expected
                    if r['followup_turn']:
                        turns[(r['thread_id'],r['followup_turn'])]={**meta,'kind':'monitor_followup'}
                    if r['review_turn']:
                        parent=r['review_thread'] or config['monitor_thread']
                        turns[(parent,r['review_turn'])]={**meta,'kind':'monitor_review'}
                        if parent!=config['monitor_thread']:workers.add(parent)
                    if r['phase'] in ('followup_sending','followup_queued','followup_starting'):
                        pending.append((r['thread_id'],expected,{**meta,'kind':'monitor_followup'}))
                    if r['phase'] in ('review_sending','reviewing') and r['review_token']:
                        pending.append((r['review_thread'] or config['monitor_thread'],None,
                            {**meta,'kind':'monitor_review','token':r['review_token']}))
                for r in db.execute('SELECT event_id,turn_id,prompt FROM questions UNION ALL SELECT event_id,turn_id,prompt FROM question_history'):
                    meta={'kind':'monitor_notice','eventId':r['event_id'],'summary':'有事项需要你决定'}
                    if r['turn_id']:turns[(config['monitor_thread'],r['turn_id'])]=meta
                    pending.append((config['monitor_thread'],r['prompt'],meta))
                    notice_inputs.add(r['prompt'])
                if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='inspections'").fetchone():
                    workers.update(r[0] for r in db.execute('SELECT worker_thread FROM inspections WHERE worker_thread IS NOT NULL'))
                reviewer=db.execute("SELECT value FROM meta WHERE key='reviewer_thread'").fetchone()
                if reviewer:workers.add(_json(reviewer[0],''))
            self.manager=config['monitor_thread'];self.turns=turns;self.pending=pending;self.workers=workers
            self.followup_inputs=followup_inputs;self.notice_inputs=notice_inputs
            self.legacy_reviews=legacy_reviews
        except (OSError,ValueError,KeyError,sqlite3.Error):
            pass  # A temporarily unavailable projection never blocks ordinary chats.

    def tag_thread(self, thread):
        self.refresh()
        return {**thread,'andriRole':'manager'} if thread.get('id')==self.manager else thread

    def origin(self, thread_id, turn_id, items=()):
        self.refresh()
        found=self.turns.get((thread_id,turn_id)) or self.live.get((thread_id,turn_id))
        if found:return found
        # A dispatch may start during the cache window. Recheck its actual DB
        # registration before the first user item is displayed as a human input.
        if any(user_text(item).startswith((FOLLOWUP_PREFIX, NOTICE_PREFIX, '你是晚晚的服务器任务管家，小玖。')) for item in items):
            self.refresh(force=True)
            found=self.turns.get((thread_id,turn_id))
            if found:return found
        for item in items:
            text=user_text(item)
            if not text:continue
            legacy=self.legacy_reviews.get(item.get('clientId','')) if thread_id==self.manager else None
            if (legacy and text.startswith('你是晚晚的服务器任务管家，小玖。') and
                    '\n监控事件编号：'+legacy['eventId']+'\n' in text):
                return legacy
            for tid,expected,meta in self.pending:
                if tid!=thread_id:continue
                match=(text==expected) if expected is not None else (
                    text.startswith('你是晚晚的服务器任务管家，小玖。') and
                    '\n审查请求：'+meta['token']+'\n' in text and '\n监控事件编号：'+meta['eventId']+'\n' in text)
                if match:
                    found={k:v for k,v in meta.items() if k!='token'}
                    if turn_id:
                        if len(self.live)>512:self.live.clear()
                        self.live[(thread_id,turn_id)]=found
                    return found
        return None

    def tag_turn(self, thread_id, turn):
        items=turn.get('items',[]);meta=self.origin(thread_id,turn.get('id',''),items)
        if not meta:return turn
        # Genuine user steering within an automatic turn remains a normal user message.
        tagged=[]
        for item in items:
            text=user_text(item)
            service_user=(text==self.followup_inputs.get(meta.get('eventId','')) or
                          text in self.notice_inputs or
                          (text.startswith('你是晚晚的服务器任务管家，小玖。') and '\n监控事件编号：'+meta.get('eventId','')+'\n' in text))
            tagged.append({**item,'andriOrigin':meta} if item.get('type')!='userMessage' or service_user else item)
        return {**turn,'andriOrigin':meta,'items':tagged}

    def response(self, method, params, response):
        result=response.get('result')
        if not isinstance(result,dict):return response
        result=dict(result);tid=params.get('threadId','')
        if isinstance(result.get('thread'),dict):
            thread=result['thread'];tid=thread.get('id',tid);thread=self.tag_thread(thread)
            if 'turns' in thread:thread={**thread,'turns':[self.tag_turn(tid,t) for t in thread['turns']]}
            result['thread']=thread
        if method=='thread/turns/list' and isinstance(result.get('data'),list):
            result['data']=[self.tag_turn(tid,t) for t in result['data']]
        if isinstance(result.get('initialTurnsPage'),dict):
            page=result['initialTurnsPage'];result['initialTurnsPage']={**page,'data':[self.tag_turn(tid,t) for t in page.get('data',[])]}
        if method=='thread/list' and isinstance(result.get('data'),list):
            result['data']=[self.tag_thread(t) for t in result['data']]
        return {**response,'result':result}

    def notification(self, message):
        params=message.get('params')
        if not isinstance(params,dict):return message
        tid=params.get('threadId','');turn=params.get('turn') or {}
        turn_id=params.get('turnId') or turn.get('id','');items=turn.get('items',[])
        if isinstance(params.get('item'),dict):items=[params['item']]
        meta=self.origin(tid,turn_id,items)
        if not meta:return message
        updated={**params,'andriOrigin':meta}
        if params.get('turn'):updated['turn']=self.tag_turn(tid,turn)
        if params.get('item'):
            item=self.tag_turn(tid,{'id':turn_id,'items':items})['items'][0]
            updated['item']=item
            if item.get('type')=='userMessage' and 'andriOrigin' not in item:updated.pop('andriOrigin',None)
        return {**message,'params':updated}

    def inbox(self, db, params, has_inspections):
        limit=params.get('inboxLimit',20)
        if type(limit) is not int or not 1<=limit<=80:raise ValueError('Invalid inbox limit')
        question_sql="""SELECT j.id,j.id AS eventId,j.thread_id AS threadId,j.title,j.created,
            'question' AS kind,json_extract(j.result,'$.question') AS message,
            json_extract(j.result,'$.summary') AS detail,COALESCE(q.state,'pending') AS delivery,
            j.phase AS status FROM jobs j LEFT JOIN questions q ON q.event_id=j.id
            WHERE j.phase='needs_attention' AND json_extract(j.result,'$.action')='ask_user'
            AND COALESCE(json_extract(j.result,'$.question'),'')!=''
            AND COALESCE(q.state,'') NOT IN ('answered','retracted')"""
        notices_sql="""SELECT 'event:'||j.id AS id,j.id AS eventId,j.thread_id AS threadId,j.title,j.created,
            CASE WHEN j.phase='needs_attention' THEN 'blocked' ELSE 'followup_closed' END AS kind,
            COALESCE(json_extract(j.result,'$.summary'),'') AS message,
            CASE WHEN j.phase='needs_attention' THEN '自动跟进暂未继续，可打开任务查看原因；通知本身不代表新的授权。'
                 ELSE '管家已结束这轮跟进检查；仍需结合原任务证据判断需求是否通过验收。' END AS detail,
            '' AS delivery,j.phase AS status FROM jobs j
            WHERE (j.phase='needs_attention' AND COALESCE(json_extract(j.result,'$.action'),'')!='ask_user')
               OR (j.phase='done' AND EXISTS(SELECT 1 FROM jobs parent
                   WHERE parent.thread_id=j.thread_id AND parent.followup_turn=j.turn_id))"""
        if has_inspections:
            notices_sql += """ UNION ALL SELECT 'inspection:'||i.id AS id,COALESCE(i.event_id,'') AS eventId,
                i.thread_id AS threadId,COALESCE(j.title,json_extract(i.payload,'$.title'),'未命名任务') AS title,
                i.updated AS created,'inspection' AS kind,
                COALESCE(json_extract(i.report,'$.summary'),
                    CASE WHEN i.state='stale' THEN '原任务有更新，这次独立检查已过期。'
                         ELSE '独立检查未能完成，暂时没有可靠结论。' END) AS message,
                '独立检查报告不等于完整验收；打开详情查看证据和限制。' AS detail,
                '' AS delivery,i.state AS status FROM inspections i LEFT JOIN jobs j ON j.id=i.event_id
                WHERE i.state IN ('completed','failed','stale','unknown')"""
        result={}
        for name,query in [('questions',question_sql),('notifications',notices_sql)]:
            cursor=params.get(name+'Cursor')
            if cursor is not None and not isinstance(cursor,str):raise ValueError('Invalid inbox cursor')
            where,args=_page_boundary(cursor)
            rows=db.execute('SELECT * FROM ('+query+')'+where+' ORDER BY created DESC,id DESC LIMIT ?',(*args,limit+1)).fetchall()
            result[name]=[dict(r) for r in rows[:limit]]
            for item in result[name]:
                if item['kind']=='inspection':
                    inspection=db.execute('SELECT report,source_turn FROM inspections WHERE id=?',(item['id'].split(':',1)[1],)).fetchone()
                    report=_json(inspection['report'],{}) or {}
                    detail=[item['detail']]
                    for key,label in [('findings','发现'),('evidence','依据'),('limitations','验证边界')]:
                        values=report.get(key,[])
                        if values:detail.append(label+'：\n'+'\n'.join(values))
                    context=db.execute('SELECT payload FROM contexts WHERE thread_id=?',(item['threadId'],)).fetchone()
                    turns=(_json(context[0],{}) or {}).get('history_excerpt',[]) if context else []
                    if not turns or turns[-1].get('turn_id')!=inspection['source_turn']:
                        detail.append('原任务上下文已变化或无法核对，这份报告不能代表当前进度。')
                    item['detail']='\n\n'.join(detail)
            result[name+'Count']=db.execute('SELECT COUNT(*) FROM ('+query+')').fetchone()[0]
            more=rows[limit-1] if len(rows)>limit else None
            result[name+'NextCursor']=str(more['created'])+':'+more['id'] if more else None
        # IDs let automatic refresh remove resolved questions from previously loaded pages.
        result['pendingQuestionIds']=[r[0] for r in db.execute('SELECT id FROM ('+question_sql+')')]
        return result

    def overview(self, params):
        if not self.runtime:return {'available':False,'message':'此连接尚未配置管家模块。'}
        limit=params.get('limit',40);cursor=params.get('cursor')
        if type(limit) is not int or not 1<=limit<=80 or (cursor is not None and not isinstance(cursor,str)):
            raise ValueError('Invalid overview arguments')
        self.refresh()
        try:
            status=json.loads((self.runtime/'status.json').read_text())
            with self.db() as db:
                db.row_factory=sqlite3.Row
                counts=dict(db.execute('SELECT phase,COUNT(*) FROM jobs GROUP BY phase'))
                args=[];where=''
                if cursor:
                    boundary,event=cursor.split(':',1);boundary=float(boundary)
                    where=' WHERE (j.created<? OR (j.created=? AND j.id<?))';args=[boundary,boundary,event]
                rows=db.execute('SELECT j.*,child.result AS outcome,child.phase AS outcome_phase FROM jobs j LEFT JOIN jobs child ON child.thread_id=j.thread_id AND child.turn_id=j.followup_turn'+where+' ORDER BY j.created DESC,j.id DESC LIMIT ?',(*args,limit+1)).fetchall()
                entries=[]
                has_inspections=bool(db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='inspections'").fetchone())
                inbox=self.inbox(db,params,has_inspections)
                for r in rows[:limit]:
                    result=_json(r['result'],{}) or {};payload=_json(r['payload'],{}) or {};outcome=_json(r['outcome'],{}) or {}
                    inspection=db.execute('SELECT id,state,report FROM inspections WHERE thread_id=? AND source_turn=? ORDER BY created DESC LIMIT 1',(r['thread_id'],r['turn_id'])).fetchone() if has_inspections else None
                    question=db.execute('SELECT state FROM questions WHERE event_id=?',(r['id'],)).fetchone()
                    entries.append({'id':r['id'],'threadId':r['thread_id'],'title':r['title'],'created':r['created'],'phase':r['phase'],
                        'action':result.get('action',''),'summary':result.get('summary',''),'followup':result.get('next_prompt',''),
                        'question':result.get('question','') if r['phase']=='needs_attention' and result.get('action')=='ask_user' and (not question or question[0] not in ('answered','retracted')) else '',
                        'questionDelivery':question[0] if question else '',
                        'followupStarted':r['phase']=='followed_up','outcome':outcome.get('summary',''),'outcomePhase':r['outcome_phase'] or '',
                        'evidenceLevel':payload.get('verification',{}).get('level','reported_only'),
                        'inspection':{'id':inspection['id'],'state':inspection['state'],'report':_json(inspection['report'])} if inspection else None})
                more=rows[limit-1] if len(rows)>limit else None
            return {'available':True,'managerThreadId':self.manager,'heartbeatAgeSeconds':max(0,time.time()-status['updated_at']),
                'enabled':status.get('enabled',False),'counts':counts,'followupsStarted':counts.get('followed_up',0),
                'inspections':status.get('inspections',{}),'entries':entries,'inbox':inbox,
                'nextCursor':str(more['created'])+':'+more['id'] if more else None,'generatedAt':time.time()}
        except (OSError,KeyError,sqlite3.Error):
            return {'available':False,'message':'管家记录暂时不可读取，请稍后刷新。'}
