"""Local test harness, standard-library only. Never reads settings.json or prints auth tokens.

python -X utf8 harness-client.py status
python -X utf8 harness-client.py connect
python -X utf8 harness-client.py action observe --wait 2
python -X utf8 harness-client.py action press_control --args-file movement.json --wait 10
python -X utf8 harness-client.py events
python -X utf8 harness-client.py stop
"""
import argparse,json,time,uuid,urllib.request,urllib.error,urllib.parse,sys,os,subprocess,socket
from pathlib import Path

DEFAULT=Path(os.environ.get('APPDATA', str(Path.home())))/'.minecraft'/'config'/'logistics-companion'/'harness-connection.json'
NATIVE_TOOLS=frozenset(('status','connect','heartbeat','events','reply','task','record','receipt','action','screenshot','release','stop','capabilities','game_tools'))
MAX_KEEPALIVE_SECONDS=30*24*3600
DEFAULT_BACKOFF_BASE=1.0
DEFAULT_BACKOFF_MAX=30.0
STALE_AFTER_SECONDS=35
LEASE_RENEW_RISK_DROP_MS=5000

class HarnessError(Exception):
    def __init__(self,reason,error,stderr_tail=''):
        super().__init__(error);self.reason=reason;self.error=error;self.stderr_tail=stderr_tail

def error_payload(exc):
    # One structured JSON document on stdout: kind, human detail, child stderr tail when available.
    if isinstance(exc,HarnessError):
        return {'error':exc.error,'reason':exc.reason,'stderr_tail':exc.stderr_tail}
    if isinstance(exc,FileNotFoundError):
        return {'error':'Connection file missing. Enable local interface in the updated mod; never provide an API key here.','reason':'transport','stderr_tail':''}
    if isinstance(exc,urllib.error.HTTPError):
        return {'error':'Local endpoint returned HTTP '+str(exc.code),'reason':'transport','stderr_tail':''}
    if isinstance(exc,json.JSONDecodeError):
        return {'error':'Invalid JSON: '+exc.msg,'reason':'parse','stderr_tail':getattr(exc,'doc','')[-400:] if getattr(exc,'doc','') else ''}
    if isinstance(exc,TimeoutError) or isinstance(exc,socket.timeout):
        return {'error':'Timed out: '+(str(exc) or type(exc).__name__),'reason':'timeout','stderr_tail':''}
    if isinstance(exc,urllib.error.URLError):
        detail=str(getattr(exc,'reason',None) or exc)
        if isinstance(getattr(exc,'reason',None),(TimeoutError,socket.timeout)) or 'timed out' in detail.lower() or 'timeout' in detail.lower():
            return {'error':'Timed out: '+detail,'reason':'timeout','stderr_tail':''}
        return {'error':'Transport error: '+detail,'reason':'transport','stderr_tail':''}
    if isinstance(exc,OSError):
        return {'error':'Transport error: '+(str(exc) or type(exc).__name__),'reason':'transport','stderr_tail':''}
    if isinstance(exc,RuntimeError):
        return {'error':str(exc),'reason':'tool','stderr_tail':''}
    if isinstance(exc,ValueError):
        return {'error':str(exc),'reason':'parse','stderr_tail':''}
    return {'error':type(exc).__name__+': '+str(exc),'reason':'error','stderr_tail':''}

def keepalive_duration(requested,connection):
    seconds=int(connection.get('ttl_seconds',3600) if requested is None else requested)
    if seconds<0:raise ValueError('Keepalive seconds must be nonnegative')
    return min(MAX_KEEPALIVE_SECONDS,seconds)

