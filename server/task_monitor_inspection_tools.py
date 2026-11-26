"""Private MCP endpoint bound to one inspection; no management writes or shell."""
import argparse
import json
import sqlite3
import sys
import subprocess
from task_monitor_rpc import RpcError
from pathlib import Path
from task_monitor_inspections import inspect_tool


def schema(properties=None):
    return {'type':'object','properties':properties or {},'additionalProperties':False}

TOOLS=[
 {'name':'inspection_context','description':'读取本次委派的任务要求、多轮对话和历次跟进决定。','inputSchema':schema()},
 {'name':'inspection_files','description':'列出任务工作区一个目录的文件，不含私密路径。','inputSchema':schema({'path':{'type':'string'}})},
 {'name':'inspection_read','description':'读取任务工作区的文本、代码、报告或图片；返回实际证据。','inputSchema':schema({'path':{'type':'string'},'start_line':{'type':'integer'},'max_lines':{'type':'integer'}})},
 {'name':'inspection_diff','description':'读取一个文件相对于 HEAD 的当前实际代码差异；不能证明某轮归属。','inputSchema':schema({'path':{'type':'string'}})}]


def main():
    p=argparse.ArgumentParser();p.add_argument('--runtime',required=True);p.add_argument('--inspection-id',required=True);a=p.parse_args()
    for line in sys.stdin:
        try:
            req=json.loads(line);ident=req.get('id');method=req.get('method');params=req.get('params',{})
            if ident is None:continue
            if method=='initialize':result={'protocolVersion':params.get('protocolVersion','2024-11-05'),'capabilities':{'tools':{}},'serverInfo':{'name':'andri-inspection','version':'1'}}
            elif method=='tools/list':result={'tools':TOOLS}
            elif method=='ping':result={}
            elif method=='tools/call':
                try:result=inspect_tool(Path(a.runtime),a.inspection_id,params['name'],params.get('arguments',{}))
                except (ValueError,KeyError,TypeError,OSError,sqlite3.Error,UnicodeError,subprocess.TimeoutExpired,RpcError) as e:
                    result={'content':[{'type':'text','text':json.dumps({'code':'inspection_read_rejected','message':'读取被拒绝或不可用；检查范围、文件类型、大小、源回合和路径。','error':type(e).__name__},ensure_ascii=False)}],'isError':True}
            else:
                print(json.dumps({'jsonrpc':'2.0','id':ident,'error':{'code':-32601,'message':'Unknown method'}}),flush=True);continue
            print(json.dumps({'jsonrpc':'2.0','id':ident,'result':result},ensure_ascii=False),flush=True)
        except (ValueError,KeyError,TypeError):
            print(json.dumps({'jsonrpc':'2.0','id':None,'error':{'code':-32700,'message':'Invalid request'}}),flush=True)


if __name__=='__main__':main()
