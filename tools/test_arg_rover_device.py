"""Negative motion tests on an explicitly uncalibrated paired controller. Never enables PWM/power."""
import argparse,json,time
from pathlib import Path
import rastag_ota as ota

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);args=p.parse_args()
    report={'passed':False,'checks':[],'actuation':False}
    data=ota.load_pair()
    try:
        with ota.link.client_context(data) as ctx:
            c=ota.Connection(data,ctx)
            def request(method,path,body=None):
                c.request(method,path,body=json.dumps(body) if isinstance(body,dict) else body,
                    headers={'Content-Type':'application/json'})
                return ota.response_json(c)
            def off():
                status,s=request('GET','/v3/motion');assert status==200
                assert not s['configured'] and not s['relay'] and not s['pwm'] and s['optos']==0 and s['mode']=='off'
                return s
            initial=off();status,session=request('POST','/v1/session',{'protocol':1});assert status==200
            report['checks'].append('uncalibrated_all_outputs_off')
            sequence=1
            def drive(**overrides):
                nonlocal sequence
                state=off();sequence+=1
                q=dict(boot=state['boot'],session=session['session'],seq=sequence,operation='drive',expires_at_ms=state['uptime_ms']+200,run=sequence,direction=1,intensity=20)
                q.update(overrides);return q
            cases=[('expired',dict(expires_at_ms=1),409),('wrong_boot',dict(boot='0'*32),409),
                   ('wrong_session',dict(session='0'*32),409),('unknown_field',dict(extra=True),400),
                   ('invalid_direction',dict(direction=9),400)]
            for name,overrides,expected in cases:
                code,_=request('POST','/v3/motion',drive(**overrides));assert code==expected,(name,code);off();report['checks'].append(name)
            command=drive();code,s=request('POST','/v3/motion',command)
            assert code==423 and not s['accepted'] and s['reason']=='calibration_required';off();report['checks'].append('uncalibrated_drive_blocked')
            state=off();command['expires_at_ms']=state['uptime_ms']+200
            code,s=request('POST','/v3/motion',command);assert code==423 and not s['accepted'];off();report['checks'].append('replay_blocked')
            state=off();sequence+=1
            q=dict(boot=state['boot'],session=session['session'],seq=sequence,operation='configure',expires_at_ms=state['uptime_ms']+1500,
                   verified=True,front_cdeg=0,stop_mm=400)
            for prefix in ['f','b','l','r']:q[prefix+'_left']=10000;q[prefix+'_right']=10500
            code,_=request('POST','/v3/motion',q);assert code==400;off();report['checks'].append('identical_presets_rejected')
            raw=json.dumps(drive());raw=raw[:-1]+',"direction":1}'
            code,_=request('POST','/v3/motion',raw);assert code==400;off();report['checks'].append('duplicate_field_rejected')
            state=off();sequence+=1
            code,s=request('POST','/v3/motion',dict(boot=state['boot'],session=session['session'],seq=sequence,operation='stop'))
            assert code==200 and s['accepted'] and not s['stop_confirmed'];off();report['checks'].append('stop_no_physical_claim')
            c.close();report['passed']=True
    except Exception as e:report['error_type']=type(e).__name__;report['error']=str(e)
    args.output.write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
    if not report['passed']:raise SystemExit(1)
if __name__=='__main__':main()