class Client:
    def __init__(self,connection):
        self.path=Path(connection);self.connection=json.loads(self.path.read_text(encoding='utf-8'))
        address=urllib.parse.urlsplit(self.connection['url'])
        if address.scheme!='http' or address.hostname!='127.0.0.1' or not address.port or address.username or address.password or address.path!='/mcp' or address.query or address.fragment:raise ValueError('Only the local companion endpoint is allowed')
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.state_path=self.path.with_name('harness-client-session.json')
        self.state=json.loads(self.state_path.read_text(encoding='utf-8')) if self.state_path.exists() else {}
    def rpc(self,method,params):
        req={'jsonrpc':'2.0','id':str(uuid.uuid4()),'method':method,'params':params}
        data=json.dumps(req,ensure_ascii=False).encode('utf-8')
        request=urllib.request.Request(self.connection['url'],data=data,headers={'Content-Type':'application/json','Accept':'application/json, text/event-stream','Authorization':'Bearer '+self.connection['token']})
        with self.opener.open(request,timeout=12) as response:r=json.load(response)
        if 'error' in r:raise RuntimeError(r['error'].get('message','RPC error'))
        return r['result']
    def call(self,name,args=None,lease=True):
        # Game actions use a scope/epoch/request envelope, not an unknown top-level RPC method.
        # This preserves old native callers and also makes Client.call('inspect_gui') work.
        if name not in NATIVE_TOOLS:return self.action(name,args)
        args=dict(args or {})
        if lease:args['lease']=self.state.get('lease','')
        r=self.rpc('tools/call',{'name':name,'arguments':args})
        text=r['content'][0]['text']
        if r.get('isError'):raise RuntimeError(json.dumps(r.get('structuredContent', {'reason_detail':text}),ensure_ascii=False))
        return json.loads(text)
    def action(self,tool,args=None,request_id=None,wait=0):
        args=dict(args or {})
        previous=self.state.get('last_action') or {}
        if request_id and previous.get('request_id')==request_id:
            if previous.get('tool')!=tool or previous.get('args')!=args:
                raise ValueError('The same request_id has different arguments; original intent preserved')
            # A prior attempt may already have executed. Query, never submit it a second time.
            result=self.call('receipt',{'request_id':request_id},lease=False)
        else:
            status=self.call('status',lease=False)
            request_id=request_id or str(uuid.uuid4())
            self.state['last_action']={'request_id':request_id,'tool':tool,'args':args,
                'epoch':status['epoch'],'scope':status['server_scope']+'|'+status['dimension']}
            self.save()
            try:result=self.call('action',self.state['last_action'])
            except OSError as exc:
                raise HarnessError('action_outcome_unknown','Transport interrupted; query receipt for request_id='+request_id+' before any retry') from exc
        until=time.monotonic()+min(60,max(0,wait))
        while result.get('state')=='running' and time.monotonic()<until:
            time.sleep(.3);result=self.call('receipt',{'request_id':request_id},lease=False)
        if isinstance(result,dict):result.setdefault('request_id',request_id)
        return result
    def save(self):
        temporary=self.state_path.with_suffix('.'+uuid.uuid4().hex+'.tmp');temporary.write_text(json.dumps(self.state,ensure_ascii=False,indent=2),encoding='utf-8');os.replace(temporary,self.state_path)
    def connect_or_reuse(self):
        if self.state.get('lease'):
            try:
                self.call('heartbeat')
                status=self.call('status',lease=False)
                return {'lease':self.state['lease'],'scope':status['server_scope']+'|'+status['dimension'],
                        'epoch':status['epoch'],'ttl_seconds':status.get('lease_ttl_seconds',3600),'server_instance':status.get('server_instance',''),'reused':True}
            except RuntimeError:
                pass
        return self.call('connect',lease=False)

def start_keeper(connection,generation,seconds):
    # No token or lease on the command line. The child uses only the local protected connection files.
    command=[sys.executable,'-X','utf8',str(Path(__file__).resolve()),'--connection',str(connection),'maintain','--generation',generation,'--seconds',str(seconds)]
    options={'stdin':subprocess.DEVNULL,'stdout':subprocess.DEVNULL,'stderr':subprocess.DEVNULL}
    if os.name=='nt':options['creationflags']=subprocess.DETACHED_PROCESS|subprocess.CREATE_NEW_PROCESS_GROUP|subprocess.CREATE_BREAKAWAY_FROM_JOB
    else:options['start_new_session']=True
    try:return subprocess.Popen(command,**options)
    except OSError:
        if os.name!='nt':raise
        options['creationflags']=subprocess.CREATE_NO_WINDOW
        return subprocess.Popen(command,**options)

def keeper_health(connection,generation,status,count=0,error="",last_success=None):
    path=Path(connection)
    if not path.is_file():return
    target=path.with_name('harness-keeper-health.json')
    # Diagnostic metadata only; never copy session/auth fields.
    previous={}
    try:previous=json.loads(target.read_text(encoding='utf-8'))
    except (OSError,ValueError):pass
    record={'generation':generation,'pid':os.getpid(),'status':status,'heartbeats':count,'updated_at':time.time(),
            'last_success_at':last_success if last_success is not None else previous.get('last_success_at') if previous.get('generation')==generation else None,'error':error}
    temporary=target.with_suffix('.'+uuid.uuid4().hex+'.tmp');temporary.write_text(json.dumps(record),encoding='utf-8');os.replace(temporary,target)

