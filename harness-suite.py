"""Bounded local test queue. Run only after preparing the scene and enabling AI in game.

Uses the existing authenticated client. No desktop gameplay, no model calls, no automatic reconnect,
no action replay after uncertainty. A persisted in-flight ID is only queried on resume.
"""
from pathlib import Path
import argparse,hashlib,importlib.util,json,os,time,uuid

spec=importlib.util.spec_from_file_location('suite_harness',Path(__file__).with_name('harness-client.py'))
hc=importlib.util.module_from_spec(spec);spec.loader.exec_module(hc)

def save(path,state):
 path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
 temp=path.with_suffix('.'+uuid.uuid4().hex+'.tmp');temp.write_text(json.dumps(state,ensure_ascii=False,indent=2),encoding='utf-8');os.replace(temp,path)

def validate(plan):
 if plan.get('schema')!=1 or not isinstance(plan.get('steps'),list) or not 1<=len(plan['steps'])<=64:raise ValueError('schema=1 and 1..64 steps required')
 for step in plan['steps']:
  if not isinstance(step,dict) or not isinstance(step.get('tool'),str) or not isinstance(step.get('args',{}),dict):raise ValueError('Invalid step')
  if step['tool'] in ['game_command','creative_take','drop_inventory','read_web','search_web','save_skill','finish']:raise ValueError('Suite excludes preparation, drops, web and definition edits; prepare separately')
  expect=step.get('expect')
  if not isinstance(expect,dict) or not expect:raise ValueError('Each step needs an explicit result assertion')
  if set(expect)-{'contains','path','equals'} or not ('contains' in expect or 'path' in expect):raise ValueError('Use contains or path+equals assertions')
  if 'contains' in expect and (not isinstance(expect['contains'],str) or not expect['contains']):raise ValueError('contains must be nonempty text')
  if 'path' in expect and (not isinstance(expect['path'],str) or not expect['path'] or any(not key for key in expect['path'].split('.')) or 'equals' not in expect):raise ValueError('path must be a nonempty dotted path with equals')
  if 'equals' in expect and 'path' not in expect:raise ValueError('equals requires path')
 return plan

def assert_result(receipt,expect):
 if receipt.get('state')!='returned':raise RuntimeError('Action did not return successfully; inspect original receipt')
 result=receipt.get('result','')
 if 'contains' in expect:
  if not isinstance(result,str) or expect['contains'] not in result:raise RuntimeError('Expected result text absent')
 if 'path' in expect:
  value=json.loads(result) if isinstance(result,str) else result
  for key in expect['path'].split('.'):
   value=value[int(key)] if isinstance(value,list) else value[key]
  if value!=expect.get('equals'):raise RuntimeError('Actual data did not match assertion')
 if not ('contains' in expect or 'path' in expect):raise ValueError('Use contains or path+equals assertion')

def run(client,plan,checkpoint,seconds,clock=time.monotonic,sleep=time.sleep):
 validate(plan);digest=hashlib.sha256(json.dumps(plan,sort_keys=True).encode()).hexdigest();checkpoint=Path(checkpoint)
 state=json.loads(checkpoint.read_text(encoding='utf-8')) if checkpoint.exists() else {'schema':1,'plan':digest,'completed':0,'receipts':[]}
 if state.get('plan')!=digest:raise ValueError('Checkpoint belongs to a different plan')
 deadline=clock()+min(86400,max(1,seconds));last_beat=clock()
 status=client.call('status',lease=False);scope=status['server_scope']+'|'+status['dimension'];epoch=status['epoch']
 if not status.get('enabled') or status.get('paused') or not status.get('lease_active'):raise RuntimeError('Enable AI and connect first; suite will not enable or reconnect')
 if state.get('scope',scope)!=scope or state.get('epoch',epoch)!=epoch:raise RuntimeError('World or control epoch changed; do not reuse this checkpoint')
 state.update(scope=scope,epoch=epoch)
 while state['completed']<len(plan['steps']):
  if clock()>=deadline:raise TimeoutError('Suite budget exhausted; checkpoint retained')
  step=plan['steps'][state['completed']]
  if 'pending' in state:r=client.call('receipt',{'request_id':state['pending']['request_id']},lease=False)
  else:
   status=client.call('status',lease=False)
   if not status.get('enabled') or status.get('paused') or status.get('busy') or status['epoch']!=epoch or status['server_scope']+'|'+status['dimension']!=scope:raise RuntimeError('Control changed or game busy; stopped without sending next action')
   intent={'request_id':str(uuid.uuid4()),'tool':step['tool'],'args':step.get('args',{}),'epoch':epoch,'scope':scope};state['pending']=intent;save(checkpoint,state)
   # A network error leaves pending saved. Next invocation queries this ID; never blindly resends.
   r=client.call('action',intent)
  while r.get('state')=='running':
   if clock()>=deadline:raise TimeoutError('Action still running; query original ID, do not replay')
   if clock()-last_beat>=10:client.call('heartbeat');last_beat=clock()
   sleep(.4);r=client.call('receipt',{'request_id':state['pending']['request_id']},lease=False)
  state['last_receipt']=r;save(checkpoint,state)
  assert_result(r,step['expect']);state['receipts'].append({'step':state['completed'],'request_id':r.get('request_id'),'state':r.get('state'),'outcome':r.get('outcome')});state['completed']+=1;state.pop('pending');save(checkpoint,state)
  if clock()-last_beat>=10:client.call('heartbeat');last_beat=clock()
 return {'completed':state['completed'],'total':len(plan['steps']),'checkpoint':str(checkpoint),'rule':'assertions passed only; quality depends on the supplied test and actual postconditions'}

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('plan',type=Path);p.add_argument('--connection',type=Path,default=hc.DEFAULT);p.add_argument('--checkpoint',type=Path,required=True);p.add_argument('--seconds',type=int,default=1800);p.add_argument('--execute',action='store_true');a=p.parse_args()
 plan=validate(json.loads(a.plan.read_text(encoding='utf-8-sig')))
 if not a.execute:print(json.dumps({'valid':True,'steps':len(plan['steps']),'note':'dry validation only; add --execute for authorized game actions'}));return
 client=hc.Client(a.connection)
 try:print(json.dumps(run(client,plan,a.checkpoint,a.seconds),ensure_ascii=False,indent=2))
 finally:
  try:
   client.call('release');client.state.pop('keeper_generation',None);client.save()
  except (RuntimeError,OSError):pass
if __name__=='__main__':main()