def maintain(connection,generation,seconds,clock=time.monotonic,sleep=time.sleep,backoff_base=DEFAULT_BACKOFF_BASE,backoff_max=DEFAULT_BACKOFF_MAX):
    # Transport failures never end the keeper early; it retries with exponential backoff
    # until the bounded duration ends. Only an explicit stop/replacement or a rejected
    # lease ends it before that.
    if backoff_base<=0:raise ValueError('Backoff base must be positive')
    if backoff_max<backoff_base:raise ValueError('Backoff max must be at least the backoff base')
    end=clock()+min(MAX_KEEPALIVE_SECONDS,max(1,seconds));count=0;retries=0;failures=0
    while clock()<end:
        c=Client(connection)
        if c.state.get('keeper_generation')!=generation:
            keeper_health(connection,generation,'replaced_or_stopped',count,'generation_replaced')
            return {'heartbeats':count,'reason':'replaced_or_stopped','retries':retries}
        try:c.call('heartbeat')
        except RuntimeError:
            keeper_health(connection,generation,'revoked_or_expired',count,'lease_rejected')
            return {'heartbeats':count,'reason':'disconnected_or_revoked','retries':retries}
        except OSError:
            retries+=1;failures+=1;keeper_health(connection,generation,'retrying',count,'transport_error')
            remaining=max(0,end-clock())
            if remaining<=0:break
            delay=min(backoff_max,backoff_base*(2**min(failures-1,16)))
            sleep(min(delay,remaining));continue
        failures=0;count+=1;keeper_health(connection,generation,'running',count,last_success=time.time());sleep(min(15,max(0,end-clock())))
    error=('transport_unavailable_after_%d_retries' % retries) if failures else ''
    keeper_health(connection,generation,'duration_ended',count,error)
    return {'heartbeats':count,'reason':'bounded_duration_ended','retries':retries}

def claim_and_start(c,no_keepalive=False,seconds=None):
    # Shared by `connect` and `status --revive-keeper`: claim or reuse the lease, save the
    # session, then start and confirm the optional background keeper.
    c.rpc('initialize',{'protocolVersion':'2025-03-26','capabilities':{},'clientInfo':{'name':'companion-local-harness','version':'0.3.8'}})
    r=c.connect_or_reuse();previous=c.state
    c.state={'lease':r['lease'],'scope':r['scope'],'after':previous.get('after',0) if r.get('reused') else 0,'keeper_generation':uuid.uuid4().hex}
    if previous.get('scope')==r['scope'] and 'last_action' in previous:c.state['last_action']=previous['last_action']
    if 'lease_health' in previous:c.state['lease_health']=previous['lease_health']
    c.save();r={k:v for k,v in r.items() if k!='lease'}
    duration=keepalive_duration(seconds,r)
    if not no_keepalive and duration>0:
        process=start_keeper(c.path,c.state['keeper_generation'],duration);r['auto_keepalive_requested_seconds']=duration;r['auto_keepalive_seconds']=0;r['keeper_started']=process.poll() is None;r['keeper_confirmed']=False
        for _ in range(10):
            try:
                health=json.loads(c.path.with_name('harness-keeper-health.json').read_text(encoding='utf-8'))
                if health.get('generation')==c.state['keeper_generation'] and health.get('status')=='running':r['keeper_confirmed']=True;r['auto_keepalive_seconds']=r['auto_keepalive_requested_seconds'];break
            except (OSError,ValueError):pass
            time.sleep(.1)
    else:
        r['auto_keepalive_seconds']=0;r['keeper_started']=False
        r['keeper_required']=r.get('ttl_seconds',3600)!=0
        r['keepalive_note']='Manual-stop lease does not expire; no background keeper required' if not r['keeper_required'] else 'Automatic keeper disabled explicitly'
    return r

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--connection',type=Path,default=DEFAULT);p.add_argument('--json',action='store_true',help='Emit compact single-line JSON on stdout (also accepted after read-only/action subcommands)')
    sub=p.add_subparsers(dest='command',required=True)
    for name in ('stop','release','heartbeat','screenshot','tools','capabilities'):sub.add_parser(name)
    s=sub.add_parser('status');s.add_argument('--compact',action='store_true');s.add_argument('--revive-keeper',action='store_true',help='Explicit opt-in: re-claim the lease and restart the background keeper when the keeper is stale or stopped')
    s=sub.add_parser('connect');s.add_argument('--no-keepalive',action='store_true');s.add_argument('--seconds',type=int,default=None,help='Keeper duration; default follows game lease, manual-stop lease needs no keeper')
    s=sub.add_parser('maintain');s.add_argument('--generation',required=True);s.add_argument('--seconds',type=int,default=3600);s.add_argument('--backoff-base',type=float,default=DEFAULT_BACKOFF_BASE,help='First retry delay in seconds; later delays double');s.add_argument('--backoff-max',type=float,default=DEFAULT_BACKOFF_MAX,help='Maximum retry delay in seconds')
    s=sub.add_parser('events');s.add_argument('--after',type=int)
    s=sub.add_parser('reply');s.add_argument('text')
    s=sub.add_parser('task');s.add_argument('text')
    s=sub.add_parser('record');s.add_argument('mode',choices=['start','stop'])
    s=sub.add_parser('receipt');s.add_argument('request_id')
    s=sub.add_parser('action');s.add_argument('tool');s.add_argument('--args-file',type=Path);s.add_argument('--args',default='{}');s.add_argument('--request-id');s.add_argument('--wait',type=float,default=0)
    s=sub.add_parser('keepalive');s.add_argument('--seconds',type=int,default=600)
    for name in ('status','action','receipt','connect','events','capabilities','tools','maintain'):
        sub.choices[name].add_argument('--json',action='store_true',default=argparse.SUPPRESS,help='Emit compact single-line JSON (same as global --json)')
    a=p.parse_args(argv);c=Client(a.connection);cmd=a.command
    if cmd=='connect':
        r=claim_and_start(c,a.no_keepalive,a.seconds)
    elif cmd in ('status','stop','tools','capabilities'):
        r=c.call('game_tools' if cmd=='tools' else cmd,lease=False)
        if cmd=='status':
            try:
                r['keeper_health']=json.loads(c.path.with_name('harness-keeper-health.json').read_text(encoding='utf-8'))
                if r['keeper_health'].get('status')=='running' and time.time()-r['keeper_health'].get('last_success_at',0)>STALE_AFTER_SECONDS:r['keeper_health']['status']='stale'
                r['keeper_health']['current_generation']=r['keeper_health'].get('generation')==c.state.get('keeper_generation')
            except (OSError,ValueError):r['keeper_health']={'status':'unknown'}
            keeper=r['keeper_health'];keeper_status=keeper.get('status');remaining=r.get('lease_remaining_ms');previous=c.state.get('lease_health') or {}
            def _number(value):return isinstance(value,(int,float)) and not isinstance(value,bool)
            dropped=_number(remaining) and _number(previous.get('remaining_ms')) and previous['remaining_ms']-remaining>=LEASE_RENEW_RISK_DROP_MS
            at_risk=keeper_status=='stale' or (dropped and keeper_status!='running')
            if at_risk:r['lease_renew_at_risk']=True
            c.state['lease_health']={'remaining_ms':remaining,'observed_at':time.time()};c.save()
            if a.revive_keeper and (at_risk or keeper_status!='running'):
                try:r['revive_keeper']=claim_and_start(c)
                except Exception as e:r['revive_keeper']={'error':type(e).__name__+': '+str(e)}
        if cmd=='status' and a.compact:r={k:v for k,v in r.items() if k in ('build','epoch','server_scope','dimension','enabled','watching','paused','busy','job','screen','lease_active','lease_ttl_seconds','lease_mode','lease_remaining_ms','lease_renew_at_risk','recording','x','y','z','local_execution','permissions','planning_policy','community','logistics','cooperation','epoch_reason','keeper_health','revive_keeper')}
        if cmd=='stop':c.state.pop('keeper_generation',None);c.save()
    elif cmd=='maintain':r=maintain(c.path,a.generation,a.seconds,backoff_base=a.backoff_base,backoff_max=a.backoff_max)
    elif cmd=='events':
        r=c.call('events',{'after':a.after if a.after is not None else c.state.get('after',0)});c.state['after']=r['next'];c.save()
    elif cmd in ('reply','task'):r=c.call(cmd,{'text':a.text},lease=cmd!='task')
    elif cmd=='record':r=c.call(cmd,{'mode':a.mode})
    elif cmd=='receipt':r=c.call(cmd,{'request_id':a.request_id},lease=False)
    elif cmd=='action':
        args=json.loads(a.args_file.read_text(encoding='utf-8-sig') if a.args_file else a.args)
        r=c.action(a.tool,args,request_id=a.request_id,wait=a.wait)
    elif cmd=='keepalive':
        until=time.monotonic()+min(MAX_KEEPALIVE_SECONDS,max(1,a.seconds));count=0
        while time.monotonic()<until:
            c.call('heartbeat');count+=1;time.sleep(min(15,max(0,until-time.monotonic())))
        r={'heartbeats':count,'seconds':min(MAX_KEEPALIVE_SECONDS,max(1,a.seconds)),'message':'Keepalive ended; timed lease expires unless renewed; manual lease persists until stopped'}
    else:
        r=c.call(cmd)
        if cmd=='release':c.state.pop('keeper_generation',None);c.save()
    print(json.dumps(r,ensure_ascii=False,indent=None if getattr(a,'json',False) else 2))

def run(argv=None):
    # Machine-readable boundary: success prints one protocol JSON document; failure prints
    # one structured error document on stdout and returns a non-zero exit code.
    try:
        main(argv)
        return 0
    except SystemExit:
        raise
    except Exception as exc:
        print(json.dumps(error_payload(exc),ensure_ascii=False))
        return 1

if __name__=='__main__':
    sys.exit(run())
